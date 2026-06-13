package com.botfunnel.funnel.dto;

import com.botfunnel.funnel.FunnelStatus;

import java.time.Instant;
import java.util.List;

// Lightweight list view: funnel metadata + entry triggers WITHOUT the steps array (the editor loads steps
// lazily via the single GET). Returned by GET .../funnels. `triggers` is the multi-entry array that
// replaced the former flat triggerType / triggerValue / keywords trio (Phase 8 / 17-funnel-multi-entry /
// Decision 1, 12); keywords now live per-trigger inside each TriggerDto. stepCount is a cheap hint for the
// list UI so it can show "N steps" without shipping the whole array.
public record FunnelSummaryResponse(
        String id,
        String projectId,
        String name,
        String description,
        FunnelStatus status,
        boolean allowReEnter,
        List<TriggerDto> triggers,
        int stepCount,
        Instant createdAt,
        Instant updatedAt
) {}
