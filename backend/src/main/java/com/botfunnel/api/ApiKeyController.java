package com.botfunnel.api;

import com.botfunnel.api.ApiKeyService.GeneratedKey;
import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.project.ProjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session-authed settings view over {@link ApiKeyService} (Decision 8). Lets a project owner obtain a
 * per-project public-API key <b>on demand</b> from the cabinet — this is the ONLY place a key is created
 * (no auto-gen on project create, no backfill).
 *
 * <p>Served by the session {@code SecurityFilterChain} ({@code @Order(2)}): the path
 * {@code /api/v1/projects/**} is matched by the broad {@code /api/**} rule, NOT by the
 * {@code /api/integrations/**} key chain ({@code @Order(1)}). Every method first resolves the owner from
 * the {@link SecurityContextHolder} and passes through {@link ProjectService#requireOwned} — a foreign /
 * missing / soft-deleted / malformed projectId collapses to a uniform 404 (anti-enumeration), so a
 * non-owner learns nothing about the key (not even its presence).
 *
 * <p><b>Security spine:</b> the plaintext key is surfaced exactly once, in the POST response, and is
 * never persisted or logged. GET only ever returns the {@code prefix•••} mask.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-key")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ProjectService projectService;

    public ApiKeyController(ApiKeyService apiKeyService, ProjectService projectService) {
        this.apiKeyService = apiKeyService;
        this.projectService = projectService;
    }

    /**
     * Current key mask for the owner's project. Ownership-guarded; returns {@code present:false,
     * mask:null} when the owned project simply has no key yet (the project exists — NOT a 404). Never
     * exposes the plaintext.
     */
    @GetMapping
    public ResponseEntity<ApiKeyMaskResponse> current(@PathVariable String projectId) {
        String ownerId = currentUserId();
        projectService.requireOwned(ownerId, projectId, false);
        return ResponseEntity.ok(
                apiKeyService.currentKeyPrefix(projectId)
                        .map(ApiKeyMaskResponse::of)
                        .orElseGet(ApiKeyMaskResponse::absent));
    }

    /**
     * Generate or regenerate the project's key. Ownership-guarded; overwrites any existing key (the old
     * hash is replaced, so the old plaintext stops resolving). Returns the new plaintext exactly once.
     */
    @PostMapping
    public ResponseEntity<ApiKeyGeneratedResponse> generate(@PathVariable String projectId) {
        String ownerId = currentUserId();
        projectService.requireOwned(ownerId, projectId, false);
        GeneratedKey generated = apiKeyService.generate(projectId);
        return ResponseEntity.ok(
                ApiKeyGeneratedResponse.of(generated.plaintext(), generated.keyPrefix()));
    }

    // Mirrors ProjectController.currentUserId() — resolve the authenticated owner from the session
    // SecurityContext. The session chain (@Order(2)) blocks anonymous requests before reaching here;
    // this guard is defense-in-depth for a malformed principal.
    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw AppException.unauthorized("Not authenticated");
        }
        return details.id();
    }
}
