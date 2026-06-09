package com.botfunnel.funnel.dto;

import java.util.List;

// Multiblock preview render result (15-message-composer / Decision 8): EXACTLY
// {renderedBlocks, sampleData, kind} — no ownerChatId / identity fields leak to the client.
//   - {@code renderedBlocks} — the ordered, rendered blocks (one per composer block). For a non-message
//     step this is an empty list. The frontend renders text/caption text-only ({{ }}, never v-html) and
//     media via :src (Decision 8); the backend has already escaped substituted values per parseMode.
//   - {@code sampleData} — true when the render ran against a stub subscriber (owner not linked) rather
//     than the real owner.
//   - {@code kind} — "message" for a {@code StepType.MESSAGE} composer step, "non_message" for the action
//     steps (DELAY/ADD_TAG/REMOVE_TAG/SET_CUSTOM_FIELD/EMIT_EVENT/SUBSCRIBE_TO_FUNNEL).
public record PreviewStepResponse(List<RenderedBlock> renderedBlocks, boolean sampleData, String kind) {

    // One rendered block in the preview. {@code text}/{@code caption} are already escaped per parseMode
    // (the substituted variable values are escaped; author markup is left untouched — VariableTemplateRenderer).
    // {@code mediaUrl}/{@code items} are passed through VERBATIM and NEVER dereferenced by the backend
    // (anti-SSRF, Decision 6) — the frontend renders them via :src. Fields are nullable by block type:
    //   - TEXT                  → text (parseMode), media/caption/items null
    //   - IMAGE/VIDEO/AUDIO/FILE → mediaUrl + caption (parseMode), text/items null
    //   - ALBUM                 → items (each rendered caption escaped), text/mediaUrl/caption null
    public record RenderedBlock(
            String type,
            String text,
            String parseMode,
            String mediaUrl,
            String caption,
            List<RenderedMediaItem> items
    ) {}

    // One rendered album element. {@code mediaUrl} verbatim (never dereferenced); {@code caption} already
    // escaped per the album block's parseMode (meaningful only on the first element — Decision 5).
    public record RenderedMediaItem(String mediaUrl, String caption) {}
}
