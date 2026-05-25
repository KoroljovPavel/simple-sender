package com.botfunnel.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Personal-message body. Telegram {@code sendMessage} accepts 1..4096 chars; {@code @NotBlank}
 * rejects whitespace-only, {@code @Size} bounds the length (AC15). Unknown body keys are dropped
 * (mass-assignment defense).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SendMessageRequest(
        @NotBlank @Size(min = 1, max = 4096) String text) {
}
