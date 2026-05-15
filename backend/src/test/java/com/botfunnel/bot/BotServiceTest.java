package com.botfunnel.bot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.bot.dto.TelegramUser;
import com.botfunnel.common.AppException;
import com.botfunnel.common.crypto.EncryptedValue;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.events.EventService;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotServiceTest {

    private static final String OWNER_ID = "owner-1";
    private static final String PROJECT_ID = "proj-1";
    private static final String IP = "127.0.0.1";
    private static final String UA = "JUnit/5";
    private static final String APP_URL = "https://app.example";
    private static final String TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";
    private static final Long TELEGRAM_BOT_ID = 9876543210L;
    private static final String TELEGRAM_USERNAME = "test_bot";
    private static final String TELEGRAM_FIRST_NAME = "Test";
    private static final String BRUTE_KEY = "brute:bot-connect:" + OWNER_ID;
    private static final String REDIS_FAIL_OPEN_WARN_FRAGMENT =
            "bot-connect brute-force counter Redis failure (fail-open)";
    private static final Pattern TOKEN_REGEX = Pattern.compile("^\\d{1,20}:[A-Za-z0-9_-]{30,50}$");

    @Mock
    private BotRepository botRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private TokenEncryptor tokenEncryptor;

    @Mock
    private TelegramApiClient telegramApiClient;

    @Mock
    private EventService eventService;

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    private BotService service;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        service = new BotService(botRepository, projectService, tokenEncryptor,
                telegramApiClient, eventService, redisTemplate, APP_URL);

        logger = (Logger) LoggerFactory.getLogger(BotService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    // ---------- Connect ----------

    @Test
    void connect_happyPath_runsSideEffectsInDocumentedOrder() {
        stubHappyPathMocks(1L);
        ArgumentCaptor<Bot> botCaptor = ArgumentCaptor.forClass(Bot.class);
        when(botRepository.save(botCaptor.capture())).thenAnswer(inv -> Mono.just(withId(inv.getArgument(0))));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectNextMatches(bot -> bot.getTelegramBotId().equals(TELEGRAM_BOT_ID))
                .verifyComplete();

        InOrder order = inOrder(projectService, valueOperations, botRepository,
                telegramApiClient, tokenEncryptor, eventService, redisTemplate);
        order.verify(projectService).requireOwned(OWNER_ID, PROJECT_ID, false);
        order.verify(valueOperations).increment(BRUTE_KEY);
        order.verify(botRepository).findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED);
        order.verify(telegramApiClient).getMe(TOKEN);
        order.verify(botRepository).findFirstByTelegramBotIdAndStatus(TELEGRAM_BOT_ID, BotStatus.CONNECTED);
        order.verify(telegramApiClient).setWebhook(eq(TOKEN), anyString(), anyString());
        order.verify(tokenEncryptor).encrypt(TOKEN);
        order.verify(botRepository).save(any(Bot.class));
        order.verify(eventService).logEvent(eq(OWNER_ID), eq("bot_connected"), eq(IP), eq(UA), anyMap());
        order.verify(redisTemplate).delete(BRUTE_KEY);
    }

    @Test
    void connect_persistFails_compensatesDeleteWebhookAndPropagatesError() {
        stubHappyPathMocks(1L);
        RuntimeException persistErr = new RuntimeException("mongo down");
        when(botRepository.save(any(Bot.class))).thenReturn(Mono.error(persistErr));
        when(telegramApiClient.deleteWebhook(TOKEN)).thenReturn(Mono.just(true));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectErrorMatches(err -> err == persistErr)
                .verify();

        InOrder order = inOrder(telegramApiClient);
        order.verify(telegramApiClient).setWebhook(eq(TOKEN), anyString(), anyString());
        order.verify(telegramApiClient).deleteWebhook(TOKEN);
        verify(telegramApiClient, times(1)).deleteWebhook(TOKEN);
    }

    @Test
    void connect_duplicateKeyOnProjectIndex_returns409BotAlreadyInProject_andCompensates() {
        stubHappyPathMocks(1L);
        when(botRepository.save(any(Bot.class))).thenReturn(Mono.error(
                new DuplicateKeyException("E11000 duplicate key error: projectId_unique_connected dup key")));
        when(telegramApiClient.deleteWebhook(TOKEN)).thenReturn(Mono.just(true));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(app.getCode()).isEqualTo("bot_already_in_project");
                })
                .verify();

        verify(telegramApiClient, times(1)).deleteWebhook(TOKEN);
    }

    @Test
    void connect_duplicateKeyOnTelegramBotIdIndex_returns409BotAlreadyConnected_andCompensates() {
        stubHappyPathMocks(1L);
        when(botRepository.save(any(Bot.class))).thenReturn(Mono.error(
                new DuplicateKeyException("E11000 duplicate key error: telegramBotId_unique_connected dup key")));
        when(telegramApiClient.deleteWebhook(TOKEN)).thenReturn(Mono.just(true));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(app.getCode()).isEqualTo("bot_already_connected");
                })
                .verify();

        verify(telegramApiClient, times(1)).deleteWebhook(TOKEN);
    }

    @Test
    void connect_rateLimitThreshold_returns429OnEleventhAttempt_noTelegramCall() {
        when(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .thenReturn(Mono.just(stubProject()));
        when(valueOperations.increment(BRUTE_KEY)).thenReturn(Mono.just(11L));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    assertThat(((AppException) err).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                })
                .verify();

        verifyNoInteractions(telegramApiClient);
        verifyNoInteractions(tokenEncryptor);
        verify(botRepository, never()).save(any());
    }

    @Test
    void connect_redisDownOnIncrement_failsOpenAndLogsWarn() {
        stubHappyPathMocks(1L);
        when(valueOperations.increment(BRUTE_KEY)).thenReturn(Mono.error(new RuntimeException("redis down")));
        when(botRepository.save(any(Bot.class))).thenAnswer(inv -> Mono.just(withId(inv.getArgument(0))));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectNextCount(1)
                .verifyComplete();

        long warns = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(REDIS_FAIL_OPEN_WARN_FRAGMENT))
                .count();
        assertThat(warns).isEqualTo(1);
    }

    @Test
    void connect_redisDownOnDelete_failsOpenAndLogsWarn() {
        stubHappyPathMocks(1L);
        when(redisTemplate.delete(BRUTE_KEY)).thenReturn(Mono.error(new RuntimeException("redis down")));
        when(botRepository.save(any(Bot.class))).thenAnswer(inv -> Mono.just(withId(inv.getArgument(0))));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectNextCount(1)
                .verifyComplete();

        long warns = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(REDIS_FAIL_OPEN_WARN_FRAGMENT))
                .count();
        assertThat(warns).isEqualTo(1);
    }

    @Test
    void connect_successDelsBruteForceCounter() {
        stubHappyPathMocks(1L);
        when(botRepository.save(any(Bot.class))).thenAnswer(inv -> Mono.just(withId(inv.getArgument(0))));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectNextCount(1)
                .verifyComplete();

        verify(redisTemplate, times(1)).delete(BRUTE_KEY);
    }

    @Test
    void connect_webhookSecretIsHashedWithSha256_neverPlaintext() {
        stubHappyPathMocks(1L);
        ArgumentCaptor<String> secretCaptor = ArgumentCaptor.forClass(String.class);
        when(telegramApiClient.setWebhook(eq(TOKEN), anyString(), secretCaptor.capture()))
                .thenReturn(Mono.just(true));
        ArgumentCaptor<Bot> botCaptor = ArgumentCaptor.forClass(Bot.class);
        when(botRepository.save(botCaptor.capture()))
                .thenAnswer(inv -> Mono.just(withId(inv.getArgument(0))));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectNextCount(1)
                .verifyComplete();

        String plaintextSecret = secretCaptor.getValue();
        Bot saved = botCaptor.getValue();
        String storedHash = saved.getWebhookSecretHash();

        assertThat(storedHash).hasSize(64);
        assertThat(storedHash).matches("[0-9a-f]{64}");
        assertThat(storedHash).isEqualTo(sha256Hex(plaintextSecret));
        assertThat(storedHash).isNotEqualTo(plaintextSecret);
    }

    @Test
    void connect_tokenSuffixIsLastThreeCharacters() {
        stubHappyPathMocks(1L);
        ArgumentCaptor<Bot> botCaptor = ArgumentCaptor.forClass(Bot.class);
        when(botRepository.save(botCaptor.capture()))
                .thenAnswer(inv -> Mono.just(withId(inv.getArgument(0))));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(botCaptor.getValue().getTokenSuffix()).isEqualTo("xyz");
    }

    @Test
    void connect_encryptedTokenAndIvAreBase64EncodedBeforeSave() {
        stubHappyPathMocks(1L);
        byte[] iv = new byte[]{1, 2, 3};
        byte[] ct = new byte[]{4, 5, 6, 7, 8};
        when(tokenEncryptor.encrypt(TOKEN)).thenReturn(new EncryptedValue(iv, ct));
        ArgumentCaptor<Bot> botCaptor = ArgumentCaptor.forClass(Bot.class);
        when(botRepository.save(botCaptor.capture()))
                .thenAnswer(inv -> Mono.just(withId(inv.getArgument(0))));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectNextCount(1)
                .verifyComplete();

        Bot saved = botCaptor.getValue();
        assertThat(saved.getEncryptedTokenIv()).isEqualTo(Base64.getEncoder().encodeToString(iv));
        assertThat(saved.getEncryptedTokenCiphertext()).isEqualTo(Base64.getEncoder().encodeToString(ct));
    }

    @Test
    void connect_bot_connected_event_metadata_containsNoTokenRegex() {
        stubHappyPathMocks(1L);
        when(botRepository.save(any(Bot.class)))
                .thenAnswer(inv -> Mono.just(withId(inv.getArgument(0))));

        StepVerifier.create(service.connect(OWNER_ID, PROJECT_ID, TOKEN, IP, UA))
                .expectNextCount(1)
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).logEvent(eq(OWNER_ID), eq("bot_connected"), eq(IP), eq(UA), metaCaptor.capture());

        Map<String, Object> meta = metaCaptor.getValue();
        for (Object value : meta.values()) {
            if (value instanceof String s) {
                assertThat(TOKEN_REGEX.matcher(s).matches())
                        .as("metadata value '%s' must not match Telegram token regex", s)
                        .isFalse();
            }
        }
    }

    // ---------- Disconnect ----------

    @Test
    void disconnect_base64DecodesIvAndCiphertextBeforeDecrypt() {
        byte[] iv = new byte[]{1, 2, 3};
        byte[] ct = new byte[]{4, 5, 6, 7, 8};
        Bot existing = seedConnectedBot(iv, ct);

        when(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .thenReturn(Mono.just(stubProject()));
        when(botRepository.findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED))
                .thenReturn(Mono.just(existing));
        when(tokenEncryptor.decrypt(any(byte[].class), any(byte[].class))).thenReturn(TOKEN);
        when(telegramApiClient.deleteWebhook(TOKEN)).thenReturn(Mono.just(true));
        when(botRepository.save(any(Bot.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.disconnect(OWNER_ID, PROJECT_ID, IP, UA)).verifyComplete();

        ArgumentCaptor<byte[]> ivCap = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> ctCap = ArgumentCaptor.forClass(byte[].class);
        verify(tokenEncryptor).decrypt(ivCap.capture(), ctCap.capture());
        assertThat(ivCap.getValue()).isEqualTo(iv);
        assertThat(ctCap.getValue()).isEqualTo(ct);
    }

    @Test
    void disconnect_telegramDown_logsWarnAndStillCompletes() {
        Bot existing = seedConnectedBot(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
        when(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .thenReturn(Mono.just(stubProject()));
        when(botRepository.findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED))
                .thenReturn(Mono.just(existing));
        when(tokenEncryptor.decrypt(any(byte[].class), any(byte[].class))).thenReturn(TOKEN);
        when(telegramApiClient.deleteWebhook(TOKEN))
                .thenReturn(Mono.error(new RuntimeException("telegram down")));
        ArgumentCaptor<Bot> botCaptor = ArgumentCaptor.forClass(Bot.class);
        when(botRepository.save(botCaptor.capture())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.disconnect(OWNER_ID, PROJECT_ID, IP, UA)).verifyComplete();

        long warns = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .count();
        assertThat(warns).isGreaterThanOrEqualTo(1);

        Bot saved = botCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(BotStatus.DISCONNECTED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).logEvent(eq(OWNER_ID), eq("bot_disconnected"), eq(IP), eq(UA),
                metaCaptor.capture());
        assertThat(metaCaptor.getValue()).containsEntry("webhookDeleted", false);
    }

    @Test
    void disconnect_happyPath_emitsBotDisconnectedEventWithDeletedTrue() {
        Bot existing = seedConnectedBot(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
        when(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .thenReturn(Mono.just(stubProject()));
        when(botRepository.findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED))
                .thenReturn(Mono.just(existing));
        when(tokenEncryptor.decrypt(any(byte[].class), any(byte[].class))).thenReturn(TOKEN);
        when(telegramApiClient.deleteWebhook(TOKEN)).thenReturn(Mono.just(true));
        ArgumentCaptor<Bot> botCaptor = ArgumentCaptor.forClass(Bot.class);
        when(botRepository.save(botCaptor.capture())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.disconnect(OWNER_ID, PROJECT_ID, IP, UA)).verifyComplete();

        Bot saved = botCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(BotStatus.DISCONNECTED);
        assertThat(saved.getEncryptedTokenCiphertext()).isNull();
        assertThat(saved.getEncryptedTokenIv()).isNull();
        assertThat(saved.getTokenSuffix()).isNull();
        assertThat(saved.getWebhookSecretHash()).isNull();
        assertThat(saved.getDisconnectedAt()).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).logEvent(eq(OWNER_ID), eq("bot_disconnected"), eq(IP), eq(UA),
                metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertThat(meta).containsEntry("webhookDeleted", true);
        assertThat(meta).containsEntry("projectId", PROJECT_ID);
        assertThat(meta).containsEntry("telegramBotId", TELEGRAM_BOT_ID);
    }

    @Test
    void disconnect_noConnectedBot_returns404() {
        when(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .thenReturn(Mono.just(stubProject()));
        when(botRepository.findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.disconnect(OWNER_ID, PROJECT_ID, IP, UA))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    assertThat(((AppException) err).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();

        verifyNoInteractions(telegramApiClient);
    }

    // ---------- Send Test Message ----------

    @Test
    void sendTestMessage_returns422_noTelegramCalls_noEvents() {
        Bot existing = seedConnectedBot(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
        when(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .thenReturn(Mono.just(stubProject()));
        when(botRepository.findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(service.sendTestMessage(OWNER_ID, PROJECT_ID, IP, UA))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(app.getCode()).isEqualTo("owner_chat_id_unknown");
                })
                .verify();

        verifyNoInteractions(telegramApiClient);
        verifyNoInteractions(eventService);
    }

    @Test
    void sendTestMessage_noConnectedBot_returns404() {
        when(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .thenReturn(Mono.just(stubProject()));
        when(botRepository.findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.sendTestMessage(OWNER_ID, PROJECT_ID, IP, UA))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    assertThat(((AppException) err).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();

        verifyNoInteractions(telegramApiClient);
        verifyNoInteractions(eventService);
    }

    // ---------- Get ----------

    @Test
    void getByProject_happyPath_returnsBot_noDecryption() {
        Bot existing = seedConnectedBot(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
        when(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .thenReturn(Mono.just(stubProject()));
        when(botRepository.findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(service.getByProject(OWNER_ID, PROJECT_ID))
                .expectNextMatches(b -> b.getEncryptedTokenCiphertext() != null
                        && b.getEncryptedTokenIv() != null
                        && b.getStatus() == BotStatus.CONNECTED)
                .verifyComplete();

        verifyNoInteractions(tokenEncryptor);
        verifyNoInteractions(telegramApiClient);
    }

    @Test
    void getByProject_noConnectedBot_returns404() {
        when(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .thenReturn(Mono.just(stubProject()));
        when(botRepository.findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.getByProject(OWNER_ID, PROJECT_ID))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    assertThat(((AppException) err).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }

    // ---------- helpers ----------

    private void stubHappyPathMocks(long incrementValue) {
        // Every stub here is lenient: this helper sets up a complete happy-path scaffold; individual
        // tests intentionally re-stub one or two collaborators to inject failure paths or capture
        // arguments. Strict stubbing would treat overridden stubs as "unused".
        lenient().when(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .thenReturn(Mono.just(stubProject()));
        lenient().when(valueOperations.increment(BRUTE_KEY)).thenReturn(Mono.just(incrementValue));
        lenient().when(redisTemplate.expire(eq(BRUTE_KEY), any(Duration.class))).thenReturn(Mono.just(true));
        lenient().when(botRepository.findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED))
                .thenReturn(Mono.empty());
        lenient().when(telegramApiClient.getMe(TOKEN)).thenReturn(Mono.just(new TelegramUser(
                TELEGRAM_BOT_ID, true, TELEGRAM_FIRST_NAME, TELEGRAM_USERNAME)));
        lenient().when(botRepository.findFirstByTelegramBotIdAndStatus(TELEGRAM_BOT_ID, BotStatus.CONNECTED))
                .thenReturn(Mono.empty());
        lenient().when(telegramApiClient.setWebhook(eq(TOKEN), anyString(), anyString()))
                .thenReturn(Mono.just(true));
        lenient().when(tokenEncryptor.encrypt(TOKEN))
                .thenReturn(new EncryptedValue(new byte[]{9, 9, 9}, new byte[]{8, 8, 8, 8}));
        lenient().when(redisTemplate.delete(BRUTE_KEY)).thenReturn(Mono.just(1L));
        lenient().doNothing().when(eventService).logEvent(anyString(), anyString(), anyString(),
                anyString(), anyMap());
    }

    private Bot withId(Bot bot) {
        bot.setId("bot-id-1");
        return bot;
    }

    private Bot seedConnectedBot(byte[] iv, byte[] ct) {
        Bot bot = new Bot();
        bot.setId("bot-id-1");
        bot.setProjectId(PROJECT_ID);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setTelegramUsername(TELEGRAM_USERNAME);
        bot.setTelegramFirstName(TELEGRAM_FIRST_NAME);
        bot.setStatus(BotStatus.CONNECTED);
        bot.setEncryptedTokenIv(Base64.getEncoder().encodeToString(iv));
        bot.setEncryptedTokenCiphertext(Base64.getEncoder().encodeToString(ct));
        bot.setTokenSuffix("xyz");
        bot.setWebhookSecretHash("a".repeat(64));
        return bot;
    }

    private Project stubProject() {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setOwnerId(OWNER_ID);
        return p;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
