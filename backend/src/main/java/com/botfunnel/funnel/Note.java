package com.botfunnel.funnel;

/**
 * Immutable embedded free-floating canvas note (18-funnel-canvas / Task 1, Decision 7). A note lives in a
 * new top-level {@code Funnel.notes[]} array, OUTSIDE {@code steps[]} — the engine never executes it; it is
 * pure editor metadata.
 *
 * <p>Flat embedded, no {@code @Document} / no {@code _class} discriminator (Decision 12 convention), mirror
 * of the {@link Trigger} / {@link FunnelStep} flat-embedded shape. Additive and nullable (Decision 8).
 *
 * <ul>
 *   <li>{@code id} — server-minted ObjectId hex (FunnelService mints it when the incoming id is null,
 *       preserves it otherwise); never trusted from the request body for shape.</li>
 *   <li>{@code text} — free note text (length-capped in the DTO + re-checked service-side).</li>
 *   <li>{@code canvasPosition} — the note's {x,y} on the editor surface; nullable.</li>
 * </ul>
 */
public record Note(String id, String text, CanvasPosition canvasPosition) {
}
