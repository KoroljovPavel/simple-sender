package com.botfunnel.funnel;

/**
 * Funnel lifecycle state. Decision 14: LOWERCASE constants — Spring Data MongoDB persists the enum
 * via {@code name()}, so the {@code funnels} partial-unique index filter {@code { status: 'active' }}
 * byte-matches {@link #active}. Constants are already lowercase, so (unlike {@code BotStatus}) no
 * {@code @JsonValue.toLowerCase()} is needed for HTTP serialisation.
 */
public enum FunnelStatus {
    draft,
    active,
    paused
}
