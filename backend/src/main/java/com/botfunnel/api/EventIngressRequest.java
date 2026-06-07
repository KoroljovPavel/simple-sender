package com.botfunnel.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/integrations/v1/events} (Decision 7). snake_case JSON names map to
 * camelCase fields via {@code @JsonProperty}.
 *
 * <p>Validation (all → 400 via {@code MethodArgumentNotValidException}):
 * <ul>
 *   <li>{@code event_name} — required, slug-shaped {@code ^[A-Za-z0-9_-]{1,64}$}.</li>
 *   <li>at least one of {@code telegram_user_id} / {@code subscriber_id} (cross-field {@code @AssertTrue}).</li>
 * </ul>
 * The {@code subscriber_id}-wins precedence is enforced by the controller, not here.
 */
public class EventIngressRequest {

    @JsonProperty("event_name")
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$")
    private String eventName;

    @JsonProperty("telegram_user_id")
    private Long telegramUserId;

    @JsonProperty("subscriber_id")
    private String subscriberId;

    /**
     * Cross-field rule: at least one identifier must be present. A blank {@code subscriber_id} counts as
     * absent so {@code {"subscriber_id":""}} cannot smuggle past the requirement. Maps to a 400 (global
     * error) before any subscriber resolution — a body missing both ids is malformed, not a 404.
     */
    @AssertTrue(message = "at least one of telegram_user_id or subscriber_id is required")
    public boolean isAtLeastOneIdentifierPresent() {
        boolean hasSubscriberId = subscriberId != null && !subscriberId.isBlank();
        return telegramUserId != null || hasSubscriberId;
    }

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public Long getTelegramUserId() { return telegramUserId; }
    public void setTelegramUserId(Long telegramUserId) { this.telegramUserId = telegramUserId; }

    public String getSubscriberId() { return subscriberId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }
}
