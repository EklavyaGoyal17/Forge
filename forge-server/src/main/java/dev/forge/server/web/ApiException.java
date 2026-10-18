package dev.forge.server.web;

import dev.forge.lab.scenario.ParameterValidator.FieldError;
import org.springframework.http.HttpStatus;

import java.util.List;

/** An error with a precise HTTP status and optional field-level details. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<FieldError> fieldErrors;

    public ApiException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public ApiException(HttpStatus status, String message, List<FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus status() {
        return status;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException invalid(List<FieldError> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation failed", errors);
    }
}
