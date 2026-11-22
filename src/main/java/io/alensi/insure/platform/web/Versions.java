package io.alensi.insure.platform.web;

import org.springframework.http.HttpStatus;

/** Optimistic concurrency over HTTP: {@code ETag} is the record's version, {@code If-Match} must equal it. */
public final class Versions {

    private Versions() {
    }

    public static String etag(long version) {
        return "\"" + version + "\"";
    }

    /** Fails with 428 when {@code If-Match} is missing and 412 when it isn't the current version. */
    public static void requireMatch(String ifMatch, long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiProblem(HttpStatus.PRECONDITION_REQUIRED, "precondition-required", "Precondition required",
                    "Send If-Match with the ETag from your last read.");
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (!value.equals(etag(currentVersion)) && !value.equals(Long.toString(currentVersion))) {
            throw new ApiProblem(HttpStatus.PRECONDITION_FAILED, "precondition-failed", "Precondition failed",
                    "The resource was changed since you read it; reload it and try again.");
        }
    }
}
