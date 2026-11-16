# GitHub Repository Searcher — Blueprint & Coding-Agent Prompts

Nov 16, 2026 · @Akshat Raj

## Read me first

This is a complete plan: a blueprint, nine copy-paste prompts for your coding agent, an interview guide and a two-day timeline to the 18 Nov deadline. No application code is written here, as you asked.

**Two things to know up front**

- **The PDF has now arrived.** I compared it with this blueprint; the differences and the fixes are in the next section. Everything else below was drafted from the prompt's restatement and still holds.
- **Time is the real constraint.** Today is Mon 16 Nov and the deadline is 18 Nov. The timeline at the end front-loads the mandatory work and names what to cut first. Confirm the exact submission time of day.

**Key decisions (the rest of the doc explains each)**

| Decision | Choice | Why in one line |
| --- | --- | --- |
| Spring Boot | 3.5.x, not 4.0 | Your brief says 3.x; 3.5 is the final 3.x line and springdoc 2.x targets it |
| HTTP client | `RestClient` | Synchronous, no extra dependency, matches a blocking MVC app; WebClient would pull in the reactive stack |
| Primary key | GitHub repository ID | It is already unique and stable; no second ID to explain |
| Upsert | PostgreSQL `INSERT ... ON CONFLICT DO UPDATE` | One atomic statement, no read-then-write race |
| Schema | Flyway migration, `ddl-auto=validate` | Same schema locally, in tests and in production |
| Filtering | JPA `Specification` + enum-based sort | Optional filters without string-built SQL |
| Boilerplate | Records for DTOs, no Lombok | Fewer moving parts to explain |
| Services | Two: orchestration and storage | Keeps the DB transaction out of the slow HTTP call |

