package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class RateLimitedException extends ApiException {
    public RateLimitedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Try again later.", Map.of());
    }
}
