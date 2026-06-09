package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// One content block of a {@code StepType.MESSAGE} composer step (15-message-composer / Decision 1).
// Mirrors the immutable {@code ContentBlock} record one-to-one: {@code type} is the BlockType
// discriminator (TEXT|IMAGE|VIDEO|AUDIO|FILE|ALBUM), the remaining fields are type-specific and
// nullable (flat shape, no _class — Decision 1):
//   - TEXT                 → text (+ optional parseMode)
//   - IMAGE/VIDEO/AUDIO/FILE → mediaUrl (+ optional caption, parseMode)
//   - ALBUM                → items (2–10 MediaItemDto; caption meaningful only on the first)
// Like FunnelStepDto/ButtonDto, NO field is bean-validated here: per-type required-field / album-size /
// type-mixing / http(s)-scheme checks are conditional on `type` and therefore live in FunnelService
// (→ 422 with a business code, not a 400). @JsonIgnoreProperties(ignoreUnknown = true) keeps the
// mass-assignment defense consistent with the other funnel DTOs.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentBlockDto(
        String type,
        String text,
        String parseMode,
        String mediaUrl,
        String caption,
        List<MediaItemDto> items
) {}
