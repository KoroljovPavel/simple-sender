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
        Instant baseline = Instant.now();
        Project oldProject = seedSoftDeletedProject("owner-1", "Old Project",
                baseline.minus(8, ChronoUnit.DAYS));
        Project newProject = seedSoftDeletedProject("owner-1", "New Project",
                baseline.minus(1, ChronoUnit.DAYS));

        Instant oldEv1Time = baseline.minus(10, ChronoUnit.DAYS);
        Instant oldEv2Time = baseline.minus(8, ChronoUnit.DAYS);
        seedEvent("owner-1", oldProject.getId(), "project_created", oldEv1Time);
        seedEvent("owner-1", oldProject.getId(), "project_soft_deleted", oldEv2Time);

        seedEvent("owner-1", newProject.getId(), "project_created",
                baseline.minus(3, ChronoUnit.DAYS));
        seedEvent("owner-1", newProject.getId(), "project_soft_deleted",
                baseline.minus(1, ChronoUnit.DAYS));

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
        assertThat(oldProjectRows).hasSize(1);

        Event hardDeletedEvent = oldProjectRows.get(0);
        assertThat(hardDeletedEvent.getEventType()).isEqualTo("project_hard_deleted");
        assertThat(hardDeletedEvent.getUserId()).isEqualTo("owner-1");
        assertThat(hardDeletedEvent.getMetadata())
                .containsEntry("projectId", oldProject.getId())
                .containsEntry("name", "Old Project");

        // AC-17b ordering proof: the surviving project_hard_deleted event was created AFTER the
        // events sweep, not before — its createdAt must be strictly greater than the seeded
        // project_created / project_soft_deleted timestamps for that project.
        assertThat(hardDeletedEvent.getCreatedAt())
                .as("project_hard_deleted must post-date prior events for the same project")
                .isAfter(oldEv1Time)
                .isAfter(oldEv2Time);

        assertThat(output.getOut())
                .containsPattern("deletedCount=1 eventsRemovedCount=2 runDurationMs=\\d+");
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
                .containsPattern("deletedCount=0 eventsRemovedCount=0 runDurationMs=\\d+");

        assertThat(projectRepository.count().block()).isZero();
        assertThat(eventRepository.count().block()).isZero();
    }

    @Test
    void cron_logsStructuredInfoOnDeletionRun(CapturedOutput output) {
        Project p = seedSoftDeletedProject("owner-1", "ToDelete",
                Instant.now().minus(10, ChronoUnit.DAYS));
        seedEvent("owner-1", p.getId(), "project_created",
                Instant.now().minus(15, ChronoUnit.DAYS));
        seedEvent("owner-1", p.getId(), "project_soft_deleted",
                Instant.now().minus(10, ChronoUnit.DAYS));

        job.hardDeleteSoftDeletedProjects();

        assertThat(output.getOut())
                .containsPattern("deletedCount=1 eventsRemovedCount=2 runDurationMs=\\d+");
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
