package com.botfunnel.subscriber;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Narrow lookup surface for Wave 2+ consumers. Methods are limited to those with a known caller in
 * Epic 05: webhook upsert ({@code findByProjectIdAndTelegramUserId}), TelegramSender block/delete
 * hooks ({@code findByProjectIdAndTelegramChatId} / the bot-scoped variant).
 */
public interface SubscriberRepository extends MongoRepository<Subscriber, String> {

    Optional<Subscriber> findByProjectIdAndTelegramUserId(String projectId, Long telegramUserId);

    Optional<Subscriber> findByProjectIdAndTelegramChatId(String projectId, Long telegramChatId);

    Optional<Subscriber> findByProjectIdAndTelegramBotIdAndTelegramChatId(
            String projectId, Long telegramBotId, Long telegramChatId);
}
