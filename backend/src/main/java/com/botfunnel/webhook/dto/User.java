package com.botfunnel.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Telegram-side user (the {@code message.from} field). Not to be confused with the
 * platform-side {@code com.botfunnel.user.User} — the package boundary disambiguates.
 * {@code is_bot} is boxed {@link Boolean} so a missing field stays {@code null} rather than
 * defaulting to {@code false}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record User(
        Long id,
        Boolean is_bot,
        String first_name,
        String last_name,
        String username,
        String language_code) {
}
