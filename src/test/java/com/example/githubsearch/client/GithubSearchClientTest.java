package com.example.githubsearch.client;

import com.example.githubsearch.config.GithubProperties;
import com.example.githubsearch.dto.github.GithubRepositoryItem;
import com.example.githubsearch.dto.github.GithubSearchResponse;
import com.example.githubsearch.exception.GithubApiException;
import com.example.githubsearch.exception.GithubRateLimitException;
import com.example.githubsearch.model.SortField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HTTP-contract tests for {@link GithubSearchClient}.
 *
 * <p>{@link MockRestServiceServer} replaces the {@code RestClient}'s request factory with a fake server:
 * the client builds a real request (real URL encoding, real headers, real Jackson parsing) and the fake
 * server answers with a canned body. No socket is opened and no GitHub quota is spent, so these tests are
 * fast and deterministic; {@link #server}{@code .verify()} then proves the request the client sent.
 *
 * <p>Canned bodies live in {@code src/test/resources/github/}. Query expectations are written with the
 * exact percent-encoding Spring produces so that {@code C++} and {@code C#} cannot silently regress into a
 * URL GitHub would misread.
 */
class GithubSearchClientTest {

    private static final String BASE_URL = "https://api.github.com";
    private static final String SEARCH_URL = BASE_URL + "/search/repositories";
    private static final String SPRING_SEARCH_URL = SEARCH_URL + "?q=spring%20in%3Aname&page=1&per_page=30";
    private static final String TOKEN = "test-token-not-real";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String RATE_LIMIT_RESET = "X-RateLimit-Reset";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    // ---------------------------------------------------------------------
    // URL construction and encoding
    // ---------------------------------------------------------------------

    @Test
    void buildsTheSearchUrlForAQueryOnly() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withSuccess(json("search-ok.json"), MediaType.APPLICATION_JSON));

        client().search("spring", null, null, 1, 30);

        server.verify();
    }

    @Test
    void appendsTheLanguageQualifierWhenALanguageIsGiven() {
        server.expect(requestTo(
                        SEARCH_URL + "?q=spring%20boot%20in%3Aname%20language%3AJava&page=1&per_page=30"))
                .andRespond(withSuccess(json("search-ok.json"), MediaType.APPLICATION_JSON));

        client().search("spring boot", "Java", null, 1, 30);

        server.verify();
    }

    @Test
    void quotesALanguageThatContainsASpace() {
        server.expect(requestTo(SEARCH_URL
                        + "?q=notes%20in%3Aname%20language%3A%22Jupyter%20Notebook%22&page=1&per_page=30"))
                .andRespond(withSuccess(json("search-empty.json"), MediaType.APPLICATION_JSON));

        client().search("notes", "Jupyter Notebook", null, 1, 30);

        server.verify();
    }

    @Test
    void percentEncodesPlusSignsInCPlusPlus() {
        server.expect(requestTo(SEARCH_URL
                        + "?q=C%2B%2B%20in%3Aname%20language%3AC%2B%2B&page=1&per_page=30"))
                .andRespond(withSuccess(json("search-empty.json"), MediaType.APPLICATION_JSON));

        client().search("C++", "C++", null, 1, 30);

        server.verify();
    }

    @Test
    void percentEncodesHashSignsInCSharp() {
        server.expect(requestTo(SEARCH_URL
                        + "?q=C%23%20in%3Aname%20language%3AC%23&page=1&per_page=30"))
                .andRespond(withSuccess(json("search-empty.json"), MediaType.APPLICATION_JSON));

        client().search("C#", "C#", null, 1, 30);

        server.verify();
    }

    @ParameterizedTest
    @EnumSource(SortField.class)
    void sendsEachWhitelistedSortWithDescendingOrder(SortField sort) {
        server.expect(requestTo(SPRING_SEARCH_URL + "&sort=" + sort.githubValue() + "&order=desc"))
                .andRespond(withSuccess(json("search-empty.json"), MediaType.APPLICATION_JSON));

        client().search("spring", null, sort, 1, 30);

        server.verify();
    }

    @Test
    void omitsSortAndOrderEntirelyWhenNoSortWasRequested() {
        // The exact URL check fails if either parameter leaks in: GitHub then uses best-match order.
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withSuccess(json("search-empty.json"), MediaType.APPLICATION_JSON));

        client().search("spring", null, null, 1, 30);

        server.verify();
    }

    @Test
    void alwaysSendsPageAndPerPage() {
        server.expect(requestTo(SEARCH_URL + "?q=spring%20in%3Aname&page=2&per_page=100"))
                .andRespond(withSuccess(json("search-empty.json"), MediaType.APPLICATION_JSON));

        client().search("spring", null, null, 2, 100);

        server.verify();
    }

    // ---------------------------------------------------------------------
    // Headers and authentication
    // ---------------------------------------------------------------------

    @Test
    void sendsTheHeadersGithubRequires() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(header(HttpHeaders.USER_AGENT, "github-repository-searcher"))
                .andRespond(withSuccess(json("search-empty.json"), MediaType.APPLICATION_JSON));

        client().search("spring", null, null, 1, 30);

        server.verify();
    }

    @Test
    void sendsTheTokenAsABearerHeaderWhenOneIsConfigured() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(json("search-empty.json"), MediaType.APPLICATION_JSON));

        client(TOKEN).search("spring", null, null, 1, 30);

        server.verify();
    }

    @Test
    void sendsNoAuthorizationHeaderWhenTheTokenIsBlank() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andExpect(request -> assertThat(request.getHeaders())
                        .doesNotContainKey(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(json("search-empty.json"), MediaType.APPLICATION_JSON));

        client("   ").search("spring", null, null, 1, 30);

        server.verify();
    }

    // ---------------------------------------------------------------------
    // Successful responses
    // ---------------------------------------------------------------------

    @Test
    void parsesRepositoriesAndEveryFieldWePersist() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withSuccess(json("search-ok.json"), MediaType.APPLICATION_JSON));

        GithubSearchResponse response = client().search("spring", null, null, 1, 30);

        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.incompleteResults()).isFalse();
        assertThat(response.items()).hasSize(2);

        GithubRepositoryItem item = response.items().get(0);
        assertThat(item.id()).isEqualTo(6296790L);
        assertThat(item.name()).isEqualTo("spring-boot");
        assertThat(item.description()).isEqualTo("Spring Boot helps you build applications");
        assertThat(item.owner().login()).isEqualTo("spring-projects");
        assertThat(item.language()).isEqualTo("Java");
        assertThat(item.stars()).isEqualTo(75000);
        assertThat(item.forks()).isEqualTo(41000);
        assertThat(item.lastUpdated()).isEqualTo(Instant.parse("2026-11-10T09:12:00Z"));
        assertThat(item.htmlUrl()).isEqualTo("https://github.com/spring-projects/spring-boot");
        assertThat(item.openIssues()).isEqualTo(12);
        assertThat(item.createdAt()).isEqualTo(Instant.parse("2012-10-19T04:00:00Z"));

        server.verify();
    }

    @Test
    void returnsAnEmptyItemListForASuccessfulSearchWithNoMatches() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withSuccess(json("search-empty.json"), MediaType.APPLICATION_JSON));

        GithubSearchResponse response = client().search("spring", null, null, 1, 30);

        assertThat(response.totalCount()).isZero();
        assertThat(response.items()).isEmpty();

        server.verify();
    }

    @Test
    void keepsNullDescriptionLanguageAndUpdatedAtInsteadOfFailing() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withSuccess(json("search-nullable.json"), MediaType.APPLICATION_JSON));

        GithubRepositoryItem item = client().search("spring", null, null, 1, 30).items().get(0);

        assertThat(item.description()).isNull();
        assertThat(item.language()).isNull();
        assertThat(item.lastUpdated()).isNull();
        assertThat(item.createdAt()).isNull();

        server.verify();
    }

    @Test
    void keepsANullOpenIssuesCountAsNullSoTheMapperCanDefaultsItLater() {
        // The DTO uses wrapper types: an absent count stays null, never a fabricated 0. Phase 3's mapper
        // applies the database's NOT NULL default. What matters here is that parsing does not throw.
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withSuccess(json("search-nullable.json"), MediaType.APPLICATION_JSON));

        GithubRepositoryItem item = client().search("spring", null, null, 1, 30).items().get(0);

        assertThat(item.openIssues()).isNull();

        server.verify();
    }

    @Test
    void ignoresUnknownGithubFields() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withSuccess(json("search-ok.json"), MediaType.APPLICATION_JSON));

        GithubSearchResponse response = client().search("spring", null, null, 1, 30);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(1).name()).isEqualTo("tiny-notes");

        server.verify();
    }

    // ---------------------------------------------------------------------
    // Rate limits
    // ---------------------------------------------------------------------

    @Test
    void mapsAForbiddenResponseWithAnExhaustedRateLimitToRateLimitException() {
        long resetAtEpochSeconds = Instant.now().plusSeconds(60).getEpochSecond();
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header(RATE_LIMIT_REMAINING, "0")
                        .header(RATE_LIMIT_RESET, String.valueOf(resetAtEpochSeconds))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json("error-rate-limit.json")));

        assertThatThrownBy(() -> client().search("spring", null, null, 1, 30))
                .isInstanceOfSatisfying(GithubRateLimitException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.retryAfterSeconds()).isNotNull().isBetween(1L, 60L);
                });

        server.verify();
    }

    @Test
    void mapsATooManyRequestsResponseWithRetryAfterToRateLimitException() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json("error-rate-limit.json")));

        assertThatThrownBy(() -> client().search("spring", null, null, 1, 30))
                .isInstanceOfSatisfying(GithubRateLimitException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.retryAfterSeconds()).isEqualTo(42L);
                    assertThat(exception.getMessage()).contains("Retry after 42 seconds");
                });

        server.verify();
    }

    @Test
    void treatsAForbiddenResponseWithoutRateLimitSignalsAsAnUpstreamFailure() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json("error-rate-limit.json")));

        assertThatThrownBy(() -> client().search("spring", null, null, 1, 30))
                .isInstanceOfSatisfying(GithubApiException.class, exception -> {
                    assertThat(exception).isNotInstanceOf(GithubRateLimitException.class);
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });

        server.verify();
    }

    @Test
    void reportsAnUnknownRetryDelayWhenGithubSendsNoTimingHeaders() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json("error-rate-limit.json")));

        assertThatThrownBy(() -> client().search("spring", null, null, 1, 30))
                .isInstanceOfSatisfying(GithubRateLimitException.class, exception -> {
                    assertThat(exception.retryAfterSeconds()).isNull();
                    assertThat(exception.getMessage()).isEqualTo("GitHub API rate limit exceeded");
                });

        server.verify();
    }

    @Test
    void fallsBackToTheRateLimitResetWhenRetryAfterIsAnHttpDateRatherThanSeconds() {
        long resetAtEpochSeconds = Instant.now().plusSeconds(30).getEpochSecond();
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header(HttpHeaders.RETRY_AFTER, "Wed, 21 Oct 2026 07:28:00 GMT")
                        .header(RATE_LIMIT_RESET, String.valueOf(resetAtEpochSeconds))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json("error-rate-limit.json")));

        assertThatThrownBy(() -> client().search("spring", null, null, 1, 30))
                .isInstanceOfSatisfying(GithubRateLimitException.class, exception ->
                        assertThat(exception.retryAfterSeconds()).isNotNull().isBetween(1L, 30L));

        server.verify();
    }

    // ---------------------------------------------------------------------
    // Upstream failures
    // ---------------------------------------------------------------------

    @Test
    void mapsUnauthorizedToBadGatewayWithoutLeakingTheToken() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Bad credentials\"}"));

        assertThatThrownBy(() -> client(TOKEN).search("spring", null, null, 1, 30))
                .isInstanceOfSatisfying(GithubApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getMessage()).doesNotContain(TOKEN);
                });

        server.verify();
    }

    @Test
    void mapsGithubValidationFailureToABadRequestForOurCaller() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json("error-validation.json")));

        assertThatThrownBy(() -> client().search("spring", null, null, 1, 30))
                .isInstanceOfSatisfying(GithubApiException.class, exception ->
                        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        server.verify();
    }

    @Test
    void mapsAServerErrorToBadGateway() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Server Error\"}"));

        assertThatThrownBy(() -> client().search("spring", null, null, 1, 30))
                .isInstanceOfSatisfying(GithubApiException.class, exception ->
                        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY));

        server.verify();
    }

    @Test
    void mapsMalformedJsonToBadGateway() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withSuccess(json("search-malformed.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().search("spring", null, null, 1, 30))
                .isInstanceOfSatisfying(GithubApiException.class, exception ->
                        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY));

        server.verify();
    }

    @Test
    void mapsAResponseWithoutItemsToBadGateway() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withSuccess(json("search-missing-items.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().search("spring", null, null, 1, 30))
                .isInstanceOfSatisfying(GithubApiException.class, exception ->
                        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY));

        server.verify();
    }

    @Test
    void mapsReadTimeoutsToGatewayTimeoutAndKeepsTheCauseForLogging() {
        server.expect(requestTo(SPRING_SEARCH_URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client().search("spring", null, null, 1, 30))
                .isInstanceOfSatisfying(GithubApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(exception.getMessage()).isEqualTo("GitHub API did not respond in time");
                    assertThat(exception).hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
                });

        server.verify();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private GithubSearchClient client() {
        return client("");
    }

    private GithubSearchClient client(String token) {
        GithubProperties properties = new GithubProperties(token, BASE_URL, CONNECT_TIMEOUT, READ_TIMEOUT);
        return new GithubSearchClient(builder, properties);
    }

    private static String json(String fileName) {
        try {
            return new ClassPathResource("github/" + fileName).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Missing test fixture github/" + fileName, exception);
        }
    }
}
