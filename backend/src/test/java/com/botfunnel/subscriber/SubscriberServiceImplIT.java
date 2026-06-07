package com.botfunnel.subscriber;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.common.test.ConcurrencyTestUtils;
import com.botfunnel.funnel.FunnelEventService;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.tag.Tag;
import com.botfunnel.tag.TagRepository;
import com.botfunnel.webhook.ProcessTelegramUpdateJob;
import com.botfunnel.webhook.RawUpdate;
import com.botfunnel.webhook.RawUpdateRepository;
import com.botfunnel.webhook.RawUpdateStatus;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// Real Spring-wired path. Webhook scenarios go through processTelegramUpdateJob.handle(rawUpdateId)
// (synchronous on the calling thread, mirroring ProcessTelegramUpdateJobTest). The Decision 10 writer
// methods (recordCustomFieldsSet / addTag / removeTag) are invoked directly via the autowired bean.
class SubscriberServiceImplIT extends AbstractIntegrationTest {

    private static final String OWNER_ID = "owner-subimpl";
    private static final Long TELEGRAM_BOT_ID = 7000001L;

    @Autowired RawUpdateRepository rawUpdateRepository;
    @Autowired BotRepository botRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired SubscriberEventRepository subscriberEventRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ProcessTelegramUpdateJob job;
    @Autowired SubscriberService subscriberService;

    // Mock the dispatcher so the tag/field trigger side effects can be asserted as a unit-of-dispatch
    // (call args + depth) without standing up matching funnels — the real fan-out is covered separately
    // in the engine IT. A @MockitoBean also keeps the wired SubscriberServiceImpl ↔ FunnelEventService
    // edge in play (the context still boots with the bean injected → the bean-cycle close is proven).
    @MockitoBean FunnelEventService funnelEventService;

    private String projectId;

    @BeforeEach
    void cleanAndSeed() {
        rawUpdateRepository.deleteAll();
        botRepository.deleteAll();
        projectRepository.deleteAll();
        subscriberRepository.deleteAll();
        subscriberEventRepository.deleteAll();
        tagRepository.deleteAll();

        Project p = new Project();
        p.setOwnerId(OWNER_ID);
        p.setName("Sub Impl Project");
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectId = projectRepository.save(p).getId();

        Bot bot = new Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setStatus(BotStatus.CONNECTED);
        bot.setConnectedAt(Instant.now());
        botRepository.save(bot);
    }

    // ─── auto-registration via webhook ──────────────────────────────────────

    @Test
    void firstStart_persistsActiveSubscriberAndEvent() {
        handle(seedStart(300L, 1L));

        Subscriber s = subscriberRepository.findByProjectIdAndTelegramUserId(projectId, 300L).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SubscriberStatus.ACTIVE);
        assertThat(s.getTelegramChatId()).isEqualTo(300L);
        assertThat(s.getTelegramBotId()).isEqualTo(TELEGRAM_BOT_ID);
        assertThat(s.getSubscribedAt()).isNotNull();
        assertThat(s.getTags()).isEmpty();

        List<SubscriberEvent> events = registeredEventsFor(s.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("subscriber_registered");
    }

    @Test
    void restartByActiveSubscriber_updatesOnlyLastSeenAt() {
        handle(seedStart(300L, 1L));
        Subscriber afterFirst = subscriberRepository.findByProjectIdAndTelegramUserId(projectId, 300L).orElseThrow();

        handle(seedStart(300L, 2L));

        // Same single subscriber, still ACTIVE, and exactly ONE lifecycle event (the registration) —
        // a re-/start by an active subscriber writes no new event.
        assertThat(subscriberRepository.findByProjectIdAndTelegramUserId(projectId, 300L).orElseThrow().getStatus())
                .isEqualTo(SubscriberStatus.ACTIVE);
        assertThat(eventsFor(afterFirst.getId())).hasSize(1);
        assertThat(eventsFor(afterFirst.getId()).get(0).getEventType()).isEqualTo("subscriber_registered");
        assertThat(subscriberRepository.count()).isEqualTo(1);
    }

