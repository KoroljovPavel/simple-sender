package com.botfunnel.bot.dto;

public record TelegramUser(Long id, boolean is_bot, String first_name, String username) {
}
