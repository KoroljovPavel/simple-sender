package com.botfunnel.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Inbound Telegram webhook update envelope. snake_case record components map natively to the
 * Telegram Bot API JSON shape (Jackson 2.12+ record support — no {@code @JsonProperty}
 * required). {@code callback_query} is a typed {@link CallbackQuery} — Phase 2 routes it to the
 * funnel engine. The remaining non-message update kinds are kept as opaque {@link JsonNode}
 * slots — the worker only needs to DETECT their presence to resolve {@code metadata.updateKind}
 * per user-spec AC11; their concrete schemas belong to later epics (05 / 06).
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown=true)} provides mass-assignment defense:
 * any future Telegram field absent from the record is silently dropped at deserialisation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(
        Long update_id,
        Message message,
        Message edited_message,
        Message channel_post,
        Message edited_channel_post,
        CallbackQuery callback_query,
        JsonNode my_chat_member,
        JsonNode chat_member,
        JsonNode inline_query,
        JsonNode shipping_query,
        JsonNode pre_checkout_query,
        JsonNode poll_answer) {
}
