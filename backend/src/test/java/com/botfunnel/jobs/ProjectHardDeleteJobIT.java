package com.botfunnel.jobs;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ProjectHardDeleteJobIT extends AbstractIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired EventRepository eventRepository;
    @Autowired ProjectHardDeleteJob job;
    @Autowired StorageProvider storageProvider;

    @BeforeEach
    void cleanState() {
        // Wipes `projects` + `events` only. JobRunr StorageProvider state (the registered
        // RecurringJob list) is intentionally NOT reset between tests — registrations come from
        // @Recurring discovery at Spring context startup and are stable for the JVM lifetime.
        // `recurringJob_registeredWithCorrectIdAndCron` reads that state read-only.
        projectRepository.deleteAll().block();
        eventRepository.deleteAll().block();
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
        return projectRepository.save(p).block();
    }

    private Event seedEvent(String ownerId, String projectId, String type, Instant createdAt) {
        // Seed directly via EventRepository (deterministic timestamp). Avoid EventService.logEvent
        // here — its fire-and-forget .subscribe() makes seed-then-cron timing flaky.
        Map<String, Object> meta = Map.of("projectId", projectId);
        Event e = new Event(ownerId, type, null, null, meta, createdAt);
        return eventRepository.save(e).block();
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

        assertThat(projectRepository.findById(oldProject.getId()).block()).isNull();
        assertThat(projectRepository.findById(newProject.getId()).block()).isNotNull();

        List<Event> allEvents = eventRepository.findAll().collectList().block();
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

        assertThat(output.getOut())
                .containsPattern("ProjectHardDeleteJob - run completed: "
                        + "deletedCount=1 eventsRemovedCount=2 runDurationMs=\\d+");
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

        assertThat(projectRepository.findById(recent.getId()).block()).isNotNull();
        assertThat(eventRepository.count().block()).isEqualTo(2L);
        List<Event> hardDeletedRows = eventRepository.findAll()
                .filter(e -> "project_hard_deleted".equals(e.getEventType()))
                .collectList().block();
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

        assertThat(projectRepository.findById(exactly.getId()).block())
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

        assertThat(projectRepository.findById(p1.getId()).block()).isNull();
        assertThat(projectRepository.findById(p2.getId()).block()).isNull();

        List<Event> hardDeletedEvents = eventRepository.findAll()
                .filter(e -> "project_hard_deleted".equals(e.getEventType()))
                .collectList().block();
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
    void cron_zeroDeletionDay_emitsStructuredInfoLog(CapturedOutput output) {
        // Empty DB after cleanState — exercise the zero-deletion-day liveness signal.
        job.hardDeleteSoftDeletedProjects();

        assertThat(output.getOut())
                .containsPattern("ProjectHardDeleteJob - run completed: "
                        + "deletedCount=0 eventsRemovedCount=0 runDurationMs=\\d+");

        assertThat(projectRepository.count().block()).isZero();
        assertThat(eventRepository.count().block()).isZero();
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
                        + "deletedCount=1 eventsRemovedCount=2 runDurationMs=\\d+");
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
