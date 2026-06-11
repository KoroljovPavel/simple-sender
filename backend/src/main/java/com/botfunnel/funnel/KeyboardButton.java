package com.botfunnel.funnel;

/**
 * Immutable reply-keyboard button of a {@link StepType#SET_KEYBOARD} step (16-persistent-keyboard /
 * Decision 2). Persisted by Spring Data as a nested document inside a {@link KeyboardRow}'s
 * {@code buttons} list, itself nested in {@link FunnelStep#getKeyboardRows()} (flat POJO, no
 * {@code _class} discriminator — Decision 12 / Decision 2). Being a record, it is deeply immutable,
 * so {@link FunnelStep#copyOf} only needs a defensive copy of the enclosing {@code keyboardRows}
 * list, not of the elements.
 *
 * <p>Distinct from the inline {@link Button} record: a reply-keyboard button carries only the label
 * the subscriber sees (and which Telegram echoes back as a plain {@code message.text} on tap — the
 * keyword link). It is single-component <b>by design</b>: future per-button fields
 * ({@code request_contact}, {@code web_app}, …) map onto Telegram's {@code KeyboardButton} object
 * form and would be added here as nullable record components (Decision 2).
 *
 * <p>No bean-validation here — text-non-blank / ≤64 / duplicate-within-keyboard checks live in the
 * service layer ({@code FunnelService.validateSetKeyboard}).
 *
 * @param text button caption (plain text — non-blank, ≤64 chars by validation; = the keyword cap so a
 *             button text can always be an exact keyword)
 */
public record KeyboardButton(String text) {
}
