package com.botfunnel.webhook;

/**
 * Result of parsing a Telegram message text against the {@link TelegramCommandParser} rules.
 * {@code command} is the lowercased-or-original-case command name WITHOUT the leading {@code /}
 * and WITHOUT the {@code @botname} suffix. {@code payload} is the text after the command +
 * optional {@code @botname} with leading whitespace consumed; empty string when absent.
 * Neither field is ever {@code null}.
 */
public record ParsedCommand(String command, String payload) {

    private static final ParsedCommand NOT_A_COMMAND = new ParsedCommand("", "");

    /**
     * Sentinel for inputs that are not commands (null, empty, leading whitespace, plain text,
     * single {@code /}). Callers compare with {@code equals} or via direct field inspection
     * — both fields are {@code ""}.
     */
    public static ParsedCommand notACommand() {
        return NOT_A_COMMAND;
    }
}
