package com.botfunnel.funnel;

/**
 * Immutable single element of a {@link BlockType#ALBUM} media group (Decision 1). Persisted by Spring
 * Data as a nested document inside {@link ContentBlock#items()} (flat POJO, no {@code _class}
 * discriminator — Decision 12). Being a record, it is deeply immutable, so {@link FunnelStep#copyOf}
 * only needs a defensive copy of the enclosing {@code blocks} list, not of the elements.
 *
 * <p>{@code type} is the per-item media kind, restricted at validation to {@code IMAGE|VIDEO|AUDIO|FILE}
 * (the {@link BlockType} enum is reused; {@code TEXT}/{@code ALBUM} are never valid here). It lets the
 * Decision-5 type-mixing predicate be enforced on save and lets the engine derive each Telegram
 * media-group element type (IMAGE→photo, VIDEO→video, AUDIO→audio, FILE→document) instead of assuming a
 * homogeneous group.
 *
 * <p>No bean-validation here — album size / caption-on-first-only / type-mixing rules live in the
 * DTO/service layer (FunnelService.validateMessage).
 *
 * @param type     per-item media kind: one of {@code IMAGE|VIDEO|AUDIO|FILE} (enforced by validation)
 * @param mediaUrl an {@code http(s)} URL or an opaque Telegram {@code file_id}
 * @param caption  optional caption — meaningful only on the <b>first</b> element of the album
 *                 (Telegram renders the group caption from the first item); enforced by validation,
 *                 not by this model
 */
public record MediaItem(BlockType type, String mediaUrl, String caption) {
}
