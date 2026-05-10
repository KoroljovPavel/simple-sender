package com.botfunnel.project;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.project.dto.CreateProjectRequest;
import com.botfunnel.project.dto.ProjectResponse;
import com.botfunnel.project.dto.UpdateProjectRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private static final int USER_AGENT_MAX = 500;

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public Mono<ResponseEntity<Flux<ProjectResponse>>> list(
            @RequestParam(name = "include_deleted", required = false, defaultValue = "false")
            boolean includeDeleted) {
        return currentUserId()
                .map(ownerId -> ResponseEntity.ok(
                        projectService.list(ownerId, includeDeleted).map(ProjectController::toResponse)));
    }

    @PostMapping
    public Mono<ResponseEntity<ProjectResponse>> create(@Valid @RequestBody CreateProjectRequest request,
                                                        ServerWebExchange exchange) {
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
        return currentUserId()
                .flatMap(ownerId -> projectService.create(ownerId, request, ip, userAgent))
                .map(p -> ResponseEntity.status(HttpStatus.CREATED).body(toResponse(p)));
    }

    @GetMapping("/{projectId}")
    public Mono<ResponseEntity<ProjectResponse>> getOne(@PathVariable String projectId) {
        return currentUserId()
                .flatMap(ownerId -> projectService.requireOwned(ownerId, projectId, false))
                .map(p -> ResponseEntity.ok(toResponse(p)));
    }

    @PatchMapping("/{projectId}")
    public Mono<ResponseEntity<ProjectResponse>> update(@PathVariable String projectId,
                                                        @Valid @RequestBody UpdateProjectRequest request,
                                                        ServerWebExchange exchange) {
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
        UpdateProjectRequest normalized = normalize(request);
        return currentUserId()
                .flatMap(ownerId -> projectService.update(ownerId, projectId, normalized, ip, userAgent))
                .map(p -> ResponseEntity.ok(toResponse(p)));
    }

    @DeleteMapping("/{projectId}")
    public Mono<ResponseEntity<ProjectResponse>> softDelete(@PathVariable String projectId,
                                                            ServerWebExchange exchange) {
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
        return currentUserId()
                .flatMap(ownerId -> projectService.softDelete(ownerId, projectId, ip, userAgent))
                .map(p -> ResponseEntity.ok(toResponse(p)));
    }

    @PostMapping("/{projectId}/restore")
    public Mono<ResponseEntity<ProjectResponse>> restore(@PathVariable String projectId,
                                                         ServerWebExchange exchange) {
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
        return currentUserId()
                .flatMap(ownerId -> projectService.restore(ownerId, projectId, ip, userAgent))
                .map(p -> ResponseEntity.ok(toResponse(p)));
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

    // Copied verbatim from ProfileController. A shared util is deferred (no other consumer yet).
    private static Mono<String> currentUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(a -> a != null && a.isAuthenticated() && a.getPrincipal() instanceof AppUserDetails)
                .map(a -> ((AppUserDetails) a.getPrincipal()).id())
                .switchIfEmpty(Mono.error(AppException.unauthorized("Not authenticated")));
    }

    private static String capUserAgent(String userAgent) {
        if (userAgent == null) return null;
        return userAgent.length() > USER_AGENT_MAX ? userAgent.substring(0, USER_AGENT_MAX) : userAgent;
    }

    private static String extractIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
    }
}
