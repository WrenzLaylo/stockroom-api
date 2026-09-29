package dev.wrenz.stockroom.common;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns every exception into an RFC 9457 ProblemDetail with a top-level "code" (spec section 7).
 * Extending ResponseEntityExceptionHandler keeps Spring's correct statuses for framework errors
 * (bad JSON, wrong types, 404 routes, 405); handleExceptionInternal adds our code to those.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private record Conflict(String code, String message) {}

    /** Only these unique constraints are expected; any other integrity violation is a bug. */
    private static final Map<String, Conflict> KNOWN_CONSTRAINTS = Map.of(
            "uq_product_shop_sku", new Conflict("DUPLICATE_SKU", "A product with this SKU already exists in your shop"),
            "uq_app_user_email", new Conflict("EMAIL_TAKEN", "An account with this email already exists"));

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail pd = problem(ex.getStatus(), ex.getCode(), ex.getMessage());
        ex.getProperties().forEach(pd::setProperty);
        return ResponseEntity.status(ex.getStatus()).body(pd);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "You don't have permission to do this");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        // JdbcTemplate errors carry no Hibernate cause, so the name can be null (Map.of rejects null keys).
        String name = constraintName(ex);
        Conflict conflict = name == null ? null : KNOWN_CONSTRAINTS.get(name);
        if (conflict != null) {
            return respond(HttpStatus.CONFLICT, conflict.code(), conflict.message());
        }
        log.error("Unexpected data integrity violation", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong");
    }

    /** PostgreSQL deadlocks and serialization failures: safe for the client to retry. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ProblemDetail> handleConcurrency(ConcurrencyFailureException ex) {
        log.warn("Concurrent update conflict: {}", ex.getMessage());
        return respond(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "The data changed while saving. Please retry.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        List<FieldErrorDto> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDto(fe.getField(), fe.getDefaultMessage()))
                .toList();
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
            pd.setProperty("code", defaultCode(statusCode));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static String defaultCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "VALIDATION_FAILED";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            default -> "HTTP_" + status.value();
        };
    }

    private static ResponseEntity<ProblemDetail> respond(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).body(problem(status, code, detail));
    }

    private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return pd;
    }

    /** Walks the cause chain to Hibernate's ConstraintViolationException and returns the constraint name. */
    @Nullable
    static String constraintName(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                return cve.getConstraintName().replace("\"", "").toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }
}
