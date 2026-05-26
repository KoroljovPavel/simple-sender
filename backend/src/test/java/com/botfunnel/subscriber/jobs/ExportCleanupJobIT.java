package com.botfunnel.subscriber.jobs;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.subscriber.export.ExportStatus;
import com.botfunnel.subscriber.export.SubscriberExport;
import com.botfunnel.subscriber.export.SubscriberExportRepository;
import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.Document;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExportCleanupJobIT extends AbstractIntegrationTest {

    @Autowired SubscriberExportRepository exportRepository;
    @Autowired EventRepository eventRepository;
    @Autowired GridFsOperations gridFsOperations;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired ExportCleanupJob cleanupJob;
    @Autowired StorageProvider storageProvider;

    @BeforeEach
    void cleanState() {
        eventRepository.deleteAll();
        mongoTemplate.remove(new Query(), SubscriberExport.class);
        gridFsOperations.delete(new Query());
    }

    @Test
    void recurringRegistration_jobScheduled_correctCronAndId() {
        RecurringJob job = storageProvider.getRecurringJobs().stream()
                .filter(rj -> "cleanup-subscriber-exports".equals(rj.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "cleanup-subscriber-exports recurring job is not registered"));
        assertThat(job.getScheduleExpression()).isEqualTo("0 3 * * *");
    }

    @Test
    void cleanup_donePast7d_purgesAndEmits() {
        String fileId = seedGridFsFile("p1");
        SubscriberExport export = seedExport("p1", ExportStatus.DONE,
                Instant.now().minus(8, ChronoUnit.DAYS), fileId);

        cleanupJob.hardDeleteExpiredExports();

        assertThat(exportRepository.findById(export.getId()).orElseThrow().getStatus())
                .isEqualTo(ExportStatus.PURGED);
        assertThat(gridFsFileExists(fileId)).isFalse();
        assertThat(purgedEvents()).hasSize(1);
    }

    @Test
    void cleanup_alreadyPurged_idempotent() {
        String fileId = seedGridFsFile("p1");
        seedExport("p1", ExportStatus.DONE, Instant.now().minus(8, ChronoUnit.DAYS), fileId);

        cleanupJob.hardDeleteExpiredExports();
        cleanupJob.hardDeleteExpiredExports(); // second run sees no DONE rows → no-op

        assertThat(purgedEvents())
                .as("the re-run on an already-PURGED row must not write a second event")
                .hasSize(1);
    }

    @Test
    void cleanup_doneWithin7d_notPurged() {
        String fileId = seedGridFsFile("p1");
        SubscriberExport export = seedExport("p1", ExportStatus.DONE,
                Instant.now().minus(3, ChronoUnit.DAYS), fileId);

        cleanupJob.hardDeleteExpiredExports();

        assertThat(exportRepository.findById(export.getId()).orElseThrow().getStatus())
                .isEqualTo(ExportStatus.DONE);
        assertThat(gridFsFileExists(fileId)).isTrue();
        assertThat(purgedEvents()).isEmpty();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private String seedGridFsFile(String projectId) {
        return gridFsOperations.store(
                new ByteArrayInputStream("col\nval\n".getBytes(StandardCharsets.UTF_8)),
                "seed.csv", "text/csv", new Document("projectId", projectId)).toHexString();
    }

    private SubscriberExport seedExport(String projectId, ExportStatus status, Instant createdAt, String fileId) {
        SubscriberExport export = new SubscriberExport();
        export.setProjectId(projectId);
        export.setOwnerId("owner-1");
        export.setStatus(status);
        export.setFileId(fileId);
        export.setRowCount(1L);
        export.setCreatedAt(createdAt);
        export.setCompletedAt(createdAt);
        export.setExpiresAt(createdAt.plus(1, ChronoUnit.DAYS));
        return exportRepository.save(export);
    }

    private boolean gridFsFileExists(String fileId) {
        GridFSFile found = gridFsOperations.findOne(
                Query.query(Criteria.where("_id").is(new org.bson.types.ObjectId(fileId))));
        return found != null;
    }

    private List<Event> purgedEvents() {
        return eventRepository.findAll().stream()
                .filter(e -> "subscribers_export_purged".equals(e.getEventType()))
                .toList();
    }
}
