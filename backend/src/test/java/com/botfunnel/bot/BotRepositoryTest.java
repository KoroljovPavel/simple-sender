package com.botfunnel.bot;

import com.botfunnel.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

import java.time.Instant;

class BotRepositoryTest extends AbstractIntegrationTest {

    @Autowired BotRepository botRepository;

    @BeforeEach
    void clean() {
        botRepository.deleteAll().block();
    }

    private Bot newBot(String projectId, Long telegramBotId, BotStatus status) {
        Bot b = new Bot();
        b.setProjectId(projectId);
        b.setTelegramBotId(telegramBotId);
        b.setTelegramUsername("smoke_" + telegramBotId + "_bot");
        b.setTelegramFirstName("Smoke " + telegramBotId);
        b.setStatus(status);
        if (status == BotStatus.CONNECTED) {
            b.setEncryptedTokenCiphertext("ct");
            b.setEncryptedTokenIv("iv");
            b.setTokenSuffix("abc");
            b.setWebhookSecretHash("hash");
            b.setConnectedAt(Instant.now());
        } else {
            b.setConnectedAt(Instant.now().minusSeconds(60));
            b.setDisconnectedAt(Instant.now());
        }
        return b;
    }

    @Test
    void findByProjectIdAndStatus_returnsConnectedRowForProject() {
        botRepository.save(newBot("proj-1", 111L, BotStatus.CONNECTED)).block();
        botRepository.save(newBot("proj-1", 222L, BotStatus.DISCONNECTED)).block();

        StepVerifier.create(botRepository.findByProjectIdAndStatus("proj-1", BotStatus.CONNECTED))
                .assertNext(bot -> {
                    org.assertj.core.api.Assertions.assertThat(bot.getTelegramBotId()).isEqualTo(111L);
                    org.assertj.core.api.Assertions.assertThat(bot.getStatus()).isEqualTo(BotStatus.CONNECTED);
                })
                .verifyComplete();
    }

    @Test
    void findByProjectIdAndStatus_returnsEmptyWhenNoConnectedBot() {
        botRepository.save(newBot("proj-2", 333L, BotStatus.DISCONNECTED)).block();

        StepVerifier.create(botRepository.findByProjectIdAndStatus("proj-2", BotStatus.CONNECTED))
                .verifyComplete();
    }

    @Test
    void findFirstByTelegramBotIdAndStatus_returnsConnectedBotByTelegramId() {
        botRepository.save(newBot("proj-3", 123L, BotStatus.CONNECTED)).block();

        StepVerifier.create(botRepository.findFirstByTelegramBotIdAndStatus(123L, BotStatus.CONNECTED))
                .assertNext(bot -> {
                    org.assertj.core.api.Assertions.assertThat(bot.getProjectId()).isEqualTo("proj-3");
                    org.assertj.core.api.Assertions.assertThat(bot.getTelegramBotId()).isEqualTo(123L);
                })
                .verifyComplete();
    }

    @Test
    void findByProjectId_returnsAllStatusesForProject() {
        botRepository.save(newBot("proj-4", 444L, BotStatus.CONNECTED)).block();
        botRepository.save(newBot("proj-4", 555L, BotStatus.DISCONNECTED)).block();

        StepVerifier.create(botRepository.findByProjectId("proj-4"))
                .expectNextCount(2)
                .verifyComplete();
    }
}
