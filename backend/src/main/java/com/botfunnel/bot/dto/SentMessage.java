package com.botfunnel.bot.dto;

import java.time.Instant;

public record SentMessage(Long chatId, Long messageId, Instant sentAt) {
}
