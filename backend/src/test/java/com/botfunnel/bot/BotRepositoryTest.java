package com.botfunnel.bot;

import com.botfunnel.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
                    assertThat(bot.getTelegramBotId()).isEqualTo(111L);
                    assertThat(bot.getStatus()).isEqualTo(BotStatus.CONNECTED);
                })
                .verifyComplete();
    }

    @Test
    void findByProjectIdAndStatus_returnsEmptyWhenNoConnectedBot() {
        // CONNECTED row exists for a DIFFERENT project: the empty result only holds if both
        // filters (status AND projectId) are applied — guards against status-only filtering.
        botRepository.save(newBot("proj-OTHER", 999L, BotStatus.CONNECTED)).block();
        botRepository.save(newBot("proj-2", 333L, BotStatus.DISCONNECTED)).block();

        StepVerifier.create(botRepository.findByProjectIdAndStatus("proj-2", BotStatus.CONNECTED))
                .verifyComplete();
    }

    @Test
    void findFirstByTelegramBotIdAndStatus_returnsConnectedBotByTelegramId() {
        // DISCONNECTED row with the SAME telegramBotId in a different project must be filtered
        // out — guards against the repository silently ignoring the status argument.
        botRepository.save(newBot("proj-3-old", 123L, BotStatus.DISCONNECTED)).block();
        botRepository.save(newBot("proj-3", 123L, BotStatus.CONNECTED)).block();

        StepVerifier.create(botRepository.findFirstByTelegramBotIdAndStatus(123L, BotStatus.CONNECTED))
                .assertNext(bot -> {
                    assertThat(bot.getProjectId()).isEqualTo("proj-3");
                    assertThat(bot.getTelegramBotId()).isEqualTo(123L);
                    assertThat(bot.getStatus()).isEqualTo(BotStatus.CONNECTED);
                })
                .verifyComplete();
    }

    @Test
    void findByProjectId_returnsAllStatusesForProject() {
        botRepository.save(newBot("proj-4", 444L, BotStatus.CONNECTED)).block();
        botRepository.save(newBot("proj-4", 555L, BotStatus.DISCONNECTED)).block();

        StepVerifier.create(botRepository.findByProjectId("proj-4").collectList())
                .assertNext(bots -> {
                    assertThat(bots).hasSize(2);
                    Set<BotStatus> statuses = new HashSet<>();
                    Set<Long> telegramBotIds = new HashSet<>();
                    bots.forEach(b -> {
                        statuses.add(b.getStatus());
                        telegramBotIds.add(b.getTelegramBotId());
                        assertThat(b.getProjectId()).isEqualTo("proj-4");
                    });
                    assertThat(statuses).containsExactlyInAnyOrder(BotStatus.CONNECTED, BotStatus.DISCONNECTED);
                    assertThat(telegramBotIds).containsExactlyInAnyOrder(444L, 555L);
                })
                .verifyComplete();
    }
}
