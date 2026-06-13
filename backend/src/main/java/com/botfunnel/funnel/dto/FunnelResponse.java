package com.botfunnel.funnel.dto;

import com.botfunnel.funnel.FunnelStatus;

import java.time.Instant;
import java.util.List;

// Full single-funnel view: metadata + entry triggers + complete ordered steps + deepLink. Returned by
// GET /{id}, PUT/PATCH, activate and pause. `triggers` is the multi-entry array (on_start + mid-entry
// redirects) that replaced the former flat triggerType / triggerValue / keywords trio (Phase 8 /
// 17-funnel-multi-entry / Decision 1, 12); keywords now live per-trigger inside each TriggerDto. deepLink
// is non-null ONLY for an active funnel that has a CONNECTED bot (t.me/<botUsername>?start=<on_start
// triggerValue>); null for draft/paused or when no bot is connected — it is derived from the on_start
// trigger's value by the FunnelService mapper (Task 4). steps order is the array position.
public record FunnelResponse(
        String id,
        String projectId,
        String name,
        String description,
        FunnelStatus status,
        boolean allowReEnter,
        List<TriggerDto> triggers,
        List<FunnelStepDto> steps,
        String deepLink,
        Instant createdAt,
        Instant updatedAt
) {}
