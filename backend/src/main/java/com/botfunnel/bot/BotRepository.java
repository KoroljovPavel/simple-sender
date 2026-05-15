package com.botfunnel.bot;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface BotRepository extends ReactiveMongoRepository<Bot, String> {

    Mono<Bot> findByProjectIdAndStatus(String projectId, BotStatus status);

    Flux<Bot> findByProjectId(String projectId);

    Mono<Bot> findFirstByTelegramBotIdAndStatus(Long telegramBotId, BotStatus status);
}
