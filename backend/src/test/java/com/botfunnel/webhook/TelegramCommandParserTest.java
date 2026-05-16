package com.botfunnel.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramCommandParserTest {

    @Test
    void parse_startNoPayload_returnsStartWithEmptyPayload() {
        ParsedCommand result = TelegramCommandParser.parse("/start");

        assertThat(result).isEqualTo(new ParsedCommand("start", ""));
    }

    @Test
    void parse_startWithPayload_returnsStartWithPayload() {
        ParsedCommand result = TelegramCommandParser.parse("/start payload");

        assertThat(result).isEqualTo(new ParsedCommand("start", "payload"));
    }

    @Test
    void parse_startWithMultiTokenPayload_preservesIntermediateSpaces() {
        ParsedCommand result = TelegramCommandParser.parse("/start ref_a b c");

        assertThat(result).isEqualTo(new ParsedCommand("start", "ref_a b c"));
    }

    @Test
    void parse_startWithBotSuffix_stripsSuffix() {
        ParsedCommand result = TelegramCommandParser.parse("/start@SomeBot");

        assertThat(result).isEqualTo(new ParsedCommand("start", ""));
    }

    @Test
    void parse_startWithBotSuffixAndPayload_stripsSuffixKeepsPayload() {
        ParsedCommand result = TelegramCommandParser.parse("/start@SomeBot ref_X");

        assertThat(result).isEqualTo(new ParsedCommand("start", "ref_X"));
    }

    @Test
    void parse_stop_returnsStopEmpty() {
        ParsedCommand result = TelegramCommandParser.parse("/stop");

        assertThat(result).isEqualTo(new ParsedCommand("stop", ""));
    }

    @Test
    void parse_stopWithBotSuffix_returnsStopEmpty() {
        ParsedCommand result = TelegramCommandParser.parse("/stop@SomeBot");

        assertThat(result).isEqualTo(new ParsedCommand("stop", ""));
    }

    @Test
    void parse_plainText_returnsNotACommand() {
        ParsedCommand result = TelegramCommandParser.parse("hello");

        assertThat(result).isEqualTo(ParsedCommand.notACommand());
    }

    @Test
    void parse_slashOnly_returnsNotACommand() {
        ParsedCommand result = TelegramCommandParser.parse("/");

        assertThat(result).isEqualTo(ParsedCommand.notACommand());
    }

    @Test
    void parse_emptyString_returnsNotACommand() {
        ParsedCommand result = TelegramCommandParser.parse("");

        assertThat(result).isEqualTo(ParsedCommand.notACommand());
    }

    @Test
    void parse_nullInput_returnsNotACommand() {
        ParsedCommand result = TelegramCommandParser.parse(null);

        assertThat(result).isEqualTo(ParsedCommand.notACommand());
    }

    @Test
    void parse_leadingWhitespace_returnsNotACommand() {
        // Parser does NOT pre-trim (user-spec AC12 — leading whitespace is not a command).
        ParsedCommand result = TelegramCommandParser.parse(" /start");

        assertThat(result).isEqualTo(ParsedCommand.notACommand());
    }

    @Test
    void parse_caseSensitivity_preservesCase() {
        // Parser preserves original case; case-folding is the worker's responsibility.
        ParsedCommand result = TelegramCommandParser.parse("/Start");

        assertThat(result).isEqualTo(new ParsedCommand("Start", ""));
    }

    @Test
    void parse_multipleSpacesBeforePayload_trimsLeadingPayloadWhitespace() {
        // Documented rule: leading whitespace in payload is trimmed (matches "/start payload" UX).
        ParsedCommand result = TelegramCommandParser.parse("/start  ref_X");

        assertThat(result).isEqualTo(new ParsedCommand("start", "ref_X"));
    }

    @Test
    void parse_newlinePayload_extractsPayload() {
        ParsedCommand result = TelegramCommandParser.parse("/start\nref_X");

        assertThat(result).isEqualTo(new ParsedCommand("start", "ref_X"));
    }

    @Test
    void parse_payloadWithEmbeddedNewline_preservesPayloadContent() {
        // Regression guard: the leading splitter consumes ONE whitespace char; subsequent
        // newlines inside the payload must survive (Telegram payloads do contain them).
        ParsedCommand result = TelegramCommandParser.parse("/start ref_X\nline2");

        assertThat(result).isEqualTo(new ParsedCommand("start", "ref_X\nline2"));
    }

    @Test
    void parse_tabBetweenCommandAndPayload_treatedAsSplitter() {
        // Documented rule: Character.isWhitespace splitter — tab behaves like space.
        ParsedCommand result = TelegramCommandParser.parse("/start\tref_X");

        assertThat(result).isEqualTo(new ParsedCommand("start", "ref_X"));
    }

    @Test
    void parse_longPayload_roundTripsIntact() {
        StringBuilder payload = new StringBuilder(4096);
        for (int i = 0; i < 4096; i++) {
            payload.append('a');
        }
        String expected = payload.toString();

        ParsedCommand result = TelegramCommandParser.parse("/start " + expected);

        assertThat(result.command()).isEqualTo("start");
        assertThat(result.payload()).isEqualTo(expected);
    }

    @Test
    void parse_emptyBotSuffix_treatedAsBareCommand() {
        // Edge pin: /start@ with empty bot name → command is "start" (split takes first @).
        ParsedCommand result = TelegramCommandParser.parse("/start@");

        assertThat(result).isEqualTo(new ParsedCommand("start", ""));
    }

    @Test
    void parse_multipleAtSigns_splitOnFirstAt() {
        // Edge pin: subsequent @ chars stay in the discarded suffix half.
        ParsedCommand result = TelegramCommandParser.parse("/start@@x");

        assertThat(result).isEqualTo(new ParsedCommand("start", ""));
    }
}
