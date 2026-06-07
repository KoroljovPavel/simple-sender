package com.botfunnel.funnel.dto;

import com.botfunnel.funnel.FunnelStatus;

import java.time.Instant;
import java.util.List;

// Full single-funnel view: metadata + complete ordered steps + deepLink. Returned by GET /{id},
// PUT/PATCH, activate and pause. deepLink is non-null ONLY for an active funnel that has a CONNECTED
// bot (t.me/<botUsername>?start=<triggerValue>); null for draft/paused or when no bot is connected.
// steps order is the array position (the server rewrote FunnelStep.order from it on the last update).
public record FunnelResponse(
        String id,
        String projectId,
        String name,
        String description,
        FunnelStatus status,
        String triggerType,
        String triggerValue,
        boolean allowReEnter,
        // Phase 3 (Decision 3): normalized keyword list (lowercase). Null/empty for non-keyword triggers.
        List<String> keywords,
        List<FunnelStepDto> steps,
        String deepLink,
        Instant createdAt,
        Instant updatedAt
) {}
