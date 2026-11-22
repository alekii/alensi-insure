package io.alensi.insure.platform.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Collects every problem in a request before failing, so the caller sees all of them at once in
 * the problem's {@code errors[]}.
 */
public class Violations {

    private final List<Map<String, String>> errors = new ArrayList<>();

    public Violations add(String field, String message) {
        errors.add(Map.of("field", field, "message", message));
        return this;
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    /** 400 {@code validation-failed} if anything was added. */
    public void throwIfAny() {
        throwIfAny(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "The request has invalid fields.");
    }

    public void throwIfAny(HttpStatus status, String type, String title, String detail) {
        if (!errors.isEmpty()) {
            throw new ApiProblem(status, type, title, detail).with("errors", List.copyOf(errors));
        }
    }
}
