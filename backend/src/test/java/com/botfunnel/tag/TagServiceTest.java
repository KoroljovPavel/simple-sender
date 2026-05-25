package com.botfunnel.tag;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.common.test.ConcurrencyTestUtils;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberRepository;
import com.botfunnel.subscriber.SubscriberStatus;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Real-Mongo service test (extends AbstractIntegrationTest): the atomic $inc, the bulk $pull
// cascade, the tag_deleted event, and the "no updatedAt field" BSON invariant all need a live
// driver. The find-or-create race is exercised for real via ConcurrencyTestUtils.parallelInvoke.
class TagServiceTest extends AbstractIntegrationTest {

    private static final String PROJECT_ID = "proj-tag-svc";
    private static final String USER_ID = "owner-tag-svc";

    @Autowired TagService tagService;
    @Autowired TagRepository tagRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired EventRepository eventRepository;
    @Autowired MongoTemplate mongoTemplate;

    @BeforeEach
    void clean() {
        tagRepository.deleteAll();
        subscriberRepository.deleteAll();
        eventRepository.deleteAll();
    }

    // ─── findOrCreate ────────────────────────────────────────────────────────

    @Test
    void findOrCreate_existingTag_returnsExisting() {
        Tag existing = seedTag("vip", 7L);

        Tag result = tagService.findOrCreate(PROJECT_ID, "vip");

        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(tagRepository.findByProjectIdOrderBySlugAsc(PROJECT_ID)).hasSize(1);
    }

    @Test
    void findOrCreate_newTag_createsAndReturns() {
        Tag result = tagService.findOrCreate(PROJECT_ID, "vip");

        assertThat(result.getId()).isNotNull();
        assertThat(result.getSlug()).isEqualTo("vip");
        assertThat(result.getSubscriberCount()).isZero();
        assertThat(tagRepository.findByProjectIdAndSlug(PROJECT_ID, "vip")).isPresent();
    }

    @Test
    void findOrCreate_raceDuplicateKey_recoversAndReturnsExisting() {
        // Eight concurrent callers race on the unique (projectId, slug) index. The losers catch
        // DuplicateKeyException, re-read, and return the winner's row. Invariant holds even if the
        // scheduler happens to serialize them: exactly one row, every caller sees the same id.
        List<Tag> results = ConcurrencyTestUtils.parallelInvoke(8,
                () -> tagService.findOrCreate(PROJECT_ID, "vip"));

        List<Tag> rows = tagRepository.findByProjectIdOrderBySlugAsc(PROJECT_ID);
        assertThat(rows).hasSize(1);
        String onlyId = rows.get(0).getId();
        assertThat(results).allSatisfy(t -> assertThat(t.getId()).isEqualTo(onlyId));
    }

    // ─── create ──────────────────────────────────────────────────────────────

    @Test
    void create_duplicateSlug_throws409TagNameTaken() {
        tagService.create(PROJECT_ID, "vip", "VIP");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tagService.create(PROJECT_ID, "vip", "VIP again"))
                .isInstanceOfSatisfying(com.botfunnel.common.AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("tag_name_taken");
                });
    }

    // ─── incrementCounter ──────────────────────────────────────────────────

    @Test
    void incrementCounter_atomicallyIncrements() {
        seedTag("vip", 5L);

        tagService.incrementCounter(PROJECT_ID, "vip", 1);
        assertThat(reread("vip").getSubscriberCount()).isEqualTo(6L);

        tagService.incrementCounter(PROJECT_ID, "vip", -1);
        assertThat(reread("vip").getSubscriberCount()).isEqualTo(5L);
    }

    // ─── deleteWithCascade ───────────────────────────────────────────────────

    @Test
    void deleteWithCascade_pullsFromSubscribersAndDeletes() {
        seedTag("vip", 3L);
        seedSubscriber(1001L, List.of("vip", "other"));
        seedSubscriber(1002L, List.of("vip"));
        seedSubscriber(1003L, List.of("vip", "course"));

        tagService.deleteWithCascade(USER_ID, PROJECT_ID, "vip");

        assertThat(tagRepository.findByProjectIdAndSlug(PROJECT_ID, "vip")).isEmpty();
        assertThat(subscriberRepository.findAll())
                .as("vip pulled from every subscriber")
                .allSatisfy(s -> assertThat(s.getTags()).doesNotContain("vip"));

        List<Event> deleted = eventRepository.findAll().stream()
                .filter(e -> "tag_deleted".equals(e.getEventType()))
                .toList();
        assertThat(deleted).hasSize(1);
        assertThat(deleted.get(0).getUserId()).isEqualTo(USER_ID);
        assertThat(deleted.get(0).getMetadata())
                .containsEntry("tagSlug", "vip")
                .containsEntry("projectId", PROJECT_ID)
                .containsEntry("subscriberCountAtDelete", 3L);
    }

    // ─── updateLabel ───────────────────────────────────────────────────────

    @Test
    void updateLabel_doesNotSetUpdatedAt() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Tag tag = new Tag();
        tag.setProjectId(PROJECT_ID);
        tag.setSlug("vip");
        tag.setLabel("old");
        tag.setSubscriberCount(0L);
        tag.setCreatedAt(t0);
        mongoTemplate.save(tag);

        tagService.updateLabel(PROJECT_ID, "vip", "new");

        Document raw = mongoTemplate.findOne(
                Query.query(Criteria.where("projectId").is(PROJECT_ID).and("slug").is("vip")),
                Document.class, "tags");
        assertThat(raw).isNotNull();
        assertThat(raw.getString("label")).isEqualTo("new");
        assertThat(raw.containsKey("updatedAt"))
                .as("Tag has no updatedAt field — updateLabel must not write one")
                .isFalse();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Tag seedTag(String slug, long subscriberCount) {
        Tag tag = new Tag();
        tag.setProjectId(PROJECT_ID);
        tag.setSlug(slug);
        tag.setLabel(slug.toUpperCase());
        tag.setSubscriberCount(subscriberCount);
        tag.setCreatedAt(Instant.now());
        return mongoTemplate.save(tag);
    }

    private Tag reread(String slug) {
        return tagRepository.findByProjectIdAndSlug(PROJECT_ID, slug).orElseThrow();
    }

    private void seedSubscriber(long telegramUserId, List<String> tags) {
        Subscriber s = new Subscriber();
        s.setProjectId(PROJECT_ID);
        s.setTelegramUserId(telegramUserId);
        s.setTelegramChatId(telegramUserId);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setTags(new java.util.ArrayList<>(tags));
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        subscriberRepository.save(s);
    }
}
