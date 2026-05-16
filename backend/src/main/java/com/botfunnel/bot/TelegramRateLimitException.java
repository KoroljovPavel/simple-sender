package com.botfunnel.bot;

// Package-private on purpose: this exception is internal to TelegramSender's .retryWhen filter
// (Decision 5 / Architecture step 11). It carries Telegram's retry_after between the 429 detection
// site and the rate-limit retry policy and never reaches GlobalErrorHandler.
class TelegramRateLimitException extends RuntimeException {

    private final int retryAfterSeconds;

    TelegramRateLimitException(int retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    int getRetryAfterSeconds() { return retryAfterSeconds; }
}
