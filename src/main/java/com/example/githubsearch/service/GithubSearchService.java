package com.example.githubsearch.service;

import com.example.githubsearch.client.GithubSearchClient;
import com.example.githubsearch.dto.SearchRequest;
import com.example.githubsearch.dto.SearchResponse;
import com.example.githubsearch.dto.github.GithubSearchResponse;
import com.example.githubsearch.entity.GithubRepository;
import com.example.githubsearch.mapper.GithubRepositoryMapper;
import com.example.githubsearch.model.SortField;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The use case behind {@code POST /api/github/search}: ask GitHub, convert, store, answer.
 *
 * <p><b>The flow, step by step.</b>
 * <ol>
 *   <li>{@code SortField.from(request.sort())} turns client text into a whitelisted enum. {@code null}
 *       stays {@code null} (GitHub then uses best-match order); an unknown word throws before any I/O, so a
 *       typo is a 400, not a wasted GitHub request.</li>
 *   <li>{@code client.search(...)} performs the only remote call and either returns GitHub's answer or
 *       throws a typed exception. Nothing is caught here: a timeout, rate limit or 5xx must reach the
 *       exception handler as a <em>failure</em>, never be converted into an empty result.</li>
 *   <li>If {@code items} is empty (or absent), the method returns the "no repositories" message and an
 *       empty list <b>without touching the database</b>. An empty answer is a successful search: the call
 *       worked and the world simply has no match. Opening a transaction to write zero rows would be wasted
 *       work and would blur the distinction between "nothing matched" and "nothing happened".</li>
 *   <li>Otherwise the mapper builds entities (skipping unusable items, defaulting counts), the storage
 *       service upserts the batch in one transaction, and the same entities are mapped back to public
 *       responses <b>in GitHub's order</b>. Returning the mapped entities rather than re-reading the
 *       database avoids a second query and guarantees the caller sees exactly what was saved.</li>
 * </ol>
 *
 * <p><b>Why this class is NOT {@code @Transactional}.</b> Transaction boundaries belong around the
 * database work, not around a remote HTTP call. If this method were transactional, Spring would open a
 * transaction (and borrow a JDBC connection) before calling GitHub and hold it for the whole request; a
 * slow GitHub response would pin pool connections and could exhaust them. Here the transaction starts only
 * inside {@link StoredRepositoryService#upsertAll(List)}, after GitHub has answered. Keeping the annotation
 * on the other bean also avoids Spring's self-invocation trap: a call from one method of this class to
 * another method of the same class bypasses the proxy, so a {@code @Transactional} annotation there would
 * silently do nothing. A call across beans always goes through the proxy.
 *
 * <p>The two messages are constants because they are part of the API contract: clients and tests should see
 * the same wording every time, and the controller does not invent it.
 */
@Service
public class GithubSearchService {

    private static final String SAVED_MESSAGE = "Repositories fetched and saved successfully";
    private static final String EMPTY_MESSAGE = "No repositories found for the given search criteria";

    private final GithubSearchClient client;
    private final GithubRepositoryMapper mapper;
    private final StoredRepositoryService storage;

    public GithubSearchService(GithubSearchClient client, GithubRepositoryMapper mapper,
                               StoredRepositoryService storage) {
        this.client = client;
        this.mapper = mapper;
        this.storage = storage;
    }

    /**
     * Runs one search end to end.
     *
     * @param request an already validated request (Phase 4 applies {@code @Valid} at the controller)
     * @return the saved repositories, or an empty list with an explanatory message
     * @throws com.example.githubsearch.exception.InvalidRequestException on an unknown sort value
     * @throws com.example.githubsearch.exception.GithubRateLimitException when GitHub's quota is exhausted
     * @throws com.example.githubsearch.exception.GithubApiException for other GitHub or network failures
     */
    public SearchResponse search(SearchRequest request) {
        SortField sort = SortField.from(request.sort());

        GithubSearchResponse githubResponse = client.search(
                request.query(), request.language(), sort, request.page(), request.perPage());

        if (githubResponse.items() == null || githubResponse.items().isEmpty()) {
            return new SearchResponse(EMPTY_MESSAGE, List.of());
        }

        List<GithubRepository> entities = mapper.toEntities(githubResponse);
        storage.upsertAll(entities);

        return new SearchResponse(SAVED_MESSAGE, mapper.toResponses(entities));
    }
}
