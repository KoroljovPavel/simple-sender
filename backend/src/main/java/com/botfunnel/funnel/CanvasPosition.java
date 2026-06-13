package com.botfunnel.funnel;

/**
 * Immutable embedded coordinate value (18-funnel-canvas / Task 1). The single new persisted concept the
 * canvas needs — a node's {x,y} on the editor surface — shared by {@link FunnelStep}, {@link Trigger}
 * and {@link Note}.
 *
 * <p>Flat embedded, no {@code @Document} / no {@code _class} discriminator (Decision 12 convention): the
 * field type is statically known wherever it is embedded, so Spring Data serialises it as a plain nested
 * document. Additive and nullable (Decision 8): an old document without this embedded field reads back as
 * {@code null} and the frontend auto-layouts that node — no migration.
 *
 * <p>The record is immutable, so it copies by reference in {@link FunnelStep#copyOf(FunnelStep)} and still
 * satisfies the deep-copy / snapshot-isolation contract (Decision 3).
 */
public record CanvasPosition(Double x, Double y) {
}
