package com.botfunnel.bot;

import com.botfunnel.bot.TelegramSendException.TerminalReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

// Pure constructor / getter coverage for the TerminalReason enum and the new 4-arg ctor (no Spring,
// no mocks). The enum-shape assertion is defensive: the catch-block hook in TelegramSender routes on
// these exact values, so a silent rename / reorder must break a test rather than mis-route a flip.
class TelegramSendExceptionTerminalReasonTest {

    @Test
    void defaultsToOther() {
        TelegramSendException ex = new TelegramSendException(400, "Bad Request", 1);
        assertThat(ex.getTerminalReason()).isEqualTo(TerminalReason.OTHER);
    }

    @ParameterizedTest
    @EnumSource(TerminalReason.class)
    void carriesEachReason(TerminalReason reason) {
        TelegramSendException ex = new TelegramSendException(403, "Forbidden", 2, reason);
        assertThat(ex.getTerminalReason()).isEqualTo(reason);
    }

    @Test
    void fourArgCtorPreservesErrorCodeAndAttempts() {
        TelegramSendException ex =
                new TelegramSendException(403, "Forbidden", 3, TerminalReason.BLOCKED_BY_USER);
        assertThat(ex.getErrorCode()).isEqualTo(403);
        assertThat(ex.getAttempts()).isEqualTo(3);
        assertThat(ex.getCode()).isEqualTo("telegram_send_failed");
        assertThat(ex.getMessage()).isEqualTo("Forbidden");
    }

    @Test
    void enumShapeIsStable() {
        // Catches silent renames/reorders that would mis-route the TelegramSender hook. containsExactly
        // pins both identity and ordinal order; the explicit name() checks guard against renames.
        assertThat(TerminalReason.values())
                .containsExactly(TerminalReason.BLOCKED_BY_USER, TerminalReason.CHAT_NOT_FOUND, TerminalReason.OTHER);
        assertThat(TerminalReason.BLOCKED_BY_USER.name()).isEqualTo("BLOCKED_BY_USER");
        assertThat(TerminalReason.CHAT_NOT_FOUND.name()).isEqualTo("CHAT_NOT_FOUND");
        assertThat(TerminalReason.OTHER.name()).isEqualTo("OTHER");
    }
}
