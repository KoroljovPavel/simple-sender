package com.botfunnel.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Subscriber list-row / profile projection. {@code status} is the lowercase {@code SubscriberStatus}
 * name (matches the user-spec wire contract, e.g. {@code "active"}). {@code @JsonIgnoreProperties}
 * is a no-op for a response but kept per the epic-wide mass-assignment convention (tech-spec AC).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubscriberResponse(
        String id,
        Long telegramUserId,
        Long telegramChatId,
        Long telegramBotId,
        String firstName,
        String lastName,
        String username,
        String languageCode,
        String status,
        List<String> tags,
        Map<String, Object> customFields,
        Instant subscribedAt,
        Instant unsubscribedAt,
        Instant blockedAt,
        Instant deletedAt,
        Instant lastSeenAt) {
}
