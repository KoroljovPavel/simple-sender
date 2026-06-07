package com.botfunnel.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Per-request public-API key authenticator for the {@code /api/integrations/**} chain (Decision 7).
 *
 * <p>Reads the {@code X-API-Key} header, resolves the project via {@link ApiKeyService#lookup(String)}
 * (SHA-256 hash lookup — no plaintext compare anywhere, mirroring {@code WebhookSecretVerifier}'s
 * discipline), and on success pins an {@link ApiKeyAuthentication} carrying ONLY the resolved
 * {@code projectId} into the {@link SecurityContext}. {@code lastUsedAt} is stamped on each
 * authenticated call.
 *
 * <p><strong>Uniform 401 (anti-enumeration).</strong> Every key fault — header absent, blank, or no
 * matching hash — takes the SAME single code path: clear the context, write a bare {@code 401} with no
 * body, and stop the chain. The response never distinguishes the cause, so an attacker cannot tell a
 * malformed key from an unknown one.
 *
 * <p><strong>No secret in logs.</strong> Neither the presented key nor its hash is ever logged; the
 * one debug line carries no key material.
 *
 * <p><strong>Not a {@code @Component}.</strong> A {@code OncePerRequestFilter} annotated
 * {@code @Component} would be auto-registered by Spring Boot as a servlet filter on EVERY request
 * (outside the security chain), 401-ing the whole app. It is instead instantiated only inside the
 * {@code /api/integrations/**} {@code SecurityFilterChain} via {@code addFilterBefore}.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, ApiKeyRepository apiKeyRepository) {
        this.apiKeyService = apiKeyService;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String presentedKey = request.getHeader(API_KEY_HEADER);

        // One uniform path for EVERY key fault: lookup() already maps null/blank to empty, and an
        // unknown hash to empty. No branch distinguishes missing vs malformed vs unknown → no
        // enumeration oracle (anti-enumeration, Decision 7).
        Optional<ApiKey> resolved = apiKeyService.lookup(presentedKey);
        if (resolved.isEmpty()) {
            rejectUniform401(response);
            return;
        }

        ApiKey apiKey = resolved.get();
        stampLastUsedAt(apiKey);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ApiKeyAuthentication(apiKey.getProjectId()));
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void rejectUniform401(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        // Bare 401, no body — identical for missing / blank / malformed / unknown keys.
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private void stampLastUsedAt(ApiKey apiKey) {
        // Best-effort usage stamp; a stamp write failure must not turn a valid call into a 5xx, so
        // any persistence fault is swallowed (the key already authenticated successfully).
        try {
            apiKey.setLastUsedAt(Instant.now());
            apiKeyRepository.save(apiKey);
        } catch (RuntimeException e) {
            log.warn("API_KEY_LASTUSED_STAMP_FAILED projectId={} error={}",
                    apiKey.getProjectId(), e.getClass().getSimpleName());
        }
    }
}
