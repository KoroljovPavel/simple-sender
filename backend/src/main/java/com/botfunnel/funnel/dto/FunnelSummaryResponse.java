package com.botfunnel.funnel.dto;

import com.botfunnel.funnel.FunnelStatus;

import java.time.Instant;

// Lightweight list view: funnel metadata WITHOUT the steps array (the editor loads steps lazily via
// the single GET). Returned by GET .../funnels. stepCount is a cheap hint for the list UI so it can
// show "N steps" without shipping the whole array.
public record FunnelSummaryResponse(
        String id,
        String projectId,
        String name,
        String description,
        FunnelStatus status,
        String triggerType,
        String triggerValue,
        boolean allowReEnter,
        int stepCount,
        Instant createdAt,
        Instant updatedAt
) {}
