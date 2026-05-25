package com.botfunnel.tag;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectService;
import com.botfunnel.tag.dto.CreateTagRequest;
import com.botfunnel.tag.dto.TagResponse;
import com.botfunnel.tag.dto.UpdateTagRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Standalone tag CRUD for a project. Every handler calls projectService.requireOwned(...) FIRST for
// anti-IDOR / anti-enumeration (uniform 404 on foreign owner, soft-deleted, or malformed projectId).
// Subscriber↔tag association endpoints (POST/DELETE /subscribers/{id}/tags/...) live in Task 8.
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tags")
public class TagController {

    private final TagService tagService;
    private final ProjectService projectService;

    public TagController(TagService tagService, ProjectService projectService) {
        this.tagService = tagService;
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<TagResponse> create(@PathVariable String projectId,
                                              @Valid @RequestBody CreateTagRequest request) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        Tag tag = tagService.create(project.getId(), request.slug(), request.label());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(tag));
    }

    @GetMapping
    public ResponseEntity<List<TagResponse>> list(@PathVariable String projectId) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        List<TagResponse> body = tagService.list(project.getId()).stream()
                .map(TagController::toResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/{slug}")
    public ResponseEntity<TagResponse> updateLabel(@PathVariable String projectId,
                                                   @PathVariable String slug,
                                                   @Valid @RequestBody UpdateTagRequest request) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        Tag tag = tagService.updateLabel(project.getId(), slug, request.label());
        return ResponseEntity.ok(toResponse(tag));
    }

    @DeleteMapping("/{slug}")
    public ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String slug) {
        String userId = currentUserId();
        Project project = projectService.requireOwned(userId, projectId, false);
        tagService.deleteWithCascade(userId, project.getId(), slug);
        return ResponseEntity.noContent().build();
    }

    private static TagResponse toResponse(Tag tag) {
        return new TagResponse(tag.getSlug(), tag.getLabel(), tag.getSubscriberCount(), tag.getCreatedAt());
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw AppException.unauthorized("Not authenticated");
        }
        return details.id();
    }
}
