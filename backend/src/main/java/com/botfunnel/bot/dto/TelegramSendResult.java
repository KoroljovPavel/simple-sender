package com.botfunnel.bot.dto;

// Field names mirror Telegram's wire format verbatim (snake_case) so Jackson deserialises by
// record-component name without @JsonProperty annotations. Same precedent as TelegramResult<T>.
public record TelegramSendResult<T>(
        boolean ok,
        T result,
        Integer error_code,
        String description,
        TelegramSendParameters parameters) {
}
