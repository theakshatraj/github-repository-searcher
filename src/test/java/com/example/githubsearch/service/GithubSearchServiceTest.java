package com.example.githubsearch.service;

import com.example.githubsearch.client.GithubSearchClient;
import com.example.githubsearch.dto.RepositoryResponse;
import com.example.githubsearch.dto.SearchRequest;
import com.example.githubsearch.dto.SearchResponse;
import com.example.githubsearch.dto.github.GithubOwner;
import com.example.githubsearch.dto.github.GithubRepositoryItem;
import com.example.githubsearch.dto.github.GithubSearchResponse;
import com.example.githubsearch.entity.GithubRepository;
import com.example.githubsearch.exception.GithubApiException;
import com.example.githubsearch.exception.GithubRateLimitException;
import com.example.githubsearch.exception.InvalidRequestException;
import com.example.githubsearch.mapper.GithubRepositoryMapper;
import com.example.githubsearch.model.SortField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the search orchestration.
 *
 * <p>The GitHub client and the storage service are mocks; the mapper is real. That combination is the point
 * of this class: it proves the service's sequencing and its two hard rules - <b>never write when GitHub has
 * no results</b> and <b>never swallow an upstream failure</b> - without a network call or a database.
 *
 * <p>The client is mocked at the {@link SortField} boundary, which is also a small check that raw request
 * text was converted before reaching it.
 */
@ExtendWith(MockitoExtension.class)
class GithubSearchServiceTest {

    private static final String SAVED_MESSAGE = "Repositories fetched and saved successfully";
    private static final String EMPTY_MESSAGE = "No repositories found for the given search criteria";
    private static final Instant LAST_UPDATED = Instant.parse("2026-11-10T09:12:00Z");
    private static final Instant CREATED_AT = Instant.parse("2012-10-19T04:00:00Z");

    @Mock
    private GithubSearchClient client;

    @Mock
    private StoredRepositoryService storage;

    private final GithubRepositoryMapper mapper = new GithubRepositoryMapper();

    private GithubSearchService service;

    @BeforeEach
    void setUp() {
        service = new GithubSearchService(client, mapper, storage);
    }

    // ---------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------

    @Test
    void savesAndReturnsMappedRepositoriesInGitHubsOrder() {
        when(client.search("spring", "Java", SortField.STARS, 1, 30))
                .thenReturn(responseOf(item(1L, "first"), item(2L, "second")));

        SearchResponse result = service.search(new SearchRequest("spring", "Java", "stars", 1, 30));

        assertThat(result.message()).isEqualTo(SAVED_MESSAGE);
        assertThat(result.repositories()).extracting(RepositoryResponse::id).containsExactly(1L, 2L);
        assertThat(result.repositories()).extracting(RepositoryResponse::name)
                .containsExactly("first", "second");
        assertThat(result.repositories().getFirst().htmlUrl())
                .isEqualTo("https://github.com/owner-first/first");
        assertThat(result.repositories().getFirst().openIssues()).isEqualTo(5);
        assertThat(result.repositories().getFirst().createdAt()).isEqualTo(CREATED_AT);

        ArgumentCaptor<List<GithubRepository>> saved = entitiesCaptor();
        verify(storage).upsertAll(saved.capture());
        assertThat(saved.getValue()).extracting(GithubRepository::getId).containsExactly(1L, 2L);
        assertThat(saved.getValue()).extracting(GithubRepository::getOwner)
                .containsExactly("owner-first", "owner-second");
    }

