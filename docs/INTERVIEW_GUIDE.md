# Interview guide — GitHub Repository Searcher

This guide describes the code that actually exists in this repository. It deliberately does not claim that the service is deployed: deployment is Phase 7 and no public URL or Dockerfile exists yet. If a statement here disagrees with the code, the code wins.

## 30-second explanation

"It's a Spring Boot REST API. You POST a search to it; it calls GitHub's repository search API, saves the repositories into PostgreSQL, updating any it already has, and returns them. A second endpoint lets you query what's stored, filtered by language and minimum stars and sorted by stars, forks or last updated, without calling GitHub again. There is also a get-by-id endpoint."

## 1-minute explanation

"It's a layered Spring Boot service: controller, two services, a dedicated GitHub client, a mapper, a Spring Data repository, an entity and DTOs. Requests are validated with Bean Validation. Duplicates are prevented by using GitHub's repository id as the primary key and a PostgreSQL `INSERT ... ON CONFLICT (id) DO UPDATE`, so a repeated search updates instead of failing. Errors such as rate limits, timeouts, bad input or a database outage become proper status codes in one consistent JSON body. The GitHub client is tested with a mocked HTTP server, the services with Mockito, the web layer with MockMvc, and the database with Testcontainers against real PostgreSQL. Swagger UI documents and exercises the API. Deployment is planned for the next phase; it is not done yet."

## 3-minute explanation

Start with the request lifecycle:

1. Tomcat and Spring's `DispatcherServlet` route `POST /api/github/search` to `GithubRepositoryController.search`.
2. Jackson turns the JSON into a `SearchRequest` record, and `@Valid` runs Bean Validation, including the cross-field rule `page * perPage <= 1000`.
3. The controller calls `GithubSearchService.search`. The controller contains no business logic.
4. The service calls `GithubSearchClient.search`, which builds `/search/repositories?q=<words> in:name [language:...]`, adds the optional token and calls GitHub through `RestClient`.
5. The client returns typed `GithubSearchResponse` records, or throws `GithubRateLimitException` / `GithubApiException` with the status our API should return.
6. If GitHub returns no items, the service returns a 200 with the "no repositories" message and writes nothing. If every item is unusable (missing id, name or owner), the same is true: the API does not claim a successful save when nothing was saved.
7. `GithubRepositoryMapper` converts usable items into `GithubRepository` entities and skips bad ones.
8. `StoredRepositoryService.upsertAll` runs in one transaction and calls the native upsert once per row.
9. PostgreSQL inserts new ids or updates existing rows, refreshing `synced_at`.
10. The service maps entities to `RepositoryResponse` DTOs, and Spring serialises a `SearchResponse` with HTTP 200.

Then explain three decisions:

- **Database-level upsert:** one atomic statement, no SELECT-then-INSERT race, and no duplicate rows under concurrent searches.
- **A dedicated GitHub client:** GitHub's URL, headers, token, JSON shape, rate limits and error codes are isolated from business logic; the service can be tested with a mock.
- **Filtering and sorting in SQL with a whitelist:** the database is efficient, and `SortField` makes it impossible to pass arbitrary client text into `Sort.by(...)`.

Close with limitations: the API is open, GitHub searches names only because of `in:name`, GitHub returns at most 1000 results, anonymous search has a tight rate limit, and nothing is deployed yet.

---

## Architecture and request lifecycle

### 1. Walk me through the architecture.

HTTP reaches `GithubRepositoryController`, which delegates to `GithubSearchService` for searches and `StoredRepositoryService` for reads. `GithubSearchService` calls `GithubSearchClient` (GitHub) and `GithubRepositoryMapper`, then hands entities to `StoredRepositoryService` for the transaction. `GithubRepositoryRepository` extends Spring Data JPA and contains the native upsert, while `GithubRepositorySpecs` builds the filters. DTOs cross the API boundary, and `GlobalExceptionHandler` turns exceptions into the standard `ErrorResponse`.

### 2. Why two services?

`GithubSearchService` orchestrates a slow external HTTP call. `StoredRepositoryService` owns database transactions and all reads/writes. This keeps a database transaction and connection from being held during the GitHub call. It also avoids Spring's self-invocation problem: a `@Transactional` method called from another method of the same bean bypasses the proxy, so the annotation belongs on a separate bean.

### 3. Why is `GithubSearchService` not `@Transactional`?

Because it performs an HTTP call before any database work. Wrapping the whole method would hold a transaction open for the network round trip. The transaction starts in `StoredRepositoryService.upsertAll`, after GitHub has answered.

### 4. Why is the entity never returned from the controller?

