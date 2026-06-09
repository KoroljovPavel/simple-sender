package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// On-the-fly multiblock preview body (15-message-composer / Decision 8): the CURRENT, possibly-unsaved
// content of a composer step the author is editing — NOT the saved step. Each block's text/caption is
// rendered server-side with escaping per {@code parseMode} exactly as the runtime engine would, so the
// editor preview is byte-for-byte faithful (anti markup/XSS drift, OWASP A03). {@code stepType} lets the
// server decide message-vs-non-message rendering without reading the saved step; {@code blocks} carries
// the ordered composer blocks (null/empty for a non-message step → renders to an empty array). The
// backend NEVER dereferences a media URL (anti-SSRF, Decision 6). Unknown body fields are dropped
// (mass-assignment defense, patterns.md).
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreviewStepRequest(String stepType, List<ContentBlockDto> blocks) {}
