package com.botfunnel.bot;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface BotRepository extends MongoRepository<Bot, String> {

    Optional<Bot> findByProjectIdAndStatus(String projectId, BotStatus status);

    List<Bot> findByProjectId(String projectId);

    Optional<Bot> findFirstByTelegramBotIdAndStatus(Long telegramBotId, BotStatus status);
}
