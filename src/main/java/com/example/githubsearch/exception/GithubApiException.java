package com.example.githubsearch.exception;

import org.springframework.http.HttpStatus;

import java.util.Objects;

/**
 * A failure that came from the GitHub side of the call and has a definite meaning for <em>our</em> caller.
 *
 * <p>Why carry an {@link HttpStatus} instead of only a message: the client is the only component that can
 * tell why GitHub failed, so it decides once what our API should answer (502 for an upstream outage, 504
 * for a timeout, 400 when GitHub rejected the query). Phase 4's exception handler then has no GitHub logic
 * of its own - it simply reads {@link #status()}.
 *
 * <p>Why a {@link RuntimeException}: the request cannot continue meaningfully, and forcing every layer to
 * declare it would add noise without recovery. {@code GlobalExceptionHandler} is the single catch point.
 *
 * <p>The cause is kept for server-side logging only. Response bodies get {@link #getMessage()}, which must
 * never contain a token, a full URL or GitHub's raw body.
 */
public class GithubApiException extends RuntimeException {

    private final HttpStatus status;

    public GithubApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public GithubApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = Objects.requireNonNull(status, "status");
    }

    /** The HTTP status our own API should return for this failure. */
    public HttpStatus status() {
        return status;
    }
}
