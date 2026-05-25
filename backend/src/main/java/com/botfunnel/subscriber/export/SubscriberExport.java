package com.botfunnel.subscriber.export;

import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

import java.time.Instant;

// Indexes auto-created via spring.data.mongodb.auto-index-creation=true (dev).
// The partial-unique compound on projectId (filtered to in-flight statuses) enforces at most one
// PENDING/RUNNING export per project at the DB level (Decision 2) — concurrent POST surfaces as
// DuplicateKeyException, mapped to 409 export_in_flight by the export controller (Task 9).
@org.springframework.data.mongodb.core.mapping.Document(collection = "subscriber_exports")
@CompoundIndexes({
        @CompoundIndex(name = "exports_in_flight_unique",
                def = "{'projectId': 1}", unique = true,
                partialFilter = "{ 'status': { $in: ['PENDING', 'RUNNING'] } }"),
        @CompoundIndex(name = "project_createdAt_desc",
                def = "{'projectId': 1, 'createdAt': -1}")
})
public class SubscriberExport {

    // Defensive class-load assertion: the partial-filter literals must stay byte-identical with
    // ExportStatus.name() — Spring Data persists the enum via name(), and a silent rename would
    // void the in-flight uniqueness guard (the index would match zero rows). Mirrors
    // RawUpdate.java:27-37 / Bot.java:31-40.
    static {
        if (!"PENDING".equals(ExportStatus.PENDING.name())
                || !"RUNNING".equals(ExportStatus.RUNNING.name())
                || !"DONE".equals(ExportStatus.DONE.name())
                || !"FAILED".equals(ExportStatus.FAILED.name())
                || !"PURGED".equals(ExportStatus.PURGED.name())) {
            throw new IllegalStateException(
                    "ExportStatus name() drifted from partial-filter literals: "
                            + ExportStatus.PENDING.name() + "/" + ExportStatus.RUNNING.name() + "/"
                            + ExportStatus.DONE.name() + "/" + ExportStatus.FAILED.name() + "/"
                            + ExportStatus.PURGED.name());
        }
    }

    @Id
    private String id;
    private String projectId;
    private String ownerId;
    private ExportStatus status;     // PENDING | RUNNING | DONE | FAILED | PURGED
    private Document filter;          // serialized SegmentFilter
    private String fileId;            // GridFS file id, populated on DONE
    private Long rowCount;            // populated on DONE
    private String errorMessage;      // populated on FAILED; scrubbed + truncated to 1024 chars
    private Instant createdAt;
    private Instant completedAt;
    private Instant expiresAt;        // populated on DONE = completedAt + URL_TTL_HOURS (Decision 15)

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public ExportStatus getStatus() { return status; }
    public void setStatus(ExportStatus status) { this.status = status; }

    public Document getFilter() { return filter; }
    public void setFilter(Document filter) { this.filter = filter; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
