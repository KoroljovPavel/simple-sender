package com.botfunnel.bot;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

// IT scope: HTTP → BotController → BotService → real Mongo, with a class-level MockWebServer
// standing in for api.telegram.org. Two scenarios that depend on Task 5's BotService rewrite
// are @Disabled; they will be enabled by Task 5. The remaining two scenarios (null-branch
// regression + legacy-document deserialization) verify behavior that is already correct in
// Wave 2 — the existing 422 stub and Spring Data MongoDB's nullable-wrapper read semantics.
class TelegramSenderIT extends AbstractIntegrationTest {

    private static final String USER_ID = "telegram-sender-it-user";
    private static final Long TELEGRAM_BOT_ID = 5550001L;
    private static final String TELEGRAM_USERNAME = "sender_test_bot";
    private static final String TELEGRAM_FIRST_NAME = "Sender";

    private static final MockWebServer mockTelegram;

    static {
        mockTelegram = new MockWebServer();
        try {
            mockTelegram.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start MockWebServer for TelegramSenderIT", e);
        }
    }

    @DynamicPropertySource
    static void registerTelegramBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("app.telegram.base-url", () -> mockTelegram.url("/").toString());
    }

    @AfterAll
    static void shutdownTelegramMock() throws IOException {
        mockTelegram.shutdown();
    }

    @Autowired BotRepository botRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EventRepository eventRepository;
    @Autowired ReactiveMongoTemplate mongoTemplate;

    @BeforeEach
    void cleanAndSeed() {
        mockTelegram.setDispatcher(new QueueDispatcher());
        try {
            //noinspection StatementWithEmptyBody
            while (mockTelegram.takeRequest(0, TimeUnit.MILLISECONDS) != null) { /* drain */ }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        botRepository.deleteAll().block();
        userRepository.deleteAll().block();
        projectRepository.deleteAll().block();
        eventRepository.deleteAll().block();

        seedUser(USER_ID, "telegram-sender-it@test.com");
    }

    // ---------- helpers ----------

    private void seedUser(String userId, String email) {
        User u = new User();
        u.setId(userId);
        u.setEmail(email);
        u.setName("TelegramSenderIT User");
        u.setPasswordHash("not-used");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u).block();
    }

    private Project saveActiveProject(String ownerId) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName("TelegramSenderIT-" + System.nanoTime());
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p).block();
    }

    private Bot seedConnectedBot(String projectId, Long ownerChatId) {
        Bot b = new Bot();
        b.setProjectId(projectId);
        b.setTelegramBotId(TELEGRAM_BOT_ID);
        b.setTelegramUsername(TELEGRAM_USERNAME);
        b.setTelegramFirstName(TELEGRAM_FIRST_NAME);
        b.setStatus(BotStatus.CONNECTED);
        b.setEncryptedTokenCiphertext("Zm9v");
        b.setEncryptedTokenIv("YmFy");
        b.setTokenSuffix("xyz");
        b.setWebhookSecretHash("a".repeat(64));
        b.setConnectedAt(Instant.now());
        b.setOwnerChatId(ownerChatId);
        return botRepository.save(b).block();
    }

    private List<RecordedRequest> drainRequests() {
        List<RecordedRequest> out = new ArrayList<>();
        try {
            RecordedRequest r;
            while ((r = mockTelegram.takeRequest(0, TimeUnit.MILLISECONDS)) != null) {
                out.add(r);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private void awaitNoEvent(Predicate<Event> predicate) {
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    boolean any = Boolean.TRUE.equals(eventRepository.findAll()
                            .filter(predicate).hasElements().block());
                    assertThat(any).isFalse();
                });
    }

    // ---------- Scenarios ----------

    @Test
    @Disabled("enabled by Task 5 — requires BotService.sendTestMessage to call TelegramSender")
    @WithMockAppUser(userId = USER_ID)
    void sendText_endToEndViaBotService_emitsBothEvents() {
        // Wave 3 — Task 5 wires BotService.sendTestMessage to call TelegramSender. Until then,
        // the existing 422 owner_chat_id_unknown stub returns regardless of ownerChatId, so the
        // happy-path send-and-events scenario cannot be exercised through HTTP.
    }

    @Test
    @Disabled("enabled by Task 5 — requires BotService.sendTestMessage to call TelegramSender")
    @WithMockAppUser(userId = USER_ID)
    void sendText_terminalFailureViaBotService_emitsFailedEventOnly() {
        // Wave 3 — Task 5 wires the failure-event-only branch. Until then, the stub short-circuits
        // before TelegramSender is ever invoked.
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void sendTestMessage_ownerChatIdNull_returns422_zeroTelegramCalls() {
        // Wave 2 behavior preserved: when ownerChatId is null, BotService short-circuits with 422
        // owner_chat_id_unknown. No Telegram traffic and no telegram_* / bot_test_message_sent
        // events written. Regression net for Epic 06 AC15 across the 04c → 04b boundary.
        Project project = saveActiveProject(USER_ID);
        seedConnectedBot(project.getId(), null);

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/test-message")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("owner_chat_id_unknown")
                .jsonPath("$.message")
                .isEqualTo("Send /start to your bot in Telegram first, then try again");

        assertThat(drainRequests()).isEmpty();
        awaitNoEvent(e -> e.getEventType() != null
                && (e.getEventType().startsWith("telegram_")
                || "bot_test_message_sent".equals(e.getEventType())));
    }

    @Test
    void sendTestMessage_legacyDocumentReadsAsNull() {
        // First explicit verification that Spring Data MongoDB reads missing wrapper-type fields
        // (Bot.ownerChatId) as null. Insert a raw BSON Document into the bots collection without
        // the ownerChatId field; load via BotRepository.findById; assert null.
        Project project = saveActiveProject(USER_ID);

        Document legacy = new Document()
                .append("projectId", project.getId())
                .append("telegramBotId", TELEGRAM_BOT_ID)
                .append("telegramUsername", TELEGRAM_USERNAME)
                .append("telegramFirstName", TELEGRAM_FIRST_NAME)
                .append("status", "CONNECTED")
                .append("encryptedTokenCiphertext", "Zm9v")
                .append("encryptedTokenIv", "YmFy")
                .append("tokenSuffix", "xyz")
                .append("webhookSecretHash", "a".repeat(64))
                .append("connectedAt", Instant.now());

        Document inserted = mongoTemplate.insert(legacy, "bots").block();
        assertThat(inserted).isNotNull();
        String id = inserted.getObjectId("_id").toHexString();

        Bot loaded = botRepository.findById(id).block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOwnerChatId()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(BotStatus.CONNECTED);
        assertThat(loaded.getTelegramBotId()).isEqualTo(TELEGRAM_BOT_ID);
    }
}
