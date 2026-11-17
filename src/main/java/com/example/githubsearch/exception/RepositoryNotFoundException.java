package com.example.githubsearch.exception;

/**
 * Signals that a caller asked for a stored repository that does not exist.
 *
 * <p>This is a client-facing 404, not a server bug: the request was well formed, the id is simply not in the
 * table (yet). Phase 4's {@code GlobalExceptionHandler} maps it to HTTP 404.
 *
 * <p>It carries the id as well as the message so the handler can build a precise response without parsing
 * text, and so tests can assert which id failed.
 */
public class RepositoryNotFoundException extends RuntimeException {

    private final Long id;

    public RepositoryNotFoundException(Long id) {
        super("Repository with id " + id + " was not found");
        this.id = id;
    }

    /** The id that was looked up and not found. */
    public Long id() {
        return id;
    }
}
