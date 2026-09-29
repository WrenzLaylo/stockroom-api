package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** A business-rule validation failure that Bean Validation can't express (e.g. duplicate order lines). */
public class ValidationException extends ApiException {
    public ValidationException(String field, String message) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                Map.of("errors", List.of(new FieldErrorDto(field, message))));
    }
}
