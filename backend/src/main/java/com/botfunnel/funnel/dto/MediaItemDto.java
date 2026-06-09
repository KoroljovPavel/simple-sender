package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// One element of a {@code BlockType.ALBUM} media group (15-message-composer / Decision 1). Mirrors the
// immutable {@code MediaItem} record: {@code mediaUrl} is an http(s) URL or an opaque Telegram file_id;
// {@code caption} is meaningful only on the FIRST element of the album (Decision 5) — that rule is
// enforced by FunnelService validation, not here. Like the other funnel DTOs, no field is bean-validated
// (per-type rules are conditional → 422 with a business code in FunnelService, not a 400).
// @JsonIgnoreProperties(ignoreUnknown = true) keeps the mass-assignment defense consistent.
@JsonIgnoreProperties(ignoreUnknown = true)
public record MediaItemDto(
        String mediaUrl,
        String caption
) {}
