package com.botfunnel.project;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

// Indexes auto-created via spring.data.mongodb.auto-index-creation=true (dev).
// Production index management is out of scope (see decisions / user-spec Constraints).
// Both compound indexes are NON-unique — service-layer enforces uniqueness among
// active projects per owner (Decision 4 / AC-T9).
@Document(collection = "projects")
@CompoundIndexes({
        @CompoundIndex(name = "owner_deleted", def = "{'ownerId': 1, 'deletedAt': 1}"),
        @CompoundIndex(name = "owner_name_deleted", def = "{'ownerId': 1, 'name': 1, 'deletedAt': 1}")
})
public class Project {

    @Id
    private String id;

    @Indexed
    private String ownerId;

    private String name;
    private String description;
    private String timezone;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
