package com.example.githubsearch.client;

import com.example.githubsearch.config.GithubProperties;
import com.example.githubsearch.dto.github.GithubSearchResponse;
import com.example.githubsearch.exception.GithubApiException;
import com.example.githubsearch.exception.GithubRateLimitException;
import com.example.githubsearch.model.SortField;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;

/**
 * The only class in the application that knows GitHub exists.
 *
 * <p>Why it is its own class: GitHub's JSON shape, headers, authentication, pagination vocabulary, rate
 * limit rules and error codes are all quirks of an external system. Keeping them behind one method means
 * the services above it speak a small, typed language ({@link GithubSearchResponse} or a typed exception),
 * and unit tests can replace this class with a mock. If GitHub changes, only this file changes; if our
 * business rules change, this file does not - that is single responsibility, and it is what makes the rest
 * of the application testable without network access.
 *
 * <p>It is a concrete class, not an interface: there is exactly one implementation (the blueprint forbids
 * speculative interfaces), and Spring can inject a concrete class perfectly well. Mockito can mock a class.
 *
 * <p>No retries: a search is user-initiated, GitHub's search quota is tight (about 10 requests/minute
 * unauthenticated), and retrying a rate-limit response makes it worse. A timeout or upstream failure is
 * reported honestly instead.
 */
@Component
public class GithubSearchClient {

    private static final String SEARCH_PATH = "/search/repositories";
    private static final String QUERY_PARAM = "{q}";
    private static final String API_MEDIA_TYPE = "application/vnd.github+json";
    private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    private static final String API_VERSION = "2022-11-28";
    private static final String USER_AGENT = "github-repository-searcher";
    private static final String RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String RATE_LIMIT_RESET_HEADER = "X-RateLimit-Reset";
    private static final String NAME_QUALIFIER = "in:name";
    private static final long UNKNOWN_RETRY_DELAY = 0L;

    private static final String TIMEOUT_MESSAGE = "GitHub API did not respond in time";
    private static final String UNAVAILABLE_MESSAGE = "GitHub API is currently unavailable";
    private static final String UNREADABLE_MESSAGE = "GitHub API returned an unreadable response";
    private static final String CREDENTIALS_MESSAGE = "GitHub API rejected the configured credentials";
    private static final String ACCESS_DENIED_MESSAGE = "GitHub API access was denied";
    private static final String INVALID_QUERY_MESSAGE = "GitHub rejected the search query";
    private static final String RATE_LIMIT_MESSAGE = "GitHub API rate limit exceeded";

    private final RestClient restClient;

