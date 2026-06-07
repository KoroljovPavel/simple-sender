package com.botfunnel.funnel.dto;

import com.botfunnel.funnel.FunnelStatus;

import java.time.Instant;
import java.util.List;

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
        // Phase 3 (Decision 3): normalized keyword list (lowercase). Null/empty for non-keyword triggers.
        List<String> keywords,
        int stepCount,
        Instant createdAt,
        Instant updatedAt
) {}
