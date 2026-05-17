package com.botfunnel.subscriber;

import reactor.core.publisher.Mono;

/**
 * Contract consumed by the webhook worker (ProcessTelegramUpdateJob) for subscriber persistence.
 * The real implementation is deferred to Epic 05; Epic 04b ships a no-op @Service to keep the
 * worker compileable and integration-testable without subscriber side effects.
 */
public interface SubscriberService {

    Mono<Void> upsertFromTelegramUpdate(String projectId,
                                        Long telegramBotId,
                                        Long chatId,
                                        String chatType,
                                        Long telegramUserId,
                                        String firstName,
                                        String lastName,
                                        String username,
                                        String languageCode);

    Mono<Void> markUnsubscribed(String projectId, Long telegramBotId, Long chatId);
}
