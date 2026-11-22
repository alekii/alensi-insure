package io.alensi.insure.platform.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * An RFC 9457 problem with a stable type, e.g. {@code https://alensi.io/problems/duplicate-customer}.
 * Thrown anywhere; {@code ApiExceptionHandler} renders it.
 */
public class ApiProblem extends RuntimeException {

    public static final String TYPE_BASE = "https://alensi.io/problems/";

    private final HttpStatus status;
    private final String type;
    private final String title;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiProblem(HttpStatus status, String type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public static ApiProblem notFound(String what) {
        return new ApiProblem(HttpStatus.NOT_FOUND, "not-found", "Not found", what + " not found.");
    }

    public static ApiProblem forbidden(String type, String title, String detail) {
        return new ApiProblem(HttpStatus.FORBIDDEN, type, title, detail);
    }

    public static ApiProblem badRequest(String type, String title, String detail) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, type, title, detail);
    }

    public static ApiProblem conflict(String type, String title, String detail) {
        return new ApiProblem(HttpStatus.CONFLICT, type, title, detail);
    }

    /** Adds an extension member, e.g. {@code existingCustomerId}. */
    public ApiProblem with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    ProblemDetail toProblemDetail() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, getMessage());
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        properties.forEach(problem::setProperty);
        return problem;
    }
}
