package com.example.githubsearch.exception;

import com.example.githubsearch.dto.ErrorResponse;
import com.example.githubsearch.dto.ErrorResponse.FieldErrorDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns every exception that escapes a controller into the API's one error body.
 *
 * <p><b>Why a {@code @RestControllerAdvice}.</b> Without it, each controller method would need its own
 * try/catch and would have to decide again which status a timeout deserves. A single advice is discovered
 * by Spring MVC and consulted whenever a handler throws, so all endpoints share the mapping in Blueprint
 * Section 11. It also keeps controllers thin: they contain no error-handling code at all.
 *
 * <p><b>How it works.</b> Spring's {@code ExceptionHandlerExceptionResolver} inspects the exception and
 * picks the most specific {@code @ExceptionHandler} method in this class. That is why
 * {@link GithubRateLimitException} reaches the 429 method even though it is also a
 * {@link GithubApiException}, and why a {@link DataAccessResourceFailureException} becomes 503 while any
 * other {@link DataAccessException} falls through to 500. Method selection uses the class hierarchy, not
 * declaration order.
 *
 * <p><b>What we return versus what we log.</b> The response gets a short, safe message and the request path.
 * The server log gets the real exception, which is where a stack trace, SQL text or an upstream response
 * belongs. Client errors (4xx) are logged at WARN because they are usually the caller's mistake; database
 * failures and unexpected exceptions are logged at ERROR because they need an operator. No response ever
 * contains a stack trace, SQL, a token or an internal class name.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** The property name of the cross-field cap rule; callers think in terms of these two inputs. */
    private static final String GITHUB_CAP_PROPERTY = "withinGithubResultCap";
    private static final String GITHUB_CAP_PUBLIC_FIELD = "page*perPage";

    private static final String MALFORMED_BODY_MESSAGE = "Request body is missing or malformed";
    private static final String MULTIPLE_VALIDATION_MESSAGE = "Request validation failed";
    private static final String DATABASE_UNAVAILABLE_MESSAGE = "Database is temporarily unavailable";
    private static final String DATABASE_ERROR_MESSAGE = "An unexpected database error occurred";
    private static final String UNEXPECTED_ERROR_MESSAGE = "An unexpected error occurred";
    private static final String INVALID_PARAMETER_FALLBACK = "is invalid";

    // ---------------------------------------------------------------------
    // Request body and parameter binding (400)
    // ---------------------------------------------------------------------

    /**
     * Handles a body Jackson could not parse, or a missing body.
     *
     * <p>The parser's own message names Jackson classes and quotes the raw input; that is useful in a log
     * and unsafe in a response, so only a fixed message is returned.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception,
                                                              HttpServletRequest request) {
        log.warn("Malformed request body on {}: {}", requestDescription(request), exception.getMessage());
        return respond(HttpStatus.BAD_REQUEST, MALFORMED_BODY_MESSAGE, request, null);
    }

    /**
     * Handles Bean Validation failures on a {@code @Valid @RequestBody}.
     *
     * <p>Each {@link FieldError} becomes one entry in {@code details}. The cross-field GitHub cap is exposed
     * as {@code page*perPage}, because {@code withinGithubResultCap} is an implementation detail the caller
     * cannot find in their JSON.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException exception,
                                                           HttpServletRequest request) {
        List<FieldErrorDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toDetail)
                .sorted(detailOrder())
                .toList();

        log.warn("Request body validation failed on {}: {}", requestDescription(request), details);
        return respond(HttpStatus.BAD_REQUEST, summarize(details), request, details);
    }

    /**
     * Handles constraints declared directly on controller parameters.
     *
     * <p>Spring Framework 6.1+ validates handler method parameters without {@code @Validated}. A violation
     * such as {@code minStars=-1} is reported as a {@link HandlerMethodValidationException}, not as a
     * {@link ConstraintViolationException}; the latter is kept for setups that still put {@code @Validated}
     * on the controller, so the API behaves the same either way.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidParameters(HandlerMethodValidationException exception,
                                                                 HttpServletRequest request) {
        List<FieldErrorDetail> details = new ArrayList<>();
        exception.getAllValidationResults().forEach(result -> {
            String field = parameterName(result.getMethodParameter().getParameterName());
            result.getResolvableErrors().forEach(error ->
                    details.add(new FieldErrorDetail(field, messageOrDefault(error.getDefaultMessage()))));
        });
        details.sort(detailOrder());

        log.warn("Request parameter validation failed on {}: {}", requestDescription(request), details);
        return respond(HttpStatus.BAD_REQUEST, summarize(details), request, details);
    }

    /** Handles {@code @Validated}-style method validation, if a future controller enables it. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception,
                                                                   HttpServletRequest request) {
        List<FieldErrorDetail> details = exception.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::toDetail)
                .sorted(detailOrder())
                .toList();

        log.warn("Request parameter validation failed on {}: {}", requestDescription(request), details);
        return respond(HttpStatus.BAD_REQUEST, summarize(details), request, details);
    }

    /**
     * Handles a value that cannot be converted to the parameter's type, such as {@code minStars=abc} or a
     * non-numeric path id. The value and the target class stay out of the response.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
                                                            HttpServletRequest request) {
        String field = parameterName(exception.getName());
        String message = isNumeric(exception.getRequiredType())
                ? "Parameter '" + field + "' must be a valid number"
                : "Parameter '" + field + "' has an invalid value";

        log.warn("Request parameter type mismatch on {}: {}", requestDescription(request), exception.getMessage());
        return respond(HttpStatus.BAD_REQUEST, message, request, null);
    }

    /** Handles business-rule rejections such as an unknown sort field. */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException exception,
                                                              HttpServletRequest request) {
        log.warn("Invalid request on {}: {}", requestDescription(request), exception.getMessage());
        return respond(HttpStatus.BAD_REQUEST, exception.getMessage(), request, null);
    }

    // ---------------------------------------------------------------------
    // Upstream GitHub failures
    // ---------------------------------------------------------------------

    /**
     * Handles an exhausted GitHub quota. 429 is the status for "retry later"; when GitHub told us how long,
     * the same number goes into the standard {@code Retry-After} header so a client can obey it without
     * parsing the message.
     */
    @ExceptionHandler(GithubRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(GithubRateLimitException exception,
                                                         HttpServletRequest request) {
        log.warn("GitHub rate limit on {}: {}", requestDescription(request), exception.getMessage());

        HttpHeaders headers = new HttpHeaders();
        Long retryAfterSeconds = exception.retryAfterSeconds();
        if (retryAfterSeconds != null && retryAfterSeconds >= 0) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString());
        }

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(errorBody(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), request, null));
    }

    /**
     * Handles every other typed GitHub failure. The client already decided whether the caller should see
     * 502 (upstream unavailable/unreadable), 504 (timeout) or 400 (GitHub rejected the query); this handler
     * only applies that decision.
     */
    @ExceptionHandler(GithubApiException.class)
    public ResponseEntity<ErrorResponse> handleGithubFailure(GithubApiException exception,
                                                             HttpServletRequest request) {
        log.warn("GitHub request failed on {}: {}", requestDescription(request), exception.getMessage(), exception);
        return respond(exception.status(), exception.getMessage(), request, null);
    }

    // ---------------------------------------------------------------------
    // Storage and unexpected failures
    // ---------------------------------------------------------------------

    /** 404 for a well-formed request whose id is not stored. */
    @ExceptionHandler(RepositoryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(RepositoryNotFoundException exception,
                                                        HttpServletRequest request) {
        log.warn("Repository not found on {}: {}", requestDescription(request), exception.getMessage());
        return respond(HttpStatus.NOT_FOUND, exception.getMessage(), request, null);
    }

    /**
     * 503 for a database that cannot be reached. This is temporary and not the caller's fault, unlike a bad
     * SQL statement, which is a server bug and becomes a generic 500 below.
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ErrorResponse> handleDatabaseUnavailable(DataAccessResourceFailureException exception,
                                                                   HttpServletRequest request) {
        log.error("Database unavailable on {}", requestDescription(request), exception);
        return respond(HttpStatus.SERVICE_UNAVAILABLE, DATABASE_UNAVAILABLE_MESSAGE, request, null);
    }

    /** Any other persistence failure is a server-side surprise: log it fully, say nothing specific. */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDatabaseError(DataAccessException exception,
                                                             HttpServletRequest request) {
        log.error("Database error on {}", requestDescription(request), exception);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, DATABASE_ERROR_MESSAGE, request, null);
    }

    /**
     * Final safety net. An unmapped exception must still produce the standard JSON body, and its message
     * must not reach the caller - it could contain SQL, a file path or a token.
     *
     * <p>Spring MVC raises its own exceptions for protocol-level mistakes: an unknown path
     * ({@code NoResourceFoundException}, 404), a wrong HTTP method (405), an unsupported content type (415).
     * They implement Spring's {@code ErrorResponse} and already carry the right 4xx status. Without this
     * branch the catch-all would turn every one of them into a misleading 500. Their status is preserved and
     * only the reason phrase is exposed; everything else is still logged in full and answered with a generic
     * 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof org.springframework.web.ErrorResponse frameworkError
                && frameworkError.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(frameworkError.getStatusCode().value());
            log.warn("Request rejected on {}: {}", requestDescription(request), exception.getMessage());
            return respond(status, status.getReasonPhrase(), request, null);
        }

        log.error("Unexpected failure on {}", requestDescription(request), exception);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR_MESSAGE, request, null);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String message,
                                                         HttpServletRequest request,
                                                         List<FieldErrorDetail> details) {
        return ResponseEntity.status(status).body(errorBody(status, message, request, details));
    }

    private static ErrorResponse errorBody(HttpStatus status, String message, HttpServletRequest request,
                                           List<FieldErrorDetail> details) {
        return new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                details);
    }

    private static FieldErrorDetail toDetail(FieldError error) {
        String field = GITHUB_CAP_PROPERTY.equals(error.getField())
                ? GITHUB_CAP_PUBLIC_FIELD
                : error.getField();
        return new FieldErrorDetail(field, messageOrDefault(error.getDefaultMessage()));
    }

    private static FieldErrorDetail toDetail(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        String field = lastDot >= 0 ? path.substring(lastDot + 1) : path;
        return new FieldErrorDetail(parameterName(field), messageOrDefault(violation.getMessage()));
    }

    private static Comparator<FieldErrorDetail> detailOrder() {
        return Comparator.comparing(FieldErrorDetail::field)
                .thenComparing(FieldErrorDetail::message);
    }

    private static String summarize(List<FieldErrorDetail> details) {
        return details.size() == 1 ? details.get(0).message() : MULTIPLE_VALIDATION_MESSAGE;
    }

    private static String parameterName(String name) {
        return name == null || name.isBlank() ? "parameter" : name;
    }

    private static String messageOrDefault(String message) {
        return message == null || message.isBlank() ? INVALID_PARAMETER_FALLBACK : message;
    }

    private static boolean isNumeric(Class<?> type) {
        return type != null && Number.class.isAssignableFrom(type);
    }

    private static String requestDescription(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }
}
