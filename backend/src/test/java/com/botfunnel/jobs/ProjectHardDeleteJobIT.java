package com.botfunnel.jobs;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberEvent;
import com.botfunnel.subscriber.SubscriberStatus;
import com.botfunnel.subscriber.export.ExportStatus;
import com.botfunnel.subscriber.export.SubscriberExport;
import com.botfunnel.tag.Tag;
import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.Document;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ProjectHardDeleteJobIT extends AbstractIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired EventRepository eventRepository;
    @Autowired ProjectHardDeleteJob job;
    @Autowired StorageProvider storageProvider;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired GridFsOperations gridFsOperations;

    // Deterministic uniqueness for the unique-compound seeds (subscribers (projectId,telegramUserId),
    // tags (projectId,slug)) — replaces System.nanoTime(), which is not guaranteed distinct across
    // rapid consecutive calls on every platform.
    private static final AtomicLong SEQ = new AtomicLong();

    @BeforeEach
    void cleanState() {
        // Wipes `projects` + `events` + the four subscriber-domain collections + every GridFS file.
        // JobRunr StorageProvider state (the registered RecurringJob list) is intentionally NOT
        // reset between tests — registrations come from @Recurring discovery at Spring context
        // startup and are stable for the JVM lifetime. `recurringJob_registeredWithCorrectIdAndCron`
        // reads that state read-only.
        projectRepository.deleteAll();
        eventRepository.deleteAll();
        mongoTemplate.remove(new Query(), Subscriber.class);
        mongoTemplate.remove(new Query(), Tag.class);
        mongoTemplate.remove(new Query(), SubscriberEvent.class);
        mongoTemplate.remove(new Query(), SubscriberExport.class);
        mongoTemplate.remove(new Query(), FUNNELS_COLLECTION);
        mongoTemplate.remove(new Query(), FUNNEL_EXECUTIONS_COLLECTION);
        gridFsOperations.delete(new Query());
    }

    private static final String FUNNELS_COLLECTION = "funnels";
    private static final String FUNNEL_EXECUTIONS_COLLECTION = "funnel_executions";

    // Funnel-domain seed helpers (Task 8 cascade): both collections store projectId at the document
    // root, so the cascade's removeByProjectId(...).in(deletedIds) sweep picks them up. Seed minimal
    // raw documents — the cascade keys on projectId only, not on any other field.
    private void seedFunnel(String projectId) {
        mongoTemplate.insert(
                new Document("projectId", projectId).append("name", "funnel-" + SEQ.incrementAndGet()),
                FUNNELS_COLLECTION);
    }

    private void seedFunnelExecution(String projectId) {
        mongoTemplate.insert(
                new Document("projectId", projectId).append("subscriberId", "sub-" + SEQ.incrementAndGet()),
                FUNNEL_EXECUTIONS_COLLECTION);
    }

    private long countInCollection(String collection, String projectId) {
        return mongoTemplate.count(Query.query(Criteria.where("projectId").is(projectId)), collection);
    }

    private Project seedActiveProject(String ownerId, String name) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName(name);
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p);
    }

    private Project seedSoftDeletedProject(String ownerId, String name, Instant deletedAt) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName(name);
        p.setDescription(null);
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(deletedAt.minus(30, ChronoUnit.DAYS));
        p.setUpdatedAt(deletedAt);
        p.setDeletedAt(deletedAt);
        return projectRepository.save(p);
    }

    private Event seedEvent(String ownerId, String projectId, String type, Instant createdAt) {
        // Seed directly via EventRepository (deterministic timestamp). Avoid EventService.logEvent
        // here — its fire-and-forget .subscribe() makes seed-then-cron timing flaky.
        Map<String, Object> meta = Map.of("projectId", projectId);
        Event e = new Event(ownerId, type, null, null, meta, createdAt);
        return eventRepository.save(e);
    }

    // ─── Subscriber-domain + GridFS seed helpers (Epic 09 cascade) ──────────────
    // Each seeds exactly one row keyed by projectId at the document root (Subscriber/Tag/
    // SubscriberEvent/SubscriberExport) so the cascade's top-level `projectId` criterion picks
    // them up. GridFS files are keyed under `metadata.projectId` (nested) like the `events` sweep.

    private void seedSubscriber(String projectId) {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        // Unique-compound (projectId, telegramUserId): SEQ keeps repeated seeds collision-free.
        s.setTelegramUserId(SEQ.incrementAndGet());
        s.setTelegramChatId(SEQ.incrementAndGet());
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        mongoTemplate.save(s);
    }

    private void seedTag(String projectId) {
        Tag t = new Tag();
        t.setProjectId(projectId);
        // Unique-compound (projectId, slug): SEQ slug keeps repeated seeds collision-free.
        t.setSlug("tag-" + SEQ.incrementAndGet());
        t.setLabel("L");
        t.setCreatedAt(Instant.now());
        mongoTemplate.save(t);
    }

    private void seedSubscriberEvent(String projectId) {
        SubscriberEvent e = new SubscriberEvent();
        e.setProjectId(projectId);
        e.setSubscriberId("sub-" + SEQ.incrementAndGet());
        e.setEventType("subscriber_registered");
        e.setCreatedAt(Instant.now());
        mongoTemplate.save(e);
    }

    private void seedSubscriberExport(String projectId) {
        SubscriberExport ex = new SubscriberExport();
        ex.setProjectId(projectId);
        ex.setOwnerId("owner-1");
        // Status MUST be DONE (load-bearing): the (projectId) partial-unique index only covers
        // in-flight (PENDING/RUNNING) exports, so DONE lets multiple seeds across projects coexist
        // without DuplicateKeyException.
        ex.setStatus(ExportStatus.DONE);
        ex.setCreatedAt(Instant.now());
        mongoTemplate.save(ex);
    }

    private void seedGridFsFile(String projectId, String exportId) {
        Document metadata = new Document("projectId", projectId).append("exportId", exportId);
        gridFsOperations.store(
                new ByteArrayInputStream("col1,col2\nval1,val2\n".getBytes(StandardCharsets.UTF_8)),
                "export-" + exportId + ".csv", metadata);
    }

    private long countByProjectId(Class<?> entityType, String projectId) {
        return mongoTemplate.count(Query.query(Criteria.where("projectId").is(projectId)), entityType);
    }

    private boolean gridFsFileExists(String projectId) {
        GridFSFile found = gridFsOperations.findOne(
                Query.query(Criteria.where("metadata.projectId").is(projectId)));
        return found != null;
    }

    @Test
    void cron_deletesOldProjectsAndCascadesEvents(CapturedOutput output) {
        // Soft-delete timestamps are placed >7d in the past to satisfy the cutoff finder,
        // but the prior-event timestamps are placed near-now (preJob - 1ms) so the AC-17b
        // ordering proof below is a true temporal sandwich:
        //   seededEvent.createdAt < preJob <= survivor.createdAt
        // If a buggy implementation emitted project_hard_deleted BEFORE the events sweep,
        // the survivor would still be a "during-run" timestamp ≥ preJob — but it would
        // ALSO be swept (its metadata.projectId matches deletedIds), so the assertion
        // `oldProjectRows.size() == 1` would fail on its own. The timestamp pair below
        // is the secondary proof that the surviving row was created during this run, not
        // pre-existing.
        Instant nowAtSeed = Instant.now();
        Project oldProject = seedSoftDeletedProject("owner-1", "Old Project",
                nowAtSeed.minus(8, ChronoUnit.DAYS));
        Project newProject = seedSoftDeletedProject("owner-1", "New Project",
                nowAtSeed.minus(1, ChronoUnit.DAYS));

        Instant oldEv1Time = nowAtSeed.minus(2, ChronoUnit.MILLIS);
        Instant oldEv2Time = nowAtSeed.minus(1, ChronoUnit.MILLIS);
        seedEvent("owner-1", oldProject.getId(), "project_created", oldEv1Time);
        seedEvent("owner-1", oldProject.getId(), "project_soft_deleted", oldEv2Time);

        Instant newEv1Time = nowAtSeed.minus(2, ChronoUnit.MILLIS);
        Instant newEv2Time = nowAtSeed.minus(1, ChronoUnit.MILLIS);
        seedEvent("owner-1", newProject.getId(), "project_created", newEv1Time);
        seedEvent("owner-1", newProject.getId(), "project_soft_deleted", newEv2Time);

        Instant preJob = Instant.now();
        job.hardDeleteSoftDeletedProjects();

        assertThat(projectRepository.findById(oldProject.getId()).orElse(null)).isNull();
        assertThat(projectRepository.findById(newProject.getId()).orElse(null)).isNotNull();

        List<Event> allEvents = eventRepository.findAll();
        assertThat(allEvents).isNotNull();

        long newProjectEventCount = allEvents.stream()
                .filter(e -> e.getMetadata() != null
                        && newProject.getId().equals(e.getMetadata().get("projectId")))
                .count();
        assertThat(newProjectEventCount).isEqualTo(2L);

        List<Event> oldProjectRows = allEvents.stream()
                .filter(e -> e.getMetadata() != null
                        && oldProject.getId().equals(e.getMetadata().get("projectId")))
                .toList();
        assertThat(oldProjectRows)
                .as("exactly one project_hard_deleted row must survive the cascade — "
                        + "if emit-before-sweep were ever introduced, the row would be swept "
                        + "by its own metadata.projectId match and this list would be empty")
                .hasSize(1);

        Event hardDeletedEvent = oldProjectRows.get(0);
        assertThat(hardDeletedEvent.getEventType()).isEqualTo("project_hard_deleted");
        assertThat(hardDeletedEvent.getUserId()).isEqualTo("owner-1");
        assertThat(hardDeletedEvent.getMetadata())
                .containsEntry("projectId", oldProject.getId())
                .containsEntry("name", "Old Project");

        // AC-17b temporal sandwich: seeded events placed BEFORE preJob; survivor placed AT/AFTER
        // preJob. Proves the survivor was created during the run (not pre-existing) and that
        // the seeded events were swept (since they would have been picked up by the .findAll()
        // above if not swept — and the size() == 1 assertion above would have failed).
        assertThat(oldEv1Time).isBefore(preJob);
        assertThat(oldEv2Time).isBefore(preJob);
        assertThat(hardDeletedEvent.getCreatedAt())
                .as("project_hard_deleted must be created during this run, not pre-existing")
                .isAfterOrEqualTo(preJob);

        // This canonical AC-17b proof seeds only `events`, so the new subscriber-domain + GridFS
        // counts are all 0 here — the dedicated cascade tests below seed and drain those.
        assertThat(output.getOut())
                .containsPattern("ProjectHardDeleteJob - run completed: "
                        + "deletedCount=1 eventsRemovedCount=2 gridFsFilesRemoved=0 exportsRemoved=0 "
                        + "subscriberEventsRemoved=0 subscribersRemoved=0 tagsRemoved=0 "
                        + "funnelsRemoved=0 funnelExecutionsRemoved=0 runDurationMs=\\d+");
    }

    @Test
    void cron_doesNotTouchProjectsYoungerThanSevenDays() {
        Project recent = seedSoftDeletedProject("owner-1", "Recent",
                Instant.now().minus(1, ChronoUnit.DAYS));
        seedEvent("owner-1", recent.getId(), "project_created",
                Instant.now().minus(3, ChronoUnit.DAYS));
        seedEvent("owner-1", recent.getId(), "project_soft_deleted",
                Instant.now().minus(1, ChronoUnit.DAYS));

        job.hardDeleteSoftDeletedProjects();

        assertThat(projectRepository.findById(recent.getId()).orElse(null)).isNotNull();
        assertThat(eventRepository.count()).isEqualTo(2L);
        List<Event> hardDeletedRows = eventRepository.findAll().stream()
                .filter(e -> "project_hard_deleted".equals(e.getEventType()))
                .toList();
        assertThat(hardDeletedRows).isNotNull().isEmpty();
    }

    @Test
    void cron_exactlySevenDaysAgo_isRemoved() {
        // Mirrors HardDeleteJobTest.hardDelete_exactly30DaysAgo_isRemoved — same limitation
        // applies: without an injected Clock, natural wall-clock drift between seed and job
        // (microseconds to milliseconds) makes the +1ns trick indistinguishable from "the test
        // happened to run after the seed". A future Clock-injection refactor could turn this
        // into a strict isolation of the +1ns cutoff. Today it documents the intended cutoff
        // semantics; correctness of the +1ns offset is also asserted by code review against
        // HardDeleteJob.java line 40 for parity.
        Project exactly = seedSoftDeletedProject("owner-1", "Exact",
                Instant.now().minus(7, ChronoUnit.DAYS));

        job.hardDeleteSoftDeletedProjects();

        assertThat(projectRepository.findById(exactly.getId()).orElse(null))
                .as("project soft-deleted exactly 7d ago must be hard-deleted on this run")
                .isNull();
    }

    @Test
    void cron_perEventMetadata_forMultipleDeletions() {
        Project p1 = seedSoftDeletedProject("owner-A", "Project A",
                Instant.now().minus(10, ChronoUnit.DAYS));
        Project p2 = seedSoftDeletedProject("owner-B", "Project B",
                Instant.now().minus(8, ChronoUnit.DAYS));

        job.hardDeleteSoftDeletedProjects();

        assertThat(projectRepository.findById(p1.getId()).orElse(null)).isNull();
        assertThat(projectRepository.findById(p2.getId()).orElse(null)).isNull();

        List<Event> hardDeletedEvents = eventRepository.findAll().stream()
                .filter(e -> "project_hard_deleted".equals(e.getEventType()))
                .toList();
        assertThat(hardDeletedEvents).hasSize(2);

        Event evA = hardDeletedEvents.stream()
                .filter(e -> "owner-A".equals(e.getUserId()))
                .findFirst().orElseThrow();
        assertThat(evA.getMetadata())
                .containsEntry("projectId", p1.getId())
                .containsEntry("name", "Project A");

        Event evB = hardDeletedEvents.stream()
                .filter(e -> "owner-B".equals(e.getUserId()))
                .findFirst().orElseThrow();
        assertThat(evB.getMetadata())
                .containsEntry("projectId", p2.getId())
                .containsEntry("name", "Project B");
    }

    @Test
    void cron_zeroDeletionDay_emitsExtendedStructuredLineWithZeros(CapturedOutput output) {
        // Empty DB after cleanState — exercise the zero-deletion-day liveness signal. The extended
        // shape must still emit with every per-collection count at 0 (operations grep for the
        // "run completed" prefix to confirm the cron is alive).
        job.hardDeleteSoftDeletedProjects();

        assertThat(output.getOut())
                .containsPattern("ProjectHardDeleteJob - run completed: "
                        + "deletedCount=0 eventsRemovedCount=0 gridFsFilesRemoved=0 exportsRemoved=0 "
                        + "subscriberEventsRemoved=0 subscribersRemoved=0 tagsRemoved=0 "
                        + "funnelsRemoved=0 funnelExecutionsRemoved=0 runDurationMs=\\d+");

        assertThat(projectRepository.count()).isZero();
        assertThat(eventRepository.count()).isZero();
    }

    @Test
    void cron_logsStructuredInfoOnDeletionRun(CapturedOutput output) {
        // Distinct signal from cron_deletesOldProjectsAndCascadesEvents: this case proves the
        // INFO line is emitted EXACTLY ONCE per run on the deletion path (not zero, not twice
        // — guards against future refactors that might log on both branches or skip logging
        // when there is work to do).
        Project p = seedSoftDeletedProject("owner-1", "ToDelete",
                Instant.now().minus(10, ChronoUnit.DAYS));
        seedEvent("owner-1", p.getId(), "project_created",
                Instant.now().minus(15, ChronoUnit.DAYS));
        seedEvent("owner-1", p.getId(), "project_soft_deleted",
                Instant.now().minus(10, ChronoUnit.DAYS));

        job.hardDeleteSoftDeletedProjects();

        String captured = output.getOut();
        assertThat(captured)
                .containsPattern("ProjectHardDeleteJob - run completed: "
                        + "deletedCount=1 eventsRemovedCount=2 gridFsFilesRemoved=0 exportsRemoved=0 "
                        + "subscriberEventsRemoved=0 subscribersRemoved=0 tagsRemoved=0 "
                        + "funnelsRemoved=0 funnelExecutionsRemoved=0 runDurationMs=\\d+");
        long occurrences = countOccurrences(captured, "ProjectHardDeleteJob - run completed: ");
        assertThat(occurrences)
                .as("structured INFO line must be emitted exactly once per run")
                .isEqualTo(1L);
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void cron_cascadesAllSubscriberDomainCollectionsAndGridFs() {
        // One project, one row in each of the four subscriber-domain collections + one GridFS file.
        // Proves NO collection is forgotten by the cascade and the binary blob is swept.
        Project oldProject = seedSoftDeletedProject("owner-1", "Old Project",
                Instant.now().minus(8, ChronoUnit.DAYS));
        seedSubscriber(oldProject.getId());
        seedTag(oldProject.getId());
        seedSubscriberEvent(oldProject.getId());
        seedSubscriberExport(oldProject.getId());
        seedGridFsFile(oldProject.getId(), "export-1");

        job.hardDeleteSoftDeletedProjects();

        assertThat(projectRepository.findById(oldProject.getId()).orElse(null)).isNull();
        assertThat(countByProjectId(Subscriber.class, oldProject.getId())).isZero();
        assertThat(countByProjectId(Tag.class, oldProject.getId())).isZero();
        assertThat(countByProjectId(SubscriberEvent.class, oldProject.getId())).isZero();
        assertThat(countByProjectId(SubscriberExport.class, oldProject.getId())).isZero();
        assertThat(gridFsFileExists(oldProject.getId()))
                .as("GridFS export blob must be swept (both fs.files and fs.chunks)")
                .isFalse();
    }

    @Test
    void cron_logsExtendedStructuredLineWithPerCollectionCounts(CapturedOutput output) {
        Project oldProject = seedSoftDeletedProject("owner-1", "Old Project",
                Instant.now().minus(8, ChronoUnit.DAYS));
        seedEvent("owner-1", oldProject.getId(), "project_created", Instant.now().minus(20, ChronoUnit.DAYS));
        seedEvent("owner-1", oldProject.getId(), "project_soft_deleted", Instant.now().minus(8, ChronoUnit.DAYS));
        seedSubscriber(oldProject.getId());
        seedTag(oldProject.getId());
        seedSubscriberEvent(oldProject.getId());
        seedSubscriberExport(oldProject.getId());
        seedGridFsFile(oldProject.getId(), "export-1");

        job.hardDeleteSoftDeletedProjects();

        assertThat(output.getOut())
                .containsPattern("ProjectHardDeleteJob - run completed: "
                        + "deletedCount=1 eventsRemovedCount=2 gridFsFilesRemoved=1 exportsRemoved=1 "
                        + "subscriberEventsRemoved=1 subscribersRemoved=1 tagsRemoved=1 "
                        + "funnelsRemoved=0 funnelExecutionsRemoved=0 runDurationMs=\\d+");
    }

    @Test
    void cron_doesNotTouchSubscriberDomainRowsForYoungProjects() {
        // Project soft-deleted 1 day ago is inside the 7d grace window — every subscriber-domain
        // row and the GridFS blob must survive untouched.
        Project recent = seedSoftDeletedProject("owner-1", "Recent",
                Instant.now().minus(1, ChronoUnit.DAYS));
        seedSubscriber(recent.getId());
        seedTag(recent.getId());
        seedSubscriberEvent(recent.getId());
        seedSubscriberExport(recent.getId());
        seedGridFsFile(recent.getId(), "export-young");

        job.hardDeleteSoftDeletedProjects();

        assertThat(projectRepository.findById(recent.getId()).orElse(null)).isNotNull();
        assertThat(countByProjectId(Subscriber.class, recent.getId())).isEqualTo(1L);
        assertThat(countByProjectId(Tag.class, recent.getId())).isEqualTo(1L);
        assertThat(countByProjectId(SubscriberEvent.class, recent.getId())).isEqualTo(1L);
        assertThat(countByProjectId(SubscriberExport.class, recent.getId())).isEqualTo(1L);
        assertThat(gridFsFileExists(recent.getId()))
                .as("young project's GridFS blob must survive the grace window")
                .isTrue();
    }

    @Test
    void cron_cascadeRecoversPartialDrainThenSecondRunIsNoOp(CapturedOutput output) {
        // Partial-cascade recovery contract (the JobRunr daily-retry guarantee). Simulate a crashed
        // prior run: the project is still soft-deleted (step 8 "drop projects" never reached) and a
        // subset of its rows survived — `subscribers` was already swept before the crash, while
        // tags / subscriber_events / subscriber_exports / GridFS remain. The recovery run must drain
        // the survivors AND treat the already-empty `subscribers` collection as a no-op (count 0, no
        // exception). A bug that skipped any survivor sweep would fail the per-collection assertions
        // below — so this exercises the real cascade `remove` steps, not the zero-deletion path.
        Project oldProject = seedSoftDeletedProject("owner-1", "Old Project",
                Instant.now().minus(8, ChronoUnit.DAYS));
        // subscribers intentionally NOT seeded → models a collection already drained by the crash.
        seedTag(oldProject.getId());
        seedSubscriberEvent(oldProject.getId());
        seedSubscriberExport(oldProject.getId());
        seedGridFsFile(oldProject.getId(), "export-survivor");

        job.hardDeleteSoftDeletedProjects();

        assertThat(projectRepository.findById(oldProject.getId()).orElse(null)).isNull();
        assertThat(countByProjectId(Subscriber.class, oldProject.getId())).isZero();
        assertThat(countByProjectId(Tag.class, oldProject.getId())).isZero();
        assertThat(countByProjectId(SubscriberEvent.class, oldProject.getId())).isZero();
        assertThat(countByProjectId(SubscriberExport.class, oldProject.getId())).isZero();
        assertThat(gridFsFileExists(oldProject.getId())).isFalse();
        assertThat(output.getOut())
                .as("recovery run drains survivors (gridFs/exports/events/tags=1) and no-ops the "
                        + "already-empty subscribers collection (subscribersRemoved=0)")
                .containsPattern("ProjectHardDeleteJob - run completed: "
                        + "deletedCount=1 eventsRemovedCount=0 gridFsFilesRemoved=1 exportsRemoved=1 "
                        + "subscriberEventsRemoved=1 subscribersRemoved=0 tagsRemoved=1 "
                        + "funnelsRemoved=0 funnelExecutionsRemoved=0 runDurationMs=\\d+");

        // Second fire on the now fully-drained state is a clean no-op (nothing eligible).
        job.hardDeleteSoftDeletedProjects();
        long noOpRuns = countOccurrences(output.getOut(),
                "deletedCount=0 eventsRemovedCount=0 gridFsFilesRemoved=0 exportsRemoved=0 "
                        + "subscriberEventsRemoved=0 subscribersRemoved=0 tagsRemoved=0 "
                        + "funnelsRemoved=0 funnelExecutionsRemoved=0");
        assertThat(noOpRuns)
                .as("the no-eligible-projects re-fire emits exactly one all-zero line")
                .isEqualTo(1L);
    }

    @Test
    void cron_gridFsFileWithoutExportPointer_isStillSwept() {
        // Locks in the GridFS-before-exports rationale (Decision 8, task edge case): the GridFS sweep
        // is keyed by metadata.projectId — NOT by the subscriber_exports pointer row. A blob whose
        // pointer was already removed must still be swept. A reimplementation that swept GridFS by
        // joining through subscriber_exports (wrong order / wrong key) would leak this orphan blob.
        Project oldProject = seedSoftDeletedProject("owner-1", "Old Project",
                Instant.now().minus(8, ChronoUnit.DAYS));
        seedGridFsFile(oldProject.getId(), "orphan-export");
        // Deliberately NO seedSubscriberExport(...) — the pointer row is already gone.

        job.hardDeleteSoftDeletedProjects();

        assertThat(projectRepository.findById(oldProject.getId()).orElse(null)).isNull();
        assertThat(gridFsFileExists(oldProject.getId()))
                .as("orphan GridFS blob (no pointer row) must still be swept by metadata.projectId")
                .isFalse();
    }

    @Test
    void cron_cascadeRemovesFunnelsAndExecutions(CapturedOutput output) {
        // Task 8 cascade: a hard-deleted project's funnels + funnel_executions (keyed by top-level
        // projectId) must be swept before the project doc is dropped, with per-collection log counters.
        Project oldProject = seedSoftDeletedProject("owner-1", "Old Project",
                Instant.now().minus(8, ChronoUnit.DAYS));
        seedFunnel(oldProject.getId());
        seedFunnel(oldProject.getId());
        seedFunnelExecution(oldProject.getId());

        job.hardDeleteSoftDeletedProjects();

        assertThat(projectRepository.findById(oldProject.getId()).orElse(null)).isNull();
        assertThat(countInCollection(FUNNELS_COLLECTION, oldProject.getId())).isZero();
        assertThat(countInCollection(FUNNEL_EXECUTIONS_COLLECTION, oldProject.getId())).isZero();
        assertThat(output.getOut())
                .containsPattern("ProjectHardDeleteJob - run completed: "
                        + "deletedCount=1 eventsRemovedCount=0 gridFsFilesRemoved=0 exportsRemoved=0 "
                        + "subscriberEventsRemoved=0 subscribersRemoved=0 tagsRemoved=0 "
                        + "funnelsRemoved=2 funnelExecutionsRemoved=1 runDurationMs=\\d+");
    }

    @Test
    void cron_cascadeDoesNotTouchFunnelsOfOtherProjects() {
        // Cross-project isolation: only the deleted project's funnels/executions are swept; an active
        // (never-deleted) control project's funnel-domain rows must survive untouched.
        Project oldProject = seedSoftDeletedProject("owner-1", "Old Project",
                Instant.now().minus(8, ChronoUnit.DAYS));
        Project control = seedActiveProject("owner-2", "Active Project");
        seedFunnel(oldProject.getId());
        seedFunnelExecution(oldProject.getId());
        seedFunnel(control.getId());
        seedFunnelExecution(control.getId());

        job.hardDeleteSoftDeletedProjects();

        assertThat(projectRepository.findById(oldProject.getId()).orElse(null)).isNull();
        assertThat(countInCollection(FUNNELS_COLLECTION, oldProject.getId())).isZero();
        assertThat(countInCollection(FUNNEL_EXECUTIONS_COLLECTION, oldProject.getId())).isZero();

        assertThat(projectRepository.findById(control.getId()).orElse(null)).isNotNull();
        assertThat(countInCollection(FUNNELS_COLLECTION, control.getId())).isEqualTo(1L);
        assertThat(countInCollection(FUNNEL_EXECUTIONS_COLLECTION, control.getId())).isEqualTo(1L);
    }

    @Test
    void recurringJob_registeredWithCorrectIdAndCron() {
        // Tech-spec line 350 mentions JobScheduler.getRecurringJobs() but that method lives on
        // StorageProvider, not JobScheduler. We autowire StorageProvider directly (an
        // InMemoryStorageProvider in tests, supplied by JobRunrInMemoryConfig).
        List<RecurringJob> recurring = storageProvider.getRecurringJobs();
        RecurringJob projectJob = recurring.stream()
                .filter(rj -> "hard-delete-projects".equals(rj.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "hard-delete-projects recurring job is not registered"));
        assertThat(projectJob.getScheduleExpression()).isEqualTo("0 3 * * *");
    }
}
