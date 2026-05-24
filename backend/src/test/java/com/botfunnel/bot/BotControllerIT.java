package com.botfunnel.bot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// IT scope: full HTTP → BotController → BotService → Mongo + Redis stack against the real
// testcontainer Mongo/Redis, with a class-level MockWebServer standing in for api.telegram.org.
// MockWebServer is started in a static initializer so @DynamicPropertySource (registered below)
// can read its URL before the Spring context boots; shut down in @AfterAll so the singleton
// container survives the entire test class lifecycle.
//
// Note: the two race-condition tests live in BotConnectRaceIT (RANDOM_PORT + TestRestTemplate)
// per D14 — MockMvc's in-process DispatcherServlet does not reliably interleave critical sections.
class BotControllerIT extends AbstractIntegrationTest {

    private static final String USER_ID = "bot-it-user-id";
    private static final String OTHER_USER_ID = "bot-it-other-user-id";
    private static final String VALID_TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";
    private static final String MALFORMED_TOKEN = "not-a-valid-token";
    private static final Long TELEGRAM_BOT_ID = 9876543210L;
    private static final Long TELEGRAM_BOT_ID_2 = 1234567890L;
    private static final String TELEGRAM_USERNAME = "test_bot";
    private static final String TELEGRAM_FIRST_NAME = "Test";
    private static final String BRUTE_KEY = "brute:bot-connect:" + USER_ID;
    private static final Pattern TOKEN_REGEX = Pattern.compile("^\\d{1,20}:[A-Za-z0-9_-]{30,50}$");
    private static final Pattern TOKEN_SUBSTRING = Pattern.compile("\\d{1,20}:[A-Za-z0-9_-]{30,50}");
    private static final String REDIS_FAIL_OPEN_FRAGMENT =
            "bot-connect brute-force counter Redis failure (fail-open)";
    private static final String TELEGRAM_DISCONNECT_WARN_FRAGMENT =
            "Telegram deleteWebhook failed during Disconnect";

    private static final MockWebServer mockTelegram;

