package com.botfunnel.subscriber;

import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.tag.TagService;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Pure unit test of the SubscriberServiceImpl state machine + Decision 10 writers. No Spring: mocked
// repositories / TagService / EventService, a fixed Clock for deterministic timestamp assertions, and
// a deep-stub MongoTemplate so the fluent addTag/removeTag update chain can be stubbed. LENIENT so the
// shared mocks across many scenarios don't trip strict-stub on per-test setup.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriberStatusMachineTest {

    private static final String PROJECT_ID = "proj-1";
    private static final Long BOT_ID = 555L;
    private static final Long CHAT_ID = 100L;
    private static final Instant NOW = Instant.parse("2026-05-25T12:00:00Z");

    @Mock SubscriberRepository subscriberRepository;
    @Mock SubscriberEventRepository subscriberEventRepository;
    @Mock TagService tagService;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) MongoTemplate mongoTemplate;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    @Mock EventService eventService;

    SubscriberServiceImpl service;

    @BeforeEach
    void initService() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new SubscriberServiceImpl(subscriberRepository, subscriberEventRepository, tagService,
                mongoTemplate, redisTemplate, eventService, fixed, 100);
    }

    // ─── /stop and manual unsubscribe ──────────────────────────────────────

    @Test
    void activeToUnsubscribed_writesEventAndTimestamp() {
        when(subscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId(PROJECT_ID, BOT_ID, CHAT_ID))
                .thenReturn(Optional.of(subscriber("sub-1", SubscriberStatus.ACTIVE)));

        service.markUnsubscribed(PROJECT_ID, BOT_ID, CHAT_ID);

        Document set = capturedSet();
        assertThat(set.get("status")).isEqualTo(SubscriberStatus.UNSUBSCRIBED);
        assertThat(set.get("unsubscribedAt")).isEqualTo(NOW);

        SubscriberEvent event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo("subscriber_unsubscribed");
        assertThat(event.getSubscriberId()).isEqualTo("sub-1");
        assertThat(event.getCreatedAt()).isEqualTo(NOW);
        assertThat(event.getMetadata()).containsEntry("reason", "command_stop");
    }

    @Test
    void markUnsubscribed_onAlreadyUnsubscribed_isNoop() {
        when(subscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId(PROJECT_ID, BOT_ID, CHAT_ID))
                .thenReturn(Optional.of(subscriber("sub-1", SubscriberStatus.UNSUBSCRIBED)));

        service.markUnsubscribed(PROJECT_ID, BOT_ID, CHAT_ID);

        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(UpdateDefinition.class), eq(Subscriber.class));
        verify(subscriberEventRepository, never()).save(any());
    }

    @Test
    void unsubscribeManual_onAlreadyUnsubscribed_throws409() {
        when(subscriberRepository.findById("sub-1"))
                .thenReturn(Optional.of(subscriber("sub-1", SubscriberStatus.UNSUBSCRIBED)));

        assertThatThrownBy(() -> service.unsubscribeManual(PROJECT_ID, "sub-1"))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("already_unsubscribed");
                });
        verify(subscriberEventRepository, never()).save(any());
    }

    @Test
    void unsubscribeManual_onBlockedOrDeleted_throws409() {
        when(subscriberRepository.findById("blocked"))
                .thenReturn(Optional.of(subscriber("blocked", SubscriberStatus.BLOCKED)));
        when(subscriberRepository.findById("deleted"))
                .thenReturn(Optional.of(subscriber("deleted", SubscriberStatus.DELETED)));

        for (String id : List.of("blocked", "deleted")) {
            assertThatThrownBy(() -> service.unsubscribeManual(PROJECT_ID, id))
                    .isInstanceOfSatisfying(AppException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo("already_unsubscribed");
                    });
        }
    }

    @Test
    void unsubscribeManual_foreignProject_throws404() {
        // Anti-IDOR: subscriber exists but belongs to a different project → uniform 404, no mutation.
        when(subscriberRepository.findById("sub-1"))
                .thenReturn(Optional.of(subscriber("sub-1", SubscriberStatus.ACTIVE)));

        assertThatThrownBy(() -> service.unsubscribeManual("other-project", "sub-1"))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(subscriberEventRepository, never()).save(any());
    }

    @Test
    void unsubscribeManual_onActive_flipsAndWritesManualReason() {
        when(subscriberRepository.findById("sub-1"))
                .thenReturn(Optional.of(subscriber("sub-1", SubscriberStatus.ACTIVE)));

        service.unsubscribeManual(PROJECT_ID, "sub-1");

        assertThat(capturedSet().get("status")).isEqualTo(SubscriberStatus.UNSUBSCRIBED);
        assertThat(capturedEvent().getMetadata()).containsEntry("reason", "manual");
    }

    // ─── reactivation on /start ────────────────────────────────────────────

    @Test
    void unsubscribedToActive_onStart_preservesSubscribedAt() {
        Instant subscribedAt = Instant.parse("2026-01-01T00:00:00Z");
        Subscriber existing = subscriber("sub-1", SubscriberStatus.UNSUBSCRIBED);
        existing.setSubscribedAt(subscribedAt);
        existing.setTags(List.of("vip"));
        existing.setCustomFields(Map.of("plan", "gold"));
        when(subscriberRepository.findByProjectIdAndTelegramUserId(PROJECT_ID, 300L))
                .thenReturn(Optional.of(existing));

        service.upsertFromTelegramUpdate(PROJECT_ID, BOT_ID, CHAT_ID, "private", 300L,
                "Alice", "L", "alice", "en");

        Document set = capturedSet();
        assertThat(set.get("status")).isEqualTo(SubscriberStatus.ACTIVE);
        assertThat(set)
                .as("reactivation must NOT overwrite subscribedAt / tags / customFields")
                .doesNotContainKeys("subscribedAt", "tags", "customFields");
        Document unset = capturedUnset();
        assertThat(unset).containsKeys("unsubscribedAt", "blockedAt", "deletedAt");

        SubscriberEvent event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo("subscriber_reactivated");
        assertThat(event.getMetadata()).containsEntry("previousStatus", "UNSUBSCRIBED");
    }

    @Test
    void upsertFromTelegramUpdate_onDuplicateKeyRace_reReadsAndRefreshes() {
        // Deterministically exercise the DuplicateKeyException catch: first lookup misses, the insert
        // loses the unique-index race, the re-read returns the winner's ACTIVE row → refresh path.
        Subscriber winner = subscriber("sub-1", SubscriberStatus.ACTIVE);
        when(subscriberRepository.findByProjectIdAndTelegramUserId(PROJECT_ID, 300L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(subscriberRepository.insert(any(Subscriber.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key"));

        service.upsertFromTelegramUpdate(PROJECT_ID, BOT_ID, CHAT_ID, "private", 300L,
                "Alice", "L", "alice", "en");

        // Recovery applied the refresh (exactly one findAndModify), and wrote NO lifecycle event
        // (the winner row was already ACTIVE).
        verify(mongoTemplate).findAndModify(any(Query.class), any(UpdateDefinition.class), eq(Subscriber.class));
        verify(subscriberEventRepository, never()).save(any());
    }

    @Test
    void reactivation_fromBlocked_clearsBlockedAt() {
        Subscriber existing = subscriber("sub-1", SubscriberStatus.BLOCKED);
        existing.setBlockedAt(Instant.parse("2026-04-01T00:00:00Z"));
        when(subscriberRepository.findByProjectIdAndTelegramUserId(PROJECT_ID, 300L))
                .thenReturn(Optional.of(existing));

        service.upsertFromTelegramUpdate(PROJECT_ID, BOT_ID, CHAT_ID, "private", 300L,
                "Bob", null, null, null);

        assertThat(capturedSet().get("status")).isEqualTo(SubscriberStatus.ACTIVE);
        assertThat(capturedUnset()).containsKey("blockedAt");
        assertThat(capturedEvent().getMetadata()).containsEntry("previousStatus", "BLOCKED");
    }

    // ─── block / delete idempotency ────────────────────────────────────────

    @Test
    void markBlocked_onAlreadyBlocked_isNoop() {
        when(subscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId(PROJECT_ID, BOT_ID, CHAT_ID))
                .thenReturn(Optional.of(subscriber("sub-1", SubscriberStatus.BLOCKED)));

        service.markBlockedByChatId(PROJECT_ID, BOT_ID, CHAT_ID);

        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(UpdateDefinition.class), eq(Subscriber.class));
        verify(subscriberEventRepository, never()).save(any());
    }

    @Test
    void markDeleted_onAlreadyDeleted_isNoop() {
        when(subscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId(PROJECT_ID, BOT_ID, CHAT_ID))
                .thenReturn(Optional.of(subscriber("sub-1", SubscriberStatus.DELETED)));

        service.markDeletedByChatId(PROJECT_ID, BOT_ID, CHAT_ID);

        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(UpdateDefinition.class), eq(Subscriber.class));
        verify(subscriberEventRepository, never()).save(any());
    }

    @Test
    void markBlocked_onActive_flipsAndWritesEvent() {
        when(subscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId(PROJECT_ID, BOT_ID, CHAT_ID))
                .thenReturn(Optional.of(subscriber("sub-1", SubscriberStatus.ACTIVE)));

        service.markBlockedByChatId(PROJECT_ID, BOT_ID, CHAT_ID);

        Document set = capturedSet();
        assertThat(set.get("status")).isEqualTo(SubscriberStatus.BLOCKED);
        assertThat(set.get("blockedAt")).isEqualTo(NOW);
        assertThat(capturedEvent().getEventType()).isEqualTo("subscriber_blocked");
    }

    // ─── recordCustomFieldsSet (Decision 10) ────────────────────────────────

    @Test
    void recordCustomFieldsSet_onEmptyDiff_writesNoEvent() {
        Map<String, Object> same = Map.of("a", 1, "b", 2);

        service.recordCustomFieldsSet(PROJECT_ID, "sub-1", new LinkedHashMap<>(same), new LinkedHashMap<>(same));

        verify(subscriberEventRepository, never()).save(any());
    }

    @Test
    void recordCustomFieldsSet_onChangedKeys_writesOneEventWithCorrectMetadata() {
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("a", 1);
        old.put("b", 2);
        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("a", 1);
        fresh.put("b", 3);
        fresh.put("c", 4);

        service.recordCustomFieldsSet(PROJECT_ID, "sub-1", old, fresh);

        SubscriberEvent event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo("subscriber_custom_field_set");
        assertThat(event.getMetadata()).containsEntry("oldValues", old);
        assertThat(event.getMetadata()).containsEntry("newValues", fresh);
        assertThat(event.getMetadata().get("changedKeys")).isEqualTo(List.of("b", "c"));
    }

    // ─── addTag / removeTag (Decision 10) ───────────────────────────────────

    @Test
    void addTag_onAlreadyPresent_isNoop() {
        stubSubscriberUpdate(0L);

        service.addTag(PROJECT_ID, "sub-1", "vip");

        verify(tagService, never()).incrementCounter(anyString(), anyString(), anyLong());
        verify(subscriberEventRepository, never()).save(any());
    }

    @Test
    void addTag_onFirstAdd_writesEventAndIncrementsCounter() {
        stubSubscriberUpdate(1L);

        service.addTag(PROJECT_ID, "sub-1", "vip");

        verify(tagService).incrementCounter(PROJECT_ID, "vip", 1);
        SubscriberEvent event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo("subscriber_tag_added");
        assertThat(event.getMetadata()).containsEntry("slug", "vip");
    }

    @Test
    void removeTag_onAbsent_isNoop() {
        stubSubscriberUpdate(0L);

        service.removeTag(PROJECT_ID, "sub-1", "vip");

        verify(tagService, never()).incrementCounter(anyString(), anyString(), anyLong());
        verify(subscriberEventRepository, never()).save(any());
    }

    @Test
    void removeTag_onPresent_writesEventAndDecrementsCounter() {
        stubSubscriberUpdate(1L);

        service.removeTag(PROJECT_ID, "sub-1", "vip");

        verify(tagService).incrementCounter(PROJECT_ID, "vip", -1);
        SubscriberEvent event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo("subscriber_tag_removed");
        assertThat(event.getMetadata()).containsEntry("slug", "vip");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void stubSubscriberUpdate(long modifiedCount) {
        when(mongoTemplate.update(Subscriber.class)
                .matching(any(Query.class))
                .apply(any(UpdateDefinition.class))
                .first())
                .thenReturn(UpdateResult.acknowledged(1, modifiedCount, null));
    }

    private Document capturedSet() {
        return (Document) capturedUpdate().getUpdateObject().get("$set");
    }

    private Document capturedUnset() {
        return (Document) capturedUpdate().getUpdateObject().get("$unset");
    }

    private UpdateDefinition capturedUpdate() {
        ArgumentCaptor<UpdateDefinition> captor = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate).findAndModify(any(Query.class), captor.capture(), eq(Subscriber.class));
        return captor.getValue();
    }

    private SubscriberEvent capturedEvent() {
        ArgumentCaptor<SubscriberEvent> captor = ArgumentCaptor.forClass(SubscriberEvent.class);
        verify(subscriberEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Subscriber subscriber(String id, SubscriberStatus status) {
        Subscriber s = new Subscriber();
        s.setId(id);
        s.setProjectId(PROJECT_ID);
        s.setTelegramUserId(300L);
        s.setTelegramBotId(BOT_ID);
        s.setTelegramChatId(CHAT_ID);
        s.setStatus(status);
        return s;
    }
}
