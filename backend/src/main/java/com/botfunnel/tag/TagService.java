package com.botfunnel.tag;

import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.subscriber.Subscriber;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Owns the {@code tags} collection for subscriber CRM (Epic 05). Encapsulates find-or-create
 * (used by Task 8 on tag assignment), the sole atomic {@code Tag.subscriberCount} mutation API
 * ({@link #incrementCounter}), and standalone CRUD with synchronous delete cascade (Decision 17).
 *
 * <p>Writer ownership (Decision 10): the only event this service writes is {@code tag_deleted} to
 * the platform {@code events} collection — a project-scoped administrative event with no
 * {@code subscriberId}. Subscriber↔tag association events ({@code subscriber_tag_added} /
 * {@code subscriber_tag_removed}) are written by {@code SubscriberServiceImpl} (Task 3 / Task 8),
 * not here; this service injects {@link EventService}, never {@code SubscriberEventRepository}.
 */
@Service
public class TagService {

    private static final String EVENT_TAG_DELETED = "tag_deleted";

    private static final String CODE_TAG_NAME_TAKEN = "tag_name_taken";
    private static final String MESSAGE_TAG_NAME_TAKEN = "Tag slug already in use";
    private static final String MESSAGE_TAG_NOT_FOUND = "Tag not found";

    private final TagRepository tagRepository;
    private final MongoTemplate mongoTemplate;
    private final EventService eventService;

    public TagService(TagRepository tagRepository, MongoTemplate mongoTemplate, EventService eventService) {
        this.tagRepository = tagRepository;
        this.mongoTemplate = mongoTemplate;
        this.eventService = eventService;
    }

    /**
     * Idempotent upsert used by Task 8 when assigning a tag to a subscriber. On the unique
     * {@code (projectId, slug)} index race, re-reads and returns the row the winner inserted.
     */
    public Tag findOrCreate(String projectId, String slug) {
        return tagRepository.findByProjectIdAndSlug(projectId, slug)
                .orElseGet(() -> {
                    try {
                        return insert(projectId, slug, null);
                    } catch (DuplicateKeyException race) {
                        // A concurrent caller won the insert — re-read and return the existing row.
                        return tagRepository.findByProjectIdAndSlug(projectId, slug)
                                .orElseThrow(() -> race);
                    }
                });
    }

    /**
     * Standalone create (POST). Maps the {@code (projectId, slug)} unique-index collision to a 409,
     * mirroring the {@code BotService.mapPersistError} precedent — single index, so a bare catch is
     * sufficient (no message-based disambiguation needed).
     */
    public Tag create(String projectId, String slug, String label) {
        try {
            return insert(projectId, slug, label);
        } catch (DuplicateKeyException e) {
            throw AppException.conflict(CODE_TAG_NAME_TAKEN, MESSAGE_TAG_NAME_TAKEN);
        }
    }

    /**
     * Sole atomic {@code Tag.subscriberCount} mutation API. {@code SubscriberServiceImpl.addTag} /
     * {@code removeTag} (Task 3) delegate here with {@code +1} / {@code -1} so Tag entity mutations
     * stay encapsulated in this service (cross-task review iteration 2). Atomic {@code $inc} via
     * {@code findAndModify} — never load-modify-save.
     */
    public void incrementCounter(String projectId, String slug, long delta) {
        mongoTemplate.findAndModify(
                Query.query(Criteria.where("projectId").is(projectId).and("slug").is(slug)),
                new Update().inc("subscriberCount", delta),
                Tag.class);
    }

    public List<Tag> list(String projectId) {
        return tagRepository.findByProjectIdOrderBySlugAsc(projectId);
    }

    /**
     * Label-only update. Atomic {@code findAndModify} sets ONLY {@code label} — never {@code
     * updatedAt} (no such field on the Tag entity; writing one would pollute the BSON schema).
     * Returns the post-update doc; a {@code null} return means no matching tag → 404.
     */
    public Tag updateLabel(String projectId, String slug, String label) {
        Tag updated = mongoTemplate.findAndModify(
                Query.query(Criteria.where("projectId").is(projectId).and("slug").is(slug)),
                new Update().set("label", label),
                FindAndModifyOptions.options().returnNew(true),
                Tag.class);
        if (updated == null) {
            throw AppException.notFound(MESSAGE_TAG_NOT_FOUND);
        }
        return updated;
    }

    /**
     * Synchronous delete cascade (Decision 17). Order matters: bulk-pull the slug from every
     * subscriber FIRST (an orphan tag is safer than orphan subscriber references if we crash
     * mid-cascade), then delete the tag, then write the {@code tag_deleted} audit event to the
     * platform {@code events} collection. Idempotent: a missing tag is a no-op 204 — no cascade,
     * no audit event for something that never existed.
     */
    public void deleteWithCascade(String currentUserId, String projectId, String slug) {
        Tag tag = tagRepository.findByProjectIdAndSlug(projectId, slug).orElse(null);
        if (tag == null) {
            return;
        }
        long subscriberCountAtDelete = tag.getSubscriberCount();
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("projectId").is(projectId).and("tags").is(slug)),
                new Update().pull("tags", slug),
                Subscriber.class);
        tagRepository.delete(tag);
        eventService.logEvent(currentUserId, EVENT_TAG_DELETED, null, null,
                Map.of("tagSlug", slug,
                        "projectId", projectId,
                        "subscriberCountAtDelete", subscriberCountAtDelete));
    }

    private Tag insert(String projectId, String slug, String label) {
        Tag tag = new Tag();
        tag.setProjectId(projectId);
        tag.setSlug(slug);
        tag.setLabel(label);
        tag.setSubscriberCount(0L);
        tag.setCreatedAt(Instant.now());
        // insert (not save) so a duplicate (projectId, slug) surfaces as DuplicateKeyException.
        return tagRepository.insert(tag);
    }
}