The entity is coupled to the table. Returning it would leak column names, lazy associations and fields such as `syncedAt` into the JSON contract. DTO records are an explicit, stable API promise.

### 5. What happens if every GitHub item is unusable?

The mapper skips items without an id, name or owner login. If the resulting list is empty, `GithubSearchService` returns HTTP 200 with `"No repositories found for the given search criteria"` and an empty array, and never touches the database. It must not say "saved successfully" when nothing was saved.

## Java and Spring

### 6. What is a record and why use it for DTOs?

A record is an immutable data carrier; the compiler generates the constructor, accessors, `equals`, `hashCode` and `toString`. DTOs are read-only responses or validated request bodies, so immutability fits and there is no boilerplate.

### 7. Why constructor injection instead of field injection?

Dependencies are `final`, explicit and cannot be half-initialised. Tests can call the constructor with mocks without starting Spring.

### 8. What does `@SpringBootApplication` do?

It combines `@Configuration`, `@EnableAutoConfiguration` and component scanning. Spring Boot auto-configures the web server, Jackson, JPA, Flyway, validation and actuator based on the classpath and properties.

### 9. How does Spring Data create a repository with no implementation?

At startup it creates a proxy from the interface. Method names and annotations are translated into queries; our `upsert` uses `@Query(nativeQuery = true)` and named parameters.

### 10. What does `@Transactional` do under the hood?

A proxy wraps the method: it begins a transaction, commits when the method returns normally, and rolls back on a runtime exception. `upsertAll` is one transaction, so a failure on row 7 rolls back rows 1 to 6.

### 11. What is `ddl-auto=validate`?

Hibernate checks that the entity mapping matches the existing schema but never changes the schema. Flyway owns all schema changes.

### 12. Why `Instant` for timestamps?

`Instant` is a point in time in UTC, so there are no local-time or daylight-saving surprises. The JVM, JDBC and database all use UTC (`TIMESTAMPTZ`).

## Database and SQL

### 13. Explain the upsert.

Insert the row. If a row with the same primary key already exists, update the other columns from `EXCLUDED`, the pseudo-table that holds the proposed row. `synced_at` is always set to `now()`. It is one atomic statement.

### 14. Why not JPA `save()`?

With an assigned id, `save()` typically does a SELECT before deciding insert or update. That is two round trips and can race under concurrency: two transactions can both see "no row" and both try to insert. `ON CONFLICT` is atomic and race-free.

### 15. Which indexes exist and why?

`LOWER(language)`, `stars DESC`, `forks DESC` and `last_updated DESC`. They support the language filter and the three sort options. At small data sizes PostgreSQL may ignore them; they matter as the table grows.

### 16. Why `LOWER(language)`?

So `java` and `Java` match. `GithubRepositorySpecs` lowercases the input with `Locale.ROOT` and compares it to `LOWER(language)`, and the functional index lets PostgreSQL use an index for that expression instead of scanning the table.

### 17. What is `synced_at`?

The time this service last wrote the row. It is not GitHub's timestamp; it is owned by the database (`DEFAULT now()`, refreshed by the upsert). It makes updates observable in tests and gives clients/operators a freshness signal internally.

### 18. How is SQL injection prevented?

All values are bound parameters, including the native upsert and the JPA specifications. The sort field is not free text: `SortField.from` maps it to a whitelisted enum, and `order` is a validated `asc`/`desc` string. No SQL is built by concatenating user input.

## REST and error handling

### 19. Why does `POST /search` return 200 and not 201?

Nothing is created at a new URL. The operation is a search with a side effect (upsert), so 200 with the result is more accurate than 201.

### 20. Why 200 for empty results?

A valid query with zero matches is a successful answer. Returning 404 would confuse "the world has no match" with "the endpoint or resource does not exist".

### 21. Why 429 for GitHub rate limits?

It is a client-visible, retryable condition. The API adds the standard `Retry-After` header when GitHub supplied a delay. Returning 500 would suggest a bug in our code.

### 22. Why 502 and 504?

The failure is in an upstream dependency. `502 Bad Gateway` covers GitHub being unavailable or returning an unreadable response; `504 Gateway Timeout` covers the 5-second connect or 10-second read timeout. Our code did not fail; the upstream did.

### 23. What happens if the database is down?

`DataAccessResourceFailureException` is handled by `GlobalExceptionHandler` and becomes `503 Service Unavailable` with a generic message. A bad SQL statement is different: it is a server bug and becomes a generic `500`.

### 24. How do you avoid leaking internals?

`ErrorResponse` contains only a timestamp, status, reason phrase, safe message, path and optional field details. Stack traces, SQL, exception class names and the GitHub token are logged server-side and never serialised.

