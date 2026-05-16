package com.botfunnel.bot.dto;

// snake_case retry_after to match Telegram's wire format under Jackson record deserialisation.
// Integer (not int) because Telegram only includes this on 429 responses; missing/null is valid.
public record TelegramSendParameters(Integer retry_after) {
}
