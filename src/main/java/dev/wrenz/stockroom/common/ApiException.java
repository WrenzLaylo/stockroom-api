package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Base for every error we raise on purpose. The handler turns it into a ProblemDetail with this code. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties;

    protected ApiException(HttpStatus status, String code, String message, Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = properties;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getProperties() { return properties; }
}