### 25. How is validation split between the body and query parameters?

`SearchRequest` uses `@NotBlank`, `@Size`, `@Pattern`, `@Min`, `@Max` and a cross-field `@AssertTrue` method. GET parameters use `@Size`, `@Min`, `@Max` and `@Pattern` on the controller method. `GlobalExceptionHandler` maps `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException`, `MethodArgumentTypeMismatchException` and malformed JSON to 400.

### 26. Why is `withinGithubResultCap` hidden in Swagger?

It is a derived boolean method used only by Bean Validation. If it appeared as a request property, clients would think they must send it. `@Schema(hidden = true)` keeps it out of the public contract while validation still runs.

## GitHub integration

### 27. What exactly is sent to GitHub?

`GET https://api.github.com/search/repositories?q=<query> in:name [language:<language>]&page=<page>&per_page=<perPage>`, optionally with `sort=<stars|forks|updated>&order=desc`. A language containing a space is quoted, for example `language:"Jupyter Notebook"`.

### 28. Why `in:name`?

The assignment is to search repositories by the query. Restricting the search to names makes the matching rule explicit and predictable. The limitation is real: descriptions, README content and topics are not searched.

### 29. What are GitHub's search limits?

GitHub returns at most 1000 results for a search, and search has a low rate limit (lower when unauthenticated). The request validator rejects `page * perPage > 1000`; an optional token raises the rate limit. Rate-limit responses become 429.

### 30. Why no retries?

Searches are user-initiated and the quota is tight. Retrying a 429 or a timeout usually makes the situation worse. A single retry on a 5xx with backoff would be a reasonable extension, but it is not implemented.

### 31. How do you test GitHub failures without calling GitHub?

`GithubSearchClientTest` uses `MockRestServiceServer` to return canned responses: success, 403 with rate-limit headers, 401, 5xx, malformed JSON and timeouts. The tests assert the resulting HTTP status and message, not GitHub's live behaviour.

## API documentation

### 32. How is the OpenAPI document produced?

Springdoc scans the controllers and DTOs at runtime. `OpenApiConfig` supplies the title, description and version; controller annotations supply tags, operation summaries, parameters, status codes and the `Retry-After` header; `@Schema` on record components supplies examples and descriptions. `/v3/api-docs` returns the JSON and `/swagger-ui.html` renders it.

### 33. Why does the version say `0.0.1-SNAPSHOT`?

That is the Maven project version from `pom.xml`. Using a different "API version" would claim a release the build did not produce.

### 34. How do you verify the documentation is accurate?

`OpenApiDocsTest` fetches `/v3/api-docs` through MockMvc and asserts the configured info, all three paths, the status codes, the parameter types and allowed values, the error schema references, the examples, and that the derived validation property is hidden. Swagger UI Try-it-out was also exercised against a running app for POST search and GET repositories.

## Testing

### 35. What does each test layer prove?

Unit tests prove service orchestration and mapping with mocks. Web tests prove routing, validation, status codes and the JSON contract without a real database. Client tests prove GitHub request building and error translation against a mocked server. Integration tests prove the specifications, pagination and upsert against real PostgreSQL.

### 36. Why Testcontainers instead of H2?

The design relies on PostgreSQL-specific behaviour: `ON CONFLICT`, the `LOWER(language)` functional index, `TIMESTAMPTZ` and null ordering. H2 can pass while production PostgreSQL fails.

### 37. How do you prove the upsert works?

An integration test saves the same GitHub id twice with different values and asserts that the table contains one row with the second value. A separate test proves that one invalid row rolls back the whole batch.

### 38. Why is Docker required for `mvn verify`?

The integration tests start a real `postgres:16` container through Testcontainers. Without a running Docker engine they cannot start. There is no silent skip: the tests are not disabled or conditional.

## Design, limitations and next steps

### 39. What would you change to scale?

A multi-row upsert instead of one statement per row, a cache for repeated searches, background refresh, request metrics, and possibly a queue for large sync jobs. None of these is required by the assignment.

### 40. What is not built?

Authentication, per-client rate limiting, CORS, a frontend, scheduled refresh, retries, a bulk upsert and deployment. The API is intentionally open for the assignment.

### 41. What happens if the same search is run twice?

Both calls return 200. The second one updates the existing rows through `ON CONFLICT`, refreshes `synced_at`, and returns the repositories again. It does not create duplicates and does not fail.

### 42. How fresh is the stored data?

Only as fresh as the last search that returned each repository. There is no webhook or scheduled sync. A row remains unchanged until a later search updates it.
