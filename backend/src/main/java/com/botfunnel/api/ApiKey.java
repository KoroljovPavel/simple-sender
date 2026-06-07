package com.botfunnel.api;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Per-project public-API credential, stored hash-only (Decision 8).
 *
 * <p>The full plaintext key is NEVER persisted: only its SHA-256 hex hash
 * ({@link com.botfunnel.common.crypto.Sha256Hex#hex(String)}) lives at rest, plus a short
 * non-sensitive {@code keyPrefix} that drives the UI mask ({@code prefix•••}). The plaintext is
 * surfaced exactly once, at (re)generation time, by {@link ApiKeyService}.
 *
 * <p>One primary key per project: {@code projectId} is unique. {@code keyHash} is unique so a
 * (astronomically improbable) hash collision fails hard rather than silently overwriting another
 * project's credential. Indexes are auto-created via
 * {@code spring.data.mongodb.auto-index-creation=true} (dev), consistent with {@code Bot}/{@code Project}.
 */
@Document(collection = "api_keys")
public class ApiKey {

    @Id
    private String id;

    @Indexed(unique = true)
    private String projectId;

    @Indexed(unique = true)
    private String keyHash;

    private String keyPrefix;

    private Instant createdAt;
    private Instant lastUsedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }

    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
