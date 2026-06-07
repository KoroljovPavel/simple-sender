package com.botfunnel.api;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.common.crypto.Sha256Hex;
import com.botfunnel.funnel.FunnelEventService;
import com.botfunnel.funnel.FunnelExecution;
import com.botfunnel.funnel.FunnelRepository;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberRepository;
import com.botfunnel.subscriber.SubscriberStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Embedded-Mongo/Redis IT for the full {@code POST /api/integrations/v1/events} response matrix
 * (202 / 202-no-op / 404 / 400 / 401 / 429), the uniform-401 across all three key causes, the
 * subscriber_id-wins precedence, anti-IDOR cross-project resolution, lastUsedAt stamping, and
 * error-isolation (engine fault → 202, never 5xx). {@code FunnelEventService} is a {@code @MockitoBean}
 * so each path asserts dispatch vs no-dispatch deterministically and the engine-fault case is
 * injectable; the wiring into a real funnel start is covered by {@link com.botfunnel.funnel.FunnelEventServiceIT}.
 */
class EventsControllerIT extends AbstractIntegrationTest {

    private static final String EVENTS_URL = "/api/integrations/v1/events";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final Long TELEGRAM_BOT_ID = 778899L;
    private static final Long TELEGRAM_USER_ID = 100L;

    @Autowired MongoTemplate mongoTemplate;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired BotRepository botRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired FunnelRepository funnelRepository;

    // The dispatcher is mocked so the controller's response codes are asserted in isolation; the
    // real fan-out wiring is proven by FunnelEventServiceIT.
    @MockitoBean FunnelEventService funnelEventService;

    // Spied so the 429 test can force an over-limit INCR for a single call without flooding 300 requests
    // and without REDIS.stop() (which would poison the shared Testcontainers singleton). Reset per test.
    @MockitoSpyBean StringRedisTemplate redisTemplate;

    private final AtomicLong seq = new AtomicLong(1);
    private String projectId;
    private String subscriberId;
    private String plaintextKey;

    @BeforeEach
    void cleanAndSeed() {
        Mockito.reset(funnelEventService);
        Mockito.reset(redisTemplate); // drop any per-test stub so the spy delegates to live Redis
        mongoTemplate.remove(new Query(), FunnelExecution.class);
        apiKeyRepository.deleteAll();
        funnelRepository.deleteAll();
        subscriberRepository.deleteAll();
        botRepository.deleteAll();
        projectRepository.deleteAll();

        projectId = seedProject();
        seedConnectedBot(projectId);
        subscriberId = seedActiveSubscriber(projectId, TELEGRAM_USER_ID);
        plaintextKey = seedApiKey(projectId);
    }

    @AfterEach
    void resetMock() {
        Mockito.reset(funnelEventService);
        Mockito.reset(redisTemplate);
    }

    // ─── happy paths → 202 ──────────────────────────────────────────────────