**Version check before Phase 0.** I confirmed from search that springdoc 2.10.0 (24 Oct 2026) targets the Spring Boot 3.5 line. I could not confirm the newest 3.5.x patch number directly, so the prompts tell the agent to check [start.spring.io](https://start.spring.io) or Maven Central and use the latest 3.5.x. Spring Boot 4 and springdoc 3.x exist; do not use them for this submission.

**How to use the prompts**

1. Keep this doc open as the source of truth. Give your agent access to it (paste or save it as `BLUEPRINT.md` in the repo).
2. Run one phase at a time. Paste the phase prompt, let the agent finish, read its completion report, commit, then move on.
3. Never let the agent skip the report. It is your interview study material.

## PDF reconciliation (read before Phase 0)

The PDF matches the blueprint on endpoints, request/response shapes and the mandatory features. It differs on five points. None changes the architecture.

| # | What the PDF says | Blueprint status | Fix |
| --- | --- | --- | --- |
| 1 | Store the 8 fields "along with other relevant statistics" | Only the 8 fields were stored | Also store `html_url`, `open_issues_count`, `created_at` |
| 2 | GET returns `{"repositories": [...]}` | I proposed extra paging fields and default size 20, which could silently truncate | Keep the `repositories` key; paging fields are additive; default size raised to 100 |
| 3 | Search by repository name, partial or full | I searched name, description and README (GitHub's default) | Add `in:name` to `q`. GitHub matches whole words, not arbitrary substrings, so "partial" means any word of the name; say so in the README |
| 4 | "Will prefer TDD" | Prompts said write tests alongside | Prompts now require test-first (red, then green, then refactor) |
| 5 | "Testable by Postman", "clarity of how to run" | Swagger and README only | Add a Postman collection in Phase 6 |

Resolved ambiguities: the GET shape (A4) is now confirmed as compatible, and `sort` values `stars`, `forks`, `updated` (A1) match the PDF. The PDF also says "design patterns" are preferred. Section 10 already names the ones used (Repository, Dependency Injection, client Adapter, DTO/Mapper, enum whitelist); mention them by name in the README and the Loom.

### Consolidated PDF decisions (already merged throughout this document)

These are authoritative requirements, not a separate addendum. Every section and phase prompt below must follow them; if a contradiction remains, this consolidated list wins and the contradiction should be corrected before coding.

1. **Stored fields:** keep the eight core assignment fields and additionally persist `html_url` (`VARCHAR(500)`), `open_issues_count` (`INTEGER NOT NULL DEFAULT 0`) and `created_at` (`TIMESTAMPTZ`). These fields must appear consistently in the Flyway migration, entity, GitHub response DTO, mapper, native upsert, public `RepositoryResponse`, API examples, tests and README. Public JSON names are `htmlUrl`, `openIssues`, `createdAt`, in that order after the eight core fields. Do not rename or change the eight core fields.
2. **GET response and page size:** preserve the required `repositories` array. The pagination metadata (`page`, `size`, `totalElements`, `totalPages`) is additive. Default `size` is **100**, maximum 100. Keep the response wrapper; do not silently truncate at 20.
3. **Name-only search:** construct GitHub `q` as `<query> in:name`, then append `language:<value>` when supplied. Use URI-template encoding. GitHub's search syntax is not arbitrary substring matching; document its actual whole-word/token behaviour accurately and avoid promising substring matches.
4. **TDD:** for each behaviour implemented in Phases 1–5, write the focused test first, run it and capture the failing result (red), implement the minimum code to pass (green), then refactor and rerun. Completion reports must identify the red test and green verification for each behaviour. Do not write implementation first and tests afterwards.
5. **Postman:** Phase 6 must create `docs/postman/github-searcher.postman_collection.json`, with a configurable `baseUrl` and requests for POST search, repeated POST search (upsert), filtered GET, invalid-sort GET, and GET by ID only if that optional endpoint was implemented.
6. **Loom:** follow the assignment form's required duration and visibility instructions. The recording must show the developer's face and screen throughout, demonstrate the deployed API/Swagger, explain the approach and key technical decisions, and discuss one challenge and its solution. Do not substitute a longer recording for the form's specified duration.

The nine phase prompts have been updated to carry these decisions directly. You should not paste or maintain a separate addendum.
## 1. Assignment analysis

**Objective.** Build a Spring Boot REST service that searches GitHub repositories through GitHub's API, saves the results in PostgreSQL without duplicates, and lets a caller query the saved data with filters and sorting, without calling GitHub again.

### Functional requirements (from the assignment)

- **FR1** Java + Spring Boot.
- **FR2** `POST /api/github/search` accepts `query`, `language`, `sort`; validates; calls GitHub `GET /search/repositories`; saves; returns the fetched repositories with a message.
- **FR3** Store: GitHub ID, name, description, owner, language, stars, forks, last updated.
- **FR4** A repository seen again updates the existing row (matched by GitHub ID).
- **FR5** `GET /api/github/repositories` reads only from PostgreSQL, with optional `language`, `minStars`, `sort`; default sort is stars.
- **FR6** Handle invalid GitHub responses, rate limits, empty results, invalid input, database errors.

### Non-functional requirements

Testable (mockable GitHub client), layered and readable, secrets only in environment variables, publicly deployed with Swagger UI, documented, and explainable by you without notes.

### Priorities

| Tier | Items |
| --- | --- |
| Mandatory | FR1 to FR6, unit + integration tests, README, public repo, deployed API + Swagger URL, Loom |
| Important quality | Flyway schema, global error format, pagination on GET, GitHub token support, Dockerfile, health check, JaCoCo report |
| Optional (cut first) | `GET /repositories/{id}`, `order` parameter, one automatic retry on 5xx, GitHub Actions CI, `synced_at` column |

### Ambiguities and the decision taken

These are not in the assignment, so each is labelled as **my proposal**.

| # | Ambiguity | Proposal |
| --- | --- | --- |
| A1 | Is `sort` on POST required? Allowed values? | Optional. Allowed: `stars`, `forks`, `updated` (same words GitHub uses). Omitted means GitHub's default best-match order |
| A2 | Is `language` on POST required? | Optional |
| A3 | `sort` meaning on GET vs POST | POST sort orders GitHub's results. GET sort orders stored rows. Different operations, same vocabulary |
| A4 | GET response shape not given | Wrapper object `{repositories, page, size, totalElements, totalPages}` for consistency with POST. Trivial to change to a bare array |
| A5 | Sort direction | Descending by default. Optional `order=asc\|desc` on GET only |
| A6 | How many results per search | `perPage` default 30, max 100, `page` default 1. One GitHub call per request |
| A7 | HTTP status for POST | 200 OK (it searches and upserts; nothing is created at a new URL) |
| A8 | Empty GitHub result | 200 with empty list and a message; nothing saved |
| A9 | Language comparison on GET | Case-insensitive exact match (`java` finds `Java`) |
| A10 | Repos with null language | Stored as null; excluded by a language filter; included otherwise |

### Risks and edge cases

- **GitHub search rate limit** is low (published as 10 requests per minute unauthenticated, 30 with a token; verify on GitHub's docs). Demo with a token set.
- **1000-result cap**: GitHub search returns at most the first 1000 matches, so deep pages fail. Cap `page * perPage` at 1000 in validation.
- **Nullable fields**: `description` and `language` are often null. Never assume non-null.
- **Query characters**: `C++`, `C#`, and spaces must be URL-encoded correctly.
- **Duplicates inside one response**: unlikely but the upsert makes it harmless.
- **Slow GitHub**: set connect and read timeouts, otherwise a request can hang.
- **Deployment cold starts and DB URL format** are the usual deadline killers; deploy early.

### Requirement traceability matrix

| Requirement | Implementation location | Test case | Priority |
| --- | --- | --- | --- |
| FR1 Java/Spring Boot | Whole project | App context test | Mandatory |
| FR2 POST search | `GithubRepositoryController.search`, `GithubSearchService` | Controller test; service test | Mandatory |
| GitHub integration | `GithubSearchClient` | Client test with mocked HTTP | Mandatory |
| FR3 Fields stored | `GithubRepository` entity, `V1__create_github_repositories.sql` (8 core fields plus html_url, open_issues_count, created_at) | Persistence IT | Mandatory |
| FR4 Upsert | `GithubRepositoryRepository.upsert` | Upsert IT (same ID twice, one row) | Mandatory |
| FR5 GET + filters + sort | `StoredRepositoryService.find`, `GithubRepositorySpecs`, `SortField` | Repository IT; controller test | Mandatory |
| No GitHub call on GET | `StoredRepositoryService` has no client dependency | Controller/service test verifies no client interaction | Mandatory |
| FR6 Invalid GitHub response | `GithubSearchClient`, `GlobalExceptionHandler` | Client test (malformed body, 5xx) | Mandatory |
| FR6 Rate limit | `GithubRateLimitException` | Client + controller test (429 + Retry-After) | Mandatory |
| FR6 Empty results | `GithubSearchService` | Service test | Mandatory |
| FR6 Invalid input | Bean Validation, `SortField.from` | Controller tests (400) | Mandatory |
| FR6 Database errors | `GlobalExceptionHandler` DataAccess handlers | Controller test with failing service | Mandatory |
| Swagger | springdoc config + annotations | Manual check of `/swagger-ui.html` | Mandatory |
| GET by ID | `StoredRepositoryService.findById` | Controller test 200/404 | Optional |

## 2. System architecture

A single Spring Boot application split into layers. One deployable, one database, one outbound HTTP dependency (GitHub). That is a modular monolith.

### Component diagram

```mermaid
flowchart LR
  C[Client: Postman / Swagger UI] -->|HTTP JSON| CT[Controller]
  CT -->|validated DTO| SS[GithubSearchService]
  CT -->|filters| ST[StoredRepositoryService]
  SS --> GC[GithubSearchClient]
  GC -->|HTTPS| GH[(GitHub Search API)]
  SS --> MP[GithubRepositoryMapper]
  SS --> ST
  ST --> RP[GithubRepositoryRepository]
  RP -->|JDBC| DB[(PostgreSQL)]
  EH[GlobalExceptionHandler] -.catches exceptions from.-> CT
```

### Component responsibilities

| Component | Does | Does not |
| --- | --- | --- |
| Controller | HTTP in/out, `@Valid`, status codes | Business logic, DB or GitHub calls |
| `GithubSearchService` | Orchestrates: call GitHub, map, store, build response | HTTP details, SQL |
| `GithubSearchClient` | All GitHub HTTP: URL, headers, auth, timeouts, error translation | Persistence, business rules |
| `GithubRepositoryMapper` | GitHub DTO to entity/row values, entity to response DTO, null handling | I/O |
| `StoredRepositoryService` | Transactional upsert and filtered reads | Calls GitHub |
| Repository + Specs | Database access | Business decisions |
| `GlobalExceptionHandler` | Turns exceptions into one error JSON shape | Swallow errors silently |

### Request lifecycle: POST /api/github/search

```mermaid
sequenceDiagram
  participant U as Client
  participant C as Controller
  participant S as GithubSearchService
  participant G as GithubSearchClient
  participant H as GitHub API
  participant T as StoredRepositoryService
  participant D as PostgreSQL
  U->>C: POST /api/github/search (JSON)
  C->>C: Bean Validation (@Valid)
  C->>S: search(SearchRequest)
  S->>G: search(query, language, sort, page, perPage)
  G->>H: GET /search/repositories?q=...
  H-->>G: 200 JSON (or 403/429/5xx)
  G-->>S: GithubSearchResponse (or typed exception)
  S->>S: map items, skip if empty
  S->>T: upsertAll(rows)
  T->>D: INSERT ... ON CONFLICT (id) DO UPDATE (one transaction)
  D-->>T: ok
  T-->>S: done
  S-->>C: SearchResponse(message, repositories)
  C-->>U: 200 OK JSON
```

### Persistence flow

The search service calls GitHub first, outside any database transaction, then hands mapped rows to `StoredRepositoryService.upsertAll`, which opens one transaction and runs one upsert per repository. If any statement fails, the whole batch rolls back.

### Retrieval flow: GET /api/github/repositories

Controller validates parameters, `SortField.from` converts the `sort` text to an enum, `StoredRepositoryService.find` builds a `Specification` (language, minStars) plus a `Pageable` with a whitelisted `Sort`, the repository runs one SQL query, entities are mapped to response DTOs. GitHub is never contacted.

### Error flow

Any layer throws a typed exception. Nothing catches it on the way up except `GithubSearchClient` (which translates low-level HTTP errors into typed ones). `GlobalExceptionHandler` maps each type to a status and the standard error JSON (Section 11).

### Why a modular monolith fits

One small domain, one team of one, a two-day deadline. Layers give separation and testability; microservices, queues or caches would add failure modes without solving a problem this app has.

## 3. Technology and dependency decisions

Use Spring Boot's parent POM so almost every version is managed for you. Only two things need an explicit version: springdoc and the JaCoCo plugin.

| Dependency | Purpose | Why it is needed | Mandatory? |
| --- | --- | --- | --- |
| Java 21 (LTS) | Language runtime | Required by brief; works with Boot 3.5 | Mandatory |
| `spring-boot-starter-parent` 3.5.x | Version management | One line keeps all libraries compatible. Use the newest 3.5.x patch (springdoc 2.10.0 was built on 3.5.16) | Mandatory |
| `spring-boot-starter-web` | REST controllers, Jackson JSON, embedded Tomcat, `RestClient` | The web layer and the GitHub HTTP client | Mandatory |
| `spring-boot-starter-data-jpa` | Spring Data repositories + Hibernate | Entity mapping and queries | Mandatory |
| `org.postgresql:postgresql` (runtime) | JDBC driver | Talks to PostgreSQL | Mandatory |
| `spring-boot-starter-validation` | Jakarta Bean Validation | `@NotBlank`, `@Min`, `@Size` on requests | Mandatory |
| `spring-boot-starter-actuator` | `/actuator/health` | Hosting health check; expose only `health` | Important |
| `flyway-core` + `flyway-database-postgresql` | SQL migrations | Reproducible schema locally, in tests, in production. Flyway 11 needs the PostgreSQL module | Important |
| `springdoc-openapi-starter-webmvc-ui` 2.10.0 | OpenAPI + Swagger UI | Required Swagger URL. Pick the 2.x line for Boot 3.x; fallback 2.8.x | Mandatory |
| `spring-boot-starter-test` | JUnit 5, Mockito, AssertJ, MockMvc, `MockRestServiceServer` | All unit and web-slice tests | Mandatory |
| `spring-boot-testcontainers` | `@ServiceConnection` wiring | Starts Postgres in tests with zero property wiring | Important |
| `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql` | Real PostgreSQL in Docker for tests | Upsert uses PostgreSQL-only SQL, so H2 would test the wrong thing | Important |
| `jacoco-maven-plugin` 0.8.13 | Coverage report | Evidence for the coverage target | Optional |

**Deliberately not used:** Lombok (records and plain classes are easier to explain), WebFlux/WebClient (adds a reactive stack you do not need), H2 (hides PostgreSQL behavior), WireMock (`MockRestServiceServer` already ships with Spring), MapStruct (one small hand-written mapper is clearer), Spring Security (not required).

**Testcontainers versions** are managed by the Boot BOM, so no version is written. This needs Docker running on your machine and in any CI.

### pom.xml essentials

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.16</version> <!-- confirm newest 3.5.x on start.spring.io -->
  <relativePath/>
</parent>

<properties>
  <java.version>21</java.version>
  <springdoc.version>2.10.0</springdoc.version>
  <jacoco.version>0.8.13</jacoco.version>
</properties>

<dependencies>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
  <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
  <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
  <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
  <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId><version>${springdoc.version}</version></dependency>

  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
</dependencies>

<build>
  <plugins>
    <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
    <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>${jacoco.version}</version>
      <executions>
        <execution><goals><goal>prepare-agent</goal></goals></execution>
        <execution><id>report</id><phase>verify</phase><goals><goal>report</goal></goals></execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

If `3.5.16` or springdoc `2.10.0` does not resolve, use the newest 3.5.x and springdoc 2.8.x, and note the change in the Phase 0 report. Springdoc 2.8.8 failed at startup with Boot 3.5.0 (fixed in 2.8.9), so use 2.8.9 or newer on the 3.5 line.

## 4. Database design

One table, no relationships. The owner is stored as a plain column because the assignment only asks for the owner name.

### Primary key decision

Use **GitHub's repository ID as the primary key** (`BIGINT`). It is already unique and stable, it is what the assignment says identifies a repository, and it removes a second ID you would have to explain. A separate surrogate key plus a unique constraint would work but adds a column and a join-free lookup you never use. The primary key itself is the unique constraint.

One caveat to say out loud in an interview: if you ever stored repositories from a second source, this key would collide. That is not a requirement here.

### Schema (Flyway `V1__create_github_repositories.sql`)

```sql
CREATE TABLE github_repositories (
    id            BIGINT        PRIMARY KEY,
    name          VARCHAR(255)  NOT NULL,
    description   TEXT,
    owner         VARCHAR(255)  NOT NULL,
    language      VARCHAR(100),
    stars         INTEGER       NOT NULL DEFAULT 0,
    forks         INTEGER       NOT NULL DEFAULT 0,
    last_updated       TIMESTAMPTZ,
    html_url           VARCHAR(500),
    open_issues_count  INTEGER       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ,
    synced_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_repo_language_lower ON github_repositories (LOWER(language));
CREATE INDEX idx_repo_stars        ON github_repositories (stars DESC);
CREATE INDEX idx_repo_forks        ON github_repositories (forks DESC);
CREATE INDEX idx_repo_last_updated ON github_repositories (last_updated DESC);
```

| Column | Type | Null? | Notes |
| --- | --- | --- | --- |
| `id` | BIGINT | No | GitHub's ID; primary key |
| `name` | VARCHAR(255) | No | Repository name, e.g. `spring-boot` |
| `description` | TEXT | Yes | Often null on GitHub |
| `owner` | VARCHAR(255) | No | `owner.login` |
| `language` | VARCHAR(100) | Yes | Null for repos with no detected language |
| `stars`, `forks` | INTEGER | No | From `stargazers_count`, `forks_count`; default 0 |
| `last_updated` | TIMESTAMPTZ | Yes | From GitHub `updated_at`, stored in UTC |
| `html_url` | VARCHAR(500) | Yes | GitHub repository web URL |
| `open_issues_count` | INTEGER | No | From GitHub `open_issues_count`; defaults to 0 |
| `created_at` | TIMESTAMPTZ | Yes | From GitHub `created_at`, stored in UTC |
| `synced_at` | TIMESTAMPTZ | No | When we last saved it; optional extra that makes upsert visible in tests |

Indexes help the filter and sort queries. With a few thousand rows PostgreSQL may still choose a sequential scan; say that honestly if asked. They are cheap to include and show you thought about access patterns.

### Example rows

| id | name | owner | language | stars | forks | last\_updated |
| --- | --- | --- | --- | --- | --- | --- |
| 6296790 | spring-boot | spring-projects | Java | 75000 | 41000 | 2026-11-10T09:12:00Z |
| 1234567 | tiny-notes | alice | null | 3 | 0 | 2025-02-01T00:00:00Z |

(Illustrative values, not real data.)

### Entity mapping

`GithubRepository` is a JPA `@Entity` on `github_repositories`. The `id` is `@Id` with **no** `@GeneratedValue` because GitHub assigns it. Map `htmlUrl` to `html_url`, `openIssues` to `open_issues_count`, and `createdAt` to `created_at`. `synced_at` is mapped `insertable = false, updatable = false` so the database default and the upsert SQL own it. Use `Instant` for timestamps and `Integer` for counts. Set `spring.jpa.hibernate.ddl-auto=validate` so Hibernate checks the mapping against the Flyway-created table but never changes it.

### Upsert strategy: `save()` vs database upsert

|  | Spring Data `save()` | PostgreSQL `INSERT ... ON CONFLICT DO UPDATE` |
| --- | --- | --- |
| How it works | Because the ID is assigned, Spring cannot tell new from existing. It runs a SELECT, then INSERT or UPDATE | One SQL statement; the database decides |
| Race safety | Two concurrent requests can both see no row and both insert; one fails | Atomic; no race |
| Round trips | 2 per row | 1 per row |
| Portability | Any database | PostgreSQL syntax |
| Explainability | Easy | Easy once you read the SQL |

**Choice: database-level upsert** via a native `@Modifying @Query` on the repository. It is correct under concurrency and matches the assignment's "update instead of duplicate" wording exactly. Do not also keep a `save()` path for writes; one persistence strategy only. The repository (and Spring Data derived queries) is still used for all reads.

```sql
INSERT INTO github_repositories
  (id, name, description, owner, language, stars, forks, last_updated, html_url, open_issues_count, created_at, synced_at)
VALUES (:id, :name, :description, :owner, :language, :stars, :forks, :lastUpdated, :htmlUrl, :openIssues, :createdAt, now())
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name, description = EXCLUDED.description, owner = EXCLUDED.owner,
  language = EXCLUDED.language, stars = EXCLUDED.stars, forks = EXCLUDED.forks,
  last_updated = EXCLUDED.last_updated, html_url = EXCLUDED.html_url,
  open_issues_count = EXCLUDED.open_issues_count, created_at = EXCLUDED.created_at,
  synced_at = now();
```

The service loops over the (at most 100) rows inside one `@Transactional` method. A single multi-row statement is an optimization you do not need.

## 5. API design

All endpoints return JSON. No authentication headers are required by the assignment. Request header for POST: `Content-Type: application/json`.

### 5.1 POST /api/github/search (mandatory)

Calls GitHub, saves the results (upsert), returns what was fetched.

**Request body**

```json
{
  "query": "spring boot",
  "language": "Java",
  "sort": "stars",
  "page": 1,
  "perPage": 30
}
```

| Field | Required | Rules |
| --- | --- | --- |
| `query` | Yes | `@NotBlank`, max 256 characters |
| `language` | No | Max 50 characters, pattern `^[A-Za-z0-9 +#.\-]+$` (allows `C++`, `C#`, `Jupyter Notebook`) |
| `sort` | No | One of `stars`, `forks`, `updated` (case-insensitive). Omitted means GitHub best match |
| `page` | No | Integer >= 1, default 1 |
| `perPage` | No | 1 to 100, default 30. `page * perPage` must be <= 1000 (GitHub cap) |

**Success: 200 OK**

```json
{
  "message": "Repositories fetched and saved successfully",
  "repositories": [
    {
      "id": 123456,
      "name": "spring-boot-example",
      "description": "An example repository for Spring Boot",
      "owner": "user123",
      "language": "Java",
      "stars": 450,
      "forks": 120,
      "lastUpdated": "2024-01-01T12:00:00Z", "htmlUrl": "https://github.com/user123/spring-boot-example", "openIssues": 12, "createdAt": "2023-12-01T10:00:00Z"
    }
  ]
}
```

**Empty result: 200 OK**, `{"message": "No repositories found for the given search criteria", "repositories": []}`. Nothing is written.

**Empty success vs failure.** GitHub answering with zero matches is a *successful* search with a valid answer: the call worked, the world just has no match. A timeout, a 403 rate limit or a 500 means we do not know the answer. That is an error and must not look like "no results".

**Status codes**

| Status | When |
| --- | --- |
| 200 | Search done (including zero results) |
| 400 | Validation failure, malformed JSON, invalid sort, GitHub rejected the query (422) |
| 429 | GitHub rate limit hit. Include a `Retry-After` header when known |
| 502 | GitHub 401/5xx, or an unreadable GitHub response |
| 503 | Database unreachable |
| 504 | GitHub timed out |
| 500 | Anything unexpected |

**Postman:** `POST {{baseUrl}}/api/github/search`, Body > raw > JSON, as above.

### 5.2 GET /api/github/repositories (mandatory)

Reads only from PostgreSQL. Never calls GitHub.

| Query parameter | Required | Default | Rules |
| --- | --- | --- | --- |
| `language` | No | none | Case-insensitive exact match; max 50 chars |
| `minStars` | No | none | Integer >= 0 |
| `sort` | No | `stars` | `stars`, `forks`, `updated` |
| `order` | No | `desc` | `asc` or `desc` (optional extension) |
| `page` | No | 0 | Integer >= 0 (zero-based) |
| `size` | No | 100 | 1 to 100 |

Example: `GET /api/github/repositories?language=Java&minStars=100&sort=stars`

**Success: 200 OK**

```json
{
  "repositories": [ { "id": 123456, "name": "spring-boot-example", "description": "...", "owner": "user123", "language": "Java", "stars": 450, "forks": 120, "lastUpdated": "2024-01-01T12:00:00Z", "htmlUrl": "https://github.com/user123/spring-boot-example", "openIssues": 12, "createdAt": "2023-12-01T10:00:00Z" } ],
  "page": 0,
  "size": 100,
  "totalElements": 1,
  "totalPages": 1
}
```

No matches is still **200** with an empty `repositories` array: the query was valid, the table just has no matching rows. Status codes: 200, 400 (bad sort, negative `minStars`, bad `page`/`size`), 503/500 for database failure.

### 5.3 GET /api/github/repositories/{id} (optional, build last)

Returns one stored repository. **200** with the repository object; **404** if the ID is not stored; **400** if `{id}` is not a number.

### 5.4 Standard error body (all non-2xx)

```json
{
  "timestamp": "2026-11-16T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid sort field 'rating'. Allowed values: stars, forks, updated",
  "path": "/api/github/repositories"
}
```

For validation errors with several problems, add an optional `details` array of `{field, message}`. Never include stack traces or internal class names.

### Example error scenarios

| Request | Status | Message (example) |
| --- | --- | --- |
| POST with `"query": ""` | 400 | `query must not be blank` |
| POST with `"sort": "rating"` | 400 | `Invalid sort field 'rating'. Allowed values: stars, forks, updated` |
| GET `minStars=-5` | 400 | `minStars must be greater than or equal to 0` |
| GET `sort=rating` | 400 | same sort message |
| GitHub rate limited | 429 | `GitHub API rate limit exceeded. Retry after 42 seconds` |
| GitHub timeout | 504 | `GitHub API did not respond in time` |
| GitHub down | 502 | `GitHub API is currently unavailable` |
| DB down | 503 | `Database is temporarily unavailable` |

## 6. GitHub API integration

Have the agent confirm details against [GitHub's REST docs for searching repositories](https://docs.github.com/en/rest/search/search#search-repositories) in Phase 2; the points below come from the assignment plus my knowledge of the API, so treat numbers like rate limits as "verify".

**Endpoint:** `GET https://api.github.com/search/repositories`

### Query construction

| Parameter | Value |
| --- | --- |
| `q` | `<query> in:name`, plus `language:<value>` when a language is given. Quote the language if it has a space: `language:"Jupyter Notebook"` |
| `sort` | `stars`, `forks` or `updated`. Omit it entirely when the user gave none (GitHub then sorts by best match) |
| `order` | `desc` whenever `sort` is set |
| `page`, `per_page` | From the request; defaults 1 and 30; max 100 per page |

Let `RestClient` do the URL encoding by passing `q` as a URI template variable. Never build the URL by string concatenation (`C++` and `C#` break otherwise). Example result: `q=spring boot in:name language:Java`, `sort=stars`, `order=desc`.

### Headers sent to GitHub

`Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, a `User-Agent` (GitHub requires one), and `Authorization: Bearer <token>` **only** if `GITHUB_TOKEN` is set. The token is optional and read from the environment (`github.token` property bound to `${GITHUB_TOKEN:}`). Never log it.

### Response fields used

GitHub returns `total_count`, `incomplete_results` and an `items` array. Per item we need:

| GitHub field | Our field | Null handling |
| --- | --- | --- |
| `id` | `id` | Skip an item with no ID (log a warning) |
| `name` | `name` | Skip if null |
| `description` | `description` | Keep null |
| `owner.login` | `owner` | Skip item if `owner` missing |
| `language` | `language` | Keep null |
| `stargazers_count` | `stars` | Null becomes 0 |
| `forks_count` | `forks` | Null becomes 0 |
| `updated_at` | `lastUpdated` | Parse ISO-8601 to `Instant`; null stays null |
| `html_url` | `htmlUrl` | Keep the repository URL |
| `open_issues_count` | `openIssues` | Null becomes 0 |
| `created_at` | `createdAt` | Parse ISO-8601 to `Instant`; null stays null |

GitHub DTO records carry `@JsonIgnoreProperties(ignoreUnknown = true)` so new GitHub fields never break parsing.

### Rate limits

GitHub signals them with HTTP 403 or 429 plus headers `X-RateLimit-Remaining`, `X-RateLimit-Reset` (epoch seconds) and sometimes `Retry-After`. Rule: a 429, or a 403 with `X-RateLimit-Remaining: 0` or a `Retry-After` header, becomes `GithubRateLimitException(retryAfterSeconds)`, returned to our caller as 429 with a `Retry-After` header. Compute seconds from `Retry-After`, else from `X-RateLimit-Reset` minus now, else omit. Any other 403 is treated as a GitHub access error (502). As published, search allows about 10 requests per minute unauthenticated and 30 with a token; verify.

### Failure mapping

| GitHub / network outcome | Exception | Our status |
| --- | --- | --- |
| 200 with valid JSON | none | 200 |
| 200 with unparseable body or missing `items` | `GithubApiException` | 502 |
| 401 (bad token) | `GithubApiException` (log, do not leak) | 502 |
| 403/429 rate limit | `GithubRateLimitException` | 429 |
| 422 (GitHub rejected the query) | `GithubApiException` with 400 | 400 |
| 5xx | `GithubApiException` | 502 |
| Connect/read timeout | `GithubApiException` with 504 | 504 |

### Timeouts and retries

Connect timeout 5 s, read timeout 10 s, both configurable in `application.yml`. **No automatic retry by default**: the call is user-initiated, the rate limit is tight and a retry can burn quota. An optional extension is one retry with a short delay on 502/503 and I/O errors only. Never retry a rate limit.

### Two different sorts. Do not confuse them

|  | POST search | GET stored |
| --- | --- | --- |
| Where | GitHub's servers | Your PostgreSQL |
| What `sort` does | Chooses which repositories come back and in what order, from all of GitHub | Orders rows you already saved |
| Filtering | `language:` qualifier in `q` | `WHERE` clause on your table |

A search with `sort=forks` and a later GET with `sort=stars` are unrelated. The stored table is a growing union of everything you have searched.

### Keeping it mockable

All of this lives in one class, `GithubSearchClient`, with one public method `search(...)` that returns `GithubSearchResponse` or throws a typed exception. Services depend on that class only, so unit tests mock it with Mockito, and the client's own tests use `MockRestServiceServer` with canned JSON. No ordinary test touches the real GitHub.

## 7. Package structure

Base package `com.example.githubsearch`. No empty packages; no interfaces unless a second implementation exists.

```text
github-repository-searcher/
├── pom.xml
├── Dockerfile
├── .dockerignore
├── .env.example
├── .gitignore
├── README.md
├── docs/BLUEPRINT.md, INTERVIEW_GUIDE.md
└── src/
    ├── main/
    │   ├── java/com/example/githubsearch/
    │   │   ├── GithubRepositorySearcherApplication.java
    │   │   ├── config/
    │   │   │   ├── GithubProperties.java        (token, baseUrl, timeouts; @ConfigurationProperties)
    │   │   │   ├── RestClientConfig.java        (builds the GitHub RestClient bean)
    │   │   │   └── OpenApiConfig.java           (title, description)
    │   │   ├── controller/
    │   │   │   └── GithubRepositoryController.java
    │   │   ├── dto/
    │   │   │   ├── SearchRequest.java
    │   │   │   ├── SearchResponse.java
    │   │   │   ├── RepositoryResponse.java
    │   │   │   ├── RepositoryPageResponse.java
    │   │   │   ├── ErrorResponse.java
    │   │   │   └── github/
    │   │   │       ├── GithubSearchResponse.java
    │   │   │       ├── GithubRepositoryItem.java
    │   │   │       └── GithubOwner.java
    │   │   ├── client/
    │   │   │   └── GithubSearchClient.java
    │   │   ├── entity/
    │   │   │   └── GithubRepository.java
    │   │   ├── repository/
    │   │   │   ├── GithubRepositoryRepository.java
    │   │   │   └── GithubRepositorySpecs.java
    │   │   ├── mapper/
    │   │   │   └── GithubRepositoryMapper.java
    │   │   ├── model/
    │   │   │   └── SortField.java               (enum: STARS, FORKS, UPDATED)
    │   │   ├── service/
    │   │   │   ├── GithubSearchService.java
    │   │   │   └── StoredRepositoryService.java
    │   │   └── exception/
    │   │       ├── GithubApiException.java
    │   │       ├── GithubRateLimitException.java
    │   │       ├── InvalidRequestException.java
    │   │       ├── RepositoryNotFoundException.java
    │   │       └── GlobalExceptionHandler.java
    │   └── resources/
    │       ├── application.yml
    │       ├── application-prod.yml
    │       └── db/migration/V1__create_github_repositories.sql
    └── test/
        ├── java/com/example/githubsearch/
        │   ├── support/PostgresTestConfig.java   (Testcontainers + @ServiceConnection)
        │   ├── client/GithubSearchClientTest.java
        │   ├── mapper/GithubRepositoryMapperTest.java
        │   ├── model/SortFieldTest.java
        │   ├── repository/GithubRepositoryRepositoryIT.java
        │   ├── service/GithubSearchServiceTest.java
        │   ├── service/StoredRepositoryServiceTest.java
        │   ├── controller/GithubRepositoryControllerTest.java
        │   └── integration/SearchFlowIT.java
        └── resources/github/ (sample JSON: search-ok.json, search-empty.json, rate-limit.json)
```

### Package rules

| Package | Responsibility | May depend on | Must not |
| --- | --- | --- | --- |
| `controller` | HTTP mapping, validation trigger, status codes | `service`, `dto`, `model` | Touch repositories, entities or the client |
| `service` | Orchestration, transactions | `client`, `repository`, `mapper`, `dto`, `model` | Know about HTTP request/response objects |
| `client` | All GitHub HTTP | `config`, `dto.github`, `exception` | Touch the database |
| `repository` | Data access | `entity` | Contain business rules |
| `entity` | Table mapping | nothing | Leave the service layer (never returned from controllers) |
| `mapper` | Conversions and null defaults | `dto`, `entity` | Do I/O |
| `exception` | Typed errors + one handler | `dto` | Swallow exceptions |

## 8. Class-level design

| Class | Responsibility | Main methods | Dependencies |
| --- | --- | --- | --- |
| `GithubRepositoryController` | Three endpoints; documents them with OpenAPI annotations | `search(@Valid SearchRequest)`, `list(language, minStars, sort, order, page, size)`, `getById(id)` | `GithubSearchService`, `StoredRepositoryService` |
| `SearchRequest` (record) | Validated POST body | accessors; defaults applied in a compact constructor or getters | none |
| `SearchResponse` (record) | `message` + `repositories` | none | none |
| `RepositoryResponse` (record) | Public shape of one repository: the eight core fields followed by `htmlUrl`, `openIssues`, `createdAt` | none | none |
| `RepositoryPageResponse` (record) | List + paging info | none | none |
| `GithubRepository` (entity) | Maps the table | getters; no business logic | none |
| `GithubRepositoryRepository` | Spring Data repo + upsert | `upsert(...)`, inherits `findAll(Specification, Pageable)` | JPA |
| `GithubRepositorySpecs` | Builds optional predicates | `hasLanguage(String)`, `minStars(Integer)` | JPA Criteria |
| `GithubSearchClient` | Calls GitHub, translates errors | `search(query, language, sort, page, perPage)` returns `GithubSearchResponse` | `RestClient`, `GithubProperties` |
| `GithubRepositoryMapper` | Converts, applies null rules | `toRows(GithubSearchResponse)`, `toResponse(GithubRepository)`, `toResponse(item)` | none |
| `GithubSearchService` | Orchestrates the search use case | `search(SearchRequest)` returns `SearchResponse` | client, mapper, `StoredRepositoryService` |
| `StoredRepositoryService` | Transactional persistence and reads | `upsertAll(List<RepositoryRow>)`, `find(language, minStars, SortField, order, page, size)`, `findById(id)` | repository, mapper |
| `SortField` (enum) | Whitelist of sort fields | `from(String)`, `githubValue()`, `entityProperty()` | none |
| `GithubProperties` | Typed config | `token()`, `baseUrl()`, `connectTimeout()`, `readTimeout()` | none |
| Exceptions | Typed failures | constructors; `GithubRateLimitException.retryAfterSeconds()` | none |
| `GlobalExceptionHandler` | `@RestControllerAdvice` mapping to `ErrorResponse` | one `@ExceptionHandler` per type | none |

### Pseudocode for the core flow

```text
GithubSearchService.search(req):
    response = client.search(req.query, req.language, req.sort, req.page, req.perPage)   // throws typed exceptions
    if response.items is empty:
        return SearchResponse("No repositories found for the given search criteria", [])
    rows = mapper.toRows(response)          // skips invalid items, applies null defaults
    storedRepositoryService.upsertAll(rows) // @Transactional, one upsert per row
    return SearchResponse("Repositories fetched and saved successfully", rows -> responses)

StoredRepositoryService.find(language, minStars, sortField, order, page, size):
    spec = Specification.where(hasLanguage(language)).and(minStars(minStars))
    sort = Sort.by(direction, sortField.entityProperty()).and(Sort.by("id"))   // tie-breaker
    pageable = PageRequest.of(page, size, sort)
    return repository.findAll(spec, pageable).map(mapper::toResponse)
```

`RepositoryRow` can simply be the `GithubRepository` entity instances built by the mapper and passed to `upsertAll`, which calls the repository's `upsert` with each field. Do not introduce a separate row class unless the agent finds it necessary.

## 9. Filtering and sorting logic

All filtering and sorting happens **in PostgreSQL**, never by loading everything and filtering in Java.

| Topic | Decision |
| --- | --- |
| Filters | `language` (case-insensitive exact), `minStars` (stars >= value). Both optional and combinable with AND |
| Sort fields | Enum `SortField`: `STARS("stars")`, `FORKS("forks")`, `UPDATED("updated")`. Each knows its GitHub parameter value and its entity property (`stars`, `forks`, `lastUpdated`) |
| Default | `stars`, descending |
| Direction | `order=desc` (default) or `asc` on GET |
| Invalid sort | `SortField.from(text)` throws `InvalidRequestException` listing allowed values, which becomes a 400. Never pass raw request text to a query or `Sort.by` |
| Null language | Rows with null language are returned when no language filter is set, and excluded when one is |
| Null `last_updated` | Must sort last for descending. Verify the generated PostgreSQL ordering with a Testcontainers integration test; do not assume a Spring `nullsLast()` hint produces the intended SQL. If it does not, use the smallest explicit PostgreSQL-compatible ordering implementation and explain it. |
| Tie-breaker | Add `id` as a second sort key so pages are stable |
| Mechanism | `JpaSpecificationExecutor` with small `Specification` methods that return `null` when the filter is absent. This avoids the `:param is null` JPQL pattern, which can fail type inference on PostgreSQL |
| SQL injection | Values are bound parameters; the sort property comes from the enum whitelist, never from user text |
| Pagination | `page` default 0, `size` default 100, max 100 (validated); response includes `totalElements` and `totalPages` |

## 10. Design patterns and SOLID

Use only what solves a real problem here.

| Pattern | Where | Problem solved | Simpler alternative enough? |
| --- | --- | --- | --- |
| Dependency Injection | Constructor injection everywhere | Classes get collaborators without creating them, so tests can pass mocks | No. It is how Spring works |
| Repository | `GithubRepositoryRepository` | Hides SQL behind an interface | Yes in tiny apps, but Spring Data gives it for free |
| Adapter/client wrapper | `GithubSearchClient` | Isolates GitHub's JSON, headers and errors from business code | Needed: it is what makes the service testable |
| Enum whitelist | `SortField` | Safe, validated sort selection | A string switch would work but scatters the logic. This is the "Strategy-lite" version; a full Strategy pattern would be over-engineering |
| DTO / mapper | `dto`, `GithubRepositoryMapper` | API shape independent from table shape; GitHub shape independent from both | Needed: your brief forbids exposing entities |

**Not used on purpose:** factories, builders, abstract base classes, service interfaces with one implementation, CQRS.

**SOLID with real examples**

- **S**: `GithubSearchClient` only talks HTTP; `GithubRepositoryMapper` only converts; the controller only handles HTTP.
- **O**: adding a fourth sort field means adding one enum constant, with no change to the service or repository.
- **L**: not a big factor here; no inheritance hierarchies were introduced, which avoids violations.
- **I**: the repository interface exposes only what the app needs (plus Spring Data's standard methods).
- **D**: services depend on injected collaborators, not on `new`; tests replace the client with a mock.

## 11. Error handling and validation

One `@RestControllerAdvice` class converts exceptions into the standard `ErrorResponse`. Validation uses annotations; business rules throw typed exceptions.

| Failure | Exception | Handled where | Status |
| --- | --- | --- | --- |
| Invalid JSON body | `HttpMessageNotReadableException` | Global handler | 400 |
| Blank query, bad `perPage`, etc. | `MethodArgumentNotValidException` | Global handler (adds `details`) | 400 |
| Bad GET params (`minStars=-1`, `size=1000`) | `HandlerMethodValidationException` and `ConstraintViolationException` | Global handler | 400 |
| Non-numeric `{id}` or `minStars=abc` | `MethodArgumentTypeMismatchException` | Global handler | 400 |
| Invalid sort field | `InvalidRequestException` | Global handler | 400 |
| GitHub timeout | `GithubApiException` (504) | Thrown by client | 504 |
| GitHub rate limit | `GithubRateLimitException` | Thrown by client; handler sets `Retry-After` | 429 |
| GitHub 401 or 5xx | `GithubApiException` (502) | Thrown by client | 502 |
| GitHub 422 | `GithubApiException` (400) | Thrown by client | 400 |
| Unexpected GitHub body | `GithubApiException` (502) | Thrown by client | 502 |
| DB connection failure | `DataAccessResourceFailureException` | Global handler | 503 |
| Other DB error | `DataAccessException` | Global handler (log full error, generic message) | 500 |
| Repository not found (by ID) | `RepositoryNotFoundException` | Global handler | 404 |
| Anything else | `Exception` | Global handler (log, generic message) | 500 |

**Rules**

- Only `GithubSearchClient` catches low-level exceptions (`RestClientResponseException`, `ResourceAccessException`) and re-throws typed ones. Everything else lets exceptions propagate.
- Log the real cause server-side at WARN/ERROR. Return a safe message to the client. Never leak SQL, stack traces, URLs with tokens.
- Every error uses `ErrorResponse(timestamp, status, error, message, path[, details])`.

## 12. Testing strategy

Three layers, fastest first. **No ordinary test calls the real GitHub.**

| Layer | Tooling | What it covers | Speed |
| --- | --- | --- | --- |
| Pure unit | JUnit 5, Mockito, AssertJ | `GithubRepositoryMapper`, `SortField`, `GithubSearchService` (mocked client and storage), `StoredRepositoryService` logic | Milliseconds |
| HTTP client | `MockRestServiceServer` bound to the `RestClient.Builder` | `GithubSearchClient`: URL and query encoding, headers, token present/absent, 200, empty, 403 rate limit with headers, 422, 5xx, malformed JSON, timeout simulation | Milliseconds |
| Web slice | `@WebMvcTest` with `@MockitoBean` services | Status codes, validation messages, JSON shapes, error format | Fast |
| Database | `@DataJpaTest` + Testcontainers PostgreSQL (`@ServiceConnection`), `@AutoConfigureTestDatabase(replace = NONE)` | Flyway migration, upsert, filters, sorts, null handling | Seconds (container start) |
| Full flow | `@SpringBootTest` + MockMvc + Testcontainers, with `@MockitoBean GithubSearchClient` | POST search persists, repeat updates, GET reads it back, no client call on GET | Seconds |

Note: `@MockBean` is deprecated in Boot 3.4+; use `@MockitoBean`. Name database tests `*IT` by convention but run them with the normal `mvn test` unless you want to configure Failsafe. Docker must be running. If Docker is unavailable on a machine, use `@Testcontainers(disabledWithoutDocker = true)` so the build does not fail, but remember those tests are then skipped, not passed.

### Test matrix

| Test scenario | Expected result | Test layer |
| --- | --- | --- |
| Valid GitHub search | Repositories fetched, saved, returned | Service + Full flow |
| Empty GitHub results | Empty list, message says none found, nothing saved | Service |
| GitHub item with null description/language | Saved with nulls, no error | Mapper + Service |
| GitHub item missing ID or owner | Item skipped, others saved | Mapper |
| Same repository returned twice | One row, fields updated | Repository IT |
| Duplicate repository ID in one batch | No duplicate, no exception | Repository IT |
| Language filter | Only matching rows, case-insensitive | Repository IT |
| Minimum stars filter | Only rows with stars >= value (boundary included) | Repository IT |
| Language + minStars together | Intersection | Repository IT |
| Sort by stars / forks / updated | Correct order, desc default, asc with `order=asc` | Repository IT |
| Default sort | Stars descending | Repository IT + Controller |
| Invalid sort (GET and POST) | 400 with allowed values in message | Controller |
| Negative `minStars`, `size` over 100 | 400 | Controller |
| Blank or missing query | 400 with field detail | Controller |
| Malformed JSON | 400 | Controller |
| GitHub rate limit | 429 plus `Retry-After` | Client + Controller |
| GitHub timeout | 504 | Client + Controller |
| GitHub 5xx / malformed body | 502 | Client |
| GitHub 401 | 502, token not leaked | Client |
| Query with `C++` / spaces | Correctly encoded `q` | Client |
| Token configured / not configured | `Authorization` header present / absent | Client |
| Database failure | 503/500 error JSON | Controller (service mocked to throw) |
| GET by ID found / missing | 200 / 404 | Controller |
| GET does not call GitHub | `verifyNoInteractions(client)` | Full flow |
| Context loads, Flyway migrates | Application starts | Full flow |

### Execution strategy

Write the mapper, client and service tests alongside each class (Phases 1 to 4), not at the end. Phase 5 is for gaps, bugs and a full clean run: `mvn clean verify`. A test that fails is a finding: fix the code, not the assertion.

### Coverage target

Aim for roughly 80% line coverage on `service`, `client`, `mapper`, `controller`, and `exception` packages, measured with JaCoCo (`target/site/jacoco/index.html`). Do not test getters, records, or config just to move the number. A meaningful 75% beats a padded 95%. Say in the README what is deliberately not covered.

## 13. Security and configuration

| Topic | Approach |
| --- | --- |
| Environment variables | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `GITHUB_TOKEN` (optional), `PORT`, `SPRING_PROFILES_ACTIVE`. `application.yml` references them as `${DB_URL:jdbc:postgresql://localhost:5432/github_searcher}` with safe local defaults only for non-secret values |
| `.env.example` | Names and dummy values only. The real `.env` is in `.gitignore`. Spring Boot does not read `.env` by itself: export variables in your shell, set them in your IDE run config, or use `docker compose --env-file` |
| GitHub token | A fine-grained or classic token with **no scopes** is enough for public search. Never log it, never return it, never commit it |
| Database credentials | Environment only. Never in `application.yml` or Git |
| Input validation | Bean Validation on every input, enum whitelist on `sort`, length limits, language pattern |
| SQL injection | Spring Data / JPA bound parameters; native upsert uses named parameters; no string-built SQL |
| CORS | No browser frontend is required, so leave CORS off. If a frontend is added later, allow its exact origin through a property. Swagger UI is served from the same origin, so it needs no CORS |
| Actuator | Expose only `health`. No `env`, `beans` or `heapdump` |
| Logging | INFO by default. Log GitHub status codes and rate-limit headers, never the Authorization header or full DB URLs |
| Error responses | Generic messages for 5xx; details only in server logs |
| Production config | `application-prod.yml`: `ddl-auto=validate`, `open-in-view=false`, `show-sql=false`, Flyway on |
| Authentication | Not required by the assignment, so none is added. Mention in the README that the API is open and would need auth/rate limiting before real public use |

One caution for a public deployment: anyone can call `POST /search` and spend your GitHub quota and fill your database. For a demo that is acceptable; state it as a known limitation.

## 14. Deployment strategy

**Goal:** public API URL, public Swagger UI URL, hosted PostgreSQL, deployed by **evening of 17 Nov** so 18 Nov is buffer.

**Approach:** one Docker image (multi-stage: Maven build stage, slim Java 21 JRE runtime, non-root user) deployed to a container-hosting platform, plus a managed PostgreSQL from the same or another provider.

**Choosing a host:** I have not verified any provider's current free tier or cold-start behavior, and these change. Before you commit, spend ten minutes checking pricing pages for two or three candidates (for example Render, Railway, Fly.io, Koyeb, or a cloud provider's free credits) and choose one that offers a Docker deploy plus Postgres without a card or with an acceptable one. Whatever you pick, a sleeping free instance can take about a minute to wake: open the URL a few minutes before anyone reviews it.

| Concern | Plan |
| --- | --- |
| Port | `server.port=${PORT:8080}` |
| DB URL format | Providers often give `postgres://user:pass@host:5432/db`. Spring needs `jdbc:postgresql://host:5432/db`. Convert it, and put username and password in separate variables. Add `?sslmode=require` if the host demands TLS |
| Schema creation | Flyway runs on startup, so there is no manual SQL step |
| Health check | `GET /actuator/health` returns `{"status":"UP"}` |
| Swagger | `/swagger-ui.html` redirects to `/swagger-ui/index.html`; raw spec at `/v3/api-docs` |
| Memory | Set `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` for small containers |

**Verify after deploy**

1. `GET <url>/actuator/health` is UP.
2. Open `<url>/swagger-ui.html`.
3. `POST <url>/api/github/search` with `{"query":"spring boot","language":"Java","sort":"stars"}` returns 200 and repositories.
4. Repeat the same POST and confirm it still returns 200 (upsert, not an error).
5. `GET <url>/api/github/repositories?language=Java&minStars=100&sort=stars` returns the saved rows.
6. `GET` with `sort=rating` returns 400 in the standard error shape.

**Troubleshooting:** app exits immediately means check DB env vars and logs; `Flyway` error about PostgreSQL support means `flyway-database-postgresql` is missing; 502s on every search with a token set means the token is wrong; 429s mean you are over the limit, wait a minute.

**Deployment checklist**

- [ ] Image builds locally with `docker build`
- [ ] Container runs locally against local Postgres
- [ ] Hosted Postgres created; credentials only in the host's secret settings
- [ ] Environment variables set (including `SPRING_PROFILES_ACTIVE=prod`)
- [ ] Health check path configured
- [ ] All six verification steps pass on the public URL
- [ ] URLs copied into the README

## 15. README design

The README is written in Phase 6. Required sections, in order:

1. Title, one-paragraph overview, and live links (API, Swagger UI, health)
2. Problem statement
3. Features (including what is optional and what is not built)
4. Architecture (Mermaid component diagram + request flow)
5. Technology stack with versions
6. Database schema (the SQL and the upsert explanation)
7. API documentation with `curl` examples and sample responses, including error cases
8. Environment variables table
9. Local setup: prerequisites (Java 21, Maven, Docker or Postgres), create DB, set variables
10. Run the app
11. Run the tests (`mvn test`, `mvn verify`, where the coverage report is, Docker requirement)
12. Swagger URL (local and deployed)
13. Deployment steps used
14. Design decisions (the key-decisions table from "Read me first", shortened)
15. Known limitations (open API, no auth, GitHub 1000-result cap, rate limit, free-host cold starts)

A stranger should be able to clone, set four variables, run `mvn spring-boot:run` and call the API in under ten minutes.

## 16. Interview explainability guide

Everything here describes the **planned** implementation. If the final code differs (for example you cut the by-ID endpoint), update this guide before you rely on it. Phase 8 asks the agent to re-check it against the real code.

### A. Project explanations

**30 seconds.** "It's a Spring Boot REST API. You POST a search to it; it calls GitHub's search API, saves the repositories into PostgreSQL, updating any it already has, and returns them. A second endpoint lets you query what's stored, filtered by language and minimum stars and sorted by stars, forks or last updated, without calling GitHub again."

**1 minute.** Add: "It's layered: controller, service, a dedicated GitHub client, repository, entities, DTOs. Requests are validated with Bean Validation. Duplicates are prevented by using GitHub's repository ID as the primary key and a PostgreSQL `INSERT ... ON CONFLICT DO UPDATE`. Errors like rate limits, timeouts or bad input return proper status codes in one consistent JSON shape. I tested the service with Mockito, the GitHub client with a mocked HTTP server, and the database with Testcontainers against real PostgreSQL. It's containerized and deployed, with Swagger docs."

**3 minutes.** Walk the request lifecycle (B), then explain three decisions: why the DB-level upsert, why the GitHub client is its own class, and why database-level filtering with a sort whitelist. Close with limitations: no auth, GitHub's 1000-result cap and rate limit.

### B. Lifecycle of `POST /api/github/search`

1. **Tomcat** receives the HTTP request; Spring's `DispatcherServlet` routes it to `GithubRepositoryController.search`.
2. **Jackson** turns the JSON body into a `SearchRequest` record.
3. **`@Valid`** runs Bean Validation. Failures throw `MethodArgumentNotValidException`, which `GlobalExceptionHandler` turns into a 400.
4. The controller calls **`GithubSearchService.search`**. The controller contains no logic.
5. The service calls **`GithubSearchClient.search`**, which builds the URL (with `q`, `sort`, `order`, `page`, `per_page`), adds headers and the optional token, and calls GitHub through `RestClient`.
6. The client turns the JSON into `GithubSearchResponse` records, or throws a typed exception (rate limit, timeout, bad response).
7. If `items` is empty the service returns an empty success response and stops.
8. **`GithubRepositoryMapper`** converts items to `GithubRepository` objects, applying null defaults and skipping unusable items.
9. **`StoredRepositoryService.upsertAll`** opens one transaction (`@Transactional`) and calls the repository's native upsert for each row.
10. **PostgreSQL** inserts new IDs or updates existing ones.
11. The service maps rows to `RepositoryResponse` DTOs and returns `SearchResponse`.
12. Spring serializes it to JSON and returns **200 OK**.

### C. Java and Spring concepts used

| Concept | What it means | Where it appears | Simple example |
| --- | --- | --- | --- |
| Dependency Injection / IoC | Spring creates objects and passes them to constructors; you do not call `new` | Every service, client and controller | `GithubSearchService(GithubSearchClient client, ...)`: Spring supplies the client. Like passing a dependency into a function in Node, but automatic |
| Bean | An object Spring creates and manages | Classes annotated `@Service`, `@RestController`, `@Repository`, `@Configuration` | `RestClientConfig` defines a `RestClient` bean |
| `@RestController` | Class whose methods handle HTTP and return JSON | The controller | Like an Express router with handlers |
| `@Service` | Marks business logic bean | Two services | Pure naming and discoverability |
| `@Repository` | Data-access bean (Spring Data creates the implementation from an interface) | `GithubRepositoryRepository` | You write an interface; Spring writes the class |
| `@Entity` | Class mapped to a table | `GithubRepository` | Fields map to columns |
| DTO | Object shaped for the API, not the table | `dto` package | Lets the table change without breaking the API |
| Record | Immutable data class with auto getters/equals | DTOs | `record RepositoryResponse(Long id, String name, ...)` |
| JPA / Hibernate | JPA is the standard; Hibernate is the implementation that generates SQL | Reads and entity mapping | Specification becomes a `WHERE` clause |
| `@Transactional` | All DB work in the method commits together or rolls back together | `StoredRepositoryService.upsertAll` | If row 7 fails, rows 1 to 6 are undone |
| Exception handling | `@RestControllerAdvice` catches exceptions from any controller | `GlobalExceptionHandler` | One place maps exceptions to status codes |
| Validation annotations | Declarative rules on fields | `SearchRequest`, GET params | `@NotBlank`, `@Min(0)`, `@Size(max = 256)` |
| `RestClient` | Spring's synchronous HTTP client | `GithubSearchClient` | Like `fetch` / `axios`, but blocking |
| JUnit 5 | Test framework | All tests | `@Test` methods |
| Mockito | Fake collaborators | Service and controller tests | `when(client.search(...)).thenReturn(...)` |
| `@ConfigurationProperties` | Typed binding of config | `GithubProperties` | `github.token` becomes a field |

### D. Database concepts

- **Primary key**: uniquely identifies a row and is automatically indexed. Ours is GitHub's ID.
- **Unique constraint**: the database refuses duplicates. The primary key already is one.
- **Index**: a lookup structure that speeds up `WHERE` and `ORDER BY`, at the cost of slower writes. At small sizes the planner may ignore it.
- **Upsert**: insert-or-update in one atomic statement (`ON CONFLICT (id) DO UPDATE`). `EXCLUDED` refers to the row you tried to insert.
- **Filtering and sorting in SQL**: the database does it efficiently; Java would have to load everything first.
- **Transaction**: a group of statements that succeed or fail together.
- **ORM vs raw SQL**: ORM (JPA) is productive for reads and mapping; raw SQL is clearer for PostgreSQL-specific features such as upsert. We use both with one clear rule: JPA reads, native SQL for the upsert.

### E. Technical decision explanations

- **Why Spring Boot?** Required by the assignment; auto-configuration, embedded server, strong ecosystem.
- **Why PostgreSQL?** Required; robust, supports `ON CONFLICT`, `TIMESTAMPTZ`, and functional indexes.
- **Why layered architecture?** Each layer has one job, so it is easy to test and to explain.
- **Why DTOs?** The API contract should not change when the table changes, and entities should not leak internals.
- **Why a separate GitHub client?** It isolates GitHub's quirks (headers, rate limits, JSON shape) and lets the service be tested with a mock.
- **Why upsert?** Atomic and race-free, and matches the requirement.
- **Why DB-level filtering?** Efficient, scalable, safe with bound parameters.
- **Why mock external APIs in tests?** Real GitHub is slow, rate-limited and changes; tests must be deterministic.
- **Why not microservices?** One small domain; microservices add deployment and failure complexity with no benefit.

### F. Likely interviewer questions

**Architecture**

1. *Walk me through the architecture.* Controller to service to client/repository, DTOs at the edges, one exception handler. See Section 2.
2. *Why two services?* The search service orchestrates and does a slow HTTP call; the storage service holds the transaction. This keeps a database connection from being held during the GitHub call and avoids Spring's self-invocation problem with `@Transactional`.
3. *Why is the entity never returned from the controller?* It couples the API to the schema and can leak fields.

**Java**

4. *What is a record?* An immutable data carrier; the compiler generates constructor, accessors, `equals`, `hashCode`. We use it for DTOs.
5. *Why constructor injection?* Dependencies are explicit, can be final, and tests can pass mocks without Spring.
6. *Why `Instant` for timestamps?* It is a point in time in UTC, so there are no time zone surprises.

**Spring Boot**

7. *What does `@SpringBootApplication` do?* Combines configuration, component scanning and auto-configuration.
8. *How does Spring Data create a repository with no implementation?* It generates a proxy at startup from the interface.
9. *What does `@Transactional` do under the hood?* A proxy wraps the method, begins a transaction, commits on return, rolls back on a runtime exception.
10. *What is `ddl-auto=validate` and why?* Hibernate checks the mapping against the schema but never changes it; Flyway owns the schema.

**SQL**

11. *Explain your upsert.* Insert; if the primary key already exists, update the other columns using the incoming values.
12. *Why not `save()`?* With an assigned ID it does a SELECT first, which is two round trips and racy under concurrency.
13. *Which indexes and why?* Language (lower-cased), stars, forks, last updated; they match the filters and sort fields. Be honest that they matter more at scale.
14. *Why `LOWER(language)`?* So `java` and `Java` match; the functional index supports it.

**REST**

15. *Why does POST return 200, not 201?* Nothing new is created at a URL; it is a search with a side effect.
16. *Why 200 for empty results?* A valid query with no matches is a successful answer.
17. *Why 429 and not 500 for GitHub rate limits?* It is a client-visible, retryable condition, and `Retry-After` tells them when.
18. *Why 502/504?* The failure is in an upstream service we depend on, not in our code.

**Testing**

19. *How do you test GitHub failures?* `MockRestServiceServer` returns canned 403, 5xx, malformed bodies; no network.
20. *Why Testcontainers instead of H2?* Upsert and `LOWER` indexes are PostgreSQL behavior; H2 can pass while production fails.
21. *How do you know upsert works?* An integration test saves the same ID twice with different stars and asserts one row with the new value.

**External API**

22. *What if GitHub is slow?* Connect and read timeouts; a timeout becomes a 504.
23. *Why no retries?* Searches are user-initiated and the quota is tight; a retry can worsen a rate limit. A single retry on 5xx is a reasonable extension.
24. *What are GitHub's search limits?* Low rate limit and only the first 1000 results; we validate `page * perPage <= 1000`.

**Error handling and performance**

25. *What happens if the DB is down?* `DataAccessResourceFailureException` becomes a 503 with a generic message; details are logged.
26. *How would this scale?* Pagination on reads, indexes, a multi-row upsert, a cache for repeated searches, and background refresh. Not built because the assignment does not need it.
27. *What would you add with another week?* Authentication, per-client rate limiting, CI, a scheduled refresh, and request metrics.

### G. Code ownership checklist

Before submitting, you should be able to do each of these without notes:

- [ ] Draw the request lifecycle from memory
- [ ] Explain every dependency in `pom.xml`
- [ ] Read the upsert SQL aloud and explain `EXCLUDED`
- [ ] Explain why `GithubSearchService` is not `@Transactional`
- [ ] Show where each HTTP status is decided
- [ ] Show where a sort value is whitelisted
- [ ] Run one unit test, one `@WebMvcTest` and one Testcontainers test, and explain what each proves
- [ ] Explain every environment variable and where it is read
- [ ] Point to anything in the code you do not understand and fix it or remove it
- [ ] Confirm the README matches what the code does, and do not claim any feature that is not there

## Coding-agent prompts (Phases 0 to 8)

Each prompt below is self-contained: it carries the project context and the full set of operating rules, so you can paste any one of them into Cursor, Claude Code, Codex, OpenCode or similar without the others.

**Before Phase 0**

1. Create an empty GitHub repository (public, as the assignment requires) and clone it.
2. Save this whole document into the repo as `docs/BLUEPRINT.md` (Docs menu: export to Markdown). Every prompt tells the agent to read it.
3. Have Docker, Java 21 and Maven installed. Docker is needed for the database tests.

**Working rhythm.** Paste one prompt, read the agent's completion report, run the manual checks it lists, commit using its suggested message, then paste the next. If the agent proposes changing the design, decide before it proceeds. The prompts forbid it from silently changing the blueprint. For Phases 1–5, enforce test-first RED → GREEN → REFACTOR for each behaviour; a green test written after the implementation is not evidence of TDD.

**Honest expectation.** On this schedule each phase should take roughly 30 to 60 minutes of agent time plus your review. If you fall behind, the cut order in the timeline section tells you what to drop.

### Phase 0: Repository inspection and project setup

```text
PROJECT: "GitHub Repository Searcher" - a Java 21 / Spring Boot 3.5.x / Maven / PostgreSQL REST API.
The approved design is in docs/BLUEPRINT.md. Read it fully before doing anything. If it is missing, stop and tell me.
THIS PHASE: Phase 0 - repository inspection and project setup. Do not implement any business features.

OPERATING RULES (apply all, every phase)
1. Inspect first: list the directory, read existing files, avoid needless rewrites. Never overwrite an existing file without reading it.
2. docs/BLUEPRINT.md is the source of truth. Do not redesign. If you hit a real problem: state it, explain why the approach fails, propose the smallest change, and WAIT for my approval before any significant architectural change.
3. Work only on this phase, in small increments. Do not start the next phase.
4. For each significant file or feature, explain what it does, why it exists, how it works, how it interacts with other parts, why you chose it, and what I must understand to explain it in an interview. I am a junior Java developer coming from Node/React.
5. Compile and run the relevant checks before claiming completion. Report exact commands and real results. Never claim a test passed, a database connected, an endpoint works, or anything is deployed unless you verified it.
6. TDD is mandatory for every behaviour in this phase: write a focused test first, run it and show the failing RED result, implement the minimum code, run it to GREEN, then refactor and rerun. In the completion report, identify each behaviour's initial failing test and final passing command/result. Do not implement first and add tests afterwards. Never weaken or delete a failing test to get green; fix the cause.
7. Quality: Java naming conventions, small focused methods, no magic numbers, no unused dependencies, no swallowed exceptions, comments only where logic needs them.
8. Finish with a PHASE COMPLETION REPORT: 1 summary, 2 files created, 3 files modified, 4 key decisions, 5 tests written, 6 commands run, 7 actual results, 8 outstanding issues, 9 manual verification steps, 10 concepts I should understand, 11 suggested commit message, 12 whether the next phase can safely begin. Then stop and wait.

TASKS
1. Inspect the current directory and git state (git status, git log, existing files). Report what exists.
2. Check tooling and report versions: java -version (must be 21), mvn -version, git --version, docker --version, and whether a PostgreSQL is reachable on localhost:5432 (pg_isready or docker ps). Do NOT install system software without asking me. If no local PostgreSQL exists, give me this command to run myself:
   docker run --name gh-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=github_searcher -p 5432:5432 -d postgres:16
3. Create the Maven project in the repo root (not in a subfolder) with groupId com.example, artifactId github-repository-searcher, base package com.example.githubsearch. Use the pom essentials from Blueprint Section 3. Before pinning, check that Spring Boot 3.5.x (newest patch) and springdoc-openapi-starter-webmvc-ui 2.10.0 resolve; if not, use the newest resolvable 3.5.x and springdoc 2.8.9 or newer on the 2.8 line, and tell me exactly what you changed and why. Do not use Spring Boot 4 or springdoc 3.x. No Lombok.
4. Create only what Phase 0 needs: GithubRepositorySearcherApplication, src/main/resources/application.yml and application-prod.yml, .env.example, .gitignore, and a test support class src/test/java/.../support/PostgresTestConfig.java that starts a Testcontainers postgres:16 container with @ServiceConnection. Do not create empty packages or placeholder classes.
5. application.yml: datasource url/username/password from DB_URL, DB_USERNAME, DB_PASSWORD with non-secret local defaults (jdbc:postgresql://localhost:5432/github_searcher, postgres, postgres); jpa ddl-auto=validate and open-in-view=false; flyway enabled; server.port=${PORT:8080}; actuator exposing only health; springdoc defaults; a github section reserved for token/baseUrl/timeouts (token from ${GITHUB_TOKEN:}, baseUrl https://api.github.com, connect 5s, read 10s). application-prod.yml: show-sql false and any production-only settings from Blueprint Section 13.
6. .env.example: names with dummy values and a comment on every variable. .gitignore: target/, .env, IDE folders, logs, OS files. Make sure no secret can be committed.
7. Add one test, ApplicationContextTest, that boots the full context using PostgresTestConfig. It must pass (Docker must be running).
8. VERIFY by actually doing it: run mvn clean verify; start the app against the local database (export the env vars; Spring does not read .env automatically); curl http://localhost:8080/actuator/health and confirm {"status":"UP"}; confirm /v3/api-docs and /swagger-ui.html respond. Stop the app afterwards.

DEFINITION OF DONE: the app starts, health is UP, the context test passes, nothing secret is tracked by git. Do not commit; suggest the commit message.
```

### Phase 1: Database and domain layer

```text
PROJECT: "GitHub Repository Searcher" - a Java 21 / Spring Boot 3.5.x / Maven / PostgreSQL REST API.
The approved design is in docs/BLUEPRINT.md. Read Sections 4, 8, 9 and 12 before starting. If it is missing, stop and tell me.
THIS PHASE: Phase 1 - database and domain layer. Phase 0 is already done; verify that first (mvn clean verify passes).

OPERATING RULES (apply all, every phase)
1. Inspect first: list the directory, read existing files, avoid needless rewrites. Never overwrite an existing file without reading it.
2. docs/BLUEPRINT.md is the source of truth. Do not redesign. If you hit a real problem: state it, explain why the approach fails, propose the smallest change, and WAIT for my approval before any significant architectural change.
3. Work only on this phase, in small increments. Do not start the next phase.
4. For each significant class or feature, explain what it does, why it exists, how it works, how it interacts with other parts, why you chose it, and what I must understand to explain it in an interview. I am a junior Java developer coming from Node/React.
5. Compile and run the relevant tests before claiming completion. Report exact commands and real results. Never claim a test passed, a database connected, an endpoint works, or anything is deployed unless you verified it.
6. TDD is mandatory for every behaviour in this phase: write a focused test first, run it and show the failing RED result, implement the minimum code, run it to GREEN, then refactor and rerun. In the completion report, identify each behaviour's initial failing test and final passing command/result. Do not implement first and add tests afterwards. Never weaken or delete a failing test to get green; fix the cause.
7. Quality: Java naming conventions, small focused methods, no magic numbers, no unused dependencies, no swallowed exceptions, comments only where logic needs them.
8. Finish with a PHASE COMPLETION REPORT: 1 summary, 2 files created, 3 files modified, 4 key decisions, 5 tests written, 6 commands run, 7 actual results, 8 outstanding issues, 9 manual verification steps, 10 concepts I should understand, 11 suggested commit message, 12 whether the next phase can safely begin. Then stop and wait.

TASKS
1. Create src/main/resources/db/migration/V1__create_github_repositories.sql with exactly the table, constraints and four indexes from Blueprint Section 4 (BIGINT primary key = GitHub's ID, no surrogate key; LOWER(language) functional index), including html_url VARCHAR(500), open_issues_count INTEGER NOT NULL DEFAULT 0, and created_at TIMESTAMPTZ.
2. Create entity/GithubRepository mapped to github_repositories: @Id Long id with NO @GeneratedValue; name, description, owner, language, stars (Integer), forks (Integer), lastUpdated (Instant, column last_updated), htmlUrl (String, column html_url), openIssues (Integer, column open_issues_count), createdAt (Instant, column created_at), syncedAt (Instant, insertable=false, updatable=false). Plain class with constructor, getters, equals/hashCode on id. No Lombok. No business logic.
3. Create model/SortField enum: STARS("stars"), FORKS("forks"), UPDATED("updated"), each with githubValue() and entityProperty() (stars, forks, lastUpdated), and a static from(String) that is case-insensitive and throws a new exception.InvalidRequestException (create it: RuntimeException with a message that lists the allowed values) for unknown or blank input. A null input returns the caller's default, so decide and document how from() handles null.
4. Create repository/GithubRepositoryRepository extending JpaRepository<GithubRepository, Long> and JpaSpecificationExecutor<GithubRepository>, plus a native upsert: @Modifying(flushAutomatically = true, clearAutomatically = true) @Query(nativeQuery = true) running INSERT ... ON CONFLICT (id) DO UPDATE SET ... exactly as in Blueprint Section 4, with named parameters, setting synced_at = now(). Verify how Hibernate 6 binds Instant and null values in the native query; if there is a type problem, fix it with the smallest change and explain it.
5. Create repository/GithubRepositorySpecs with static methods hasLanguage(String) (case-insensitive equality on language) and minStars(Integer) (stars >= value). Each returns null when its argument is null or blank, so absent filters add no predicate.
6. Tests (use PostgresTestConfig from Phase 0; @DataJpaTest + @AutoConfigureTestDatabase(replace = NONE) + @Import the config; Docker must be running):
   - SortFieldTest: valid values, case-insensitivity, invalid and blank values.
   - GithubRepositoryRepositoryIT: Flyway migration creates the table; upsert inserts a new row; upserting the same ID with different stars, forks, description, htmlUrl, openIssues and createdAt leaves exactly ONE row with the new values and a later synced_at; two rows with the same ID inside one batch/transaction cause no error and no duplicate; null description, null language and null last_updated are accepted; language filter is case-insensitive and excludes null-language rows; minStars is inclusive at the boundary; language + minStars combine; sort by stars, forks and lastUpdated in both directions; id tie-breaker gives stable order; a transaction containing a failing row (e.g. null name) rolls back earlier rows.
7. Run mvn clean verify and show real results.

Do NOT create services, controllers, DTOs, the GitHub client or the mapper in this phase. In your report, explain in plain words: why the GitHub ID is the primary key, why a database upsert instead of save(), and what Flyway and ddl-auto=validate each do.
```

### Phase 2: GitHub API integration

```text
PROJECT: "GitHub Repository Searcher" - a Java 21 / Spring Boot 3.5.x / Maven / PostgreSQL REST API.
The approved design is in docs/BLUEPRINT.md. Read Sections 6, 11 and 12 before starting. If it is missing, stop and tell me.
THIS PHASE: Phase 2 - GitHub API integration. Phases 0 and 1 are done; verify mvn clean verify passes first.

OPERATING RULES (apply all, every phase)
1. Inspect first: list the directory, read existing files, avoid needless rewrites. Never overwrite an existing file without reading it.
2. docs/BLUEPRINT.md is the source of truth. Do not redesign. If you hit a real problem: state it, explain why the approach fails, propose the smallest change, and WAIT for my approval before any significant architectural change.
3. Work only on this phase, in small increments. Do not start the next phase.
4. For each significant class or feature, explain what it does, why it exists, how it works, how it interacts with other parts, why you chose it, and what I must understand to explain it in an interview. I am a junior Java developer coming from Node/React.
5. Compile and run the relevant tests before claiming completion. Report exact commands and real results. Never claim a test passed, a database connected, an endpoint works, or anything is deployed unless you verified it.
6. TDD is mandatory for every behaviour in this phase: write a focused test first, run it and show the failing RED result, implement the minimum code, run it to GREEN, then refactor and rerun. In the completion report, identify each behaviour's initial failing test and final passing command/result. Do not implement first and add tests afterwards. Never weaken or delete a failing test to get green; fix the cause.
7. Quality: Java naming conventions, small focused methods, no magic numbers, no unused dependencies, no swallowed exceptions, comments only where logic needs them.
8. Finish with a PHASE COMPLETION REPORT: 1 summary, 2 files created, 3 files modified, 4 key decisions, 5 tests written, 6 commands run, 7 actual results, 8 outstanding issues, 9 manual verification steps, 10 concepts I should understand, 11 suggested commit message, 12 whether the next phase can safely begin. Then stop and wait.

TASKS
First, read GitHub's current documentation for GET /search/repositories (docs.github.com/en/rest/search/search) and confirm: parameters q, sort, order, per_page, page; response fields; the rate-limit behaviour and headers; the 1000-result cap. Tell me anything that differs from Blueprint Section 6 before you code against it.

1. config/GithubProperties: a record bound with @ConfigurationProperties(prefix = "github") holding token (may be empty), baseUrl, connectTimeout, readTimeout (Duration). Register it.
2. config/RestClientConfig: expose a RestClient.Builder bean for the GitHub client with the base URL, connect and read timeouts applied (JDK HttpClient request factory or an equivalent Spring Boot 3.5 mechanism). The timeouts must NOT be set inside GithubSearchClient itself, so that tests can bind MockRestServiceServer to a plain builder without it being overwritten.
3. dto/github records, all with @JsonIgnoreProperties(ignoreUnknown = true): GithubSearchResponse(total_count, incomplete_results, items), GithubRepositoryItem(id, name, description, owner, language, stargazers_count, forks_count, updated_at, html_url, open_issues_count, created_at), GithubOwner(login). Use @JsonProperty for snake_case names. Use wrapper types so missing numbers are null, not 0.
4. exception/GithubApiException (RuntimeException carrying an HttpStatus our API should return) and exception/GithubRateLimitException (extends it or stands beside it; carries Long retryAfterSeconds, may be null).
5. client/GithubSearchClient (concrete class, no interface): constructor takes RestClient.Builder and GithubProperties. One public method search(String query, String language, SortField sort, int page, int perPage) returning GithubSearchResponse.
   - Build q = query + ` in:name`, plus ` language:<value>` when language is non-blank; quote the language when it contains a space. This enforces repository-name search only. Let RestClient encode via URI template variables; never concatenate the URL. Add sort and order=desc only when sort is not null; page and per_page always.
   - Headers: Accept application/vnd.github+json, X-GitHub-Api-Version 2022-11-28, a User-Agent, and Authorization: Bearer <token> only when the token is non-blank. Never log the token.
   - Failure mapping exactly as Blueprint Section 6: 403/429 with rate-limit signals -> GithubRateLimitException with retryAfterSeconds from Retry-After, else X-RateLimit-Reset minus now, else null; other 403 and 401 -> GithubApiException(502); 422 -> GithubApiException(400); 5xx -> 502; unparseable body or null items -> 502; connect/read timeout (ResourceAccessException caused by a timeout or IO error) -> GithubApiException(504). Preserve the cause for logging, but return safe messages.
   - No retries.
6. Tests with MockRestServiceServer bound to a plain RestClient.Builder (src/test/resources/github/*.json for canned bodies). NO real network. Cover: correct URL and parameters for query only, query + language, language with a space, C++ and C# (verify correct encoding), each sort value, no sort; headers present; token header present only when configured; 200 with items; 200 with empty items; items with null description, language and updated_at; html_url and created_at mapped; null open_issues_count defaults safely to 0; unknown extra JSON fields ignored; 403 with X-RateLimit-Remaining 0 and X-RateLimit-Reset; 429 with Retry-After; 403 without rate-limit headers; 401; 422; 500; malformed JSON; response without items; simulated timeout via withException(new SocketTimeoutException()).
7. Run mvn clean verify and report real results. Do not call the real GitHub from any test.

Do NOT write services, mapper, controller or error handler yet. In your report, explain: why the client is its own class, why query encoding matters, how MockRestServiceServer works, and the difference between GitHub-side sorting and database sorting.
```

### Phase 3: Core business logic

```text
PROJECT: "GitHub Repository Searcher" - a Java 21 / Spring Boot 3.5.x / Maven / PostgreSQL REST API.
The approved design is in docs/BLUEPRINT.md. Read Sections 2, 5, 6, 8, 9 and 12 before starting. If it is missing, stop and tell me.
THIS PHASE: Phase 3 - core business logic (services, mapper, DTOs). Phases 0 to 2 are done; verify mvn clean verify passes first.

OPERATING RULES (apply all, every phase)
1. Inspect first: list the directory, read existing files, avoid needless rewrites. Never overwrite an existing file without reading it.
2. docs/BLUEPRINT.md is the source of truth. Do not redesign. If you hit a real problem: state it, explain why the approach fails, propose the smallest change, and WAIT for my approval before any significant architectural change.
3. Work only on this phase, in small increments. Do not start the next phase.
4. For each significant class or feature, explain what it does, why it exists, how it works, how it interacts with other parts, why you chose it, and what I must understand to explain it in an interview. I am a junior Java developer coming from Node/React.
5. Compile and run the relevant tests before claiming completion. Report exact commands and real results. Never claim a test passed, a database connected, an endpoint works, or anything is deployed unless you verified it.
6. TDD is mandatory for every behaviour in this phase: write a focused test first, run it and show the failing RED result, implement the minimum code, run it to GREEN, then refactor and rerun. In the completion report, identify each behaviour's initial failing test and final passing command/result. Do not implement first and add tests afterwards. Never weaken or delete a failing test to get green; fix the cause.
7. Quality: Java naming conventions, small focused methods, no magic numbers, no unused dependencies, no swallowed exceptions, comments only where logic needs them.
8. Finish with a PHASE COMPLETION REPORT: 1 summary, 2 files created, 3 files modified, 4 key decisions, 5 tests written, 6 commands run, 7 actual results, 8 outstanding issues, 9 manual verification steps, 10 concepts I should understand, 11 suggested commit message, 12 whether the next phase can safely begin. Then stop and wait.

TASKS
1. DTOs (Java records, in dto/): SearchRequest(query, language, sort, page, perPage) with Bean Validation per Blueprint Section 5.1 (@NotBlank + @Size(max=256) on query; @Size(max=50) and @Pattern on language; page @Min(1); perPage @Min(1) @Max(100)); defaults page=1, perPage=30 applied when null; a validation that page*perPage <= 1000 (e.g. a @AssertTrue method or class-level constraint). SearchResponse(message, repositories), RepositoryResponse(id, name, description, owner, language, stars, forks, lastUpdated, htmlUrl, openIssues, createdAt), RepositoryPageResponse(repositories, page, size, totalElements, totalPages). Never expose the entity. Keep the eight core fields first and append htmlUrl, openIssues, createdAt.
2. mapper/GithubRepositoryMapper (plain @Component, no I/O): toEntities(GithubSearchResponse) that skips items with null id, null name or null owner (log a WARN with the id if known), defaults null stars/forks/open_issues_count to 0, keeps null description/language/updated_at/html_url/created_at, parses nothing manually (use the Instant from Jackson); toResponse(GithubRepository); a List overload. Map html_url -> htmlUrl, open_issues_count -> openIssues and created_at -> createdAt.
3. service/StoredRepositoryService (@Service): @Transactional upsertAll(List<GithubRepository>) that calls the repository's native upsert once per entity inside ONE transaction; @Transactional(readOnly = true) find(language, minStars, SortField sort, Sort.Direction direction, int page, int size) building Specification.where(hasLanguage).and(minStars), a Sort on sort.entityProperty() with nulls last plus id as tie-breaker, PageRequest, returning RepositoryPageResponse; @Transactional(readOnly = true) findById(Long) throwing a new exception.RepositoryNotFoundException when absent. This class must NOT depend on GithubSearchClient.
4. service/GithubSearchService (@Service, NOT @Transactional): search(SearchRequest). Convert request.sort to SortField (null stays null), call client.search, return SearchResponse with message "No repositories found for the given search criteria" and an empty list WITHOUT touching the database when items are empty; otherwise map, call storedRepositoryService.upsertAll, return message "Repositories fetched and saved successfully" with the mapped responses in GitHub's order. Exceptions from the client propagate untouched. Explain in the report why this class is not transactional (no DB connection held during the HTTP call; avoids @Transactional self-invocation).
5. Tests with JUnit 5 + Mockito + AssertJ:
   - GithubRepositoryMapperTest: full item including htmlUrl/openIssues/createdAt; null description/language; null stars/forks/open_issues_count; missing id/owner skipped; empty list.
   - GithubSearchServiceTest (mock client and StoredRepositoryService, real mapper): valid search saves and returns; empty items -> message, empty list, verifyNoInteractions(storage); skipped items not saved; client rate-limit and timeout exceptions propagate and nothing is saved; request sort passed to client as the right SortField; defaults applied.
   - StoredRepositoryServiceTest (mock repository): find builds the right Pageable (property, direction, tie-breaker, page, size) using ArgumentCaptor; findById found and not found; upsertAll calls upsert once per entity.
   - One integration test with Testcontainers: upsertAll twice with the same IDs leaves no duplicates and updates values; a failing row rolls back the whole batch.
6. Run mvn clean verify and report real results.

Do NOT create the controller or global exception handler yet. In your report explain: the full flow inside GithubSearchService, what @Transactional does and why it is on StoredRepositoryService, why entities are mapped to DTOs, and how empty results differ from failures.
```

### Phase 4: REST API implementation

```text
PROJECT: "GitHub Repository Searcher" - a Java 21 / Spring Boot 3.5.x / Maven / PostgreSQL REST API.
The approved design is in docs/BLUEPRINT.md. Read Sections 5, 9, 11 and 12 before starting. If it is missing, stop and tell me.
THIS PHASE: Phase 4 - REST controller, error handling and API tests. Phases 0 to 3 are done; verify mvn clean verify passes first.

OPERATING RULES (apply all, every phase)
1. Inspect first: list the directory, read existing files, avoid needless rewrites. Never overwrite an existing file without reading it.
2. docs/BLUEPRINT.md is the source of truth. Do not redesign. If you hit a real problem: state it, explain why the approach fails, propose the smallest change, and WAIT for my approval before any significant architectural change.
3. Work only on this phase, in small increments. Do not start the next phase.
4. For each significant class or feature, explain what it does, why it exists, how it works, how it interacts with other parts, why you chose it, and what I must understand to explain it in an interview. I am a junior Java developer coming from Node/React.
5. Compile and run the relevant tests before claiming completion. Report exact commands and real results. Never claim a test passed, a database connected, an endpoint works, or anything is deployed unless you verified it.
6. Quality: Java naming conventions, small focused methods, thin controllers, no magic numbers, no unused dependencies, no swallowed exceptions, comments only where logic needs them. Never weaken or delete a failing test to get green; fix the cause.
7. Finish with a PHASE COMPLETION REPORT: 1 summary, 2 files created, 3 files modified, 4 key decisions, 5 tests written, 6 commands run, 7 actual results, 8 outstanding issues, 9 manual verification steps, 10 concepts I should understand, 11 suggested commit message, 12 whether the next phase can safely begin. Then stop and wait.

TASKS
1. dto/ErrorResponse record: timestamp (Instant), status (int), error (reason phrase), message, path, optional details (list of field/message pairs, omitted from JSON when null or empty).
2. controller/GithubRepositoryController (@RestController, @RequestMapping("/api/github")), thin, constructor-injected with GithubSearchService and StoredRepositoryService only:
   - POST /search: @Valid @RequestBody SearchRequest -> 200 with SearchResponse.
   - GET /repositories: optional language (max 50), minStars (>= 0), sort (default "stars"; converted with SortField.from, so invalid values give 400), order (asc|desc, default desc), page (>= 0, default 0), size (1..100, default 100). Returns 200 with RepositoryPageResponse, including 200 with an empty list when nothing matches. It must never call GithubSearchService or the GitHub client.
   - GET /repositories/{id} (OPTIONAL: build it last, only if the rest is green): 200 or 404.
3. exception/GlobalExceptionHandler (@RestControllerAdvice) producing ErrorResponse with the request path, mapping exactly the table in Blueprint Section 11: HttpMessageNotReadableException, MethodArgumentNotValidException (with details), HandlerMethodValidationException and ConstraintViolationException (use whichever Spring 6.2 raises for your parameter validation setup, handle both if both can occur, and test the one that actually occurs), MethodArgumentTypeMismatchException, InvalidRequestException -> 400; GithubRateLimitException -> 429 with a Retry-After header when retryAfterSeconds is known; GithubApiException -> its carried status (502/504/400); RepositoryNotFoundException -> 404; DataAccessResourceFailureException -> 503; other DataAccessException -> 500 with a generic message; any other Exception -> 500 generic. Log the real cause server-side (WARN for client errors and upstream issues, ERROR for 500s). Never put stack traces, SQL, tokens or internal class names in a response body.
4. Tests:
   - @WebMvcTest(GithubRepositoryController.class) with @MockitoBean services (not the deprecated @MockBean): POST valid -> 200 and JSON shape matches Blueprint Section 5.1 exactly (id, name, description, owner, language, stars, forks, lastUpdated, htmlUrl, openIssues, createdAt; timestamps as ISO-8601 UTC); POST blank/missing query, query over 256, perPage 0 and 101, page*perPage over 1000, bad language characters, malformed JSON, invalid sort -> 400 with the standard error body; service throwing rate-limit -> 429 + Retry-After; throwing GithubApiException 502 and 504 -> those statuses; GET defaults (including size=100); GET with language/minStars/sort passes the right arguments to the service (ArgumentCaptor); minStars=-1, size=101, page=-1, sort=rating, minStars=abc -> 400; empty page -> 200 with empty list; service throwing DataAccessResourceFailureException -> 503 and generic DataAccessException -> 500 with no internal detail; GET by id 200, 404, and non-numeric id 400 if built.
   - integration/SearchFlowIT: @SpringBootTest + MockMvc + Testcontainers with @MockitoBean GithubSearchClient returning a canned GithubSearchResponse. Flow: POST search saves; POST the same repositories again with changed stars and confirm 200 and still one row each with new values; GET with language/minStars/sort returns the expected order; verify the mocked client was NOT called by any GET; an empty client result returns the "No repositories found" message and saves nothing.
5. Start the app locally against the local PostgreSQL and exercise both endpoints with curl, including one error case. A single real call to GitHub is allowed for this manual smoke test only (not automated). Report the actual responses, and tell me if GitHub rate-limited you.
6. Run mvn clean verify and report real results.

Do NOT add Swagger annotations or the README in this phase. In your report explain: how a request travels from Tomcat through DispatcherServlet to the controller, how @Valid and @RestControllerAdvice work, why each status code was chosen, and why the controller has no logic.
```

### Phase 5: Testing and quality assurance

```text
PROJECT: "GitHub Repository Searcher" - a Java 21 / Spring Boot 3.5.x / Maven / PostgreSQL REST API.
The approved design is in docs/BLUEPRINT.md. Read Sections 1, 10, 11 and 12 before starting. If it is missing, stop and tell me.
THIS PHASE: Phase 5 - testing and quality assurance. Phases 0 to 4 are done.

OPERATING RULES (apply all, every phase)
1. Inspect first: list the directory, read existing files, avoid needless rewrites. Never overwrite an existing file without reading it.
2. docs/BLUEPRINT.md is the source of truth. Do not redesign. If you hit a real problem: state it, explain why the approach fails, propose the smallest change, and WAIT for my approval before any significant architectural change.
3. Work only on this phase, in small increments. Do not start the next phase.
4. For each significant finding or fix, explain what was wrong, why it mattered, how you fixed it, and what I must understand to explain it in an interview. I am a junior Java developer coming from Node/React.
5. Compile and run the relevant tests before claiming completion. Report exact commands and real results. Never claim a test passed, a database connected, an endpoint works, or anything is deployed unless you verified it.
6. For any bug fix or newly added behaviour in this QA phase, use test-first RED → GREEN → REFACTOR: add or update the focused test, run it and record the failure, make the smallest fix, rerun to green, then refactor. Do not change production code before a reproducing test exists unless the issue cannot be isolated by a test; explain any exception.
7. Quality: Java naming conventions, small focused methods, thin controllers, no magic numbers, no unused dependencies, no swallowed exceptions, comments only where logic needs them. NEVER weaken, loosen or delete a failing test to get green; fix the production code unless the test itself is demonstrably wrong, and then explain why.
8. Finish with a PHASE COMPLETION REPORT: 1 summary, 2 files created, 3 files modified, 4 key decisions, 5 tests written, 6 commands run, 7 actual results, 8 outstanding issues, 9 manual verification steps, 10 concepts I should understand, 11 suggested commit message, 12 whether the next phase can safely begin. Then stop and wait.

TASKS
1. Run mvn clean verify with Docker running. Report the exact test counts (run, failed, errored, skipped). Investigate every skipped test; none may be skipped because Docker was missing.
2. Walk the traceability matrix in Blueprint Section 1 and the test matrix in Section 12 row by row. For each row, state the test class and method that covers it, or mark it MISSING. Write the missing tests. Do not pad with tests that only exercise getters, records or config.
3. Specifically verify, with tests that really run: database upsert (same ID twice, one row, updated values); language filter case-insensitivity; minStars boundary; filters combined; sort by stars, forks and updated in both directions with a stable tie-breaker; explicitly verify PostgreSQL nulls-last behaviour for descending updated sort using an integration test against Testcontainers; invalid sort -> 400; GitHub rate limit -> 429 with Retry-After; GitHub timeout -> 504; GitHub 5xx and malformed body -> 502; empty GitHub result; GET never calls GitHub.
4. Generate the JaCoCo report (target/site/jacoco/index.html). Report line coverage for service, client, mapper, controller and exception packages. Our target is roughly 80% on those packages; do not chase the number with meaningless tests. List the uncovered branches that matter and either test them or justify leaving them.
5. Code review pass against the blueprint: look for duplicated logic, business logic in controllers, entities leaking outside the service layer, GitHub HTTP code outside GithubSearchClient, string-built SQL, raw user text reaching Sort.by or a query, swallowed exceptions, secrets or tokens in logs, unused dependencies in pom.xml, and magic numbers (page size limit, 1000 cap, timeouts). Fix real bugs; list style nits separately and do not fix them unless trivial.
6. Check that no mandatory requirement from Blueprint Section 1 is missing. Do a manual end-to-end run with curl against the local app: search, repeat search, GET with filters, one 400 case, and the GET-by-id endpoint if it exists.
7. Finish with a table: requirement -> evidence (test name or curl result) -> status. Be honest about any requirement that is only partially met.

Do not add features. In your report list every bug found, its root cause, and the fix.
```

### Phase 6: Swagger and documentation

```text
PROJECT: "GitHub Repository Searcher" - a Java 21 / Spring Boot 3.5.x / Maven / PostgreSQL REST API.
The approved design is in docs/BLUEPRINT.md. Read Sections 5, 13, 14, 15 and 16 before starting. If it is missing, stop and tell me.
THIS PHASE: Phase 6 - Swagger/OpenAPI and documentation. Phases 0 to 5 are done; verify mvn clean verify passes first.

OPERATING RULES (apply all, every phase)
1. Inspect first: list the directory, read existing files, avoid needless rewrites. Never overwrite an existing file without reading it.
2. docs/BLUEPRINT.md is the source of truth. Do not redesign. If you hit a real problem: state it, explain why the approach fails, propose the smallest change, and WAIT for my approval before any significant architectural change.
3. Work only on this phase, in small increments. Do not start the next phase.
4. For each significant file, explain what it does, why it exists, how it works, and what I must understand to explain it in an interview. I am a junior Java developer coming from Node/React.
5. Compile and run the relevant checks before claiming completion. Report exact commands and real results. Never claim a page loads, a command works or anything is deployed unless you verified it.
6. Quality: documentation must describe what the code actually does. Never document a feature that does not exist, and never invent URLs, versions or results.
7. Finish with a PHASE COMPLETION REPORT: 1 summary, 2 files created, 3 files modified, 4 key decisions, 5 tests written, 6 commands run, 7 actual results, 8 outstanding issues, 9 manual verification steps, 10 concepts I should understand, 11 suggested commit message, 12 whether the next phase can safely begin. Then stop and wait.

TASKS
1. config/OpenApiConfig: API title, description, version. Annotate the controller with @Tag, @Operation (summary + description), @Parameter descriptions and allowed values, @ApiResponse for every status the endpoint can return (200, 400, 429, 502, 503, 504, 404 where applicable) using the ErrorResponse schema, and @Schema(example = ...) on request and response record components so Swagger shows realistic examples matching Blueprint Section 5. Keep annotations readable; do not bury the controller logic.
2. Verify with the running app: /swagger-ui.html loads, /v3/api-docs lists every endpoint with the right parameters, and Try-it-out works for POST search and GET repositories. Report what you saw.
3. Create `docs/postman/github-searcher.postman_collection.json` with a collection-level `baseUrl` variable and requests for: POST search, repeat the same POST search to demonstrate upsert, filtered GET, invalid-sort GET, and GET by ID only if that optional endpoint was implemented. Use example bodies and environment variables only; never include a real token or password.
4. README.md with exactly the 15 sections in Blueprint Section 15. Include: live-link placeholders written as TODO (do NOT invent URLs), a Mermaid architecture diagram, the complete SQL schema including all 11 persisted repository fields plus synced_at, the exact name-only search qualifier (`in:name`) and its matching limitation, curl examples with real sample output taken from your local run, an environment-variable table (DB_URL, DB_USERNAME, DB_PASSWORD, GITHUB_TOKEN optional, PORT, SPRING_PROFILES_ACTIVE), local setup including how to start PostgreSQL with Docker, run, test and coverage commands, design decisions, and known limitations (open API with no auth, GitHub 1000-result cap and rate limit, free-host cold starts). Only list features that exist.
5. Finalize .env.example with every variable and a comment each.
6. Create docs/INTERVIEW_GUIDE.md from Blueprint Section 16, edited so every statement matches the real code (class names, endpoints, behaviours). Remove or flag anything that was not built (for example the by-ID endpoint if skipped). Add the 30-second, 1-minute and 3-minute explanations and the 25+ questions, with answers true to this implementation.
7. Confirm docs/BLUEPRINT.md is present.
8. A stranger test: following only the README, can someone clone, start PostgreSQL, set variables, run and call the API? Execute the README steps literally in a clean shell and report any step that failed.
```

### Phase 7: Deployment preparation

```text
PROJECT: "GitHub Repository Searcher" - a Java 21 / Spring Boot 3.5.x / Maven / PostgreSQL REST API.
The approved design is in docs/BLUEPRINT.md. Read Sections 13 and 14 before starting. If it is missing, stop and tell me.
THIS PHASE: Phase 7 - deployment preparation. Phases 0 to 6 are done; verify mvn clean verify passes first.

OPERATING RULES (apply all, every phase)
1. Inspect first: list the directory, read existing files, avoid needless rewrites. Never overwrite an existing file without reading it.
2. docs/BLUEPRINT.md is the source of truth. Do not redesign. If you hit a real problem: state it, explain why the approach fails, propose the smallest change, and WAIT for my approval before any significant architectural change.
3. Work only on this phase, in small increments. Do not start the next phase.
4. For each significant file, explain what it does, why it exists, how it works, and what I must understand to explain it in an interview. I am a junior Java developer coming from Node/React.
5. Compile and run the relevant checks before claiming completion. Report exact commands and real results. Never claim the application is deployed, publicly reachable, or that a hosted database is connected unless you actually verified the live URL. You cannot deploy without my credentials: prepare everything and tell me what I must do by hand.
6. Quality: no secrets in the image, the repo or the logs. Never weaken a test to get green.
7. Finish with a PHASE COMPLETION REPORT: 1 summary, 2 files created, 3 files modified, 4 key decisions, 5 tests written, 6 commands run, 7 actual results, 8 outstanding issues, 9 manual verification steps, 10 concepts I should understand, 11 suggested commit message, 12 whether the next phase can safely begin. Then stop and wait.

TASKS
1. Dockerfile (multi-stage): a Maven + JDK 21 build stage that caches dependencies (copy pom.xml first, resolve, then copy src) and runs mvn -DskipTests package; a slim JRE 21 runtime stage; run as a non-root user; EXPOSE 8080; ENTRYPOINT java -jar app.jar. Do not bake any secret or .env into the image. Add .dockerignore (target, .git, .env, IDE files). Pick current, specific image tags (for example eclipse-temurin 21) and tell me which; do not use latest.
2. Confirm application-prod.yml: ddl-auto=validate, open-in-view=false, show-sql=false, Flyway on, actuator exposing only health, port from ${PORT:8080}, sensible logging levels, no secrets. Document that JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 can be set on small hosts.
3. Make the database URL robust: explain and document how to convert a provider's postgres://user:pass@host:5432/db string into DB_URL=jdbc:postgresql://host:5432/db with DB_USERNAME and DB_PASSWORD separate, and the ?sslmode=require option. Do not add code to parse provider URLs unless it is trivial and tested.
4. Verify for real: docker build succeeds; run the container against the local PostgreSQL (host networking or host.docker.internal, state which you used) with SPRING_PROFILES_ACTIVE=prod and env vars; curl /actuator/health, /swagger-ui.html, /v3/api-docs, POST /api/github/search, GET /api/github/repositories. Report real output. Confirm the image and repo contain no secrets (grep the build context and docker history).
5. Add a Deployment section to README.md: the generic steps (create hosted PostgreSQL, create a Docker-based web service from the GitHub repo, set the six environment variables, set the health-check path /actuator/health, deploy, run the six verification steps from Blueprint Section 14), with provider-specific placeholders. Ask me which hosting provider I chose before writing provider-specific commands, and do not claim any provider's free-tier terms.
6. Leave README live-URL TODOs in place until I give you real URLs.

List precisely what I must do by hand to deploy, in order.
```

### Phase 8: Final audit and submission

```text
PROJECT: "GitHub Repository Searcher" - a Java 21 / Spring Boot 3.5.x / Maven / PostgreSQL REST API.
The approved design is in docs/BLUEPRINT.md. Read the whole document. If it is missing, stop and tell me.
THIS PHASE: Phase 8 - final audit and submission readiness. All earlier phases are done. My public API URL is: <PASTE URL OR WRITE "not deployed yet">. My public repository URL is: <PASTE URL OR WRITE "not pushed yet">.

OPERATING RULES (apply all, every phase)
1. Inspect first: list the directory, read existing files. Make no changes unless an audit finding requires a fix; keep fixes minimal.
2. docs/BLUEPRINT.md is the source of truth. Do not redesign. If you find a real problem: state it, explain it, propose the smallest fix, and WAIT for my approval before any significant change.
3. Audit only. Do not add features.
4. Explain each finding in plain words and what I must understand to defend it in an interview. I am a junior Java developer coming from Node/React.
5. Run everything you report on. Report exact commands and real results. Never mark an item PASS without evidence (a command output, a test name, a URL response). If you cannot verify something, mark it UNVERIFIED and say what I must do.
6. Never weaken or delete a test to get green. Never invent results.
7. Finish with a PHASE COMPLETION REPORT (items 1 to 12 as in earlier phases) followed by the two reports below. Then stop.

AUDIT CHECKLIST (table: item, evidence, PASS / FAIL / UNVERIFIED)
- Java implementation; Spring Boot startup; PostgreSQL connection; Flyway migration applies on an empty database.
- GitHub integration works against the real GitHub once (manual curl is fine) and in tests only against mocks.
- POST /api/github/search: validation, persistence, response shape equals the assignment example.
- Upsert: repeating a search leaves one row per repository ID with updated values.
- GET /api/github/repositories: language, minStars, sort (stars, forks, updated), default sort stars, empty result, invalid input.
- Error handling: invalid GitHub responses, rate limits, empty results, invalid inputs, database errors each return the documented status and the standard error body.
- Unit tests and integration tests: run mvn clean verify; report counts; no skipped tests; coverage numbers.
- Swagger UI loads and documents every endpoint, locally and on the deployed URL.
- README: all 15 sections present; follow the setup steps literally; every claim matches the code; live URLs are real or clearly marked TODO.
- .env.example complete; .gitignore effective (git ls-files must show no .env).
- Secrets: scan the working tree AND full git history (git log -p --all) for GitHub tokens (ghp_, github_pat_, gho_), passwords, JDBC URLs with credentials, and private keys. Report any hit with the commit; if found, tell me to revoke the secret first and then how to remove it from history.
- Deployment: if I gave a URL, curl /actuator/health, /swagger-ui.html, POST search, and GET repositories on it and report real output; otherwise mark UNVERIFIED.
- Public repository: git remote -v; check that the repo URL answers 200 without authentication if I gave one; confirm the default branch contains the final code.
- Requirement traceability: walk every row of Blueprint Section 1 and point to evidence.

REPORT A: SUBMISSION READINESS. Overall verdict, blocking issues, non-blocking issues, and an ordered fix list.
REPORT B: INTERVIEW READINESS. Using docs/INTERVIEW_GUIDE.md and the actual code, list every concept used (DI, beans, JPA, transactions, validation, exception handling, RestClient, upsert, indexes, Testcontainers, Mockito, etc.), the file and class where it appears, and a 2-sentence plain explanation. Then list the 15 hardest questions an interviewer could ask about THIS code, and mark any where my likely answer would be weak. Point out any claim in the README or interview guide that the code does not support. Finish with the specific files I should reread tonight, in order.
```

## Final submission checklist

Tick these yourself on the last day. Phase 8's audit gives the evidence; this list is what you personally confirm.

**Assignment deliverables**

- [ ] Public GitHub repository link opens in a private/incognito window
- [ ] Public API base URL works (health returns UP)
- [ ] Swagger UI URL opens and Try-it-out works
- [ ] Public Loom recording link opens without login (check in incognito)

**Functionality**

- [ ] POST search returns repositories and the response shape matches the assignment example
- [ ] Repeating the same search does not create duplicates and updates the rows
- [ ] GET with `language`, `minStars`, `sort` works; default sort is stars
- [ ] Empty results, invalid input, rate limit and GitHub failure each return the documented status

**Quality**

- [ ] `mvn clean verify` passes with no skipped tests
- [ ] README has all 15 sections and live URLs are real
- [ ] `.env` is not tracked; no token or password anywhere in git history
- [ ] You can explain every item in the code ownership checklist (Section 16G)

**Loom outline (2 to 5 minutes; follow the assignment form exactly)**

Keep your face and screen visible throughout the recording. Explain the approach, demonstrate the deployed Swagger UI (search, repeat search to show upsert, filtered GET), explain one or two key technical decisions, and describe one challenge you faced and how you solved it. Keep the recording within the form's 2–5 minute limit; do not use the longer optional code-tour outline if it pushes you over the limit.

## Development timeline (Mon 16 to Wed 18 Nov 2026)

Two working days. Confirm the exact cut-off time on 18 Nov; this plan finishes the build by Tuesday night so Wednesday is audit, video and buffer. Phase times are my rough estimates including your review, not measured.

| When | Work | Done when |
| --- | --- | --- |
| Mon 16 Nov, evening | Create public repo; save blueprint to `docs/`; check Java 21, Maven, Docker. Spend 10 minutes checking two or three hosts' current terms and create a hosted PostgreSQL. **Phase 0** (about 45 min), **Phase 1** (about 60 min) | App boots, health UP, upsert and filter tests green |
| Tue 17 Nov, morning | **Phase 2** (client), **Phase 3** (services) | Client and service tests green |
| Tue 17 Nov, midday | **Phase 4** (controller, errors) | Both endpoints work via curl |
| Tue 17 Nov, afternoon | **Phase 7 pulled forward**: Dockerfile and first deploy. Fix deployment problems now, not on the last day | Public health and Swagger URLs respond |
| Tue 17 Nov, late afternoon | **Phase 5** (QA, coverage) | `mvn clean verify` green, gaps closed |
| Tue 17 Nov, evening | **Phase 6** (Swagger, README, interview guide). Reread Section 16 | README complete, URLs filled in |
| Wed 18 Nov, morning | **Phase 8** audit, fix findings, final redeploy, record and test the Loom | Checklist fully ticked |
| Wed 18 Nov, before cut-off | Submit all four links. Do not start new work after audit | Submitted with buffer |

### If you fall behind: cut in this order

1. `GET /repositories/{id}` (optional)
2. The `order` parameter and `synced_at`, if not yet built
3. JaCoCo report (keep the tests, drop the report)
4. GitHub Actions or any CI idea (not planned anyway)

**Never cut:** the upsert and its test, rate-limit and timeout handling and tests, the README, the deployment, the Swagger URL, the Loom.

**If the deployment fights you for more than an hour,** stop and ask for help with the exact error output; do not spend the whole evening on it. A local run plus clear README instructions is a weaker submission than a live URL, but a broken last-day deploy is worse.

**If Docker is not available on your machine,** tell the agent in Phase 0; the Testcontainers tests need an alternative plan (a local PostgreSQL with a test database) and that is a blueprint change that needs your approval.

## Assumptions to confirm tonight

- [ ] Compare this blueprint with the original assignment PDF, especially the GET response shape (Ambiguity A4) and the allowed `sort` values (A1).
- [ ] Confirm the submission cut-off time on 18 Nov and your time zone.
- [ ] Confirm Docker runs on your machine (needed for database tests).
- [ ] Pick a hosting provider after checking its current terms yourself.
- [ ] Confirm the newest Spring Boot 3.5.x patch and springdoc version resolve (Phase 0 does this and reports).
