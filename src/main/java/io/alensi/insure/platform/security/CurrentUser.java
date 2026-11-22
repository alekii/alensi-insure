package io.alensi.insure.platform.security;

import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The signed-in caller: their Alensi Identity user ID (the token's {@code sub}). */
public final class CurrentUser {

    /** Recorded as the actor of work no person started, such as handling a payment event. */
    public static final UUID SYSTEM = new UUID(0, 0);

    private CurrentUser() {
    }

    public static UUID id() {
        return UUID.fromString(token().getToken().getSubject());
    }

    public static boolean isSignedIn() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken;
    }

    /** The signed-in user, or {@link #SYSTEM} outside a request. */
    public static UUID actorId() {
        return isSignedIn() ? id() : SYSTEM;
    }

    private static JwtAuthenticationToken token() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            return jwt;
        }
        throw new AuthenticationCredentialsNotFoundException("No signed-in user");
    }
}
