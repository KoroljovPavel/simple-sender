package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

// Full-replace update body for a funnel's metadata + trigger + entire step list. The steps array is
// authoritative: its position is the step order (the server rewrites FunnelStep.order from the index),
// so reordering ↑/↓ is simply a new array. triggerType / triggerValue / allowReEnter are nullable —
// FunnelService applies on_start / "" / false defaults and validates triggerValue shape
// (^[A-Za-z0-9_-]{0,64}$ → 422; spaces/specials would break the deep-link). @JsonIgnoreProperties
// drops a hostile {"status":...} body field (mass-assignment defense). @Valid cascades bean-validation
// into each FunnelStepDto (only its @NotNull stepType — the rest is service-level per-type validation).
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateFunnelRequest(
        @Size(max = 128) String name,
        @Size(max = 1024) String description,
        String triggerType,
        String triggerValue,
        Boolean allowReEnter,
        // Phase 3 (Decision 3): keyword list — required (non-empty after normalization) iff
        // triggerType=keyword, rejected for any other trigger type (FunnelService → 422). Normalized
        // server-side (lowercase, trim, de-dupe).
        List<String> keywords,
        @Valid List<FunnelStepDto> steps
) {}
