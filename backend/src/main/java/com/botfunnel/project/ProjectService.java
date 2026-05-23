package com.botfunnel.project;

import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.project.dto.CreateProjectRequest;
import com.botfunnel.project.dto.UpdateProjectRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ProjectService {

    private static final String EVENT_PROJECT_CREATED = "project_created";
    private static final String EVENT_PROJECT_UPDATED = "project_updated";
    private static final String EVENT_PROJECT_RENAMED = "project_renamed";
    private static final String EVENT_PROJECT_SOFT_DELETED = "project_soft_deleted";
    private static final String EVENT_PROJECT_RESTORED = "project_restored";

    private static final String CODE_PROJECT_LIMIT_REACHED = "project_limit_reached";
    private static final String CODE_PROJECT_NAME_TAKEN = "project_name_taken";

    private static final String MESSAGE_NOT_FOUND = "Project not found";
    private static final String MESSAGE_LIMIT_REACHED = "Project limit reached";
    private static final String MESSAGE_NAME_TAKEN = "Project name already taken";

    private static final String RESTORED_SUFFIX = " (restored)";

    private final ProjectRepository projectRepository;
    private final EventService eventService;
    private final int maxPerUser;

    public ProjectService(ProjectRepository projectRepository,
                          EventService eventService,
                          @Value("${app.projects.max-per-user:5}") int maxPerUser) {
        this.projectRepository = projectRepository;
        this.eventService = eventService;
        this.maxPerUser = maxPerUser;
    }

    // The platform's isolation primitive: every future /api/v1/projects/{id}/{module} handler
    // funnels through this guard. Anti-enumeration (Decision 2): foreign / soft-deleted-without-flag
    // / missing / malformed ObjectId all collapse to an identical AppException.notFound — a caller
    // probing for project existence learns nothing.
    public Project requireOwned(String ownerId, String projectId, boolean includeSoftDeleted) {
        Optional<Project> found;
        try {
            found = projectRepository.findById(projectId);
        } catch (IllegalArgumentException e) {
            // Bad ObjectId hex collapses into the same anti-enumeration 404.
            throw AppException.notFound(MESSAGE_NOT_FOUND);
        }
        Project project = found.orElseThrow(() -> AppException.notFound(MESSAGE_NOT_FOUND));
        if (!ownerId.equals(project.getOwnerId())) {
            throw AppException.notFound(MESSAGE_NOT_FOUND);
        }
        if (!includeSoftDeleted && project.getDeletedAt() != null) {
            throw AppException.notFound(MESSAGE_NOT_FOUND);
        }
        return project;
    }

    public List<Project> list(String ownerId, boolean includeDeleted) {
        return includeDeleted
                ? projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
                : projectRepository.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(ownerId);
    }

    public Project create(String ownerId, CreateProjectRequest dto, String ip, String userAgent) {
        long count = projectRepository.countByOwnerIdAndDeletedAtIsNull(ownerId);
        if (count >= maxPerUser) {
            throw AppException.unprocessableEntity(CODE_PROJECT_LIMIT_REACHED, MESSAGE_LIMIT_REACHED);
        }
        if (projectRepository.findByOwnerIdAndNameAndDeletedAtIsNull(ownerId, dto.name()).isPresent()) {
            throw AppException.conflict(CODE_PROJECT_NAME_TAKEN, MESSAGE_NAME_TAKEN);
        }
        Instant now = Instant.now();
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName(dto.name());
        p.setDescription(blankToNull(dto.description()));
        p.setTimezone(dto.timezone());
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        p.setDeletedAt(null);
        Project saved = projectRepository.save(p);
        eventService.logEvent(ownerId, EVENT_PROJECT_CREATED, ip, userAgent,
                Map.of("projectId", saved.getId(), "name", saved.getName()));
        return saved;
    }

    public Project update(String ownerId, String projectId, UpdateProjectRequest dto,
                          String ip, String userAgent) {
        Project project = requireOwned(ownerId, projectId, false);
        String currentName = project.getName();
        String requestedName = dto.name();
        boolean nameChanging = requestedName != null && !requestedName.equals(currentName);

        if (nameChanging
                && projectRepository.findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(
                        ownerId, requestedName, project.getId()).isPresent()) {
            throw AppException.conflict(CODE_PROJECT_NAME_TAKEN, MESSAGE_NAME_TAKEN);
        }

        if (nameChanging) {
            project.setName(requestedName);
        }
        applyDescription(project, dto.description());
        applyTimezone(project, dto.timezone());
        project.setUpdatedAt(Instant.now());
        Project saved = projectRepository.save(project);
        if (nameChanging) {
            eventService.logEvent(ownerId, EVENT_PROJECT_RENAMED, ip, userAgent,
                    Map.of(
                            "projectId", saved.getId(),
                            "previousName", currentName,
                            "name", saved.getName()));
        } else {
            eventService.logEvent(ownerId, EVENT_PROJECT_UPDATED, ip, userAgent,
                    Map.of("projectId", saved.getId()));
        }
        return saved;
    }

    public Project softDelete(String ownerId, String projectId, String ip, String userAgent) {
        Project project = requireOwned(ownerId, projectId, false);
        Instant now = Instant.now();
        project.setDeletedAt(now);
        project.setUpdatedAt(now);
        Project saved = projectRepository.save(project);
        eventService.logEvent(ownerId, EVENT_PROJECT_SOFT_DELETED, ip, userAgent,
                Map.of("projectId", saved.getId(), "name", saved.getName()));
        return saved;
    }

    public Project restore(String ownerId, String projectId, String ip, String userAgent) {
        Project project = requireOwned(ownerId, projectId, true);
        // Decision 14: deletedAt-FIRST guard. An already-active project must NOT be
        // mutated through restore — return 404 BEFORE quota / name-conflict / save.
        // Defense-in-depth against future regressions silently appending " (restored)"
        // to a live project's name.
        if (project.getDeletedAt() == null) {
            throw AppException.notFound(MESSAGE_NOT_FOUND);
        }
        long count = projectRepository.countByOwnerIdAndDeletedAtIsNull(ownerId);
        if (count >= maxPerUser) {
            throw AppException.unprocessableEntity(CODE_PROJECT_LIMIT_REACHED, MESSAGE_LIMIT_REACHED);
        }
        String originalName = project.getName();
        boolean collides = projectRepository
                .findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(ownerId, originalName, project.getId())
                .isPresent();
        String finalName = collides ? originalName + RESTORED_SUFFIX : originalName;
        project.setName(finalName);
        project.setDeletedAt(null);
        project.setUpdatedAt(Instant.now());
        Project saved = projectRepository.save(project);
        Map<String, Object> meta = new HashMap<>();
        meta.put("projectId", saved.getId());
        meta.put("name", saved.getName());
        if (collides) {
            meta.put("renamedDueToConflict", true);
        }
        eventService.logEvent(ownerId, EVENT_PROJECT_RESTORED, ip, userAgent, meta);
        return saved;
    }

    private static void applyDescription(Project project, String description) {
        if (description == null) {
            return;
        }
        project.setDescription(description.isBlank() ? null : description);
    }

    private static void applyTimezone(Project project, String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return;
        }
        project.setTimezone(timezone);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
