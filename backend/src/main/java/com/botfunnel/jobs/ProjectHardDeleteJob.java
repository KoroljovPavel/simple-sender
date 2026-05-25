package com.botfunnel.jobs;

import com.botfunnel.events.EventService;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.mongodb.client.result.DeleteResult;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
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
    // Epic 09 subscriber-domain collections swept by the cascade (Decision 8).
    private static final String SUBSCRIBER_EXPORTS_COLLECTION = "subscriber_exports";
    private static final String SUBSCRIBER_EVENTS_COLLECTION = "subscriber_events";
    private static final String SUBSCRIBERS_COLLECTION = "subscribers";
    private static final String TAGS_COLLECTION = "tags";
    // GridFS file-metadata collection (default `fs.files`); counted before the GridFS delete since
    // GridFsOperations.delete(query) returns no count.
    private static final String GRIDFS_FILES_COLLECTION = "fs.files";

    private final ProjectRepository projectRepository;
    private final EventService eventService;
    private final MongoTemplate template;
    private final GridFsOperations gridFsOperations;

    public ProjectHardDeleteJob(ProjectRepository projectRepository,
                                EventService eventService,
                                MongoTemplate template,
                                GridFsOperations gridFsOperations) {
        this.projectRepository = projectRepository;
        this.eventService = eventService;
        this.template = template;
        this.gridFsOperations = gridFsOperations;
    }

    @Recurring(id = "hard-delete-projects", cron = "0 3 * * *")
    @Job(name = "Hard delete soft-deleted projects")
    public void hardDeleteSoftDeletedProjects() {
        long startedAtMillis = System.currentTimeMillis();
        // +1ns trick (mirrors HardDeleteJob): converts the strict-< finder into an inclusive
        // boundary so a project soft-deleted exactly 7d ago to the second is captured this run
        // instead of surviving another day.
        Instant cutoff = Instant.now().minus(RETENTION).plusNanos(1);

        List<Project> projects = projectRepository.findByDeletedAtBefore(cutoff);

        if (projects.isEmpty()) {
            // Zero-deletion-day still emits the structured INFO line so operations see the cron
            // is alive (AC-17c). Do NOT short-circuit before this log. Same field shape as the
            // deletion path (all per-collection counts at 0) so the grep target is stable.
            log.info("ProjectHardDeleteJob - run completed: deletedCount=0 eventsRemovedCount=0 "
                            + "gridFsFilesRemoved=0 exportsRemoved=0 subscriberEventsRemoved=0 "
                            + "subscribersRemoved=0 tagsRemoved=0 runDurationMs={}",
                    System.currentTimeMillis() - startedAtMillis);
            return;
        }

        List<String> deletedIds = projects.stream().map(Project::getId).toList();

        // Cascade in this exact order — AC-17b depends on it:
        //  1. Sweep prior events for these projects FIRST. The fresh project_hard_deleted rows
        //     emitted in step 2 land AFTER this sweep finishes synchronously so they are not
        //     collateral damage in this run.
        //  2. Emit one project_hard_deleted event per deleted project, synchronously via
        //     EventService.logEvent(...) so the row reaches Mongo BEFORE step 3 begins.
        //  3. Drop the project documents.
        // No cross-collection transaction (Mongo replica-set transactions are not configured
        // here). If step 3 fails after step 1 succeeds, the next daily run picks the same
        // projects back up via findByDeletedAtBefore — idempotent recovery, intentional trade-off.
        DeleteResult eventsDelete = template.remove(
                Query.query(Criteria.where("metadata.projectId").in(deletedIds)),
                EVENTS_COLLECTION);
        long eventsRemovedCount = eventsDelete == null ? 0L : eventsDelete.getDeletedCount();

        // Epic 09 subscriber-domain + GridFS cascade (Decision 8). Order matters:
        //  - GridFS files are removed BEFORE subscriber_exports so a mid-cascade crash never
        //    orphans a binary blob whose only pointer row (in subscriber_exports) is already gone.
        //    The GridFS sweep is keyed by metadata.projectId, not by exportId, so a file whose
        //    pointer row was already removed is still swept correctly.
        //  - Each step is idempotent: remove on an empty match is a no-op. A partial-cascade
        //    failure leaves the project doc in place (step 8 below not reached), so the next daily
        //    run re-selects it via findByDeletedAtBefore and re-processes the survivors.
        // GridFS files: GridFsOperations.delete(query) removes both fs.files and fs.chunks but
        // returns no count, so count fs.files first (daily cron, not a hot path).
        Query gridFsByProject = Query.query(Criteria.where("metadata.projectId").in(deletedIds));
        long gridFsFilesRemoved = template.count(gridFsByProject, GRIDFS_FILES_COLLECTION);
        gridFsOperations.delete(gridFsByProject);

        long exportsRemoved = removeByProjectId(deletedIds, SUBSCRIBER_EXPORTS_COLLECTION);
        long subscriberEventsRemoved = removeByProjectId(deletedIds, SUBSCRIBER_EVENTS_COLLECTION);
        long subscribersRemoved = removeByProjectId(deletedIds, SUBSCRIBERS_COLLECTION);
        long tagsRemoved = removeByProjectId(deletedIds, TAGS_COLLECTION);

        for (Project p : projects) {
            eventService.logEvent(
                    p.getOwnerId(),
                    EVENT_PROJECT_HARD_DELETED,
                    null,
                    null,
                    Map.of("projectId", p.getId(), "name", p.getName()));
        }

        template.remove(
                Query.query(Criteria.where("_id").in(deletedIds)),
                PROJECTS_COLLECTION);

        log.info("ProjectHardDeleteJob - run completed: deletedCount={} eventsRemovedCount={} "
                        + "gridFsFilesRemoved={} exportsRemoved={} subscriberEventsRemoved={} "
                        + "subscribersRemoved={} tagsRemoved={} runDurationMs={}",
                projects.size(), eventsRemovedCount, gridFsFilesRemoved, exportsRemoved,
                subscriberEventsRemoved, subscribersRemoved, tagsRemoved,
                System.currentTimeMillis() - startedAtMillis);
    }

    // Sweeps a subscriber-domain collection by top-level projectId (these entities store projectId
    // at the document root, unlike `events`/GridFS which nest it under metadata). Null-defensive
    // like the events sweep above.
    private long removeByProjectId(List<String> deletedIds, String collection) {
        DeleteResult result = template.remove(
                Query.query(Criteria.where("projectId").in(deletedIds)), collection);
        return result == null ? 0L : result.getDeletedCount();
    }
}
