package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// On-the-fly preview body (Decision 9): the CURRENT, possibly-unsaved content of a step the author is
// editing — NOT the saved step. The renderer escapes substituted values per {@code parseMode} exactly
// as the runtime engine would, so the editor preview is byte-for-byte faithful. {@code stepType} lets
// the server decide message-vs-non-message rendering without reading the saved step. All fields are
// optional/nullable (a MENU has no mandatory text; a blank/null text renders to ""). Unknown body
// fields are dropped (mass-assignment defense, patterns.md).
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreviewStepRequest(String stepType, String text, String parseMode) {}
