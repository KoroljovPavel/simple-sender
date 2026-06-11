package com.botfunnel.funnel;

import java.util.List;

/**
 * Immutable single row of a persistent reply keyboard on a {@link StepType#SET_KEYBOARD} step
 * (16-persistent-keyboard / Decision 2). A SET_KEYBOARD step holds an ordered
 * {@link FunnelStep#getKeyboardRows()} list; each row renders as one row of bottom-keyboard buttons
 * in Telegram's {@code ReplyKeyboardMarkup}. Persisted by Spring Data as a nested document (flat
 * POJO, no {@code _class} discriminator — Decision 12 / Decision 2).
 *
 * <p>Being a record, {@code KeyboardRow} is deeply immutable for its scalar shape. {@link #buttons}
 * is a {@code List} container (mutable as a reference), but the elements ({@link KeyboardButton}) are
 * immutable records; {@link FunnelStep#copyOf} takes a defensive shallow copy of the outer
 * {@code keyboardRows} list, which is sufficient for snapshot isolation (Decision 3) because each
 * {@code KeyboardRow} (and each {@code KeyboardButton}) is itself immutable.
 *
 * <p>No bean-validation here — row-count (1..10), per-row button-count (1..4) and per-button rules
 * live in the service layer ({@code FunnelService.validateSetKeyboard}).
 *
 * @param buttons the row's buttons (1..4 by validation)
 */
public record KeyboardRow(List<KeyboardButton> buttons) {
}
