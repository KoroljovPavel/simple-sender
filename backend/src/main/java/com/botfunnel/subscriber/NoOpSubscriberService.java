package com.botfunnel.subscriber;

import org.springframework.stereotype.Service;

/**
 * Placeholder implementation until Epic 05 ships real subscriber persistence. Both methods
 * return without side effects. NOT annotated @Primary — Epic 05 will replace this @Service directly.
 */
@Service
public class NoOpSubscriberService implements SubscriberService {

    @Override
    public void upsertFromTelegramUpdate(String projectId,
                                         Long telegramBotId,
                                         Long chatId,
                                         String chatType,
                                         Long telegramUserId,
                                         String firstName,
                                         String lastName,
                                         String username,
                                         String languageCode) {
        // no-op
    }

    @Override
    public void markUnsubscribed(String projectId, Long telegramBotId, Long chatId) {
        // no-op
    }
}
