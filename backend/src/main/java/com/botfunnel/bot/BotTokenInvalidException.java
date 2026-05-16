package com.botfunnel.bot;

import com.botfunnel.common.AppException;
import org.springframework.http.HttpStatus;

public class BotTokenInvalidException extends AppException {

    private final String botId;

    public BotTokenInvalidException(String botId, String reason) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_bot_token", reason);
        this.botId = botId;
    }

    public String getBotId() { return botId; }
}
