package com.botfunnel.bot.dto;

public record TelegramResult<T>(boolean ok, T result, Integer error_code, String description) {
}
