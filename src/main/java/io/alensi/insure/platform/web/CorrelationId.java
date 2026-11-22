package io.alensi.insure.platform.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The request's trace ID: from {@code traceparent} when present, otherwise new. Returned as
 * {@code X-Correlation-Id}, logged as {@code correlationId}, and stored with audit events and problems.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationId extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    static final String MDC_KEY = "correlationId";

    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    /** The current request's correlation ID, or a new one outside requests. */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : newId();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = fromTraceparent(request.getHeader("traceparent"));
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String fromTraceparent(String traceparent) {
        if (traceparent != null) {
            Matcher matcher = TRACEPARENT.matcher(traceparent.trim());
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return newId();
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
