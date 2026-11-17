package com.example.githubsearch.controller;

import com.example.githubsearch.dto.RepositoryPageResponse;
import com.example.githubsearch.dto.RepositoryResponse;
import com.example.githubsearch.dto.SearchRequest;
import com.example.githubsearch.dto.SearchResponse;
import com.example.githubsearch.exception.GithubApiException;
import com.example.githubsearch.exception.GithubRateLimitException;
import com.example.githubsearch.exception.InvalidRequestException;
import com.example.githubsearch.exception.RepositoryNotFoundException;
import com.example.githubsearch.model.SortField;
import com.example.githubsearch.service.GithubSearchService;
import com.example.githubsearch.service.StoredRepositoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-contract tests for {@link GithubRepositoryController} and {@link GlobalExceptionHandler}.
 *
 * <p><b>Why {@code @WebMvcTest}.</b> This slice starts Spring MVC, Jackson and the {@code
 * @RestControllerAdvice}, but no database, no Flyway and no GitHub client. The two services are
 * {@code @MockitoBean}s, so a test can make them return a value or throw the exact exception a real failure
 * would produce. That is what lets us prove the web layer's mapping decisions (status, headers, body) in
 * milliseconds, without a container and without a network call.
 *
 * <p><b>Why the assertions are strict.</b> The status code and the error body are the API's public contract.
 * A client that parses {@code details[0].field} must keep working when the controller is refactored; a
 * message that leaks a SQL error or an internal class name is a security bug. These tests fail if either
 * changes.
 *
 * <p><b>What is deliberately not tested here.</b> The services' own behaviour (filtering, sorting, upserts,
 * GitHub error translation) already has unit and integration tests in earlier phases. Mocking them here is
 * what isolates the HTTP decisions under test.
 */
@WebMvcTest(GithubRepositoryController.class)
class GithubRepositoryControllerTest {

    private static final String SAVED_MESSAGE = "Repositories fetched and saved successfully";
    private static final String EMPTY_MESSAGE = "No repositories found for the given search criteria";
    private static final Instant LAST_UPDATED = Instant.parse("2024-01-01T12:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2023-12-01T10:00:00Z");
    private static final long REPOSITORY_ID = 123_456L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GithubSearchService searchService;

    @MockitoBean
    private StoredRepositoryService storedRepositoryService;

    // ---------------------------------------------------------------------
    // POST /api/github/search - success
    // ---------------------------------------------------------------------

    @Test
    void postingAValidSearchReturnsTheBlueprintSuccessShape() throws Exception {
        when(searchService.search(any(SearchRequest.class)))
                .thenReturn(new SearchResponse(SAVED_MESSAGE, List.of(sampleRepository())));

        ResultActions result = postSearch("""
                {
                  "query": "spring boot",
                  "language": "Java",
                  "sort": "stars",
                  "page": 1,
                  "perPage": 30
                }
                """)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(SAVED_MESSAGE))
                .andExpect(jsonPath("$.repositories").isArray())
                .andExpect(jsonPath("$.repositories", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.repositories[0].id").value(REPOSITORY_ID))
                .andExpect(jsonPath("$.repositories[0].name").value("spring-boot-example"))
                .andExpect(jsonPath("$.repositories[0].description")
                        .value("An example repository for Spring Boot"))
                .andExpect(jsonPath("$.repositories[0].owner").value("user123"))
                .andExpect(jsonPath("$.repositories[0].language").value("Java"))
                .andExpect(jsonPath("$.repositories[0].stars").value(450))
                .andExpect(jsonPath("$.repositories[0].forks").value(120))
                .andExpect(jsonPath("$.repositories[0].lastUpdated").value("2024-01-01T12:00:00Z"))
                .andExpect(jsonPath("$.repositories[0].htmlUrl")
                        .value("https://github.com/user123/spring-boot-example"))
                .andExpect(jsonPath("$.repositories[0].openIssues").value(12))
                .andExpect(jsonPath("$.repositories[0].createdAt").value("2023-12-01T10:00:00Z"));

        JsonNode body = objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
        assertThat(fieldNames(body)).containsExactly("message", "repositories");
        assertThat(fieldNames(body.get("repositories").get(0))).containsExactly(
                "id", "name", "description", "owner", "language", "stars", "forks",
                "lastUpdated", "htmlUrl", "openIssues", "createdAt");
    }

    @Test
    void postingAnEmptyGithubResultStillReturns200WithTheEmptyMessage() throws Exception {
        when(searchService.search(any(SearchRequest.class)))
                .thenReturn(new SearchResponse(EMPTY_MESSAGE, List.of()));

        postSearch("{\"query\": \"nothing-matches-this\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(EMPTY_MESSAGE))
                .andExpect(jsonPath("$.repositories").isEmpty());
    }

    @Test
    void bindsTheValidatedBodyBeforeCallingTheService() throws Exception {
        when(searchService.search(any(SearchRequest.class)))
                .thenReturn(new SearchResponse(EMPTY_MESSAGE, List.of()));

        postSearch("{\"query\": \"spring boot\"}").andExpect(status().isOk());

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(searchService).search(captor.capture());
        assertThat(captor.getValue().query()).isEqualTo("spring boot");
        assertThat(captor.getValue().page()).isEqualTo(SearchRequest.DEFAULT_PAGE);
        assertThat(captor.getValue().perPage()).isEqualTo(SearchRequest.DEFAULT_PER_PAGE);
    }

    // ---------------------------------------------------------------------
    // POST /api/github/search - validation and malformed input
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "query [{0}] is rejected")
    @ValueSource(strings = {"{\"query\": \"\"}", "{\"query\": \"   \"}", "{}"})
    void postingABlankOrMissingQueryReturns400WithTheFieldDetail(String body) throws Exception {
        postSearch(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("query must not be blank"))
                .andExpect(jsonPath("$.path").value("/api/github/search"))
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.details[0].field").value("query"))
                .andExpect(jsonPath("$.details[0].message").value("query must not be blank"));

        verifyNoInteractions(searchService);
    }

    @Test
    void postingSeveralInvalidFieldsReturnsAllDetailsInAStableOrder() throws Exception {
        postSearch("{\"query\": \"\", \"perPage\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.details[0].field").value("perPage"))
                .andExpect(jsonPath("$.details[1].field").value("query"));

        verifyNoInteractions(searchService);
    }