    public GithubSearchClient(RestClient.Builder restClientBuilder, GithubProperties properties) {
        RestClient.Builder configured = restClientBuilder
                .defaultHeader(HttpHeaders.ACCEPT, API_MEDIA_TYPE)
                .defaultHeader(API_VERSION_HEADER, API_VERSION)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT);
        if (StringUtils.hasText(properties.token())) {
            // Set on the outgoing header only. The token is never stored on this object, never logged
            // and never included in an exception message.
            configured = configured.defaultHeader(
                    HttpHeaders.AUTHORIZATION, "Bearer " + properties.token());
        }
        this.restClient = configured.build();
    }

    /**
     * Searches GitHub repositories whose <em>name</em> matches {@code query}.
     *
     * <p>The {@code q} value is built from words and qualifiers, but it is passed to {@link RestClient} as
     * a URI template variable so Spring percent-encodes it. That is why {@code C++} becomes
     * {@code C%2B%2B} and {@code C#} becomes {@code C%23}: a raw {@code +} in a query string is widely
     * interpreted as a space, and a raw {@code #} would truncate the request at the fragment.
     *
     * @param query   what the caller is looking for; matched against repository names only
     * @param language optional language filter; blank means no {@code language:} qualifier
     * @param sort    optional GitHub sort field; {@code null} omits {@code sort} and {@code order}, so
     *                GitHub applies its best-match ranking
     * @param page    GitHub page number, one-based
     * @param perPage page size, 1 to 100 (validated by the caller in Phase 3/4)
     * @return GitHub's parsed answer; {@code items} is never null
     * @throws GithubRateLimitException when GitHub says the rate limit is exhausted
     * @throws GithubApiException       for every other failure, carrying the status our API should return
     */
    public GithubSearchResponse search(String query, String language, SortField sort, int page, int perPage) {
        String uriTemplate = buildUriTemplate(sort);
        Object[] uriVariables = buildUriVariables(query, language, sort, page, perPage);

        GithubSearchResponse response;
        try {
            response = restClient.get()
                    .uri(uriTemplate, uriVariables)
                    .retrieve()
                    .body(GithubSearchResponse.class);
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        } catch (ResourceAccessException exception) {
            // Connection refused, DNS failure, connect timeout, read timeout - all mean "no answer".
            throw new GithubApiException(HttpStatus.GATEWAY_TIMEOUT, TIMEOUT_MESSAGE, exception);
        } catch (RestClientException exception) {
            // A 2xx whose body Jackson could not read, or a transport problem not classified above.
            throw new GithubApiException(HttpStatus.BAD_GATEWAY, UNREADABLE_MESSAGE, exception);
        }

        if (response == null || response.items() == null) {
            throw new GithubApiException(HttpStatus.BAD_GATEWAY, UNREADABLE_MESSAGE);
        }
        return response;
    }

    private static String buildUriTemplate(SortField sort) {
        String template = SEARCH_PATH + "?q={q}&page={page}&per_page={perPage}";
        return sort == null ? template : template + "&sort={sort}&order=desc";
    }

    private static Object[] buildUriVariables(String query, String language, SortField sort,
                                              int page, int perPage) {
        if (sort == null) {
            return new Object[]{buildQuery(query, language), page, perPage};
        }
        return new Object[]{buildQuery(query, language), page, perPage, sort.githubValue()};
    }

    /**
     * Builds the GitHub search expression: the free-text words, restricted to repository names, plus an
     * optional language qualifier. A language containing a space must be quoted
     * ({@code language:"Jupyter Notebook"}), otherwise GitHub would treat the words separately.
     */
    private static String buildQuery(String query, String language) {
        String words = query == null ? "" : query.trim();
        StringBuilder q = new StringBuilder(words.isEmpty() ? NAME_QUALIFIER : words + " " + NAME_QUALIFIER);

        if (StringUtils.hasText(language)) {
            String trimmedLanguage = language.trim();
            q.append(" language:");
            if (trimmedLanguage.indexOf(' ') >= 0) {
                q.append('"').append(trimmedLanguage).append('"');
            } else {
                q.append(trimmedLanguage);
            }
        }
        return q.toString();
    }

    private static GithubApiException translate(RestClientResponseException exception) {
        HttpStatusCode statusCode = exception.getStatusCode();
        HttpHeaders headers = exception.getResponseHeaders();

        if (isRateLimited(statusCode, headers)) {
            Long retryAfterSeconds = retryAfterSeconds(headers);
            return new GithubRateLimitException(
                    retryAfterSeconds, rateLimitMessage(retryAfterSeconds), exception);
        }
        // Compare integer codes instead of converting to the HttpStatus enum: GitHub (or a proxy in
        // front of it) may answer with a code this Spring version does not know, and HttpStatus.valueOf
        // would then throw IllegalArgumentException and turn an upstream failure into a 500.
        int code = statusCode.value();
        if (code == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
            return new GithubApiException(HttpStatus.BAD_REQUEST, INVALID_QUERY_MESSAGE, exception);
        }
        if (code == HttpStatus.UNAUTHORIZED.value()) {
            return new GithubApiException(HttpStatus.BAD_GATEWAY, CREDENTIALS_MESSAGE, exception);
        }
        if (code == HttpStatus.FORBIDDEN.value()) {
            return new GithubApiException(HttpStatus.BAD_GATEWAY, ACCESS_DENIED_MESSAGE, exception);
        }
        return new GithubApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE_MESSAGE, exception);
    }

    /**
     * GitHub uses 403 for both "not allowed" and "rate limited"; only the headers distinguish them. A 429
     * is always a rate limit. Any exhausted budget or explicit {@code Retry-After} counts as one.
     */
    private static boolean isRateLimited(HttpStatusCode statusCode, HttpHeaders headers) {
        if (statusCode.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return true;
        }
        if (statusCode.value() != HttpStatus.FORBIDDEN.value() || headers == null) {
            return false;
        }
        return "0".equals(headers.getFirst(RATE_LIMIT_REMAINING_HEADER))
                || headers.containsKey(HttpHeaders.RETRY_AFTER);
    }

    /**
     * Prefers {@code Retry-After} (seconds), falls back to {@code X-RateLimit-Reset} (epoch seconds) minus
     * now, and returns {@code null} when GitHub provided neither. A reset in the past is clamped to zero.
     */
    private static Long retryAfterSeconds(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        Long retryAfter = parseNonNegativeLong(headers.getFirst(HttpHeaders.RETRY_AFTER));
        if (retryAfter != null) {
            return retryAfter;
        }
        Long resetAtEpochSeconds = parseNonNegativeLong(headers.getFirst(RATE_LIMIT_RESET_HEADER));
        if (resetAtEpochSeconds == null) {
            return null;
        }
        long secondsUntilReset = resetAtEpochSeconds - Instant.now().getEpochSecond();
        return Math.max(secondsUntilReset, UNKNOWN_RETRY_DELAY);
    }

    /**
     * Parses a header as a non-negative whole number of seconds. A non-numeric value (an HTTP date, for
     * example) is treated as absent so the caller can try the next signal; it is not an error.
     */
    private static Long parseNonNegativeLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String rateLimitMessage(Long retryAfterSeconds) {
        return retryAfterSeconds == null
                ? RATE_LIMIT_MESSAGE
                : RATE_LIMIT_MESSAGE + ". Retry after " + retryAfterSeconds + " seconds";
    }
}
