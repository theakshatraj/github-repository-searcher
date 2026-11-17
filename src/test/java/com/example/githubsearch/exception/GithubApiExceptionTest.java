package com.example.githubsearch.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two facts the web layer will rely on: which HTTP status each exception represents, and whether
 * a rate-limit failure knows when the caller may retry.
 *
 * <p>These are deliberately plain unit tests. The client is responsible for deciding which exception to
 * throw; this test makes sure the exception types can express that decision without leaking GitHub's
 * response body to our callers.
 */
class GithubApiExceptionTest {

    @Test
    void carriesTheStatusOurApiShouldReturnAndPreservesTheCause() {
        IllegalStateException cause = new IllegalStateException("github said no");

        GithubApiException exception = new GithubApiException(
                HttpStatus.BAD_GATEWAY, "GitHub API is currently unavailable", cause);

        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exception).hasMessage("GitHub API is currently unavailable");
        assertThat(exception).hasCause(cause);
    }

    @Test
    void rateLimitIsATooManyRequestsFailureThatCarriesRetryAfterSeconds() {
        GithubRateLimitException exception = new GithubRateLimitException(
                42L, "GitHub API rate limit exceeded. Retry after 42 seconds", null);

        assertThat(exception.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exception.retryAfterSeconds()).isEqualTo(42L);
    }

    @Test
    void rateLimitCanHaveUnknownRetryTiming() {
        GithubRateLimitException exception = new GithubRateLimitException(
                null, "GitHub API rate limit exceeded", null);

        assertThat(exception.retryAfterSeconds()).isNull();
    }
}