    @Test
    void events_validKey_knownUser_listener_returns202() throws Exception {
        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"telegram_user_id\":" + TELEGRAM_USER_ID + "}"))
                .andExpect(status().isAccepted());

        // Dispatched with the resolved project + subscriber, event trigger, depth 0 (external root).
        Mockito.verify(funnelEventService).dispatchForSubscriber(
                projectId, subscriberId, FunnelEventService.TRIGGER_EVENT, "purchase", 0);
    }

    @Test
    void events_validKey_knownUser_noListener_returns202() throws Exception {
        // No listener funnel exists → the dispatcher is a no-op; the endpoint still returns 202.
        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"no_listener\",\"telegram_user_id\":" + TELEGRAM_USER_ID + "}"))
                .andExpect(status().isAccepted());

        Mockito.verify(funnelEventService).dispatchForSubscriber(
                projectId, subscriberId, FunnelEventService.TRIGGER_EVENT, "no_listener", 0);
    }

    // ─── unknown subscriber → 404, never auto-created ───────────────────────

    @Test
    void events_unknownSubscriber_returns404() throws Exception {
        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"telegram_user_id\":999999999}"))
                .andExpect(status().isNotFound());

        // No auto-create: only the one seeded subscriber exists.
        assertThat(subscriberRepository.count()).isEqualTo(1);
        Mockito.verifyNoInteractions(funnelEventService);
    }

    @Test
    void events_crossProjectSubscriberId_returns404() throws Exception {
        // A subscriber that exists but belongs to a DIFFERENT project must collapse to the same 404 as a
        // missing one (anti-IDOR; the by-id lookup is pinned to the key's project).
        String otherProject = seedProject();
        String foreignSubscriberId = seedActiveSubscriber(otherProject, 555L);

        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"subscriber_id\":\"" + foreignSubscriberId + "\"}"))
                .andExpect(status().isNotFound());

        Mockito.verifyNoInteractions(funnelEventService);
    }

    // ─── 400 — two distinct sub-paths ───────────────────────────────────────

    @Test
    void events_malformedEventNameSlug_returns400() throws Exception {
        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"!bad slug\",\"telegram_user_id\":" + TELEGRAM_USER_ID + "}"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(funnelEventService);
    }

    @Test
    void events_missingBothIdentifiers_returns400() throws Exception {
        // event_name valid, but neither identifier present → body malformed → 400 (not 404).
        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\"}"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(funnelEventService);
    }

    // ─── subscriber_id precedence ───────────────────────────────────────────

    @Test
    void events_subscriberIdWins_whenBothPresent() throws Exception {
        // subscriber_id points at the seeded subscriber; telegram_user_id is a bogus number. If
        // precedence is wrong it would 404 on the bogus user; correct precedence resolves by id → 202.
        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"subscriber_id\":\"" + subscriberId
                                + "\",\"telegram_user_id\":424242}"))
                .andExpect(status().isAccepted());

        Mockito.verify(funnelEventService).dispatchForSubscriber(
                projectId, subscriberId, FunnelEventService.TRIGGER_EVENT, "purchase", 0);
    }

    // ─── 401 — uniform across all three key causes ──────────────────────────

    @Test
    void events_noKey_returns401() throws Exception {
        mockMvc.perform(post(EVENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"telegram_user_id\":" + TELEGRAM_USER_ID + "}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void events_malformedKey_returns401() throws Exception {
        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"telegram_user_id\":" + TELEGRAM_USER_ID + "}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void events_unknownKey_returns401() throws Exception {
        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, "deadbeef-not-a-real-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"telegram_user_id\":" + TELEGRAM_USER_ID + "}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void events_401_uniform_acrossAllThreeKeyCauses() throws Exception {
        // Status + body must be byte-identical for missing / malformed / unknown — no enumeration oracle.
        MvcResult missing = mockMvc.perform(post(EVENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        MvcResult malformed = mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, "   ")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        MvcResult unknown = mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, "deadbeef-not-a-real-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();

        int status = missing.getResponse().getStatus();
        assertThat(status).isEqualTo(401);
        assertThat(malformed.getResponse().getStatus()).isEqualTo(status);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(status);

        String body = missing.getResponse().getContentAsString();
        assertThat(malformed.getResponse().getContentAsString()).isEqualTo(body);
        assertThat(unknown.getResponse().getContentAsString()).isEqualTo(body);
    }

    // ─── 429 — over the global rate-limit (Redis-spy forces over-limit count) ─

    @Test
    void events_overRateLimit_returns429() throws Exception {
        // Stub the spied INCR to return a count above the default 300/min ceiling for this one call.
        ValueOperations<String, String> ops = mock();
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(EventsController.RATE_KEY)).thenReturn(99999L);

        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"telegram_user_id\":" + TELEGRAM_USER_ID + "}"))
                .andExpect(status().isTooManyRequests());

        Mockito.verifyNoInteractions(funnelEventService);
    }

    @Test
    void events_redisDown_failsOpen_returns202() throws Exception {
        // Redis outage on the rate-limit INCR must NOT 5xx the endpoint — it fails open → 202.
        ValueOperations<String, String> ops = mock();
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(EventsController.RATE_KEY)).thenThrow(new RuntimeException("redis down"));

        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"telegram_user_id\":" + TELEGRAM_USER_ID + "}"))
                .andExpect(status().isAccepted());

        Mockito.verify(funnelEventService).dispatchForSubscriber(
                projectId, subscriberId, FunnelEventService.TRIGGER_EVENT, "purchase", 0);
    }

    // ─── error-isolation — dispatcher throws → 202, never 5xx ───────────────

    @Test
    void events_engineFault_returns202_noopNo5xx() throws Exception {
        doThrow(new RuntimeException("engine boom")).when(funnelEventService)
                .dispatchForSubscriber(anyString(), anyString(), anyString(), anyString(), Mockito.anyInt());

        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"telegram_user_id\":" + TELEGRAM_USER_ID + "}"))
                .andExpect(status().isAccepted());
    }

    // ─── lastUsedAt stamped on the authenticated call ───────────────────────

    @Test
    void events_validKey_stampsLastUsedAt() throws Exception {
        assertThat(apiKeyRepository.findByProjectId(projectId).orElseThrow().getLastUsedAt()).isNull();

        mockMvc.perform(post(EVENTS_URL)
                        .header(API_KEY_HEADER, plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"telegram_user_id\":" + TELEGRAM_USER_ID + "}"))
                .andExpect(status().isAccepted());

        assertThat(apiKeyRepository.findByProjectId(projectId).orElseThrow().getLastUsedAt()).isNotNull();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private String seedProject() {
        Project p = new Project();
        p.setOwnerId("owner-" + seq.incrementAndGet());
        p.setName("Test");
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p).getId();
    }

    private String seedApiKey(String projectId) {
        String plaintext = "test-plaintext-key-" + seq.incrementAndGet();
        ApiKey key = new ApiKey();
        key.setProjectId(projectId);
        key.setKeyHash(Sha256Hex.hex(plaintext));
        key.setKeyPrefix(plaintext.substring(0, 8));
        key.setCreatedAt(Instant.now());
        key.setLastUsedAt(null);
        apiKeyRepository.save(key);
        return plaintext;
    }

    private String seedActiveSubscriber(String projectId, Long telegramUserId) {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(telegramUserId);
        s.setTelegramChatId(telegramUserId);
        s.setTelegramBotId(TELEGRAM_BOT_ID);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        return subscriberRepository.save(s).getId();
    }

    private void seedConnectedBot(String projectId) {
        Bot bot = new Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setTelegramUsername("event_bot_" + seq.incrementAndGet());
        bot.setStatus(BotStatus.CONNECTED);
        bot.setConnectedAt(Instant.now());
        botRepository.save(bot);
    }
}
