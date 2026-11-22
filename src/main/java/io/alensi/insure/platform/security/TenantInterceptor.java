package io.alensi.insure.platform.security;

import io.alensi.identity.client.AlensiAccess;
import io.alensi.identity.client.AlensiIdentityException;
import io.alensi.insure.platform.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves the request's tenant through alensi-identity (token organization, X-Alensi-Tenant, or
 * the caller's only membership) before any handler runs, so a request without a tenant never
 * reaches a transaction.
 */
class TenantInterceptor implements HandlerInterceptor {

    private final AlensiAccess access;

    TenantInterceptor(AlensiAccess access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenant;
        try {
            tenant = access.tenant();
        } catch (AccessDeniedException ex) {
            throw tenantNotAllowed();
        } catch (AlensiIdentityException ex) {
            // Identity refuses unknown tenants (404) and a header contradicting the token's
            // organization (400) as well as non-members (403): to the caller these all mean the same.
            if (ex.status() >= 400 && ex.status() < 500) {
                throw tenantNotAllowed();
            }
            throw ex;
        }
        if (tenant == null) {
            throw ApiProblem.badRequest("tenant-required", "Tenant required",
                    "You work for several insurers: choose one with the X-Alensi-Tenant header.");
        }
        CurrentTenant.set(tenant);
        return true;
    }

    private static ApiProblem tenantNotAllowed() {
        return ApiProblem.forbidden("tenant-not-allowed", "Tenant not allowed",
                "You are not an active member of the requested tenant.");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentTenant.set(null);
    }
}