    static {
        mockTelegram = new MockWebServer();
        try {
            mockTelegram.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start MockWebServer for BotControllerIT", e);
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
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired TokenEncryptor tokenEncryptor;

    // @MockitoSpyBean wraps the real bean so Mongo persistence still works for normal tests; only
    // the targeted persist-failure scenario (#postConnect_persistFails…) re-stubs save() to throw.
    // Mockito.reset(...) in @BeforeEach restores real delegation for all subsequent tests.
    @MockitoSpyBean BotRepository botRepositorySpy;
    @MockitoSpyBean StringRedisTemplate redisTemplateSpy;

    private ListAppender<ILoggingEvent> botServiceAppender;
    private Logger botServiceLogger;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @BeforeEach
    void cleanAndSeed() {
        // Drain MockWebServer state from any previous test (mandatory: ~22 tests share one instance).
        // Reset the dispatcher to a fresh QueueDispatcher so any pre-enqueued responses from a
        // prior test that errored mid-flight can't leak into the next.
        mockTelegram.setDispatcher(new QueueDispatcher());
        try {
            //noinspection StatementWithEmptyBody
            while (mockTelegram.takeRequest(0, TimeUnit.MILLISECONDS) != null) { /* drain */ }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        Mockito.reset(botRepositorySpy);
        Mockito.reset(redisTemplateSpy);

        botRepository.deleteAll();
        userRepository.deleteAll();
        projectRepository.deleteAll();
        eventRepository.deleteAll();
        redisTemplate.delete(BRUTE_KEY);

        seedUser(USER_ID, "bot-it@test.com");

        botServiceLogger = (Logger) LoggerFactory.getLogger(BotService.class);
        botServiceAppender = new ListAppender<>();
        botServiceAppender.start();
        botServiceLogger.addAppender(botServiceAppender);
    }

    @AfterEach
    void tearDown() {
        botServiceLogger.detachAppender(botServiceAppender);
        botServiceAppender.stop();
    }

    // ---------- helpers ----------

    private void seedUser(String userId, String email) {
        User u = new User();
        u.setId(userId);
        u.setEmail(email);
        u.setName("BotIT User");
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
        p.setName("BotIT-" + System.nanoTime());
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p);
    }

    private Project saveSoftDeletedProject(String ownerId) {
        Project p = saveActiveProject(ownerId);
        p.setDeletedAt(Instant.now());
        return projectRepository.save(p);
    }

    private Bot seedConnectedBot(String projectId, Long telegramBotId) {
        Bot b = new Bot();
        b.setProjectId(projectId);
        b.setTelegramBotId(telegramBotId);
        b.setTelegramUsername(TELEGRAM_USERNAME);
        b.setTelegramFirstName(TELEGRAM_FIRST_NAME);
        b.setStatus(BotStatus.CONNECTED);
        b.setEncryptedTokenCiphertext("Zm9v");
        b.setEncryptedTokenIv("YmFy");
        b.setTokenSuffix("xyz");
        b.setWebhookSecretHash("a".repeat(64));
        b.setConnectedAt(Instant.now());
        return botRepository.save(b);
    }

    // Sibling of seedConnectedBot for tests that exercise paths reaching TokenEncryptor.decrypt
    // (e.g. TelegramSender.sendText). The placeholder ciphertext from seedConnectedBot decodes to
    // a 3-byte IV and fails the AES-GCM IV_BYTES (12) length check — so any positive-path test
    // that actually decrypts must seed REAL ciphertext via tokenEncryptor.encrypt(VALID_TOKEN).
    private Bot seedConnectedBotWithRealEncryption(String projectId, Long telegramBotId, Long ownerChatId) {
        EncryptedValue ev = tokenEncryptor.encrypt(VALID_TOKEN);
        Bot b = new Bot();
        b.setProjectId(projectId);
        b.setTelegramBotId(telegramBotId);
        b.setTelegramUsername(TELEGRAM_USERNAME);
        b.setTelegramFirstName(TELEGRAM_FIRST_NAME);
        b.setOwnerChatId(ownerChatId);
        b.setStatus(BotStatus.CONNECTED);
        b.setEncryptedTokenCiphertext(Base64.getEncoder().encodeToString(ev.ciphertext()));
        b.setEncryptedTokenIv(Base64.getEncoder().encodeToString(ev.iv()));
        b.setTokenSuffix("xyz");
        b.setWebhookSecretHash("a".repeat(64));
        b.setConnectedAt(Instant.now());
        return botRepository.save(b);
    }

    private static MockResponse jsonResponse(int statusCode, String body) {
        return new MockResponse()
                .setResponseCode(statusCode)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static MockResponse getMeOk(Long id, String username, String firstName) {
        return jsonResponse(200, String.format(
                "{\"ok\":true,\"result\":{\"id\":%d,\"is_bot\":true,\"first_name\":\"%s\",\"username\":\"%s\"}}",
                id, firstName, username));
    }

    private static MockResponse setWebhookOk() {
        return jsonResponse(200, "{\"ok\":true,\"result\":true}");
    }

    private static MockResponse deleteWebhookOk() {
        return jsonResponse(200, "{\"ok\":true,\"result\":true}");
    }

    private static MockResponse statusResponse(int code, String description) {
        return jsonResponse(code, String.format(
                "{\"ok\":false,\"error_code\":%d,\"description\":\"%s\"}", code, description));
    }

    private void enqueueHappyPathConnect() {
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(setWebhookOk());
    }

    private void awaitEvent(Predicate<Event> predicate) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findAll().stream().anyMatch(predicate));
    }

    private Event findEvent(Predicate<Event> predicate) {
        return eventRepository.findAll().stream().filter(predicate).findFirst().orElseThrow();
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

    private void assertNoTokenLeak(String body) {
        if (body == null) return;
        assertThat(TOKEN_REGEX.matcher(body).matches())
                .as("response payload must not equal a Telegram token: <%s>", body)
                .isFalse();
        assertThat(TOKEN_SUBSTRING.matcher(body).find())
                .as("response payload must not contain a Telegram-token-shaped substring: <%s>", body)
                .isFalse();
    }

    // ---------- POST /connect — happy + validation ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_validToken_returns200WithBotResponse() throws Exception {
        Project project = saveActiveProject(USER_ID);
        enqueueHappyPathConnect();

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telegramBotId").value(TELEGRAM_BOT_ID))
                .andExpect(jsonPath("$.telegramUsername").value(TELEGRAM_USERNAME))
                .andExpect(jsonPath("$.telegramFirstName").value(TELEGRAM_FIRST_NAME))
                .andExpect(jsonPath("$.tokenSuffix").value("xyz"))
                .andExpect(jsonPath("$.status").value("connected"))
                .andExpect(jsonPath("$.connectedAt").exists())
                .andExpect(jsonPath("$.encryptedTokenCiphertext").doesNotExist())
                .andExpect(jsonPath("$.encryptedTokenIv").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.webhookSecretHash").doesNotExist());

        Bot persisted = botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED).orElseThrow();
        assertThat(persisted.getTokenSuffix()).isEqualTo("xyz");
        assertThat(persisted.getEncryptedTokenCiphertext()).isNotBlank();
        assertThat(persisted.getEncryptedTokenIv()).isNotBlank();
        assertThat(persisted.getWebhookSecretHash()).matches("[0-9a-f]{64}");

        awaitEvent(e -> "bot_connected".equals(e.getEventType()) && USER_ID.equals(e.getUserId()));
        Event evt = findEvent(e -> "bot_connected".equals(e.getEventType()));
        assertThat(evt.getMetadata()).containsOnlyKeys("projectId", "telegramBotId", "telegramUsername");
        assertThat(evt.getMetadata())
                .containsEntry("projectId", project.getId())
                .containsEntry("telegramBotId", TELEGRAM_BOT_ID)
                .containsEntry("telegramUsername", TELEGRAM_USERNAME);

        // AC10: setWebhook URL parameter equals ${app.url}/webhooks/telegram/{projectId}.
        List<RecordedRequest> reqs = drainRequests();
        assertThat(reqs).hasSize(2);
        assertThat(reqs.get(0).getPath()).endsWith("/getMe");
        assertThat(reqs.get(1).getPath()).endsWith("/setWebhook");
        String body = reqs.get(1).getBody().readUtf8();
        assertThat(body).contains("/webhooks/telegram/" + project.getId());
        assertThat(body).contains("secret_token");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_malformedToken_returns400WithFieldError_andNoTelegramCall() throws Exception {
        Project project = saveActiveProject(USER_ID);

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", MALFORMED_TOKEN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("token")));

        assertThat(drainRequests()).isEmpty();
        assertThat(botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED)).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_telegramGetMe401_returns422() throws Exception {
        Project project = saveActiveProject(USER_ID);
        mockTelegram.enqueue(statusResponse(401, "Unauthorized"));

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("invalid_bot_token"));

        assertThat(botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED)).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_telegram5xxExhausted_returns502() throws Exception {
        Project project = saveActiveProject(USER_ID);
        for (int i = 0; i < 4; i++) {
            mockTelegram.enqueue(statusResponse(503, "Service Unavailable"));
        }

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.code").value("telegram_unavailable"));

        assertThat(botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED)).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_setWebhook4xxConfigError_returns500WithCodeWebhookConfigError() throws Exception {
        Project project = saveActiveProject(USER_ID);
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(statusResponse(400, "HTTPS url must be provided for webhook"));

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().is(500))
                .andExpect(jsonPath("$.code").value("webhook_config_error"));

        assertThat(botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED)).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_platformBotIdAlreadyConnected_returns409BotAlreadyConnected() throws Exception {
        Project foreignProject = saveActiveProject(OTHER_USER_ID);
        seedConnectedBot(foreignProject.getId(), TELEGRAM_BOT_ID);

        Project myProject = saveActiveProject(USER_ID);
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));

        mockMvc.perform(post("/api/v1/projects/" + myProject.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.code").value("bot_already_connected"));

        List<RecordedRequest> reqs = drainRequests();
        assertThat(reqs).hasSize(1);
        assertThat(reqs.get(0).getPath()).endsWith("/getMe");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_projectAlreadyHasConnectedBot_returns409BotAlreadyInProject() throws Exception {
        Project project = saveActiveProject(USER_ID);
        seedConnectedBot(project.getId(), TELEGRAM_BOT_ID_2);

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.code").value("bot_already_in_project"));

        assertThat(drainRequests()).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_persistFails_compensatingDeleteWebhookFires_returns500() throws Exception {
        Project project = saveActiveProject(USER_ID);
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(setWebhookOk());
        mockTelegram.enqueue(deleteWebhookOk());

        doThrow(new RuntimeException("persist failure simulated"))
                .when(botRepositorySpy).save(Mockito.any(Bot.class));

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().is(500));

        List<RecordedRequest> reqs = drainRequests();
        assertThat(reqs).extracting(RecordedRequest::getPath)
                .anySatisfy(p -> assertThat(p).endsWith("/setWebhook"))
                .anySatisfy(p -> assertThat(p).endsWith("/deleteWebhook"));
        int setIdx = -1;
        int delIdx = -1;
        for (int i = 0; i < reqs.size(); i++) {
            String path = reqs.get(i).getPath();
            if (path.endsWith("/setWebhook")) setIdx = i;
            if (path.endsWith("/deleteWebhook")) delIdx = i;
        }
        assertThat(setIdx).isLessThan(delIdx);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_eleventhAttemptWithinWindow_returns429_noTelegramCalls() throws Exception {
        Project project = saveActiveProject(USER_ID);
        // Pre-load the brute-force counter to the threshold so the next attempt trips immediately.
        for (int i = 0; i < 10; i++) {
            redisTemplate.opsForValue().increment(BRUTE_KEY);
        }
        redisTemplate.expire(BRUTE_KEY, Duration.ofSeconds(900));

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().is(429));

        assertThat(drainRequests()).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_successfulConnect_deletesBruteForceKey() throws Exception {
        Project project = saveActiveProject(USER_ID);
        redisTemplate.opsForValue().increment(BRUTE_KEY);
        assertThat(redisTemplate.hasKey(BRUTE_KEY)).isTrue();

        enqueueHappyPathConnect();

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().isOk());

        assertThat(redisTemplate.hasKey(BRUTE_KEY)).isFalse();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_redisDown_failsOpen_connectSucceeds_warnLogged() throws Exception {
        // AC11 fail-open / D14: stub the spied redisTemplate so opsForValue() returns a mock
        // ValueOperations whose increment() throws. The connect chain's catch swallows the
        // error, logs the dedicated WARN line once, and continues.
        Project project = saveActiveProject(USER_ID);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> brokenOps = Mockito.mock(ValueOperations.class);
        Mockito.when(brokenOps.increment(anyString()))
                .thenThrow(new RuntimeException("redis down"));
        doReturn(brokenOps).when(redisTemplateSpy).opsForValue();

        enqueueHappyPathConnect();

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().isOk());

        long warns = botServiceAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(REDIS_FAIL_OPEN_FRAGMENT))
                .count();
        assertThat(warns).isGreaterThanOrEqualTo(1);
    }

    // ---------- POST /disconnect ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postDisconnect_happyPath_returns200_andUpdatesMongoAtomically() throws Exception {
        Project project = saveActiveProject(USER_ID);
        Bot seeded = persistRealConnectedBot(project.getId(), VALID_TOKEN);
        mockTelegram.enqueue(deleteWebhookOk());

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/disconnect").with(csrf()))
                .andExpect(status().isOk());

        Bot disconnected = botRepository.findById(seeded.getId()).orElseThrow();
        assertThat(disconnected.getStatus()).isEqualTo(BotStatus.DISCONNECTED);
        assertThat(disconnected.getEncryptedTokenCiphertext()).isNull();
        assertThat(disconnected.getEncryptedTokenIv()).isNull();
        assertThat(disconnected.getTokenSuffix()).isNull();
        assertThat(disconnected.getWebhookSecretHash()).isNull();
        assertThat(disconnected.getDisconnectedAt()).isNotNull();

        awaitEvent(e -> "bot_disconnected".equals(e.getEventType()));
        Event evt = findEvent(e -> "bot_disconnected".equals(e.getEventType()));
        assertThat(evt.getMetadata()).containsOnlyKeys("projectId", "telegramBotId", "webhookDeleted");
        assertThat(evt.getMetadata()).containsEntry("webhookDeleted", true);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postDisconnect_telegramDown_warnLogged_returns200() throws Exception {
        Project project = saveActiveProject(USER_ID);
        Bot seeded = persistRealConnectedBot(project.getId(), VALID_TOKEN);
        for (int i = 0; i < 4; i++) {
            mockTelegram.enqueue(statusResponse(503, "Service Unavailable"));
        }

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/disconnect").with(csrf()))
                .andExpect(status().isOk());

        long warns = botServiceAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(TELEGRAM_DISCONNECT_WARN_FRAGMENT))
                .count();
        assertThat(warns).isEqualTo(1);

        Bot disconnected = botRepository.findById(seeded.getId()).orElseThrow();
        assertThat(disconnected.getStatus()).isEqualTo(BotStatus.DISCONNECTED);

        awaitEvent(e -> "bot_disconnected".equals(e.getEventType()));
        Event evt = findEvent(e -> "bot_disconnected".equals(e.getEventType()));
        assertThat(evt.getMetadata()).containsEntry("webhookDeleted", false);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postDisconnect_noConnectedBot_returns404() throws Exception {
        Project project = saveActiveProject(USER_ID);

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/disconnect").with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(drainRequests()).isEmpty();
    }

    // ---------- GET / ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getBot_seededConnected_returns200_noneReturns404() throws Exception {
        Project p1 = saveActiveProject(USER_ID);
        seedConnectedBot(p1.getId(), TELEGRAM_BOT_ID);

        mockMvc.perform(get("/api/v1/projects/" + p1.getId() + "/bot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telegramBotId").value(TELEGRAM_BOT_ID))
                .andExpect(jsonPath("$.status").value("connected"))
                .andExpect(jsonPath("$.encryptedTokenCiphertext").doesNotExist());

        Project p2 = saveActiveProject(USER_ID);
        mockMvc.perform(get("/api/v1/projects/" + p2.getId() + "/bot"))
                .andExpect(status().isNotFound());
    }

    // ---------- POST /test-message ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postTestMessage_in06_returns422_zeroTelegramCalls_zeroEvents() throws Exception {
        Project project = saveActiveProject(USER_ID);
        seedConnectedBot(project.getId(), TELEGRAM_BOT_ID);

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/test-message").with(csrf()))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("owner_chat_id_unknown"))
                .andExpect(jsonPath("$.message")
                        .value("Send /start to your bot in Telegram first, then try again"));

        assertThat(drainRequests()).isEmpty();
        boolean hasTestMessage = eventRepository.findAll().stream()
                .anyMatch(e -> "bot_test_message_sent".equals(e.getEventType()));
        assertThat(hasTestMessage).isFalse();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postTestMessage_in07_seededOwnerChatId_returns200_emitsEvent() throws Exception {
        Project project = saveActiveProject(USER_ID);
        Long ownerChatId = 42L;
        Bot seeded = seedConnectedBotWithRealEncryption(project.getId(), TELEGRAM_BOT_ID, ownerChatId);

        long ts = Instant.now().getEpochSecond();
        mockTelegram.enqueue(jsonResponse(200, String.format(
                "{\"ok\":true,\"result\":{\"message_id\":100,\"chat\":{\"id\":%d,\"type\":\"private\"},\"date\":%d,\"text\":\"Hello\"}}",
                ownerChatId, ts)));

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/test-message").with(csrf()))
                .andExpect(status().isOk());

        awaitEvent(e -> "bot_test_message_sent".equals(e.getEventType()));
        Event evt = findEvent(e -> "bot_test_message_sent".equals(e.getEventType()));
        assertThat(evt.getMetadata()).containsOnlyKeys(
                "projectId", "telegramBotId", "chatId", "messageId");
        assertThat(evt.getMetadata())
                .containsEntry("projectId", project.getId())
                .containsEntry("telegramBotId", TELEGRAM_BOT_ID)
                .containsEntry("chatId", ownerChatId)
                .containsEntry("messageId", 100L);

        List<RecordedRequest> reqs = drainRequests();
        assertThat(reqs).hasSize(1);
        assertThat(reqs.get(0).getPath()).endsWith("/sendMessage");
        String body = reqs.get(0).getBody().readUtf8();
        assertThat(body).contains("\"chat_id\":" + ownerChatId);
        assertThat(body).contains("Hello from Bot Funnel Service! Bot connected ✅");
        assertThat(seeded).isNotNull();
    }

    // ---------- Anti-enumeration ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void antiEnumeration_foreignSoftDeletedMalformedProjectId_returns404_andHostileBodyIgnored() throws Exception {
        Project foreign = saveActiveProject(OTHER_USER_ID);
        Project softDeleted = saveSoftDeletedProject(USER_ID);

        Map<String, Object> hostile = new HashMap<>();
        hostile.put("token", VALID_TOKEN);
        hostile.put("ownerId", "attacker");

        mockMvc.perform(post("/api/v1/projects/" + foreign.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(hostile)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/projects/" + softDeleted.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/projects/zzz-not-an-objectid/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().isNotFound());

        assertThat(drainRequests()).isEmpty();
    }

    // ---------- Token-leak negative assertion ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void noTokenLeak_inAnyResponseBodyOrEventMetadata() throws Exception {
        Project project = saveActiveProject(USER_ID);
        enqueueHappyPathConnect();

        byte[] connectBody = mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertNoTokenLeak(connectBody == null ? null : new String(connectBody));

        mockTelegram.enqueue(deleteWebhookOk());
        byte[] discBody = mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/disconnect").with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertNoTokenLeak(discBody == null ? null : new String(discBody));

        awaitEvent(e -> "bot_disconnected".equals(e.getEventType()));
        List<Event> events = eventRepository.findAll();
        for (Event evt : events) {
            if (evt.getMetadata() != null) {
                for (Object v : evt.getMetadata().values()) {
                    if (v instanceof String s) assertNoTokenLeak(s);
                }
            }
            assertNoTokenLeak(evt.getIpAddress());
            assertNoTokenLeak(evt.getUserAgent());
        }

        Project p2 = saveActiveProject(USER_ID);
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID_2, "leakcheck_bot", "Leak"));
        mockTelegram.enqueue(statusResponse(400, "Bad webhook for token " + VALID_TOKEN + " on chat"));

        byte[] errBody = mockMvc.perform(post("/api/v1/projects/" + p2.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().is(500))
                .andReturn().getResponse().getContentAsByteArray();
        assertNoTokenLeak(errBody == null ? null : new String(errBody));
    }

    // ---------- Unauthenticated 403 sweep ----------

    @Test
    void anyEndpoint_unauthenticated_returns403() throws Exception {
        // Anonymous request to an authenticated() path: AuthorizationFilter raises
        // AccessDeniedException → ExceptionTranslationFilter routes it to AccessDeniedHandler
        // → 403. Same shape as SecurityBlockTest.undefinedPathBlocked_returns403 (Task 10).
        // The previous expectation of 401 was a reactive-stack carry-over (the reactive chain
        // emitted 401 on anonymous-on-authenticated); the servlet stack default is 403, and we
        // pin it exactly so a future Http401AuthenticationEntryPoint addition becomes visible.
        mockMvc.perform(get("/api/v1/projects/any-id/bot"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/projects/any-id/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/projects/any-id/bot/disconnect").with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/projects/any-id/bot/test-message").with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---------- Webhook secret hashing ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void webhookSecretInMongo_isSha256Hash() throws Exception {
        Project project = saveActiveProject(USER_ID);
        enqueueHappyPathConnect();

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().isOk());

        Bot persisted = botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED).orElseThrow();
        assertThat(persisted.getWebhookSecretHash()).matches("[0-9a-f]{64}");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void tokenSuffix_persistedAndReturned_clearedOnDisconnect() throws Exception {
        Project project = saveActiveProject(USER_ID);
        enqueueHappyPathConnect();

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", VALID_TOKEN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenSuffix").value("xyz"));

        Bot connected = botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED).orElseThrow();
        assertThat(connected.getTokenSuffix()).isEqualTo("xyz");

        mockTelegram.enqueue(deleteWebhookOk());
        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/bot/disconnect").with(csrf()))
                .andExpect(status().isOk());

        Bot disconnected = botRepository.findById(connected.getId()).orElseThrow();
        assertThat(disconnected.getTokenSuffix()).isNull();
    }

    // ---------- helpers (real-connected seeding) ----------

    // Drives a real Connect through the full pipeline to seed a properly-encrypted Bot row that
    // a subsequent Disconnect can decrypt. Helpers that only stuff fake ciphertext into Mongo
    // would fail the AES-GCM unwrap inside BotService.disconnect.
    private Bot persistRealConnectedBot(String projectId, String token) throws Exception {
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(setWebhookOk());
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/bot/connect")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", token))))
                .andExpect(status().isOk());
        // Drain the requests recorded by this seeding round so individual tests start with a
        // clean recorded-request log.
        drainRequests();
        // Reset the brute-force counter; the seeding helper bumps it from 0 → 1.
        redisTemplate.delete(BRUTE_KEY);
        return botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElseThrow();
    }
}
