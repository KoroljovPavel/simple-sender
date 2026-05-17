package com.botfunnel.webhook;

import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.Instant;

// Indexes auto-created via spring.data.mongodb.auto-index-creation=true (dev).
// Compound (projectId, updateId) is the idempotency key — the controller relies on
// DuplicateKeyException to short-circuit Telegram retries (Decision 4).
// TTL on createdAt uses an UPPERCASE partial filter to match Spring Data MongoDB's
// enum.name() persistence — FAILED rows persist as the audit trail (Decision 8).
@org.springframework.data.mongodb.core.mapping.Document(collection = "raw_updates")
@CompoundIndexes({
        @CompoundIndex(name = "projectId_updateId_unique",
                def = "{'projectId': 1, 'updateId': 1}",
                unique = true)
})
public class RawUpdate {

    // Defensive class-load assertion: the partial-filter literals must stay byte-identical
    // with RawUpdateStatus.name() — Spring Data persists the enum via name(), and a silent
    // enum rename would void the TTL contract (Decision 8 / completeness F5).
    static {
        if (!"PENDING".equals(RawUpdateStatus.PENDING.name())
                || !"DONE".equals(RawUpdateStatus.DONE.name())
                || !"FAILED".equals(RawUpdateStatus.FAILED.name())) {
            throw new IllegalStateException(
                    "RawUpdateStatus name() drifted from partial-filter literals: "
                            + RawUpdateStatus.PENDING.name() + "/"
                            + RawUpdateStatus.DONE.name() + "/"
                            + RawUpdateStatus.FAILED.name());
        }
    }

    @Id
    private String id;

    @Indexed
    private String projectId;

    private Long updateId;

    private Document payload;

    private RawUpdateStatus processingStatus;

    // Scrub + truncate ≤1024 chars handled at worker layer (T9).
    private String processingError;

    @Indexed(name = "ttl_createdAt",
            expireAfter = "90d",
            partialFilter = "{ 'processingStatus': { $in: ['PENDING', 'DONE'] } }")
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public Long getUpdateId() { return updateId; }
    public void setUpdateId(Long updateId) { this.updateId = updateId; }

    public Document getPayload() { return payload; }
    public void setPayload(Document payload) { this.payload = payload; }

    public RawUpdateStatus getProcessingStatus() { return processingStatus; }
    public void setProcessingStatus(RawUpdateStatus processingStatus) { this.processingStatus = processingStatus; }

    public String getProcessingError() { return processingError; }
    public void setProcessingError(String processingError) { this.processingError = processingError; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
