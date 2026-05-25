package com.botfunnel.subscriber;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.TextScore;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// Indexes auto-created via spring.data.mongodb.auto-index-creation=true (dev).
// Unique compound (projectId, telegramUserId) guarantees one subscriber per Telegram user per
// project — this is the cross-project isolation boundary (R5). language="none" is deliberate
// (Decision 6): UA + EN + emoji parity with no stemmer divergence — do NOT change to "russian"
// or omit it, as that would silently alter text-query semantics.
@Document(collection = "subscribers", language = "none")
@CompoundIndexes({
        @CompoundIndex(name = "project_telegramUser_unique",
                def = "{'projectId': 1, 'telegramUserId': 1}", unique = true),
        @CompoundIndex(name = "project_lastSeen_desc",
                def = "{'projectId': 1, 'lastSeenAt': -1}"),
        @CompoundIndex(name = "project_subscribedAt_desc",
                def = "{'projectId': 1, 'subscribedAt': -1}"),
        @CompoundIndex(name = "project_status",
                def = "{'projectId': 1, 'status': 1}"),
        @CompoundIndex(name = "project_tags",
                def = "{'projectId': 1, 'tags': 1}")
})
public class Subscriber {

    @Id
    private String id;
    private String projectId;
    private Long telegramUserId;
    private Long telegramChatId;
    private Long telegramBotId;

    @TextIndexed(weight = 3)
    private String firstName;
    @TextIndexed(weight = 3)
    private String lastName;
    @TextIndexed(weight = 5)
    private String username;

    private String languageCode;
    private SubscriberStatus status;          // ACTIVE | UNSUBSCRIBED | BLOCKED | DELETED
    private List<String> tags;                // tag slugs (denormalized for indexed filter)
    private Map<String, Object> customFields; // key = CustomFieldDefinition.name; per-type validated
    private Instant subscribedAt;             // immutable after create
    private Instant unsubscribedAt;
    private Instant blockedAt;
    private Instant deletedAt;
    private Instant lastSeenAt;

    @TextScore
    private Float score;                      // populated only when a search query returns matches

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public Long getTelegramUserId() { return telegramUserId; }
    public void setTelegramUserId(Long telegramUserId) { this.telegramUserId = telegramUserId; }

    public Long getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(Long telegramChatId) { this.telegramChatId = telegramChatId; }

    public Long getTelegramBotId() { return telegramBotId; }
    public void setTelegramBotId(Long telegramBotId) { this.telegramBotId = telegramBotId; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getLanguageCode() { return languageCode; }
    public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }

    public SubscriberStatus getStatus() { return status; }
    public void setStatus(SubscriberStatus status) { this.status = status; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public Map<String, Object> getCustomFields() { return customFields; }
    public void setCustomFields(Map<String, Object> customFields) { this.customFields = customFields; }

    public Instant getSubscribedAt() { return subscribedAt; }
    public void setSubscribedAt(Instant subscribedAt) { this.subscribedAt = subscribedAt; }

    public Instant getUnsubscribedAt() { return unsubscribedAt; }
    public void setUnsubscribedAt(Instant unsubscribedAt) { this.unsubscribedAt = unsubscribedAt; }

    public Instant getBlockedAt() { return blockedAt; }
    public void setBlockedAt(Instant blockedAt) { this.blockedAt = blockedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public Float getScore() { return score; }
    public void setScore(Float score) { this.score = score; }
}
