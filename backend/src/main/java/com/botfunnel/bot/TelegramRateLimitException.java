package com.botfunnel.bot;

// Package-private on purpose: this exception is internal to TelegramSender's outer 429 loop
// (Decision 2 / nested-loop topology). It carries Telegram's retry_after between the 429 detection
// site (inner loop) and the rate-limit sleep in the outer loop, and never reaches GlobalErrorHandler.
class TelegramRateLimitException extends RuntimeException {

    private final int retryAfterSeconds;

    TelegramRateLimitException(int retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    int getRetryAfterSeconds() { return retryAfterSeconds; }
}
