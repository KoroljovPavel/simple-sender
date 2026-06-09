package com.botfunnel.funnel;

/**
 * Discriminator for a {@link ContentBlock} inside a {@link StepType#MESSAGE} composer step
 * (Decision 1: flat embedded POJO, no {@code _class}). UPPERCASE — this is a block-kind
 * discriminator, mirroring the codebase convention for enum discriminators (e.g. {@link StepType}).
 *
 * <ul>
 *   <li>{@code TEXT} — a plain text message (optional {@code parseMode}).</li>
 *   <li>{@code IMAGE} / {@code VIDEO} / {@code AUDIO} / {@code FILE} — a single media message
 *       (URL or {@code file_id}) with an optional caption.</li>
 *   <li>{@code ALBUM} — a Telegram media group of 2–10 {@link MediaItem}s.</li>
 * </ul>
 */
public enum BlockType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    ALBUM
}
