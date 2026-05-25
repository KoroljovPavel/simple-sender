package com.botfunnel.subscriber;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.bot.PersonalMessageSenderTestConfig;
import com.botfunnel.common.crypto.EncryptedValue;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Personal-message send maps TelegramSender outcomes to AC5 responses. Drives the real controller →
// (short-timeout @Primary) TelegramSender → MockWebServer-stubbed Telegram. The short timeout keeps the
// 429-retry-exhaustion path to ~3s instead of the production 30s budget.
@Import(PersonalMessageSenderTestConfig.class)
class SubscriberPersonalMessageIT extends AbstractIntegrationTest {

    private static final String USER_ID = "pm-it-user";
    private static final Long TELEGRAM_BOT_ID = 8888001L;
    private static final Long CHAT_ID = 500L;
    private static final String TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789";

    private static final MockWebServer TELEGRAM = new MockWebServer();

    static {
        try {
            TELEGRAM.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void telegramProps(DynamicPropertyRegistry registry) {
        registry.add("app.telegram.base-url", () -> TELEGRAM.url("/").toString());
        // Pin the personal-message bucket so the rate-limit test's magic number is explicit.
        registry.add("app.subscriber.rate-limit.personal-message-per-min", () -> "60");
    }

    @AfterAll
    static void shutdown() throws IOException {
        TELEGRAM.shutdown();
    }

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired SubscriberEventRepository subscriberEventRepository;
    @Autowired BotRepository botRepository;
    @Autowired TokenEncryptor tokenEncryptor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String projectId;
    private String subscriberId;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        subscriberRepository.deleteAll();
        subscriberEventRepository.deleteAll();
        botRepository.deleteAll();
        // Fresh QueueDispatcher each test → clears any responses left unconsumed by a prior test.
        TELEGRAM.setDispatcher(new QueueDispatcher());

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("pm@test.com");
        u.setName("Owner");
        u.setPasswordHash("x");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);

        Project p = new Project();
        p.setOwnerId(USER_ID);
        p.setName("Proj-" + System.nanoTime());
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectId = projectRepository.save(p).getId();

        EncryptedValue ev = tokenEncryptor.encrypt(TOKEN);
        Bot bot = new Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setStatus(BotStatus.CONNECTED);
        bot.setEncryptedTokenIv(Base64.getEncoder().encodeToString(ev.iv()));
        bot.setEncryptedTokenCiphertext(Base64.getEncoder().encodeToString(ev.ciphertext()));
        bot.setConnectedAt(Instant.now());
        botRepository.save(bot);

        subscriberId = seedSubscriber(CHAT_ID);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void send_happyPath_returns200AndWritesPersonalMessageSent() throws Exception {
        TELEGRAM.enqueue(ok200());

        mockMvc.perform(send("Привіт!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"));

        assertThat(eventsFor("personal_message_sent")).hasSize(1);
        assertThat(eventsFor("personal_message_failed")).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void send_403blocked_marksBlockedAndReturnsBlockedStatus_noPersonalMessageFailedEvent() throws Exception {
        TELEGRAM.enqueue(json(403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}"));

        mockMvc.perform(send("hi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("blocked"));

        assertThat(subscriberRepository.findById(subscriberId).orElseThrow().getStatus())
                .isEqualTo(SubscriberStatus.BLOCKED);
        // subscriber_blocked is written by the sender hook (Task 6); the controller does NOT write
        // personal_message_failed for a terminal reason that the sender already handled.
        assertThat(eventsFor("subscriber_blocked")).hasSize(1);
        assertThat(eventsFor("personal_message_failed")).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void send_400chatNotFound_marksDeletedAndReturnsDeletedStatus_noPersonalMessageFailedEvent() throws Exception {
        TELEGRAM.enqueue(json(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}"));

        mockMvc.perform(send("hi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("deleted"));

        assertThat(subscriberRepository.findById(subscriberId).orElseThrow().getStatus())
                .isEqualTo(SubscriberStatus.DELETED);
        assertThat(eventsFor("subscriber_deleted")).hasSize(1);
        assertThat(eventsFor("personal_message_failed")).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void send_400otherReason_writesPersonalMessageFailedEvent_withTerminalReasonOTHER() throws Exception {
        TELEGRAM.enqueue(json(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message text is too long\"}"));

        mockMvc.perform(send("hi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("telegram_send_failed"));

        List<SubscriberEvent> failed = eventsFor("personal_message_failed");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).getMetadata()).containsEntry("terminalReason", "OTHER");
        // Subscriber state is untouched for an OTHER failure.
        assertThat(subscriberRepository.findById(subscriberId).orElseThrow().getStatus())
                .isEqualTo(SubscriberStatus.ACTIVE);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void send_429AfterRetryExhausted_returns503TelegramRateLimited() throws Exception {
        // Always-429 dispatcher → the sender exhausts its (short, 3s) retry budget → 503.
        TELEGRAM.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return json(429, "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":0}}");
            }
        });

        mockMvc.perform(send("hi"))
                .andExpect(status().is(503))
                .andExpect(jsonPath("$.code").value("telegram_rate_limited"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void send_textBlank_returns400() throws Exception {
        int before = TELEGRAM.getRequestCount(); // cumulative across the class — assert a zero delta
        mockMvc.perform(send("   "))
                .andExpect(status().isBadRequest());
        assertThat(TELEGRAM.getRequestCount() - before).isEqualTo(0);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void send_text4097chars_returns400() throws Exception {
        int before = TELEGRAM.getRequestCount(); // cumulative across the class — assert a zero delta
        mockMvc.perform(send("a".repeat(4097)))
                .andExpect(status().isBadRequest());
        assertThat(TELEGRAM.getRequestCount() - before).isEqualTo(0);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void rate_limited_returns429_andNoSenderCalled() throws Exception {
        for (int i = 0; i < 60; i++) {
            TELEGRAM.enqueue(ok200());
        }
        int before = TELEGRAM.getRequestCount();

        for (int i = 0; i < 60; i++) {
            mockMvc.perform(send("msg " + i)).andExpect(status().isOk());
        }
        // 61st within the same minute → rejected before the sender is invoked.
        mockMvc.perform(send("overflow"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.code").value("personal_message_rate_limited"));

        assertThat(TELEGRAM.getRequestCount() - before)
                .as("exactly 60 outbound sends; the 61st never reached Telegram")
                .isEqualTo(60);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_hostileBodyMassAssignment_dropped() throws Exception {
        TELEGRAM.enqueue(ok200());

        mockMvc.perform(post(url() + "/" + subscriberId + "/messages").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hi\",\"evil_key\":\"x\",\"status\":\"hacked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String url() {
        return "/api/v1/projects/" + projectId + "/subscribers";
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder send(String text)
            throws Exception {
        return post(url() + "/" + subscriberId + "/messages").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("text", text)));
    }

    private String seedSubscriber(long chatId) {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(chatId);
        s.setTelegramChatId(chatId);
        s.setTelegramBotId(TELEGRAM_BOT_ID);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setTags(new ArrayList<>());
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        return subscriberRepository.save(s).getId();
    }

    private List<SubscriberEvent> eventsFor(String type) {
        return subscriberEventRepository.findAll().stream()
                .filter(e -> type.equals(e.getEventType()))
                .toList();
    }

    private static MockResponse ok200() {
        return json(200, "{\"ok\":true,\"result\":{\"message_id\":1001,\"chat\":{\"id\":" + CHAT_ID + "}}}");
    }

    private static MockResponse json(int statusCode, String body) {
        return new MockResponse()
                .setResponseCode(statusCode)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
