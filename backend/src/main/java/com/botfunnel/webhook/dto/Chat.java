package com.botfunnel.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Telegram chat metadata. {@code type} is one of {@code "private" | "group" | "supergroup" |
 * "channel"}. Modelled as {@link String} (not enum) so an unexpected future value does not
 * fail deserialisation — worker compares as plain string.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Chat(
        Long id,
        String type,
        String title,
        String username) {
}
