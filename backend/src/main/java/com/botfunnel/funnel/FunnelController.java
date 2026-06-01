package com.botfunnel.funnel;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.funnel.dto.CreateFunnelRequest;
import com.botfunnel.funnel.dto.FunnelResponse;
import com.botfunnel.funnel.dto.FunnelSummaryResponse;
import com.botfunnel.funnel.dto.UpdateFunnelRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Linear-funnel CRUD + lifecycle under the project-scoped versioned namespace (patterns.md). Owner is
// resolved from the session (never the body); all anti-IDOR / validation lives in FunnelService. PUT
// and PATCH share one handler — both full-replace the funnel (steps array is authoritative), so PATCH
// is offered as a convenience alias, not partial-update semantics.
@RestController
@RequestMapping("/api/v1/projects/{projectId}/funnels")
public class FunnelController {

    private final FunnelService funnelService;

    public FunnelController(FunnelService funnelService) {
        this.funnelService = funnelService;
    }

    @PostMapping
    public ResponseEntity<FunnelResponse> create(@PathVariable String projectId,
                                                 @Valid @RequestBody CreateFunnelRequest request) {
        FunnelResponse body = funnelService.create(currentUserId(), projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    public ResponseEntity<List<FunnelSummaryResponse>> list(
            @PathVariable String projectId,
            @RequestParam(name = "status", required = false) FunnelStatus status) {
        return ResponseEntity.ok(funnelService.list(currentUserId(), projectId, status));
    }

    @GetMapping("/{funnelId}")
    public ResponseEntity<FunnelResponse> getOne(@PathVariable String projectId,
                                                 @PathVariable String funnelId) {
        return ResponseEntity.ok(funnelService.get(currentUserId(), projectId, funnelId));
    }

    @PutMapping("/{funnelId}")
    public ResponseEntity<FunnelResponse> update(@PathVariable String projectId,
                                                 @PathVariable String funnelId,
                                                 @Valid @RequestBody UpdateFunnelRequest request) {
        return ResponseEntity.ok(funnelService.update(currentUserId(), projectId, funnelId, request));
    }

    @PatchMapping("/{funnelId}")
    public ResponseEntity<FunnelResponse> patch(@PathVariable String projectId,
                                                @PathVariable String funnelId,
                                                @Valid @RequestBody UpdateFunnelRequest request) {
        return ResponseEntity.ok(funnelService.update(currentUserId(), projectId, funnelId, request));
    }

    @DeleteMapping("/{funnelId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                       @PathVariable String funnelId) {
        funnelService.delete(currentUserId(), projectId, funnelId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{funnelId}/activate")
    public ResponseEntity<FunnelResponse> activate(@PathVariable String projectId,
                                                   @PathVariable String funnelId) {
        return ResponseEntity.ok(funnelService.activate(currentUserId(), projectId, funnelId));
    }

    @PostMapping("/{funnelId}/pause")
    public ResponseEntity<FunnelResponse> pause(@PathVariable String projectId,
                                                @PathVariable String funnelId) {
        return ResponseEntity.ok(funnelService.pause(currentUserId(), projectId, funnelId));
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw AppException.unauthorized("Not authenticated");
        }
        return details.id();
    }
}
