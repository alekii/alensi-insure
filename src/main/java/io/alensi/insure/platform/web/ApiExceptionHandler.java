package io.alensi.insure.platform.web;

import java.net.URI;
import java.util.List;
import java.util.Map;

import io.alensi.identity.client.AlensiIdentityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error as {@code application/problem+json} with a type and the correlation ID (api-spec.md). */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler
    ResponseEntity<ProblemDetail> problem(ApiProblem ex) {
        ProblemDetail problem = withCorrelationId(ex.toProblemDetail());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler
    ProblemDetail accessDenied(AccessDeniedException ex) {
        return typed(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail stale(OptimisticLockingFailureException ex) {
        return typed(HttpStatus.PRECONDITION_FAILED, "precondition-failed", "Precondition failed",
                "The resource was changed by someone else; reload it and try again.");
    }

    @ExceptionHandler
    ProblemDetail unknownSortField(PropertyReferenceException ex) {
        return typed(HttpStatus.BAD_REQUEST, "invalid-sort", "Invalid sort", ex.getMessage());
    }

    /** Fail closed: without identity no access decision can be made. */
    @ExceptionHandler
    ProblemDetail identityUnavailable(AlensiIdentityException ex) {
        log.warn("alensi-identity unavailable: {}", ex.getMessage());
        return typed(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", "Service unavailable",
                "Access can't be checked right now. Try again shortly.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = typed(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "The request has invalid fields.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
            WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            withCorrelationId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail typed(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(ApiProblem.TYPE_BASE + type));
        problem.setTitle(title);
        return withCorrelationId(problem);
    }

    private static ProblemDetail withCorrelationId(ProblemDetail problem) {
        problem.setProperty("correlationId", CorrelationId.current());
        return problem;
    }
}
