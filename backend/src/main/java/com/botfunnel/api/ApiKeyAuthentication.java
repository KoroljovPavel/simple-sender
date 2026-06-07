package com.botfunnel.api;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/**
 * Authentication token produced by {@link ApiKeyAuthFilter} once a presented public-API key resolves
 * to a project (Decision 7). The principal is the resolved {@code projectId} — the controller reads it
 * to pin every downstream lookup to that project (anti-IDOR; there is no {@code {projectId}} in the
 * path). Pre-authenticated: the filter only ever constructs this after a successful hash lookup, so it
 * is created already {@code authenticated()}.
 *
 * <p>The raw key value is NEVER stored on this token — only the resolved project id — so it cannot leak
 * via a credentials accessor or a log of the security context.
 */
public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final String projectId;

    public ApiKeyAuthentication(String projectId) {
        super(List.of());
        this.projectId = projectId;
        setAuthenticated(true);
    }

    /** Credentials are intentionally absent — the key is never retained past the hash lookup. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return projectId;
    }

    public String getProjectId() {
        return projectId;
    }
}