    @Test
    void startAfterBlocked_reactivatesAndPreservesTagsAndCustomFields() {
        Instant subscribedAt = Instant.parse("2026-01-01T00:00:00Z");
        Subscriber blocked = new Subscriber();
        blocked.setProjectId(projectId);
        blocked.setTelegramUserId(300L);
        blocked.setTelegramChatId(300L);
        blocked.setTelegramBotId(TELEGRAM_BOT_ID);
        blocked.setStatus(SubscriberStatus.BLOCKED);
        blocked.setSubscribedAt(subscribedAt);
        blocked.setBlockedAt(Instant.parse("2026-03-01T00:00:00Z"));
        blocked.setLastSeenAt(subscribedAt);
        blocked.setTags(List.of("vip"));
        blocked.setCustomFields(Map.of("plan", "gold"));
        String subId = subscriberRepository.save(blocked).getId();

        handle(seedStart(300L, 1L));

        Subscriber reread = subscriberRepository.findById(subId).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(SubscriberStatus.ACTIVE);
        assertThat(reread.getSubscribedAt()).isEqualTo(subscribedAt);
        assertThat(reread.getBlockedAt()).isNull();
        assertThat(reread.getTags()).containsExactly("vip");
        assertThat(reread.getCustomFields()).containsEntry("plan", "gold");

        List<SubscriberEvent> events = eventsFor(subId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("subscriber_reactivated");
        assertThat(events.get(0).getMetadata()).containsEntry("previousStatus", "BLOCKED");
    }

    @Test
    void duplicateKeyException_onInsertRace_recoversAndUpdates() {
        // Two workers process /start for the SAME telegramUserId concurrently. One insert wins; the
        // loser catches DuplicateKeyException, re-reads, and applies refresh — no exception leaks, one row.
        ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<>(
                List.of(seedStart(300L, 1L), seedStart(300L, 2L)));

        ConcurrencyTestUtils.parallelInvoke(2, () -> {
            job.handle(ids.poll());
            return null;
        });

        // Exactly one surviving ACTIVE row (the recovery applied refresh, not a second insert)…
        List<Subscriber> rows = subscriberRepository.findAll().stream()
                .filter(s -> 300L == s.getTelegramUserId())
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(SubscriberStatus.ACTIVE);
        // …and the registration event fired exactly once (no double-register from the racing worker).
        assertThat(registeredEventsFor(rows.get(0).getId())).hasSize(1);
    }

    // ─── Decision 10 writers (direct invocation) ────────────────────────────

    @Test
    void recordCustomFieldsSet_writesOneEventWithChangedKeys() {
        Subscriber s = seedActiveSubscriber(400L);

        subscriberService.recordCustomFieldsSet(projectId, s.getId(),
                Map.of("a", 1), Map.of("a", 2, "b", 3), 0);

        List<SubscriberEvent> events = subscriberEventRepository.findAll().stream()
                .filter(e -> "subscriber_custom_field_set".equals(e.getEventType()))
                .toList();
        assertThat(events).hasSize(1);
        Map<String, Object> meta = events.get(0).getMetadata();
        assertThat(meta).containsEntry("oldValues", Map.of("a", 1));
        assertThat(meta).containsEntry("newValues", Map.of("a", 2, "b", 3));
        assertThat(meta.get("changedKeys")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void addTag_atomicallyBumpsTagSubscriberCountAndWritesEvent() {
        Subscriber s = seedActiveSubscriber(400L);
        seedTag("vip", 0L);

        subscriberService.addTag(projectId, s.getId(), "vip", 0);

        assertThat(subscriberRepository.findById(s.getId()).orElseThrow().getTags()).contains("vip");
        assertThat(tagRepository.findByProjectIdAndSlug(projectId, "vip").orElseThrow().getSubscriberCount())
                .isEqualTo(1L);
        List<SubscriberEvent> events = tagEventsFor(s.getId(), "subscriber_tag_added");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getMetadata()).containsEntry("slug", "vip");
    }

    // ─── Phase 3 trigger side effects (Task 5, Decision 5/6) ─────────────────

    @Test
    void addTag_newTag_firesTagAddedTrigger() {
        // A real tag add (membership changed) fires tag_added(slug) via the dispatcher, after the audit
        // write, with the explicit originDepth (0 here = manual/root). Pins the 4-arg addTag writer.
        Subscriber s = seedActiveSubscriber(400L);
        seedTag("vip", 0L);

        subscriberService.addTag(projectId, s.getId(), "vip", 0);

        verify(funnelEventService).dispatchForSubscriber(projectId, s.getId(),
                FunnelEventService.TRIGGER_TAG_ADDED, "vip", 0);
    }

    @Test
    void addTag_alreadyPresent_doesNotFire() {
        // Idempotent no-op (tag already on the subscriber) → no dispatch, no audit (existing behavior).
        Subscriber s = seedActiveSubscriber(400L);
        s.setTags(new java.util.ArrayList<>(List.of("vip")));
        subscriberRepository.save(s);
        seedTag("vip", 1L);

        subscriberService.addTag(projectId, s.getId(), "vip", 0);

        assertThat(tagEventsFor(s.getId(), "subscriber_tag_added")).isEmpty();
        verify(funnelEventService, never())
                .dispatchForSubscriber(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void addTag_funnelStep_depthIsParentPlusOne() {
        // The ADD_TAG funnel step passes execution.getEnrollDepth() + 1; the writer forwards that
        // explicit depth verbatim (here a parent-1 child → depth 2).
        Subscriber s = seedActiveSubscriber(400L);
        seedTag("vip", 0L);

        subscriberService.addTag(projectId, s.getId(), "vip", 2);

        verify(funnelEventService).dispatchForSubscriber(projectId, s.getId(),
                FunnelEventService.TRIGGER_TAG_ADDED, "vip", 2);
    }

    @Test
    void recordCustomFieldsSet_changedKeys_firesPerKey() {
        // N changed keys → N custom_field_set dispatches, one per key, each at the explicit originDepth.
        Subscriber s = seedActiveSubscriber(400L);

        subscriberService.recordCustomFieldsSet(projectId, s.getId(),
                Map.of("a", 1), Map.of("a", 2, "b", 3), 0);

        verify(funnelEventService).dispatchForSubscriber(projectId, s.getId(),
                FunnelEventService.TRIGGER_CUSTOM_FIELD_SET, "a", 0);
        verify(funnelEventService).dispatchForSubscriber(projectId, s.getId(),
                FunnelEventService.TRIGGER_CUSTOM_FIELD_SET, "b", 0);
        verify(funnelEventService, times(2)).dispatchForSubscriber(anyString(), anyString(),
                eq(FunnelEventService.TRIGGER_CUSTOM_FIELD_SET), anyString(), anyInt());
    }

    @Test
    void recordCustomFieldsSet_emptyDiff_doesNotFire() {
        // Empty changedKeys → no dispatch (and no audit, existing behavior).
        Subscriber s = seedActiveSubscriber(400L);

        subscriberService.recordCustomFieldsSet(projectId, s.getId(),
                Map.of("a", 1), Map.of("a", 1), 0);

        verify(funnelEventService, never())
                .dispatchForSubscriber(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void recordCustomFieldsSet_passesExplicitOriginDepth() {
        // The writer forwards the explicit originDepth (here a funnel-step child = enrollDepth + 1 = 4)
        // to each per-key custom_field_set dispatch.
        Subscriber s = seedActiveSubscriber(400L);

        subscriberService.recordCustomFieldsSet(projectId, s.getId(),
                Map.of("a", 1), Map.of("a", 2), 4);

        verify(funnelEventService).dispatchForSubscriber(projectId, s.getId(),
                FunnelEventService.TRIGGER_CUSTOM_FIELD_SET, "a", 4);
    }

    @Test
    void removeTag_atomicallyDecrementsTagSubscriberCountAndWritesEvent() {
        Subscriber s = seedActiveSubscriber(400L);
        s.setTags(new java.util.ArrayList<>(List.of("vip")));
        subscriberRepository.save(s);
        seedTag("vip", 1L);

        subscriberService.removeTag(projectId, s.getId(), "vip");

        assertThat(subscriberRepository.findById(s.getId()).orElseThrow().getTags()).doesNotContain("vip");
        assertThat(tagRepository.findByProjectIdAndSlug(projectId, "vip").orElseThrow().getSubscriberCount())
                .isEqualTo(0L);
        List<SubscriberEvent> events = tagEventsFor(s.getId(), "subscriber_tag_removed");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getMetadata()).containsEntry("slug", "vip");
    }

    // ─── lookup-by-chat (Task 4) ─────────────────────────────────────────────

    @Test
    void lookupByChat_delegatesToRepository() {
        Subscriber s = seedActiveSubscriber(400L);

        // Hit: returns the row the repository resolves for (projectId, telegramBotId, chatId).
        java.util.Optional<Subscriber> found =
                subscriberService.findByChat(projectId, TELEGRAM_BOT_ID, 400L);
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getId()).isEqualTo(s.getId());

        // Miss: an unknown chatId mirrors the repository's Optional.empty().
        assertThat(subscriberService.findByChat(projectId, TELEGRAM_BOT_ID, 999999L)).isEmpty();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void handle(String rawUpdateId) {
        job.handle(rawUpdateId);
    }

    private String seedStart(Long fromId, long updateId) {
        RawUpdate raw = new RawUpdate();
        raw.setProjectId(projectId);
        raw.setUpdateId(updateId);
        raw.setPayload(privateStartPayload(fromId, fromId));
        raw.setProcessingStatus(RawUpdateStatus.PENDING);
        raw.setCreatedAt(Instant.now());
        return rawUpdateRepository.save(raw).getId();
    }

    private Subscriber seedActiveSubscriber(Long telegramUserId) {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(telegramUserId);
        s.setTelegramChatId(telegramUserId);
        s.setTelegramBotId(TELEGRAM_BOT_ID);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        s.setTags(new java.util.ArrayList<>());
        s.setCustomFields(new java.util.HashMap<>());
        return subscriberRepository.save(s);
    }

    private void seedTag(String slug, long count) {
        Tag tag = new Tag();
        tag.setProjectId(projectId);
        tag.setSlug(slug);
        tag.setLabel(slug);
        tag.setSubscriberCount(count);
        tag.setCreatedAt(Instant.now());
        tagRepository.save(tag);
    }

    private Document privateStartPayload(Long chatId, Long fromId) {
        Document chat = new Document().append("id", chatId).append("type", "private");
        Document message = new Document()
                .append("message_id", 1L)
                .append("chat", chat)
                .append("date", 1700000000L)
                .append("text", "/start");
        message.append("from", new Document()
                .append("id", fromId)
                .append("is_bot", false)
                .append("first_name", "Test")
                .append("last_name", "User")
                .append("username", "testuser")
                .append("language_code", "en"));
        return new Document().append("update_id", 1L).append("message", message);
    }

    private List<SubscriberEvent> eventsFor(String subscriberId) {
        return subscriberEventRepository.findAll().stream()
                .filter(e -> subscriberId.equals(e.getSubscriberId()))
                .toList();
    }

    private List<SubscriberEvent> registeredEventsFor(String subscriberId) {
        return eventsFor(subscriberId).stream()
                .filter(e -> "subscriber_registered".equals(e.getEventType()))
                .toList();
    }

    private List<SubscriberEvent> tagEventsFor(String subscriberId, String eventType) {
        return eventsFor(subscriberId).stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .toList();
    }
}
