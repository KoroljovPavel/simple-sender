package com.botfunnel.bot;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.common.crypto.EncryptedValue;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// IT scope: HTTP → BotController → BotService → real Mongo, with a class-level MockWebServer
// standing in for api.telegram.org. Two scenarios that depend on Task 7's BotService rewrite
// are @Disabled; they will be enabled by Task 7. The remaining two scenarios (null-branch
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

    // Valid bot-token shape (matches TelegramApiClient.TOKEN_SHAPE). Encrypted per-test with the
    // test-profile AES-GCM key so the direct-TelegramSender scenarios pass decrypt + shape check
    // and reach the MockWebServer with /bot{token}/... on the path.
    private static final String VALID_TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired BotRepository botRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EventRepository eventRepository;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired TelegramSender telegramSender;
    @Autowired TokenEncryptor tokenEncryptor;

    @BeforeEach
    void cleanAndSeed() {
        mockTelegram.setDispatcher(new QueueDispatcher());
        try {
            //noinspection StatementWithEmptyBody
            while (mockTelegram.takeRequest(0, TimeUnit.MILLISECONDS) != null) { /* drain */ }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        botRepository.deleteAll();
        userRepository.deleteAll();
        projectRepository.deleteAll();
        eventRepository.deleteAll();

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
        userRepository.save(u);
    }

    private Project saveActiveProject(String ownerId) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName("TelegramSenderIT-" + System.nanoTime());
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p);
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
        return botRepository.save(b);
    }

    // Seeds a CONNECTED bot whose stored token decrypts to VALID_TOKEN under the test-profile key,
    // so a direct TelegramSender call passes AES-GCM decrypt + requireValidTokenShape and actually
    // hits MockWebServer (unlike seedConnectedBot, whose "Zm9v"/"YmFy" stubs fail decrypt).
    private Bot seedConnectedBotWithRealToken(String projectId) {
        EncryptedValue ev = tokenEncryptor.encrypt(VALID_TOKEN);
        Bot b = new Bot();
        b.setProjectId(projectId);
        b.setTelegramBotId(TELEGRAM_BOT_ID);
        b.setTelegramUsername(TELEGRAM_USERNAME);
        b.setTelegramFirstName(TELEGRAM_FIRST_NAME);
        b.setStatus(BotStatus.CONNECTED);
        b.setEncryptedTokenCiphertext(Base64.getEncoder().encodeToString(ev.ciphertext()));
        b.setEncryptedTokenIv(Base64.getEncoder().encodeToString(ev.iv()));
        b.setTokenSuffix("xyz");
        b.setWebhookSecretHash("a".repeat(64));
        b.setConnectedAt(Instant.now());
        return botRepository.save(b);
    }

    private static MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static MockResponse okSendMessage(long messageId, long chatId) {
        return jsonResponse(200, String.format(
                "{\"ok\":true,\"result\":{\"message_id\":%d,\"chat\":{\"id\":%d}}}",
                messageId, chatId));
    }

    private static MockResponse okAnswerCallbackQuery() {
        return jsonResponse(200, "{\"ok\":true,\"result\":true}");
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
                    boolean any = eventRepository.findAll().stream().anyMatch(predicate);
                    assertThat(any).isFalse();
                });
    }

    // ---------- Scenarios ----------

    @Test
    @Disabled("enabled by Task 7 — requires BotService.sendTestMessage to call TelegramSender")
    @WithMockAppUser(userId = USER_ID)
    void sendText_endToEndViaBotService_emitsBothEvents() {
        // Wave 2 — Task 7 wires BotService.sendTestMessage to call TelegramSender. Until then,
        // the existing 422 owner_chat_id_unknown stub returns regardless of ownerChatId, so the
        // happy-path send-and-events scenario cannot be exercised through HTTP.
    }

    @Test
    @Disabled("enabled by Task 7 — requires BotService.sendTestMessage to call TelegramSender")
    @WithMockAppUser(userId = USER_ID)
    void sendText_terminalFailureViaBotService_emitsFailedEventOnly() {
        // Wave 2 — Task 7 wires the failure-event-only branch. Until then, the stub short-circuits
        // before TelegramSender is ever invoked.
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void sendTestMessage_ownerChatIdNull_returns422_zeroTelegramCalls() throws Exception {
        // Wave 2 behavior preserved: when ownerChatId is null, BotService short-circuits with 422
        // owner_chat_id_unknown. No Telegram traffic and no telegram_* / bot_test_message_sent
        // events written. Regression net for Epic 06 AC15 across the 04c → 04b boundary.
        Project project = saveActiveProject(USER_ID);
        seedConnectedBot(project.getId(), null);

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/test-message")
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("owner_chat_id_unknown"))
                .andExpect(jsonPath("$.message")
                        .value("Send /start to your bot in Telegram first, then try again"));

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

        Document inserted = mongoTemplate.insert(legacy, "bots");
        assertThat(inserted).isNotNull();
        String id = inserted.getObjectId("_id").toHexString();

        Optional<Bot> loadedOpt = botRepository.findById(id);
        assertThat(loadedOpt).isPresent();
        Bot loaded = loadedOpt.get();
        assertThat(loaded.getOwnerChatId()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(BotStatus.CONNECTED);
        assertThat(loaded.getTelegramBotId()).isEqualTo(TELEGRAM_BOT_ID);
    }

    // ---------- Task 2: reply_markup + answerCallbackQuery (direct TelegramSender) ----------

    @Test
    void sendText_withReplyMarkup_includesInlineKeyboardInBody() throws Exception {
        Project project = saveActiveProject(USER_ID);
        Bot bot = seedConnectedBotWithRealToken(project.getId());
        mockTelegram.enqueue(okSendMessage(42L, 555L));

        // {"inline_keyboard":[[{text,callback_data}],[{text,url}]]}
        Map<String, Object> replyMarkup = Map.of(
                "inline_keyboard", List.of(
                        List.of(Map.of("text", "Buy", "callback_data", "exec-1:0")),
                        List.of(Map.of("text", "Site", "url", "https://example.com"))));

        telegramSender.sendText(bot.getId(), 555L, "Choose:", null, USER_ID, replyMarkup);

        List<RecordedRequest> requests = drainRequests();
        assertThat(requests).hasSize(1);
        RecordedRequest req = requests.get(0);
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/bot" + VALID_TOKEN + "/sendMessage");

        JsonNode body = OBJECT_MAPPER.readTree(req.getBody().readUtf8());
        JsonNode keyboard = body.path("reply_markup").path("inline_keyboard");
        assertThat(keyboard.isArray()).isTrue();
        assertThat(keyboard).hasSize(2);
        assertThat(keyboard.get(0).get(0).get("text").asText()).isEqualTo("Buy");
        assertThat(keyboard.get(0).get(0).get("callback_data").asText()).isEqualTo("exec-1:0");
        assertThat(keyboard.get(1).get(0).get("text").asText()).isEqualTo("Site");
        assertThat(keyboard.get(1).get(0).get("url").asText()).isEqualTo("https://example.com");
    }

    @Test
    void sendText_nullReplyMarkup_omitsKeyField() throws Exception {
        Project project = saveActiveProject(USER_ID);
        Bot bot = seedConnectedBotWithRealToken(project.getId());
        mockTelegram.enqueue(okSendMessage(43L, 555L));

        // 6-arg overload with null reply_markup must produce the identical body shape as the 5-arg
        // path: no reply_markup key (only-if-non-null idiom, mirrors parse_mode).
        telegramSender.sendText(bot.getId(), 555L, "Plain", null, USER_ID, null);

        List<RecordedRequest> requests = drainRequests();
        assertThat(requests).hasSize(1);
        JsonNode body = OBJECT_MAPPER.readTree(requests.get(0).getBody().readUtf8());
        assertThat(body.has("reply_markup")).isFalse();
        assertThat(body.get("text").asText()).isEqualTo("Plain");
    }

    @Test
    void answerCallbackQuery_postsToAnswerCallbackQueryEndpoint() throws Exception {
        Project project = saveActiveProject(USER_ID);
        Bot bot = seedConnectedBotWithRealToken(project.getId());
        mockTelegram.enqueue(okAnswerCallbackQuery());

        telegramSender.answerCallbackQuery(bot.getId(), "cbq-123", "Done");

        List<RecordedRequest> requests = drainRequests();
        assertThat(requests).hasSize(1);
        RecordedRequest req = requests.get(0);
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/bot" + VALID_TOKEN + "/answerCallbackQuery");

        JsonNode body = OBJECT_MAPPER.readTree(req.getBody().readUtf8());
        assertThat(body.get("callback_query_id").asText()).isEqualTo("cbq-123");
        assertThat(body.get("text").asText()).isEqualTo("Done");
    }

    @Test
    void answerCallbackQuery_nullText_omitsTextField() throws Exception {
        Project project = saveActiveProject(USER_ID);
        Bot bot = seedConnectedBotWithRealToken(project.getId());
        mockTelegram.enqueue(okAnswerCallbackQuery());

        telegramSender.answerCallbackQuery(bot.getId(), "cbq-456", null);

        List<RecordedRequest> requests = drainRequests();
        assertThat(requests).hasSize(1);
        JsonNode body = OBJECT_MAPPER.readTree(requests.get(0).getBody().readUtf8());
        assertThat(body.get("callback_query_id").asText()).isEqualTo("cbq-456");
        assertThat(body.has("text")).isFalse();
    }

    @Test
    void answerCallbackQuery_telegram5xx_bestEffortDoesNotThrow() {
        Project project = saveActiveProject(USER_ID);
        Bot bot = seedConnectedBotWithRealToken(project.getId());
        // 5xx on every attempt (initial + MAX_RETRIES) → transient exhausted; best-effort swallows.
        for (int i = 0; i <= 3; i++) {
            mockTelegram.enqueue(jsonResponse(503,
                    "{\"ok\":false,\"error_code\":503,\"description\":\"Service Unavailable\"}"));
        }

        // Decision 8: ack failure must NOT propagate (would otherwise block funnel advance).
        assertThatCode(() -> telegramSender.answerCallbackQuery(bot.getId(), "cbq-5xx", "x"))
                .doesNotThrowAnyException();
        // It did attempt (retry loop ran) — at least one request reached MockWebServer.
        assertThat(drainRequests()).isNotEmpty();
    }

    @Test
    void answerCallbackQuery_disconnectedBot_noTelegramCall() {
        Project project = saveActiveProject(USER_ID);
        Bot bot = seedConnectedBotWithRealToken(project.getId());
        bot.setStatus(BotStatus.DISCONNECTED);
        botRepository.save(bot);

        // CONNECTED filter rejects → best-effort swallows the 404; no HTTP traffic.
        assertThatCode(() -> telegramSender.answerCallbackQuery(bot.getId(), "cbq-off", "x"))
                .doesNotThrowAnyException();
        assertThat(drainRequests()).isEmpty();
    }
}
