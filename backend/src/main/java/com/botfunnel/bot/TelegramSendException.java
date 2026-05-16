package com.botfunnel.bot;

import com.botfunnel.common.AppException;
import org.springframework.http.HttpStatus;

public class TelegramSendException extends AppException {

    private final Integer errorCode;
    private final int attempts;

    // scrubbedDescription parameter name documents the caller's obligation: callers MUST pre-scrub
    // with TelegramApiClient.scrubTokens(...). This constructor never scrubs.
    public TelegramSendException(Integer errorCode, String scrubbedDescription, int attempts) {
        super(HttpStatus.BAD_REQUEST, "telegram_send_failed", scrubbedDescription);
        this.errorCode = errorCode;
        this.attempts = attempts;
    }

    public Integer getErrorCode() { return errorCode; }
    public int getAttempts() { return attempts; }
}
