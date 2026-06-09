package com.botfunnel.bot.dto;

/**
 * Input element for {@code TelegramSender.sendMediaGroup} — one entry of a Telegram media group
 * ({@code /sendMediaGroup}). Self-contained on purpose: the sender owns the Telegram wire-shape
 * concern and must not couple to the funnel-layer {@code ContentBlock}/{@code MediaItem} model.
 *
 * <p>{@code type} is the Telegram media-group element type ({@code photo}/{@code video}/
 * {@code audio}/{@code document}). {@code mediaUrl} is a URL or {@code file_id} — Telegram fetches
 * it; the backend never dereferences it (no SSRF, Decision 6). {@code caption} is meaningful only
 * on the <strong>first</strong> element (Decision 5); the builder drops it on later elements.
 * {@code parseMode} formats that first-element caption ({@code null}/{@code HTML}/{@code MarkdownV2}).
 */
public record AlbumItem(String type, String mediaUrl, String caption, String parseMode) {
}
