package com.example.githubsearch.exception;

import org.springframework.http.HttpStatus;

/**
 * GitHub refused the call because a rate limit was hit - a retryable condition, not an outage.
 *
 * <p>It extends {@link GithubApiException} so generic handling still works, but it is more specific, so
 * Spring's exception resolver will pick a dedicated handler for it. It always represents HTTP 429 for our
 * caller, even when GitHub itself answered 403 (GitHub uses 403 for both "forbidden" and "rate limited";
 * the headers tell them apart).
 *
 * <p>{@link #retryAfterSeconds()} may be {@code null} when GitHub gave neither {@code Retry-After} nor a
 * usable {@code X-RateLimit-Reset}. Phase 4 then omits the {@code Retry-After} header rather than inventing
 * a number.
 */
public class GithubRateLimitException extends GithubApiException {

    private final Long retryAfterSeconds;

    public GithubRateLimitException(Long retryAfterSeconds, String message, Throwable cause) {
        super(HttpStatus.TOO_MANY_REQUESTS, message, cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Seconds the caller should wait before retrying, or {@code null} when GitHub did not say. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
