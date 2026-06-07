package com.botfunnel.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Telegram {@code callback_query} update payload — emitted when a subscriber taps an inline
 * keyboard button on a {@code MENU} step (Phase 2). The worker resolves the parked execution
 * from it and advances the chosen branch via {@code FunnelTriggerService.advanceOnCallback}.
 *
 * <ul>
 *   <li>{@code id} — the Telegram callbackQueryId; required by {@code answerCallbackQuery}
 *       (clears the subscriber's spinner) inside {@code advanceOnCallback}.</li>
 *   <li>{@code from} — the tapping user. Modelled for completeness but NOT surfaced into any
 *       event metadata (Decision 9 — no PII: {@code first_name} / {@code username} never logged).</li>
 *   <li>{@code message} — the message carrying the tapped keyboard; the worker reads
 *       {@code message.chat().id()} to resolve the subscriber's {@code chatId}. Nullable —
 *       a callback on an inline-mode message carries no {@code message}/{@code chat}.</li>
 *   <li>{@code data} — the button's {@code callback_data} ({@code "{executionId}:{buttonIndex}"},
 *       Decision 6); strict parsing/validation is the responsibility of {@code advanceOnCallback}.</li>
 * </ul>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown=true)} provides mass-assignment defense — any
 * Telegram field absent from the record is silently dropped at deserialisation, matching the
 * rest of the webhook DTO module.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CallbackQuery(
        String id,
        User from,
        Message message,
        String data) {
}
