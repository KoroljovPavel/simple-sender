package com.botfunnel.funnel;

import java.util.List;

/**
 * Immutable content block of a {@link StepType#MESSAGE} composer step (Decision 1). A composer step
 * holds an ordered {@link FunnelStep#getBlocks()} list; each block is rendered as one Telegram message
 * in order. Persisted by Spring Data as a nested document inside {@code FunnelStep.blocks} (flat POJO,
 * no {@code _class} discriminator — Decision 12 / Decision 1). No {@code @JsonTypeInfo} / sealed
 * hierarchy: a single flat record with a {@link BlockType} discriminator and nullable per-type fields.
 *
 * <p>Being a record, {@code ContentBlock} is deeply immutable for the scalar fields. {@link #items} is
 * a {@code List} container (mutable as a reference), but the elements ({@link MediaItem}) are immutable
 * records; {@link FunnelStep#copyOf} takes a defensive shallow copy of the outer {@code blocks} list,
 * which is sufficient for snapshot isolation (Decision 3) because each {@code ContentBlock} is itself
 * immutable.
 *
 * <p>No bean-validation here — per-type field requirements, album size (2–10), caption-on-first-only,
 * type-mixing and {@code http(s)} URL checks live in the DTO/service layer (Task 4).
 *
 * @param type      block discriminator: {@code TEXT|IMAGE|VIDEO|AUDIO|FILE|ALBUM}
 * @param text      message text — {@code TEXT} only
 * @param parseMode {@code null|HTML|MarkdownV2} — applies to {@code TEXT} text and media captions
 * @param mediaUrl  {@code http(s)} URL or Telegram {@code file_id} — {@code IMAGE/VIDEO/AUDIO/FILE} only
 * @param caption   media caption — {@code IMAGE/VIDEO/AUDIO/FILE} only
 * @param items     album elements (2–10) — {@code ALBUM} only; {@code null} for other types
 */
public record ContentBlock(
        BlockType type,
        String text,
        String parseMode,
        String mediaUrl,
        String caption,
        List<MediaItem> items
) {
}
