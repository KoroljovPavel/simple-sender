package com.botfunnel.webhook;

/**
 * Pure utility that recognises Telegram bot commands ({@code /start}, {@code /stop}, …) in
 * incoming message text and extracts the optional payload.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code null} / empty / leading-whitespace / non-slash-prefixed / single {@code /}
 *       → {@link ParsedCommand#notACommand()}.</li>
 *   <li>The command token spans from the character after {@code /} up to the first
 *       {@link Character#isWhitespace(int) whitespace} character (newline and tab count).</li>
 *   <li>If the command token contains an ASCII {@code @}, the suffix (intended for
 *       {@code @botname} group disambiguation) is stripped — the split is on the FIRST
 *       {@code @}; subsequent {@code @} chars stay in the discarded suffix half.</li>
 *   <li>Case is PRESERVED in the {@code command} field. Case-folding for matching is the
 *       caller's responsibility (the worker matches {@code /start} / {@code /stop} only in
 *       lowercase).</li>
 *   <li>Leading whitespace in the payload is trimmed (matches "/start payload" UX); inner
 *       whitespace and newlines inside the payload are preserved verbatim.</li>
 * </ul>
 *
 * <p>Allocation-light, regex-free, no logging, no static mutable state — invoked for every
 * text message the worker processes.
 */
public final class TelegramCommandParser {

    private TelegramCommandParser() {
        throw new UnsupportedOperationException("utility class");
    }

    public static ParsedCommand parse(String input) {
        if (input == null || input.isEmpty()) {
            return ParsedCommand.notACommand();
        }
        if (input.charAt(0) != '/') {
            return ParsedCommand.notACommand();
        }
        if (input.length() == 1) {
            return ParsedCommand.notACommand();
        }

        int splitAt = -1;
        for (int i = 1; i < input.length(); i++) {
            if (Character.isWhitespace(input.charAt(i))) {
                splitAt = i;
                break;
            }
        }

        String commandToken;
        String payload;
        if (splitAt == -1) {
            commandToken = input.substring(1);
            payload = "";
        } else {
            commandToken = input.substring(1, splitAt);
            // Skip leading whitespace in the payload; preserve inner whitespace verbatim.
            int payloadStart = splitAt + 1;
            while (payloadStart < input.length()
                    && Character.isWhitespace(input.charAt(payloadStart))) {
                payloadStart++;
            }
            payload = input.substring(payloadStart);
        }

        int atSign = commandToken.indexOf('@');
        String command = atSign >= 0 ? commandToken.substring(0, atSign) : commandToken;

        return new ParsedCommand(command, payload);
    }
}
