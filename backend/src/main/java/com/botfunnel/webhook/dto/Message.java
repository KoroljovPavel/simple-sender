package com.botfunnel.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Telegram message payload. {@code from} is nullable — channel posts carry no author.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Message(
        Long message_id,
        User from,
        Chat chat,
        Long date,
        String text) {
}
