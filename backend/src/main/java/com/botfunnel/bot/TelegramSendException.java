package com.botfunnel.bot;

import com.botfunnel.common.AppException;
import org.springframework.http.HttpStatus;

public class TelegramSendException extends AppException {

    /**
     * Routing hint for the {@code TelegramSender} catch-block subscriber hook (Decision 4 / Task 6).
     * Travels on the exception itself so the mapping stays colocated with {@code toThrowable} rather
     * than re-sniffing substrings downstream. {@code BLOCKED_BY_USER} → flip subscriber to BLOCKED;
     * {@code CHAT_NOT_FOUND} → flip to DELETED; {@code OTHER} → no CRM side-effect.
     */
    public enum TerminalReason { BLOCKED_BY_USER, CHAT_NOT_FOUND, OTHER }

    private final Integer errorCode;
    private final int attempts;
    private final TerminalReason terminalReason;

    // scrubbedDescription parameter name documents the caller's obligation: callers MUST pre-scrub
    // with TelegramApiClient.scrubTokens(...). This constructor never scrubs. Defaults the routing
    // hint to OTHER for callers that carry no mapping info (empty body, ok=false, timeout,
    // transient_failure_exhausted, interrupted).
    public TelegramSendException(Integer errorCode, String scrubbedDescription, int attempts) {
        this(errorCode, scrubbedDescription, attempts, TerminalReason.OTHER);
    }

    public TelegramSendException(Integer errorCode, String scrubbedDescription, int attempts,
                                 TerminalReason terminalReason) {
        super(HttpStatus.BAD_REQUEST, "telegram_send_failed", scrubbedDescription);
        this.errorCode = errorCode;
        this.attempts = attempts;
        this.terminalReason = terminalReason;
    }

    public Integer getErrorCode() { return errorCode; }
    public int getAttempts() { return attempts; }
    public TerminalReason getTerminalReason() { return terminalReason; }
}
