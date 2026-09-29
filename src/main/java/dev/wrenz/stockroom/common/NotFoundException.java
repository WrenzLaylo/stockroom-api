package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Also used for other shops' data: 404 instead of 403 hides that the row exists. */
public class NotFoundException extends ApiException {
    public NotFoundException(String what, long id) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " not found", Map.of());
    }
}
