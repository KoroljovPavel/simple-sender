package com.botfunnel.bot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
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
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

// IT scope: full HTTP → BotController → BotService → Mongo + Redis stack against the real
// testcontainer Mongo/Redis, with a class-level MockWebServer standing in for api.telegram.org.
// MockWebServer is started in a static initializer so @DynamicPropertySource (registered below)
// can read its URL before the Spring context boots; shut down in @AfterAll so the singleton
// container survives the entire test class lifecycle.
class BotControllerIT extends AbstractIntegrationTest {

    private static final String USER_ID = "bot-it-user-id";
    private static final String OTHER_USER_ID = "bot-it-other-user-id";
    private static final String VALID_TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";
    private static final String VALID_TOKEN_2 = "9876543210:ZYXwvuTSR_qpoNMLkjiHGFedcba9876543210abc";
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

    // Class-level MockWebServer started before Spring context boot. The @DynamicPropertySource
    // needs the URL at registry-build time, so the static initializer must run first; Spring only
    // calls @DynamicPropertySource methods after class loading completes.
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
    @Autowired ReactiveRedisTemplate<String, String> redisTemplate;
    @Autowired org.springframework.context.ApplicationContext applicationContext;

    // @MockitoSpyBean wraps the real bean so Mongo persistence still works for normal tests; only
    // the targeted persist-failure scenario (#postConnect_persistFails…) re-stubs save() to throw.
    // Mockito.reset(botRepositorySpy) in @AfterEach restores real delegation for all other tests.
    @MockitoSpyBean BotRepository botRepositorySpy;
    @MockitoSpyBean ReactiveRedisTemplate<String, String> redisTemplateSpy;

    private ListAppender<ILoggingEvent> botServiceAppender;
    private Logger botServiceLogger;

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

