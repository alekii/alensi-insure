package io.alensi.insure.platform.security;

import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The signed-in caller: their Alensi Identity user ID (the token's {@code sub}). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        return UUID.fromString(token().getToken().getSubject());
    }

    private static JwtAuthenticationToken token() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            return jwt;
        }
        throw new AuthenticationCredentialsNotFoundException("No signed-in user");
    }
}
