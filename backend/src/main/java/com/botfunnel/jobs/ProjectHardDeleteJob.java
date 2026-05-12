package com.botfunnel.jobs;

import com.botfunnel.events.EventService;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.mongodb.client.result.DeleteResult;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// Daily recurring cron. Cascade-deletes projects soft-deleted more than 7 days ago plus their
// audit events, preserving a fresh project_hard_deleted event per deleted project as a
// permanent audit trail (AC-17 / AC-17b / AC-17c). Pattern-matches HardDeleteJob (users):
// same cron slot "0 3 * * *" UTC, same @Recurring registration, same +1ns cutoff trick.
@Component
public class ProjectHardDeleteJob {

    private static final Logger log = LoggerFactory.getLogger(ProjectHardDeleteJob.class);
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final String EVENT_PROJECT_HARD_DELETED = "project_hard_deleted";
    private static final String EVENTS_COLLECTION = "events";
    private static final String PROJECTS_COLLECTION = "projects";

    private final ProjectRepository projectRepository;
    private final EventService eventService;
    private final ReactiveMongoTemplate template;

    public ProjectHardDeleteJob(ProjectRepository projectRepository,
                                EventService eventService,
                                ReactiveMongoTemplate template) {
        this.projectRepository = projectRepository;
        this.eventService = eventService;
        this.template = template;
    }

    @Recurring(id = "hard-delete-projects", cron = "0 3 * * *")
    @Job(name = "Hard delete soft-deleted projects")
    public void hardDeleteSoftDeletedProjects() {
        long startedAtMillis = System.currentTimeMillis();
        // +1ns trick (mirrors HardDeleteJob): converts the strict-< finder into an inclusive
        // boundary so a project soft-deleted exactly 7d ago to the second is captured this run
        // instead of surviving another day.
        Instant cutoff = Instant.now().minus(RETENTION).plusNanos(1);

        List<Project> projects = projectRepository.findByDeletedAtBefore(cutoff)
                .collectList()
                .blockOptional()
                .orElseGet(List::of);

        if (projects.isEmpty()) {
            // Zero-deletion-day still emits the structured INFO line so operations see the cron
            // is alive (AC-17c). Do NOT short-circuit before this log.
            log.info("ProjectHardDeleteJob - run completed: deletedCount=0 eventsRemovedCount=0 runDurationMs={}",
                    System.currentTimeMillis() - startedAtMillis);
            return;
        }

        List<String> deletedIds = projects.stream().map(Project::getId).toList();

        // Cascade in this exact order — AC-17b depends on it:
        //  1. Sweep prior events for these projects FIRST. The fresh project_hard_deleted rows
        //     emitted in step 2 land AFTER this sweep finishes (synchronous .block()) so they
        //     are not collateral damage in this run.
        //  2. Emit one project_hard_deleted event per deleted project, synchronously via
        //     EventService.logEventBlocking(...).block() so the row reaches Mongo BEFORE
        //     step 3 begins. Using the fire-and-forget logEvent(...) here would race and
        //     violate AC-17b in production under load.
        //  3. Drop the project documents.
        // No cross-collection transaction (Mongo replica-set transactions are not configured
        // here). If step 3 fails after step 1 succeeds, the next daily run picks the same
        // projects back up via findByDeletedAtBefore — idempotent recovery, intentional trade-off.
        DeleteResult eventsDelete = template.remove(
                        Query.query(Criteria.where("metadata.projectId").in(deletedIds)),
                        EVENTS_COLLECTION)
                .block();
        long eventsRemovedCount = eventsDelete == null ? 0L : eventsDelete.getDeletedCount();

        for (Project p : projects) {
            eventService.logEventBlocking(
                            p.getOwnerId(),
                            EVENT_PROJECT_HARD_DELETED,
                            null,
                            null,
                            Map.of("projectId", p.getId(), "name", p.getName()))
                    .block();
        }

        template.remove(
                        Query.query(Criteria.where("_id").in(deletedIds)),
                        PROJECTS_COLLECTION)
                .block();

        log.info("ProjectHardDeleteJob - run completed: deletedCount={} eventsRemovedCount={} runDurationMs={}",
                projects.size(), eventsRemovedCount, System.currentTimeMillis() - startedAtMillis);
    }
}