    @Test
    void postingAQueryLongerThan256CharactersReturns400() throws Exception {
        String tooLong = "x".repeat(257);
        postSearch("{\"query\": \"" + tooLong + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("query must be at most 256 characters"))
                .andExpect(jsonPath("$.details[0].field").value("query"));

        verifyNoInteractions(searchService);
    }

    @ParameterizedTest(name = "perPage {0} is rejected")
    @ValueSource(ints = {0, 101})
    void postingPerPageOutsideOneToOneHundredReturns400(int perPage) throws Exception {
        postSearch("{\"query\": \"spring\", \"perPage\": " + perPage + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("perPage"));

        verifyNoInteractions(searchService);
    }

    @Test
    void postingAWindowBeyondGitHubResultCapReturns400AndNamesThePublicFields() throws Exception {
        postSearch("{\"query\": \"spring\", \"page\": 11, \"perPage\": 100}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("page * perPage must not exceed 1000 because GitHub returns at most "
                                + "1000 search results"))
                .andExpect(jsonPath("$.details[0].field").value("page*perPage"));

        verifyNoInteractions(searchService);
    }

    @Test
    void postingALanguageWithForbiddenCharactersReturns400() throws Exception {
        postSearch("{\"query\": \"spring\", \"language\": \"Java!\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Language may only contain letters, digits, spaces and the characters + # . -"))
                .andExpect(jsonPath("$.details[0].field").value("language"));

        verifyNoInteractions(searchService);
    }

    @Test
    void postingMalformedJsonReturns400WithoutParserInternals() throws Exception {
        postSearch("{\"query\": ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"))
                .andExpect(jsonPath("$.path").value("/api/github/search"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(content().string(not(containsString("JsonParseException"))))
                .andExpect(content().string(not(containsString("com.fasterxml"))));

        verifyNoInteractions(searchService);
    }

    @Test
    void postingAnInvalidGithubSortReturns400FromTheServiceWhitelist() throws Exception {
        when(searchService.search(any(SearchRequest.class)))
                .thenThrow(new InvalidRequestException(
                        "Invalid sort field 'rating'. Allowed values: stars, forks, updated"));

        postSearch("{\"query\": \"spring\", \"sort\": \"rating\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Invalid sort field 'rating'. Allowed values: stars, forks, updated"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    // ---------------------------------------------------------------------
    // POST /api/github/search - upstream failures
    // ---------------------------------------------------------------------

    @Test
    void aRateLimitBecomes429WithRetryAfterHeader() throws Exception {
        when(searchService.search(any(SearchRequest.class)))
                .thenThrow(new GithubRateLimitException(42L,
                        "GitHub API rate limit exceeded. Retry after 42 seconds", null));

        postSearch("{\"query\": \"spring\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "42"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message")
                        .value("GitHub API rate limit exceeded. Retry after 42 seconds"))
                .andExpect(jsonPath("$.path").value("/api/github/search"));
    }

    @Test
    void aRateLimitWithoutAKnownDelayOmitsRetryAfterHeader() throws Exception {
        when(searchService.search(any(SearchRequest.class)))
                .thenThrow(new GithubRateLimitException(null, "GitHub API rate limit exceeded", null));

        postSearch("{\"query\": \"spring\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    }

    @ParameterizedTest(name = "GitHub status {0} becomes {0}")
    @MethodSource("upstreamFailures")
    void mapsTheStatusCarriedByGithubApiException(HttpStatus status, String message) throws Exception {
        when(searchService.search(any(SearchRequest.class)))
                .thenThrow(new GithubApiException(status, message));

        postSearch("{\"query\": \"spring\"}")
                .andExpect(status().is(status.value()))
                .andExpect(jsonPath("$.status").value(status.value()))
                .andExpect(jsonPath("$.error").value(status.getReasonPhrase()))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value("/api/github/search"));
    }

    static Stream<Arguments> upstreamFailures() {
        return Stream.of(
                Arguments.of(HttpStatus.BAD_GATEWAY, "GitHub API is currently unavailable"),
                Arguments.of(HttpStatus.GATEWAY_TIMEOUT, "GitHub API did not respond in time"),
                Arguments.of(HttpStatus.BAD_REQUEST, "GitHub rejected the search query"));
    }

    // ---------------------------------------------------------------------
    // GET /api/github/repositories - success and service arguments
    // ---------------------------------------------------------------------

    @Test
    void getDefaultsToStarsDescendingFirstPageAndSizeOneHundred() throws Exception {
        when(storedRepositoryService.find(null, null, SortField.STARS, Sort.Direction.DESC, 0, 100))
                .thenReturn(new RepositoryPageResponse(List.of(), 0, 100, 0, 0));

        mockMvc.perform(get("/api/github/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));

        verify(storedRepositoryService).find(null, null, SortField.STARS, Sort.Direction.DESC, 0, 100);
        verifyNoInteractions(searchService);
    }

    @Test
    void getPassesLanguageMinStarsSortOrderAndPagingToTheService() throws Exception {
        when(storedRepositoryService.find(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new RepositoryPageResponse(List.of(sampleRepository()), 2, 50, 1, 1));

        mockMvc.perform(get("/api/github/repositories")
                        .param("language", "Java")
                        .param("minStars", "100")
                        .param("sort", "forks")
                        .param("order", "asc")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.repositories[0].id").value(REPOSITORY_ID))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        ArgumentCaptor<String> language = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> minStars = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<SortField> sort = ArgumentCaptor.forClass(SortField.class);
        ArgumentCaptor<Sort.Direction> direction = ArgumentCaptor.forClass(Sort.Direction.class);
        ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);

        verify(storedRepositoryService).find(language.capture(), minStars.capture(), sort.capture(),
                direction.capture(), page.capture(), size.capture());

        assertThat(language.getValue()).isEqualTo("Java");
        assertThat(minStars.getValue()).isEqualTo(100);
        assertThat(sort.getValue()).isEqualTo(SortField.FORKS);
        assertThat(direction.getValue()).isEqualTo(Sort.Direction.ASC);
        assertThat(page.getValue()).isEqualTo(2);
        assertThat(size.getValue()).isEqualTo(50);
        verifyNoInteractions(searchService);
    }

    @Test
    void getAcceptsAnEmptyResultAs200() throws Exception {
        when(storedRepositoryService.find(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new RepositoryPageResponse(List.of(), 0, 100, 0, 0));

        mockMvc.perform(get("/api/github/repositories").param("language", "COBOL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories").isArray())
                .andExpect(jsonPath("$.repositories").isEmpty());
    }

    // ---------------------------------------------------------------------
    // GET /api/github/repositories - bad query parameters
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is rejected")
    @MethodSource("invalidGetParameters")
    void getRejectsInvalidQueryParameters(String queryString, String field, String message) throws Exception {
        mockMvc.perform(get("/api/github/repositories?" + queryString))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value("/api/github/repositories"))
                .andExpect(jsonPath("$.details[0].field").value(field))
                .andExpect(jsonPath("$.details[0].message").value(message));

        verifyNoInteractions(storedRepositoryService, searchService);
    }

    static Stream<Arguments> invalidGetParameters() {
        return Stream.of(
                Arguments.of("minStars=-1", "minStars",
                        "minStars must be greater than or equal to 0"),
                Arguments.of("page=-1", "page",
                        "page must be greater than or equal to 0"),
                Arguments.of("size=101", "size",
                        "size must be less than or equal to 100"),
                Arguments.of("language=" + "j".repeat(51), "language",
                        "language must be at most 50 characters"),
                Arguments.of("order=sideways", "order",
                        "order must be 'asc' or 'desc'"));
    }

    @Test
    void getRejectsAnUnknownSortBeforeTouchingTheDatabase() throws Exception {
        mockMvc.perform(get("/api/github/repositories").param("sort", "rating"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Invalid sort field 'rating'. Allowed values: stars, forks, updated"));

        verifyNoInteractions(storedRepositoryService, searchService);
    }

    @Test
    void getRejectsANonNumericMinStarsAsABadRequest() throws Exception {
        mockMvc.perform(get("/api/github/repositories").param("minStars", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parameter 'minStars' must be a valid number"))
                .andExpect(jsonPath("$.details").doesNotExist());

        verifyNoInteractions(storedRepositoryService, searchService);
    }

    // ---------------------------------------------------------------------
    // GET /api/github/repositories/{id}
    // ---------------------------------------------------------------------

    @Test
    void getByIdReturnsTheStoredRepository() throws Exception {
        when(storedRepositoryService.findById(REPOSITORY_ID)).thenReturn(sampleRepository());

        mockMvc.perform(get("/api/github/repositories/{id}", REPOSITORY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(REPOSITORY_ID))
                .andExpect(jsonPath("$.name").value("spring-boot-example"))
                .andExpect(jsonPath("$.owner").value("user123"))
                .andExpect(jsonPath("$.lastUpdated").value("2024-01-01T12:00:00Z"));

        verifyNoInteractions(searchService);
    }

    @Test
    void getByIdReturns404WhenTheRepositoryIsNotStored() throws Exception {
        when(storedRepositoryService.findById(9L)).thenThrow(new RepositoryNotFoundException(9L));

        mockMvc.perform(get("/api/github/repositories/{id}", 9L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Repository with id 9 was not found"))
                .andExpect(jsonPath("$.path").value("/api/github/repositories/9"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void getByIdReturns400ForANonNumericId() throws Exception {
        mockMvc.perform(get("/api/github/repositories/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parameter 'id' must be a valid number"));

        verifyNoInteractions(storedRepositoryService, searchService);
    }

    // ---------------------------------------------------------------------
    // Database and unexpected failures
    // ---------------------------------------------------------------------

    @Test
    void databaseUnavailableBecomes503WithoutInternalDetail() throws Exception {
        when(storedRepositoryService.find(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException(
                        "Connection to 127.0.0.1:5432 refused"));

        mockMvc.perform(get("/api/github/repositories"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("Database is temporarily unavailable"))
                .andExpect(content().string(not(containsString("127.0.0.1"))))
                .andExpect(content().string(not(containsString("DataAccessResourceFailureException"))));
    }

    @Test
    void anotherDatabaseErrorBecomes500WithGenericMessage() throws Exception {
        when(storedRepositoryService.find(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"github_repositories_pkey\""));

        mockMvc.perform(get("/api/github/repositories"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected database error occurred"))
                .andExpect(content().string(not(containsString("duplicate key"))))
                .andExpect(content().string(not(containsString("github_repositories_pkey"))));
    }

    @Test
    void anUnexpectedFailureBecomes500WithoutLeakingTheCause() throws Exception {
        when(searchService.search(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("secret internal detail"));

        postSearch("{\"query\": \"spring\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value("/api/github/search"))
                .andExpect(content().string(not(containsString("secret internal detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private ResultActions postSearch(String json) throws Exception {
        return mockMvc.perform(post("/api/github/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static RepositoryResponse sampleRepository() {
        return new RepositoryResponse(
                REPOSITORY_ID,
                "spring-boot-example",
                "An example repository for Spring Boot",
                "user123",
                "Java",
                450,
                120,
                LAST_UPDATED,
                "https://github.com/user123/spring-boot-example",
                12,
                CREATED_AT);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
