package com.example.githubsearch.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * The single error body used by every non-2xx response.
 *
 * <p><b>Why one shape.</b> A client should not need a different parser per failure. Whether the request was
 * malformed, GitHub timed out or the database was down, the caller reads the same fields: what went wrong
 * ({@code message}), when ({@code timestamp}), where ({@code path}) and which HTTP status. That consistency is
 * what makes the API predictable enough to automate against.
 *
 * <p><b>Why {@code details} is optional.</b> Only Bean Validation can produce several independent problems at
 * once (for example a blank query and a bad page size). {@link JsonInclude.Include#NON_EMPTY} removes the
 * field entirely when there is nothing to say, so clients see {@code null} or {@code []} only if they choose
 * to - the wire format stays small and there is exactly one way to represent "no extra detail".
 *
 * <p><b>What must never appear here.</b> No stack traces, SQL, exception class names or GitHub tokens. Those
 * go to the server log; this record is the safe, client-facing summary.
 *
 * @param timestamp when the error was produced, in UTC ({@code 2026-11-16T10:30:00Z})
 * @param status    numeric HTTP status, e.g. {@code 400}
 * @param error     HTTP reason phrase, e.g. {@code Bad Request}
 * @param message   a human-readable explanation that is safe to show to the caller
 * @param path      request path without the query string, e.g. {@code /api/github/repositories}
 * @param details   field-level validation problems; omitted from JSON when {@code null} or empty
 */
public record ErrorResponse(
        @Schema(description = "When the error was produced, in UTC.", example = "2026-11-16T10:30:00Z")
        Instant timestamp,

        @Schema(description = "Numeric HTTP status.", example = "400")
        int status,

        @Schema(description = "HTTP reason phrase.", example = "Bad Request")
        String error,

        @Schema(description = "Human-readable explanation that is safe to show to the caller.",
                example = "Validation failed for one or more fields")
        String message,

        @Schema(description = "Request path without the query string.",
                example = "/api/github/repositories")
        String path,

        @Schema(description = "Field-level validation problems; omitted from JSON when null or empty.")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldErrorDetail> details) {

    /**
     * One invalid field and the reason it was rejected.
     *
     * <p>{@code field} uses the public name of the input, not a Java property: a cross-field rule on the
     * request body reports {@code page*perPage}, which is what the caller can actually fix.
     *
     * @param field   name of the offending request field or query parameter
     * @param message the validation message for that field
     */
    public record FieldErrorDetail(
            @Schema(description = "Offending request field or query parameter.", example = "query")
            String field,

            @Schema(description = "Validation message for that field.", example = "query must not be blank")
            String message) {
    }
}
