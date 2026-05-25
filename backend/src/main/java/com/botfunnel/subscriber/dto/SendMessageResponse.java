package com.botfunnel.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Personal-message outcome (AC5). {@code status} is {@code sent} (2xx), {@code blocked} (Telegram
 * 403 — subscriber flipped to BLOCKED by the sender hook) or {@code deleted} (400 chat-not-found —
 * flipped to DELETED). {@code message} is a UI-renderable description. A 2xx HTTP response carries
 * the terminal state visibly to the caller; concrete send errors surface as HTTP 4xx/5xx instead.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SendMessageResponse(String status, String message) {
}
