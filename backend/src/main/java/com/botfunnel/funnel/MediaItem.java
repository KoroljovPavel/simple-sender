package com.botfunnel.funnel;

/**
 * Immutable single element of a {@link BlockType#ALBUM} media group (Decision 1). Persisted by Spring
 * Data as a nested document inside {@link ContentBlock#items()} (flat POJO, no {@code _class}
 * discriminator — Decision 12). Being a record, it is deeply immutable, so {@link FunnelStep#copyOf}
 * only needs a defensive copy of the enclosing {@code blocks} list, not of the elements.
 *
 * <p>No bean-validation here — album size / caption-on-first-only / type-mixing rules live in the
 * DTO/service layer (Task 4).
 *
 * @param mediaUrl an {@code http(s)} URL or an opaque Telegram {@code file_id}
 * @param caption  optional caption — meaningful only on the <b>first</b> element of the album
 *                 (Telegram renders the group caption from the first item); enforced by validation
 *                 in Task 4, not by this model
 */
public record MediaItem(String mediaUrl, String caption) {
}
