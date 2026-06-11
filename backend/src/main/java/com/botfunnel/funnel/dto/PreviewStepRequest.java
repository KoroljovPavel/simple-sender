package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// On-the-fly preview body (15-message-composer / Decision 8; 16-persistent-keyboard / Decision 7): the
// CURRENT, possibly-unsaved content of the step the author is editing — NOT the saved step. Text fields are
// rendered server-side with escaping per the relevant parse mode exactly as the runtime engine would, so the
// editor preview is byte-for-byte faithful (anti markup/XSS drift, OWASP A03). {@code stepType} is currently
// dead code in the service (the render branch gates on the SAVED step's type, Decision 7) — kept only as the
// client's declared intent. The backend NEVER dereferences a media URL (anti-SSRF, Decision 6). Unknown body
// fields are dropped (mass-assignment defense, patterns.md).
//
// MESSAGE composer fields:
//   - {@code blocks} — the ordered composer blocks (null/empty for a non-message step → empty render).
//
// SET_KEYBOARD / CLEAR_KEYBOARD fields (16-persistent-keyboard / Decision 7) — the live form state for a
// keyboard step:
//   - {@code keyboardText}     — the step's single text field, rendered through VariableTemplateRenderer with
//                                {@code keyboardParseMode} escaping (one TEXT block in the response).
//   - {@code keyboardParseMode}— null | "HTML" | "MarkdownV2"; passed to the renderer as-is (preview is
//                                non-validating — an unknown value is treated as plain by the renderer).
//   - {@code keyboardRows}     — the button rows; their labels are NEVER variable-rendered (a label IS the
//                                keyword link — a tap echoes it verbatim into keyword dispatch; rendering
//                                variables into a label would break that matching). The response echoes them
//                                verbatim, clamped to the validation caps.
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreviewStepRequest(
        String stepType,
        List<ContentBlockDto> blocks,
        String keyboardText,
        String keyboardParseMode,
        List<KeyboardRowDto> keyboardRows
) {}
