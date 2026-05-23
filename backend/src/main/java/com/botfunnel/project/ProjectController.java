package com.botfunnel.project;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.common.HttpRequestUtils;
import com.botfunnel.project.dto.CreateProjectRequest;
import com.botfunnel.project.dto.ProjectResponse;
import com.botfunnel.project.dto.UpdateProjectRequest;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list(
            @RequestParam(name = "include_deleted", required = false, defaultValue = "false")
            boolean includeDeleted) {
        String ownerId = currentUserId();
        List<ProjectResponse> body = projectService.list(ownerId, includeDeleted).stream()
                .map(ProjectController::toResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request,
                                                  HttpServletRequest httpRequest) {
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);
        String ownerId = currentUserId();
        Project saved = projectService.create(ownerId, request, ip, userAgent);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> getOne(@PathVariable String projectId) {
        String ownerId = currentUserId();
        Project p = projectService.requireOwned(ownerId, projectId, false);
        return ResponseEntity.ok(toResponse(p));
    }

    @PatchMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> update(@PathVariable String projectId,
                                                  @Valid @RequestBody UpdateProjectRequest request,
                                                  HttpServletRequest httpRequest) {
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);
        UpdateProjectRequest normalized = normalize(request);
        String ownerId = currentUserId();
        Project p = projectService.update(ownerId, projectId, normalized, ip, userAgent);
        return ResponseEntity.ok(toResponse(p));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> softDelete(@PathVariable String projectId,
                                                      HttpServletRequest httpRequest) {
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);
        String ownerId = currentUserId();
        Project p = projectService.softDelete(ownerId, projectId, ip, userAgent);
        return ResponseEntity.ok(toResponse(p));
    }

    @PostMapping("/{projectId}/restore")
    public ResponseEntity<ProjectResponse> restore(@PathVariable String projectId,
                                                   HttpServletRequest httpRequest) {
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);
        String ownerId = currentUserId();
        Project p = projectService.restore(ownerId, projectId, ip, userAgent);
        return ResponseEntity.ok(toResponse(p));
    }

    // PATCH semantics normalization (tech-spec "PATCH semantics"):
    //   name        — null = no change; blank = no change (drop blank to avoid AC-12b false-collide)
    //   description — null = no change; blank = clear (preserved as blank, service maps to null)
    //   timezone    — null = no change; blank = no change
    private static UpdateProjectRequest normalize(UpdateProjectRequest req) {
        String name = req.name();
        if (name != null && name.isBlank()) {
            name = null;
        }
        String description = req.description();
        String timezone = req.timezone();
        if (timezone != null && timezone.isBlank()) {
            timezone = null;
        }
        return new UpdateProjectRequest(name, description, timezone);
    }

    private static ProjectResponse toResponse(Project p) {
        return new ProjectResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getTimezone(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getDeletedAt());
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw AppException.unauthorized("Not authenticated");
        }
        return details.id();
    }
}
