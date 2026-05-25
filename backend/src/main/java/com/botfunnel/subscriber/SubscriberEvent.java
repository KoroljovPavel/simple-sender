package com.botfunnel.subscriber;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

// Indexes auto-created via spring.data.mongodb.auto-index-creation=true (dev).
// Sole writer for CRM lifecycle events (Decision 10) — distinct from the platform-wide `events`
// collection. 365d TTL on createdAt keeps the profile history feed bounded; the (subscriberId,
// createdAt desc) compound serves that feed.
@Document(collection = "subscriber_events")
@CompoundIndexes({
        @CompoundIndex(name = "subscriber_recent",
                def = "{'subscriberId': 1, 'createdAt': -1}")
})
public class SubscriberEvent {

    @Id
    private String id;
    private String subscriberId;
    private String projectId;
    private String eventType;        // see Decision 10 enumeration
    private Map<String, Object> metadata;

    @Indexed(name = "ttl_createdAt", expireAfter = "365d")
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSubscriberId() { return subscriberId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
