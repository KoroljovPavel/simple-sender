package com.botfunnel.project;

import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.project.dto.CreateProjectRequest;
import com.botfunnel.project.dto.UpdateProjectRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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
    public Mono<Project> requireOwned(String ownerId, String projectId, boolean includeSoftDeleted) {
        return projectRepository.findById(projectId)
                .onErrorMap(IllegalArgumentException.class,
                        e -> AppException.notFound(MESSAGE_NOT_FOUND))
                .switchIfEmpty(Mono.error(AppException.notFound(MESSAGE_NOT_FOUND)))
                .flatMap(project -> {
                    if (!ownerId.equals(project.getOwnerId())) {
                        return Mono.error(AppException.notFound(MESSAGE_NOT_FOUND));
                    }
                    if (!includeSoftDeleted && project.getDeletedAt() != null) {
                        return Mono.error(AppException.notFound(MESSAGE_NOT_FOUND));
                    }
                    return Mono.just(project);
                });
    }

    public Flux<Project> list(String ownerId, boolean includeDeleted) {
        return includeDeleted
                ? projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
                : projectRepository.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(ownerId);
    }

    public Mono<Project> create(String ownerId, CreateProjectRequest dto, String ip, String userAgent) {
        return projectRepository.countByOwnerIdAndDeletedAtIsNull(ownerId)
                .flatMap(count -> {
                    if (count >= maxPerUser) {
                        return Mono.<Project>error(AppException.unprocessableEntity(
                                CODE_PROJECT_LIMIT_REACHED, MESSAGE_LIMIT_REACHED));
                    }
                    return projectRepository.findByOwnerIdAndNameAndDeletedAtIsNull(ownerId, dto.name())
                            .flatMap(existing -> Mono.<Project>error(AppException.conflict(
                                    CODE_PROJECT_NAME_TAKEN, MESSAGE_NAME_TAKEN)))
                            .switchIfEmpty(Mono.defer(() -> {
                                Instant now = Instant.now();
                                Project p = new Project();
                                p.setOwnerId(ownerId);
                                p.setName(dto.name());
                                p.setDescription(blankToNull(dto.description()));
                                p.setTimezone(dto.timezone());
                                p.setCreatedAt(now);
                                p.setUpdatedAt(now);
                                p.setDeletedAt(null);
                                return projectRepository.save(p);
                            }))
                            .doOnSuccess(saved -> eventService.logEvent(
                                    ownerId, EVENT_PROJECT_CREATED, ip, userAgent,
                                    Map.of("projectId", saved.getId(), "name", saved.getName())));
                });
    }

    public Mono<Project> update(String ownerId, String projectId, UpdateProjectRequest dto,
                                String ip, String userAgent) {
        return requireOwned(ownerId, projectId, false)
                .flatMap(project -> {
                    String currentName = project.getName();
                    String requestedName = dto.name();
                    boolean nameChanging = requestedName != null && !requestedName.equals(currentName);

                    Mono<Project> conflictGuard = nameChanging
                            ? projectRepository.findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(
                                    ownerId, requestedName, project.getId())
                                    .flatMap(other -> Mono.<Project>error(AppException.conflict(
                                            CODE_PROJECT_NAME_TAKEN, MESSAGE_NAME_TAKEN)))
                                    .then(Mono.just(project))
                            : Mono.just(project);

                    return conflictGuard.flatMap(p -> {
                        if (nameChanging) {
                            p.setName(requestedName);
                        }
                        applyDescription(p, dto.description());
                        applyTimezone(p, dto.timezone());
                        p.setUpdatedAt(Instant.now());
                        return projectRepository.save(p)
                                .doOnSuccess(saved -> {
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
                                });
                    });
                });
    }

    public Mono<Project> softDelete(String ownerId, String projectId, String ip, String userAgent) {
        return requireOwned(ownerId, projectId, false)
                .flatMap(project -> {
                    Instant now = Instant.now();
                    project.setDeletedAt(now);
                    project.setUpdatedAt(now);
                    return projectRepository.save(project)
                            .doOnSuccess(saved -> eventService.logEvent(
                                    ownerId, EVENT_PROJECT_SOFT_DELETED, ip, userAgent,
                                    Map.of("projectId", saved.getId(), "name", saved.getName())));
                });
    }

    public Mono<Project> restore(String ownerId, String projectId, String ip, String userAgent) {
        return requireOwned(ownerId, projectId, true)
                .flatMap(project -> {
                    // Decision 14: deletedAt-FIRST guard. An already-active project must NOT be
                    // mutated through restore — return 404 BEFORE quota / name-conflict / save.
                    // Defense-in-depth against future regressions silently appending " (restored)"
                    // to a live project's name.
                    if (project.getDeletedAt() == null) {
                        return Mono.<Project>error(AppException.notFound(MESSAGE_NOT_FOUND));
                    }
                    return projectRepository.countByOwnerIdAndDeletedAtIsNull(ownerId)
                            .flatMap(count -> {
                                if (count >= maxPerUser) {
                                    return Mono.<Project>error(AppException.unprocessableEntity(
                                            CODE_PROJECT_LIMIT_REACHED, MESSAGE_LIMIT_REACHED));
                                }
                                String originalName = project.getName();
                                return projectRepository
                                        .findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(
                                                ownerId, originalName, project.getId())
                                        .map(other -> true)
                                        .defaultIfEmpty(false)
                                        .flatMap(collides -> {
                                            String finalName = collides
                                                    ? originalName + RESTORED_SUFFIX
                                                    : originalName;
                                            project.setName(finalName);
                                            project.setDeletedAt(null);
                                            project.setUpdatedAt(Instant.now());
                                            return projectRepository.save(project)
                                                    .doOnSuccess(saved -> {
                                                        Map<String, Object> meta = new HashMap<>();
                                                        meta.put("projectId", saved.getId());
                                                        meta.put("name", saved.getName());
                                                        if (collides) {
                                                            meta.put("renamedDueToConflict", true);
                                                        }
                                                        eventService.logEvent(ownerId,
                                                                EVENT_PROJECT_RESTORED, ip, userAgent, meta);
                                                    });
                                        });
                            });
                });
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
