package com.example.githubsearch.exception;

/**
 * Signals that the caller supplied a value this API does not accept, where the rule cannot be expressed
 * with Bean Validation annotations (for whitelists such as the sort field).
 *
 * <p>It is a {@link RuntimeException} because there is no sensible way for callers to recover mid-request.
 * Phase 4's {@code GlobalExceptionHandler} maps it to HTTP 400 with the standard error body.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
