package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message, Map.of());
    }
}
