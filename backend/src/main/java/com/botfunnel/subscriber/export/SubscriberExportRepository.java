package com.botfunnel.subscriber.export;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for subscriber-export records. Query surface added by Task 9 (the export/refresh/cleanup
 * flows owner), per the narrow-surface rule.
 */
public interface SubscriberExportRepository extends MongoRepository<SubscriberExport, String> {

    /** Recent-exports list for a project (Decision 15 UI feed) — DONE rows, newest first. */
    List<SubscriberExport> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, ExportStatus status);

    /** Cleanup-job selector (Decision 8): DONE exports older than the retention cutoff. */
    List<SubscriberExport> findByStatusAndCreatedAtBefore(ExportStatus status, Instant cutoff);
}
