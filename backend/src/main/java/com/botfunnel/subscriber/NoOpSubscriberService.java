package com.botfunnel.subscriber;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Placeholder implementation until Epic 05 ships real subscriber persistence. Both methods
 * complete empty. NOT annotated @Primary — Epic 05 will replace this @Service directly.
 */
@Service
public class NoOpSubscriberService implements SubscriberService {

    @Override
    public Mono<Void> upsertFromTelegramUpdate(String projectId,
                                               Long telegramBotId,
                                               Long chatId,
                                               String chatType,
                                               Long telegramUserId,
                                               String firstName,
                                               String lastName,
                                               String username,
                                               String languageCode) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> markUnsubscribed(String projectId, Long telegramBotId, Long chatId) {
        return Mono.empty();
    }
}
