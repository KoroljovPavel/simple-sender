package com.botfunnel.subscriber.export;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for subscriber-export records. Surface kept bare in Wave 1 — the export/refresh/cleanup
 * query methods are added by the task that owns those flows (Task 9), per the narrow-surface rule.
 */
public interface SubscriberExportRepository extends MongoRepository<SubscriberExport, String> {
}