    @Test
    void doesNotTouchTheDatabaseWhenGitHubReturnsNoItems() {
        when(client.search("nothing", null, null, 1, 30)).thenReturn(noItems());

        SearchResponse result = service.search(new SearchRequest("nothing", null, null, 1, 30));

        assertThat(result.message()).isEqualTo(EMPTY_MESSAGE);
        assertThat(result.repositories()).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test
    void doesNotTouchTheDatabaseWhenTheItemsFieldIsNull() {
        when(client.search("nothing", null, null, 1, 30))
                .thenReturn(new GithubSearchResponse(null, false, null));

        SearchResponse result = service.search(new SearchRequest("nothing", null, null, 1, 30));

        assertThat(result.message()).isEqualTo(EMPTY_MESSAGE);
        assertThat(result.repositories()).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test
    void savesOnlyTheItemsTheMapperCouldUse() {
        GithubRepositoryItem valid = item(1L, "valid");
        GithubRepositoryItem noOwner = new GithubRepositoryItem(
                2L, "no-owner", null, null, "Java", 1, 0, null, null, 0, null);
        when(client.search("spring", null, null, 1, 30)).thenReturn(responseOf(valid, noOwner));

        SearchResponse result = service.search(new SearchRequest("spring", null, null, 1, 30));

        assertThat(result.message()).isEqualTo(SAVED_MESSAGE);
        assertThat(result.repositories()).extracting(RepositoryResponse::id).containsExactly(1L);
        ArgumentCaptor<List<GithubRepository>> saved = entitiesCaptor();
        verify(storage).upsertAll(saved.capture());
        assertThat(saved.getValue()).extracting(GithubRepository::getId).containsExactly(1L);
    }

    @Test
    void doesNotClaimASaveWhenEveryGitHubItemWasUnusable() {
        // GitHub answered with items, but none has the id, name and owner login the table requires.
        // Nothing can be written, so "saved successfully" would be a lie and opening a transaction to
        // save zero rows would be pointless.
        GithubRepositoryItem noOwner = new GithubRepositoryItem(
                2L, "no-owner", null, null, "Java", 1, 0, null, null, 0, null);
        GithubRepositoryItem noName = new GithubRepositoryItem(
                3L, null, null, new GithubOwner("someone"), "Java", 1, 0, null, null, 0, null);
        when(client.search("spring", null, null, 1, 30)).thenReturn(responseOf(noOwner, noName));

        SearchResponse result = service.search(new SearchRequest("spring", null, null, 1, 30));

        assertThat(result.message()).isEqualTo(EMPTY_MESSAGE);
        assertThat(result.repositories()).isEmpty();
        verifyNoInteractions(storage);
    }

    // ---------------------------------------------------------------------
    // Request -> client mapping
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"stars, STARS", "forks, FORKS", "updated, UPDATED"})
    void passesTheRequestedSortToTheClientAsTheWhitelistedEnum(String sortText, SortField expected) {
        when(client.search("spring", null, expected, 1, 30)).thenReturn(noItems());

        service.search(new SearchRequest("spring", null, sortText, 1, 30));

        verify(client).search("spring", null, expected, 1, 30);
    }

    @Test
    void omitsTheSortEntirelyWhenTheRequestHasNone() {
        when(client.search("spring", null, null, 1, 30)).thenReturn(noItems());

        service.search(new SearchRequest("spring", null, null, 1, 30));

        verify(client).search("spring", null, null, 1, 30);
    }

    @Test
    void passesTheDefaultsForAnOmittedPageAndPerPage() {
        when(client.search("spring", null, null, 1, 30)).thenReturn(noItems());

        service.search(new SearchRequest("spring", null, null, null, null));

        verify(client).search("spring", null, null, 1, 30);
    }

    @Test
    void rejectsAnInvalidSortBeforeCallingGitHub() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.search(new SearchRequest("spring", null, "rating", 1, 30)))
                .withMessageContaining("rating")
                .withMessageContaining("Allowed values: stars, forks, updated");

        verifyNoInteractions(client, storage);
    }

    // ---------------------------------------------------------------------
    // Upstream failures propagate untouched
    // ---------------------------------------------------------------------

    @Test
    void propagatesRateLimitFailuresAndSavesNothing() {
        GithubRateLimitException rateLimit = new GithubRateLimitException(
                42L, "GitHub API rate limit exceeded. Retry after 42 seconds", null);
        when(client.search("spring", null, null, 1, 30)).thenThrow(rateLimit);

        assertThatThrownBy(() -> service.search(new SearchRequest("spring", null, null, 1, 30)))
                .isSameAs(rateLimit);

        verifyNoInteractions(storage);
    }

    @Test
    void propagatesTimeoutFailuresAndSavesNothing() {
        GithubApiException timeout = new GithubApiException(
                HttpStatus.GATEWAY_TIMEOUT, "GitHub API did not respond in time");
        when(client.search("spring", null, null, 1, 30)).thenThrow(timeout);

        assertThatThrownBy(() -> service.search(new SearchRequest("spring", null, null, 1, 30)))
                .isSameAs(timeout);

        verifyNoInteractions(storage);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<GithubRepository>> entitiesCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static GithubSearchResponse noItems() {
        return new GithubSearchResponse(0, false, List.of());
    }

    private static GithubSearchResponse responseOf(GithubRepositoryItem... items) {
        return new GithubSearchResponse(items.length, false, Arrays.asList(items));
    }

    private static GithubRepositoryItem item(long id, String name) {
        return new GithubRepositoryItem(
                id,
                name,
                "The " + name + " repository",
                new GithubOwner("owner-" + name),
                "Java",
                10,
                2,
                LAST_UPDATED,
                "https://github.com/owner-" + name + "/" + name,
                5,
                CREATED_AT);
    }
}
