package com.botfunnel.subscriber;

import java.util.Map;
import java.util.Optional;

/**
 * Subscriber CRM lifecycle contract (Epic 05). The single implementation is
 * {@code SubscriberServiceImpl} — the SOLE writer for CRM lifecycle events to the
 * {@code subscriber_events} collection (Decision 10), including {@code subscriber_custom_field_set},
 * {@code subscriber_tag_added}, and {@code subscriber_tag_removed}. Controllers (Task 5 custom-fields
 * PATCH, Task 8 tag assign/unassign) MUST route audit events through these methods rather than
 * writing to {@code subscriber_events} directly.
 *
 * <p>Consumed by: {@code ProcessTelegramUpdateJob} (webhook upsert + {@code /stop}),
 * {@code TelegramSender} (block/delete hooks — Task 6), {@code SubscriberController} (manual
 * unsubscribe + custom fields — Task 5/8), {@code TagController}/subscriber-tag endpoints (Task 8).
 */
public interface SubscriberService {

    void upsertFromTelegramUpdate(String projectId,
                                  Long telegramBotId,
                                  Long chatId,
                                  String chatType,
                                  Long telegramUserId,
                                  String firstName,
                                  String lastName,
                                  String username,
                                  String languageCode);

    void markUnsubscribed(String projectId, Long telegramBotId, Long chatId);

    /**
     * Telegram 403 hook (Task 6): flips a subscriber to BLOCKED (sets {@code blockedAt}, writes
     * {@code subscriber_blocked}). Idempotent — an already-BLOCKED row is a no-op; a never-registered
     * chat is a silent no-op.
     */
    void markBlockedByChatId(String projectId, Long telegramBotId, Long chatId);

    /**
     * Telegram 400 "chat not found" hook (Task 6): flips a subscriber to DELETED (sets
     * {@code deletedAt}, writes {@code subscriber_deleted}). Idempotent — an already-DELETED row is a
     * no-op; a never-registered chat is a silent no-op.
     */
    void markDeletedByChatId(String projectId, Long telegramBotId, Long chatId);

    /**
     * Manual unsubscribe (Task 8 CRM action): flips an ACTIVE subscriber to UNSUBSCRIBED and writes
     * {@code subscriber_unsubscribed{reason:"manual"}}. {@code projectId} scopes the lookup as
     * anti-IDOR defense-in-depth — a subscriber that belongs to a different project collapses to the
     * same uniform 404 as a missing one (the controller's {@code requireOwned} proves project
     * ownership but not that the path {@code subscriberId} belongs to that project). Throws 409
     * {@code already_unsubscribed} when the subscriber is not ACTIVE (UNSUBSCRIBED/BLOCKED/DELETED).
     */
    void unsubscribeManual(String projectId, String subscriberId);

    /**
     * Decision 10 sole-writer entry point for {@code subscriber_custom_field_set}. Records ONE audit
     * event with metadata {@code {oldValues, newValues, changedKeys}} (changedKeys = symmetric diff of
     * the two maps). Does NOT mutate the subscriber document (Task 5's PATCH owns that); idempotent —
     * an empty diff writes no event.
     */
    void recordCustomFieldsSet(String projectId, String subscriberId,
                               Map<String, Object> oldValues, Map<String, Object> newValues);

    /**
     * Decision 10 sole writer for {@code subscriber_tag_added}. Atomic {@code $addToSet} on the
     * subscriber's tags; only when membership actually changed does it bump {@code Tag.subscriberCount}
     * (delegated to {@code TagService.incrementCounter}, +1) and write the event. Idempotent on an
     * already-present tag.
     */
    void addTag(String projectId, String subscriberId, String slug);

    /**
     * Decision 10 sole writer for {@code subscriber_tag_removed}. Atomic mirror of {@link #addTag}:
     * {@code $pull}, then (only when membership changed) {@code Tag.subscriberCount} -1 and the event.
     * Idempotent on an already-absent tag.
     */
    void removeTag(String projectId, String subscriberId, String slug);

    /**
     * Non-HTTP subscriber lookup by {@code (projectId, telegramBotId, chatId)} for consumers outside a
     * request scope — the funnel engine's {@code SET_CUSTOM_FIELD} trigger/step (Task 6), which has no
     * {@code SecurityContextHolder} or path variables. Delegates to the existing
     * {@code SubscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId}; returns
     * {@link Optional#empty()} when no subscriber matches (engine: log + skip). Does NO anti-IDOR —
     * the caller already operates in a trusted project scope.
     */
    Optional<Subscriber> findByChat(String projectId, Long telegramBotId, Long chatId);
}
