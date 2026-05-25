package com.botfunnel.tag;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

// Indexes auto-created via spring.data.mongodb.auto-index-creation=true (dev).
// Unique compound (projectId, slug) scopes tag slugs per project. subscriberCount is denormalized
// and maintained via $inc on tag add/remove (Task 4). No service / controller here — Task 4 owns those.
@Document(collection = "tags")
@CompoundIndexes({
        @CompoundIndex(name = "project_slug_unique",
                def = "{'projectId': 1, 'slug': 1}", unique = true)
})
public class Tag {

    @Id
    private String id;
    private String projectId;
    private String slug;            // immutable; matches ^[a-z0-9_-]{1,32}$
    private String label;           // editable display name
    private long subscriberCount;   // denormalized; updated via $inc on add/remove
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public long getSubscriberCount() { return subscriberCount; }
    public void setSubscriberCount(long subscriberCount) { this.subscriberCount = subscriberCount; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
