package com.botfunnel.subscriber.jobs;

import com.botfunnel.events.EventService;
import com.botfunnel.subscriber.export.ExportStatus;
import com.botfunnel.subscriber.export.SubscriberExport;
import com.botfunnel.subscriber.export.SubscriberExportRepository;
import org.bson.types.ObjectId;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Nightly recurring cron (Decision 8, AC20). Purges DONE exports whose GridFS file has aged past the
 * retention window: deletes the binary blob, flips the export to PURGED, then writes a
 * {@code subscribers_export_purged} audit event. Same off-peak slot as {@code ProjectHardDeleteJob}
 * (JobRunr serializes recurring jobs). Idempotent: the DONE-only selector means a re-run skips
 * already-purged rows, and a per-export failure leaves the row DONE for the next run to retry.
 */
@Component
public class ExportCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ExportCleanupJob.class);
    private static final String EVT_PURGED = "subscribers_export_purged";

    private final SubscriberExportRepository exportRepository;
    private final MongoTemplate mongoTemplate;
    private final GridFsOperations gridFsOperations;
    private final EventService eventService;
    private final int retentionDays;

    public ExportCleanupJob(SubscriberExportRepository exportRepository,
                            MongoTemplate mongoTemplate,
                            GridFsOperations gridFsOperations,
                            EventService eventService,
                            @Value("${app.subscriber.export.retention-days}") int retentionDays) {
        this.exportRepository = exportRepository;
        this.mongoTemplate = mongoTemplate;
        this.gridFsOperations = gridFsOperations;
        this.eventService = eventService;
        this.retentionDays = retentionDays;
    }

    @Recurring(id = "cleanup-subscriber-exports", cron = "0 3 * * *")
    @Job(name = "Purge expired subscriber exports")
    public void hardDeleteExpiredExports() {
        long startedAtMillis = System.currentTimeMillis();
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        List<SubscriberExport> expired =
                exportRepository.findByStatusAndCreatedAtBefore(ExportStatus.DONE, cutoff);

        long purgedCount = 0;
        for (SubscriberExport export : expired) {
            try {
                // GridFS file first so a mid-step crash never flips status to PURGED while the blob
                // survives (which the DONE-only re-run could not then reclaim).
                if (export.getFileId() != null) {
                    gridFsOperations.delete(
                            Query.query(Criteria.where("_id").is(new ObjectId(export.getFileId()))));
                }
                // Predicate-protected flip — a null return means the row already left DONE (a racing
                // run claimed it); skip the event so it is not double-written.
                SubscriberExport flipped = mongoTemplate.findAndModify(
                        Query.query(Criteria.where("_id").is(export.getId())
                                .and("status").is(ExportStatus.DONE.name())),
                        new Update().set("status", ExportStatus.PURGED.name()),
                        SubscriberExport.class);
                if (flipped == null) {
                    continue;
                }
                eventService.logEvent(export.getOwnerId(), EVT_PURGED, null, null,
                        Map.of("projectId", export.getProjectId(), "exportId", export.getId()));
                purgedCount++;
            } catch (Exception e) {
                // Per-export failure is safe: the row stays DONE and the next nightly run retries it.
                log.warn("ExportCleanupJob - failed to purge export (exportId={}): {}",
                        export.getId(), e.getMessage());
            }
        }

        log.info("ExportCleanupJob - run completed: purgedCount={} runDurationMs={}",
                purgedCount, System.currentTimeMillis() - startedAtMillis);
    }
}
