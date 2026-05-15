package com.botfunnel.bot.dto;

import com.botfunnel.bot.BotStatus;

import java.time.Instant;

// D17: BotResponse carries tokenSuffix (last 3 chars of the secret) and never the raw or
// encrypted token. No raw token, no encrypted ciphertext, no IV, no webhook secret in any form.
public record BotResponse(
        Long telegramBotId,
        String telegramUsername,
        String telegramFirstName,
        String tokenSuffix,
        BotStatus status,
        Instant connectedAt
) {}