        botRepository.deleteAll().block();
        userRepository.deleteAll().block();
        projectRepository.deleteAll().block();
        eventRepository.deleteAll().block();
        redisTemplate.delete(BRUTE_KEY).block();

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
        userRepository.save(u).block();
    }

    private Project saveActiveProject(String ownerId) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName("BotIT-" + System.nanoTime());
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p).block();
    }

    private Project saveSoftDeletedProject(String ownerId) {
        Project p = saveActiveProject(ownerId);
        p.setDeletedAt(Instant.now());
        return projectRepository.save(p).block();
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
        return botRepository.save(b).block();
    }

    private static MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
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

    private static MockResponse status(int code, String description) {
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
                .until(() -> eventRepository.findAll().filter(predicate).hasElements().block());
    }

    private Event findEvent(Predicate<Event> predicate) {
        return eventRepository.findAll().filter(predicate).blockFirst();
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
        // A response/event-metadata value matches the Telegram-token regex ⇒ token leaked.
        // Anchored regex on full strings, plus substring scan, catches both whole-string and
        // embedded leaks (e.g. an error message that quotes the token).
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
    void postConnect_validToken_returns200WithBotResponse() {
        // AC1 / AC8 / AC10. Asserts: 200 + full BotResponse body shape (lowercase status), no token
        // fields beyond tokenSuffix, persisted Bot row, bot_connected event with correct metadata,
        // setWebhook URL equals ${app.url}/webhooks/telegram/{projectId}, secret_token form param
        // present and non-blank.
        Project project = saveActiveProject(USER_ID);
        enqueueHappyPathConnect();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.telegramBotId").isEqualTo(TELEGRAM_BOT_ID)
                .jsonPath("$.telegramUsername").isEqualTo(TELEGRAM_USERNAME)
                .jsonPath("$.telegramFirstName").isEqualTo(TELEGRAM_FIRST_NAME)
                .jsonPath("$.tokenSuffix").isEqualTo("xyz")
                .jsonPath("$.status").isEqualTo("connected")
                .jsonPath("$.connectedAt").exists()
                .jsonPath("$.encryptedTokenCiphertext").doesNotExist()
                .jsonPath("$.encryptedTokenIv").doesNotExist()
                .jsonPath("$.token").doesNotExist()
                .jsonPath("$.webhookSecretHash").doesNotExist();

        Bot persisted = botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED).block();
        assertThat(persisted).isNotNull();
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
    void postConnect_malformedToken_returns400WithFieldError_andNoTelegramCall() {
        // AC2 / D16: bean-validation regex short-circuits before the service runs. MockWebServer
        // must record zero requests — proves no Telegram call ever fires for a malformed token.
        Project project = saveActiveProject(USER_ID);

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", MALFORMED_TOKEN))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).contains("token"));

        assertThat(drainRequests()).isEmpty();
        assertThat(botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED).block()).isNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_telegramGetMe401_returns422() {
        // AC3: Telegram 401 → AppException unprocessable_entity / invalid_bot_token. No Bot row.
        Project project = saveActiveProject(USER_ID);
        mockTelegram.enqueue(status(401, "Unauthorized"));

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_bot_token");

        assertThat(botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED).block()).isNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_telegram5xxExhausted_returns502() {
        // AC4: 5xx retried per TelegramApiClient.buildRetry (3 retries → 4 attempts total). After
        // exhaustion the chain emits AppException(BAD_GATEWAY, telegram_unavailable).
        Project project = saveActiveProject(USER_ID);
        for (int i = 0; i < 4; i++) {
            mockTelegram.enqueue(status(503, "Service Unavailable"));
        }

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.code").isEqualTo("telegram_unavailable");

        assertThat(botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED).block()).isNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_setWebhook4xxConfigError_returns500WithCodeWebhookConfigError() {
        // AC5 / D8: 4xx with a description on setWebhook → 500 webhook_config_error.
        Project project = saveActiveProject(USER_ID);
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(status(400, "HTTPS url must be provided for webhook"));

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.code").isEqualTo("webhook_config_error");

        // No Bot row may be persisted: the failure happens at setWebhook, before save().
        assertThat(botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED).block()).isNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_platformBotIdAlreadyConnected_returns409BotAlreadyConnected() {
        // AC6 / D1: a CONNECTED row exists for the same telegramBotId in another project. The
        // pre-check (ensureTelegramBotIdNotConnectedAnywhere) trips before setWebhook fires —
        // so getMe is the only Telegram call recorded.
        Project foreignProject = saveActiveProject(OTHER_USER_ID);
        seedConnectedBot(foreignProject.getId(), TELEGRAM_BOT_ID);

        Project myProject = saveActiveProject(USER_ID);
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + myProject.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("bot_already_connected");

        List<RecordedRequest> reqs = drainRequests();
        assertThat(reqs).hasSize(1);
        assertThat(reqs.get(0).getPath()).endsWith("/getMe");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_projectAlreadyHasConnectedBot_returns409BotAlreadyInProject() {
        // AC7 / D5: the project already has a CONNECTED bot. ensureNoConnectedBotForProject trips
        // before any Telegram call, so MockWebServer records zero requests.
        Project project = saveActiveProject(USER_ID);
        seedConnectedBot(project.getId(), TELEGRAM_BOT_ID_2);

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("bot_already_in_project");

        assertThat(drainRequests()).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_persistFails_compensatingDeleteWebhookFires_returns500() {
        // AC9 / D4: simulate a generic persist failure (not a duplicate-key) by stubbing the spy.
        // The compensating deleteWebhook must fire so Telegram is rolled back; the original error
        // surfaces as 500 from the GlobalErrorHandler default branch.
        Project project = saveActiveProject(USER_ID);
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(setWebhookOk());
        mockTelegram.enqueue(deleteWebhookOk());

        doReturn(Mono.error(new RuntimeException("persist failure simulated")))
                .when(botRepositorySpy).save(Mockito.any(Bot.class));

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(500);

        List<RecordedRequest> reqs = drainRequests();
        assertThat(reqs).extracting(RecordedRequest::getPath)
                .anySatisfy(p -> assertThat(p).endsWith("/setWebhook"))
                .anySatisfy(p -> assertThat(p).endsWith("/deleteWebhook"));
        // Order: setWebhook precedes the compensating deleteWebhook.
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
    void postConnect_parallelSameToken_exactlyOneSucceeds_otherReturns409() {
        // AC12: two concurrent Connects, same token, different projects. Both call getMe + setWebhook;
        // the loser hits the platform-wide partial unique index on telegramBotId → DuplicateKeyException
        // mapped to 409 bot_already_connected; loser's compensating deleteWebhook fires.
        Project p1 = saveActiveProject(USER_ID);
        Project p2 = saveActiveProject(USER_ID);

        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(setWebhookOk());
        mockTelegram.enqueue(setWebhookOk());
        mockTelegram.enqueue(deleteWebhookOk());

        Mono<Integer> r1 = postConnect(p1.getId(), VALID_TOKEN);
        Mono<Integer> r2 = postConnect(p2.getId(), VALID_TOKEN);
        List<Integer> statuses = Mono.zip(r1, r2, (a, b) -> List.of(a, b)).block();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        long connected = botRepository.findByProjectId(p1.getId())
                .mergeWith(botRepository.findByProjectId(p2.getId()))
                .filter(b -> b.getStatus() == BotStatus.CONNECTED)
                .count().block();
        assertThat(connected).isEqualTo(1L);

        // setCount may be 1 or 2: if the loser races the winner past the
        // ensureTelegramBotIdNotConnectedAnywhere pre-check it will reach setWebhook (setCount=2)
        // and then need to compensate (delCount=1); if the loser arrives after the winner already
        // persisted, the pre-check trips early (setCount=1, delCount=0). Either ordering is correct
        // — the strong invariants are exactly-one CONNECTED row and the {200, 409} status pair.
        List<RecordedRequest> reqs = drainRequests();
        long setCount = reqs.stream().filter(r -> r.getPath().endsWith("/setWebhook")).count();
        long delCount = reqs.stream().filter(r -> r.getPath().endsWith("/deleteWebhook")).count();
        assertThat(setCount).isBetween(1L, 2L);
        assertThat(delCount).isEqualTo(setCount - 1L);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_parallelSameProjectDifferentTokens_exactlyOneSucceeds_otherReturns409() {
        // D5: two concurrent Connects, same project, different tokens. Loser hits the per-project
        // partial unique index → 409 bot_already_in_project; loser's compensating deleteWebhook fires.
        Project project = saveActiveProject(USER_ID);

        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID_2, "other_bot", "Other"));
        mockTelegram.enqueue(setWebhookOk());
        mockTelegram.enqueue(setWebhookOk());
        mockTelegram.enqueue(deleteWebhookOk());

        Mono<Integer> r1 = postConnect(project.getId(), VALID_TOKEN);
        Mono<Integer> r2 = postConnect(project.getId(), VALID_TOKEN_2);
        List<Integer> statuses = Mono.zip(r1, r2, (a, b) -> List.of(a, b)).block();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        long connected = botRepository.findByProjectId(project.getId())
                .filter(b -> b.getStatus() == BotStatus.CONNECTED)
                .count().block();
        assertThat(connected).isEqualTo(1L);

        // setCount may be 1 or 2 depending on race ordering through
        // ensureNoConnectedBotForProject (see same-token race comment); the strong invariant is
        // exactly-one CONNECTED row + {200, 409} status pair, with delCount tracking setCount-1.
        List<RecordedRequest> reqs = drainRequests();
        long setCount = reqs.stream().filter(r -> r.getPath().endsWith("/setWebhook")).count();
        long delCount = reqs.stream().filter(r -> r.getPath().endsWith("/deleteWebhook")).count();
        assertThat(setCount).isBetween(1L, 2L);
        assertThat(delCount).isEqualTo(setCount - 1L);
    }

    private Mono<Integer> postConnect(String projectId, String token) {
        return Mono.fromCallable(() -> webTestClient.mutateWith(csrf())
                        .post().uri("/api/v1/projects/" + projectId + "/bot/connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("token", token))
                        .exchange()
                        .returnResult(String.class)
                        .getStatus().value())
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_eleventhAttemptWithinWindow_returns429_noTelegramCalls() {
        // AC11 trip: 11 INCRs → counter = 11 > threshold 10 → 429. The 11th attempt issues NO
        // Telegram calls — verified by an empty drain after the request.
        Project project = saveActiveProject(USER_ID);
        // Pre-load the brute-force counter to the threshold so the next attempt trips immediately.
        for (int i = 0; i < 10; i++) {
            redisTemplate.opsForValue().increment(BRUTE_KEY).block();
        }
        redisTemplate.expire(BRUTE_KEY, Duration.ofSeconds(900)).block();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(429);

        assertThat(drainRequests()).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_successfulConnect_deletesBruteForceKey() {
        // AC11 DEL: a successful Connect must DEL the brute-force counter. After the call the
        // key must not exist in Redis.
        Project project = saveActiveProject(USER_ID);
        // Seed a counter so the DEL is observable.
        redisTemplate.opsForValue().increment(BRUTE_KEY).block();
        assertThat(redisTemplate.hasKey(BRUTE_KEY).block()).isTrue();

        enqueueHappyPathConnect();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isOk();

        assertThat(redisTemplate.hasKey(BRUTE_KEY).block()).isFalse();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postConnect_redisDown_failsOpen_connectSucceeds_warnLogged() {
        // AC11 fail-open / D14: stub the spied redisTemplate so opsForValue() returns a mock
        // ReactiveValueOperations whose increment() emits an error. The connect chain's
        // onErrorResume swallows the error, logs the dedicated WARN line once, and continues.
        Project project = saveActiveProject(USER_ID);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> brokenOps = Mockito.mock(ReactiveValueOperations.class);
        Mockito.when(brokenOps.increment(anyString()))
                .thenReturn(Mono.error(new RuntimeException("redis down")));
        doReturn(brokenOps).when(redisTemplateSpy).opsForValue();

        enqueueHappyPathConnect();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isOk();

        long warns = botServiceAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(REDIS_FAIL_OPEN_FRAGMENT))
                .count();
        assertThat(warns).isGreaterThanOrEqualTo(1);
    }

    // ---------- POST /disconnect ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postDisconnect_happyPath_returns200_andUpdatesMongoAtomically() {
        // AC13a / AC13c: disconnect deletes the webhook on Telegram and atomically clears the
        // sensitive fields (status DISCONNECTED, no ciphertext / iv / tokenSuffix / hash, sets
        // disconnectedAt). Bot row is updated in place — id is preserved.
        Project project = saveActiveProject(USER_ID);
        Bot seeded = persistRealConnectedBot(project.getId(), VALID_TOKEN);
        mockTelegram.enqueue(deleteWebhookOk());

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/disconnect")
                .exchange()
                .expectStatus().isOk();

        Bot disconnected = botRepository.findById(seeded.getId()).block();
        assertThat(disconnected).isNotNull();
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
    void postDisconnect_telegramDown_warnLogged_returns200() {
        // AC13b: persistent Telegram failure must NEVER block the local disconnect update.
        Project project = saveActiveProject(USER_ID);
        Bot seeded = persistRealConnectedBot(project.getId(), VALID_TOKEN);
        for (int i = 0; i < 4; i++) {
            mockTelegram.enqueue(status(503, "Service Unavailable"));
        }

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/disconnect")
                .exchange()
                .expectStatus().isOk();

        long warns = botServiceAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(TELEGRAM_DISCONNECT_WARN_FRAGMENT))
                .count();
        assertThat(warns).isEqualTo(1);

        Bot disconnected = botRepository.findById(seeded.getId()).block();
        assertThat(disconnected.getStatus()).isEqualTo(BotStatus.DISCONNECTED);

        awaitEvent(e -> "bot_disconnected".equals(e.getEventType()));
        Event evt = findEvent(e -> "bot_disconnected".equals(e.getEventType()));
        assertThat(evt.getMetadata()).containsEntry("webhookDeleted", false);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postDisconnect_noConnectedBot_returns404() {
        Project project = saveActiveProject(USER_ID);

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/disconnect")
                .exchange()
                .expectStatus().isNotFound();

        assertThat(drainRequests()).isEmpty();
    }

    // ---------- GET / ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getBot_seededConnected_returns200_noneReturns404() {
        // AC14: GET / returns the connected bot when present, 404 otherwise. After Disconnect the
        // GET endpoint must surface 404 (no DISCONNECTED row leaked).
        Project p1 = saveActiveProject(USER_ID);
        seedConnectedBot(p1.getId(), TELEGRAM_BOT_ID);

        webTestClient.get().uri("/api/v1/projects/" + p1.getId() + "/bot")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.telegramBotId").isEqualTo(TELEGRAM_BOT_ID)
                .jsonPath("$.status").isEqualTo("connected")
                .jsonPath("$.encryptedTokenCiphertext").doesNotExist();

        Project p2 = saveActiveProject(USER_ID);
        webTestClient.get().uri("/api/v1/projects/" + p2.getId() + "/bot")
                .exchange()
                .expectStatus().isNotFound();
    }

    // ---------- POST /test-message (06 short-circuit) ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postTestMessage_in06_returns422_zeroTelegramCalls_zeroEvents() {
        // AC15 / D7: in feature 06 the test-message endpoint short-circuits with 422 owner_chat_id_unknown.
        // Zero Telegram calls; zero bot_test_message_sent events.
        Project project = saveActiveProject(USER_ID);
        seedConnectedBot(project.getId(), TELEGRAM_BOT_ID);

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/test-message")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("owner_chat_id_unknown");

        assertThat(drainRequests()).isEmpty();
        boolean hasTestMessage = eventRepository.findAll()
                .filter(e -> "bot_test_message_sent".equals(e.getEventType()))
                .hasElements().block();
        assertThat(hasTestMessage).isFalse();
    }

    // ---------- Anti-enumeration ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void antiEnumeration_foreignSoftDeletedMalformedProjectId_returns404_andHostileBodyIgnored() {
        // AC16: foreign / soft-deleted / malformed all collapse to 404 with no distinguishing body.
        // Hostile body fields are silently dropped by @JsonIgnoreProperties(ignoreUnknown = true).
        Project foreign = saveActiveProject(OTHER_USER_ID);
        Project softDeleted = saveSoftDeletedProject(USER_ID);

        Map<String, Object> hostile = new HashMap<>();
        hostile.put("token", VALID_TOKEN);
        hostile.put("ownerId", "attacker");

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + foreign.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(hostile)
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + softDeleted.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/zzz-not-an-objectid/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isNotFound();

        assertThat(drainRequests()).isEmpty();
    }

    // ---------- Token-leak negative assertion ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void noTokenLeak_inAnyResponseBodyOrEventMetadata() {
        // AC17 / AC23: no HTTP body field and no event-metadata value matches the token regex.
        // Exercise:
        //   1. Happy-path Connect + Disconnect (sanity sweep over bodies + emitted events)
        //   2. setWebhook 4xx with the token echoed inside the Telegram error description —
        //      a leak-prone scenario where a naive error-mapper might propagate the token into
        //      the 5xx response body. The DTO contract + scrubTokens at the log site prevent this;
        //      this assertion would FAIL if either guarantee regressed.
        Project project = saveActiveProject(USER_ID);
        enqueueHappyPathConnect();

        byte[] connectBody = webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        assertNoTokenLeak(connectBody == null ? null : new String(connectBody));

        mockTelegram.enqueue(deleteWebhookOk());
        byte[] discBody = webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/disconnect")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        assertNoTokenLeak(discBody == null ? null : new String(discBody));

        awaitEvent(e -> "bot_disconnected".equals(e.getEventType()));
        List<Event> events = eventRepository.findAll().collectList().block();
        for (Event evt : events) {
            // AC23: the metadata map must never contain a token-shaped value.
            if (evt.getMetadata() != null) {
                for (Object v : evt.getMetadata().values()) {
                    if (v instanceof String s) assertNoTokenLeak(s);
                }
            }
            // ipAddress / userAgent are top-level fields per Event entity.
            assertNoTokenLeak(evt.getIpAddress());
            assertNoTokenLeak(evt.getUserAgent());
        }

        // Leak-prone scenario: Telegram 4xx with the token quoted in `description`. The previous
        // happy-path sweeps cannot fail because no token ever flows into the response shape they
        // produce; this branch is what makes the assertion non-vacuous.
        Project p2 = saveActiveProject(USER_ID);
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID_2, "leakcheck_bot", "Leak"));
        mockTelegram.enqueue(status(400, "Bad webhook for token " + VALID_TOKEN + " on chat"));

        byte[] errBody = webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + p2.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody().returnResult().getResponseBody();
        assertNoTokenLeak(errBody == null ? null : new String(errBody));
    }

    // ---------- Unauthenticated 401 sweep ----------

    @Test
    void anyEndpoint_unauthenticatedBareClient_returns401() {
        // Mirrors ProjectControllerIT#anyEndpoint_unauthenticatedBareClient_returns401: a bare
        // WebTestClient (no @WithMockAppUser, no test-time mutators beyond csrf for state-changing
        // verbs) bound to the in-memory ApplicationContext proves the production filter chain
        // returns 401 across every verb shape on /api/v1/projects/{id}/bot/**.
        var bare = org.springframework.test.web.reactive.server.WebTestClient
                .bindToApplicationContext(applicationContext)
                .configureClient()
                .build();

        bare.get().uri("/api/v1/projects/any-id/bot")
                .exchange()
                .expectStatus().isUnauthorized();

        bare.mutateWith(csrf())
                .post().uri("/api/v1/projects/any-id/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isUnauthorized();

        bare.mutateWith(csrf())
                .post().uri("/api/v1/projects/any-id/bot/disconnect")
                .exchange()
                .expectStatus().isUnauthorized();

        bare.mutateWith(csrf())
                .post().uri("/api/v1/projects/any-id/bot/test-message")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------- Webhook secret hashing ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void webhookSecretInMongo_isSha256Hash() {
        // AC20 / D2: webhookSecretHash in Mongo is hex-encoded SHA-256 (64 hex chars), not the
        // plaintext sent to Telegram. The plaintext only travels the wire to api.telegram.org.
        Project project = saveActiveProject(USER_ID);
        enqueueHappyPathConnect();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isOk();

        Bot persisted = botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED).block();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getWebhookSecretHash()).matches("[0-9a-f]{64}");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void tokenSuffix_persistedAndReturned_clearedOnDisconnect() {
        // AC18 / D17: tokenSuffix is the last 3 chars of the secret. Persisted and surfaced on the
        // BotResponse; cleared (set to null) on Disconnect.
        Project project = saveActiveProject(USER_ID);
        enqueueHappyPathConnect();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", VALID_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tokenSuffix").isEqualTo("xyz");

        Bot connected = botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED).block();
        assertThat(connected.getTokenSuffix()).isEqualTo("xyz");

        mockTelegram.enqueue(deleteWebhookOk());
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + project.getId() + "/bot/disconnect")
                .exchange()
                .expectStatus().isOk();

        Bot disconnected = botRepository.findById(connected.getId()).block();
        assertThat(disconnected.getTokenSuffix()).isNull();
    }

    // ---------- helpers (real-connected seeding) ----------

    // Drives a real Connect through the full pipeline to seed a properly-encrypted Bot row that
    // a subsequent Disconnect can decrypt. Helpers that only stuff fake ciphertext into Mongo
    // would fail the AES-GCM unwrap inside BotService.disconnect.
    private Bot persistRealConnectedBot(String projectId, String token) {
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(setWebhookOk());
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + projectId + "/bot/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", token))
                .exchange()
                .expectStatus().isOk();
        // Drain the requests recorded by this seeding round so individual tests start with a
        // clean recorded-request log.
        drainRequests();
        // Reset the brute-force counter; the seeding helper bumps it from 0 → 1.
        redisTemplate.delete(BRUTE_KEY).block();
        return botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).block();
    }
}
