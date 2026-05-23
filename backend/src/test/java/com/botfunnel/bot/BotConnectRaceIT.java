package com.botfunnel.bot;

import com.botfunnel.JobRunrInMemoryConfig;
import com.botfunnel.common.test.ConcurrencyTestUtils;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Race tests live here (not in BotControllerIT) per D14 — MockMvc's in-process DispatcherServlet
// dispatch does NOT reliably interleave critical sections under a parked-VT release barrier. Real
// transport (loopback Tomcat at RANDOM_PORT) is required to genuinely race two POSTs through the
// security filter chain + service layer. Uses ConcurrencyTestUtils.parallelInvoke(...) (D10) to
// guarantee N VTs are parked at a CountDownLatch barrier before simultaneous release.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JobRunrInMemoryConfig.class)
class BotConnectRaceIT {

    private static final String USER_ID = "race-user-id";
    private static final String EMAIL = "race@test.com";
    private static final String PASSWORD = "RaceTestPwd1";
    private static final String VALID_TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";
    private static final String VALID_TOKEN_2 = "9876543210:ZYXwvuTSR_qpoNMLkjiHGFedcba9876543210abc";
    private static final Long TELEGRAM_BOT_ID = 9876543210L;
    private static final Long TELEGRAM_BOT_ID_2 = 1234567890L;
    private static final String TELEGRAM_USERNAME = "test_bot";
    private static final String TELEGRAM_FIRST_NAME = "Test";

    private static final MockWebServer mockTelegram;

    static {
        mockTelegram = new MockWebServer();
        try {
            mockTelegram.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start MockWebServer for BotConnectRaceIT", e);
        }
    }

    @DynamicPropertySource
    static void registerTelegramBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("app.telegram.base-url", () -> mockTelegram.url("/").toString());
        // Reuse the singleton Mongo + Redis testcontainers started by AbstractIntegrationTest's
        // static block. This class deliberately does NOT extend AbstractIntegrationTest because
        // we need RANDOM_PORT (real Tomcat) instead of the inherited MOCK environment that
        // Task 10 will set there.
        registry.add("spring.data.mongodb.uri", com.botfunnel.AbstractIntegrationTest.MONGO_DB::getReplicaSetUrl);
        registry.add("spring.data.redis.url",
                () -> "redis://" + com.botfunnel.AbstractIntegrationTest.REDIS.getHost()
                        + ":" + com.botfunnel.AbstractIntegrationTest.REDIS.getFirstMappedPort());
    }

    @AfterAll
    static void shutdownTelegramMock() throws IOException {
        mockTelegram.shutdown();
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired BotRepository botRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        java.util.Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        seedUser();
    }

    private void seedUser() {
        User u = new User();
        u.setId(USER_ID);
        u.setEmail(EMAIL);
        u.setName("Race User");
        u.setPasswordHash(passwordEncoder.encode(PASSWORD));
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
    }

    private Project saveActiveProject() {
        Project p = new Project();
        p.setOwnerId(USER_ID);
        p.setName("Race-" + System.nanoTime());
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p);
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

    @Test
    void postConnect_parallelSameToken_exactlyOneSucceeds_otherReturns409() throws Exception {
        // AC12 / D14: two concurrent Connects, same token, different projects. Both call getMe +
        // setWebhook; the loser hits the platform-wide partial unique index on telegramBotId →
        // DuplicateKeyException mapped to 409 bot_already_connected; loser's compensating
        // deleteWebhook fires. Real Tomcat transport is required to genuinely interleave the
        // critical sections — MockMvc's in-process dispatcher cannot.
        Project p1 = saveActiveProject();
        Project p2 = saveActiveProject();

        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(setWebhookOk());
        mockTelegram.enqueue(setWebhookOk());
        mockTelegram.enqueue(deleteWebhookOk());

        SessionState session = login();
        String body = objectMapper.writeValueAsString(Map.of("token", VALID_TOKEN));

        // ConcurrencyTestUtils.parallelInvoke fires the SAME Callable n times in parallel —
        // dispatch the two different projectIds via an atomic counter so the underlying calls
        // diverge on the post-barrier release.
        List<String> targetProjects = List.of(p1.getId(), p2.getId());
        AtomicInteger dispatch = new AtomicInteger(0);
        List<ResponseEntity<String>> results = ConcurrencyTestUtils.parallelInvoke(2,
                () -> postConnect(targetProjects.get(dispatch.getAndIncrement()), body, session));

        List<Integer> statuses = results.stream().map(r -> r.getStatusCode().value()).toList();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        long connected = botRepository.findByProjectId(p1.getId()).stream()
                .filter(b -> b.getStatus() == BotStatus.CONNECTED).count()
                + botRepository.findByProjectId(p2.getId()).stream()
                .filter(b -> b.getStatus() == BotStatus.CONNECTED).count();
        assertThat(connected).isEqualTo(1L);

        // setCount may be 1 or 2: if the loser races the winner past the
        // ensureTelegramBotIdNotConnectedAnywhere pre-check it will reach setWebhook (setCount=2)
        // and then need to compensate (delCount=1); if the loser arrives after the winner already
        // persisted, the pre-check trips early (setCount=1, delCount=0). The strong invariants
        // are exactly-one CONNECTED row and the {200, 409} status pair.
        List<RecordedRequest> reqs = drainRequests();
        long setCount = reqs.stream().filter(r -> r.getPath().endsWith("/setWebhook")).count();
        long delCount = reqs.stream().filter(r -> r.getPath().endsWith("/deleteWebhook")).count();
        assertThat(setCount).isBetween(1L, 2L);
        assertThat(delCount).isEqualTo(setCount - 1L);
    }

    @Test
    void postConnect_parallelSameProjectDifferentTokens_exactlyOneSucceeds_otherReturns409() throws Exception {
        // D5 / D14: two concurrent Connects, same project, different tokens. Loser hits the
        // per-project partial unique index → 409 bot_already_in_project; loser's compensating
        // deleteWebhook fires.
        Project project = saveActiveProject();

        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID, TELEGRAM_USERNAME, TELEGRAM_FIRST_NAME));
        mockTelegram.enqueue(getMeOk(TELEGRAM_BOT_ID_2, "other_bot", "Other"));
        mockTelegram.enqueue(setWebhookOk());
        mockTelegram.enqueue(setWebhookOk());
        mockTelegram.enqueue(deleteWebhookOk());

        SessionState session = login();
        String body1 = objectMapper.writeValueAsString(Map.of("token", VALID_TOKEN));
        String body2 = objectMapper.writeValueAsString(Map.of("token", VALID_TOKEN_2));
        List<String> bodies = List.of(body1, body2);
        AtomicInteger dispatch = new AtomicInteger(0);

        List<ResponseEntity<String>> results = ConcurrencyTestUtils.parallelInvoke(2,
                () -> postConnect(project.getId(), bodies.get(dispatch.getAndIncrement()), session));

        List<Integer> statuses = results.stream().map(r -> r.getStatusCode().value()).toList();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        long connected = botRepository.findByProjectId(project.getId()).stream()
                .filter(b -> b.getStatus() == BotStatus.CONNECTED).count();
        assertThat(connected).isEqualTo(1L);

        List<RecordedRequest> reqs = drainRequests();
        long setCount = reqs.stream().filter(r -> r.getPath().endsWith("/setWebhook")).count();
        long delCount = reqs.stream().filter(r -> r.getPath().endsWith("/deleteWebhook")).count();
        assertThat(setCount).isBetween(1L, 2L);
        assertThat(delCount).isEqualTo(setCount - 1L);
    }

    /**
     * Holds session cookie + XSRF token for parallel POSTs. Both must travel together so the
     * security filter chain authenticates the request and CSRF protection accepts it.
     */
    private record SessionState(String cookieHeader, String xsrfToken) {}

    // Drives a real /api/auth/login round-trip against the running Tomcat to bind a SESSION cookie
    // to the seeded test user. The race-tests do NOT exercise the login flow itself — they just
    // need an authenticated session to ride through the bot/connect race. Pre-fetches XSRF-TOKEN
    // via a safe GET, echoes it back on the login POST, then collects the final SESSION+XSRF
    // cookies for the parallel POSTs.
    private SessionState login() throws Exception {
        // Step 1: prime the CSRF cookie via a safe GET. Spring Security materialises XSRF-TOKEN on
        // the first request through the chain.
        ResponseEntity<String> getResp = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        String primedCookies = String.join("; ",
                getResp.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));
        String xsrf = extractXsrfToken(primedCookies);

        // Step 2: log in with the primed cookies + X-XSRF-TOKEN header.
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        if (xsrf != null) loginHeaders.add("X-XSRF-TOKEN", xsrf);
        if (!primedCookies.isBlank()) loginHeaders.add("Cookie", primedCookies);

        String loginBody = objectMapper.writeValueAsString(Map.of(
                "email", EMAIL, "password", PASSWORD, "rememberMe", false));
        ResponseEntity<String> loginResp = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(loginBody, loginHeaders), String.class);
        assertThat(loginResp.getStatusCode().is2xxSuccessful())
                .as("seeded login must succeed: %s", loginResp.getBody())
                .isTrue();

        // Step 3: merge Set-Cookie values from the login response (session-rotated post-login per
        // Task 6 changeSessionId() defense). XSRF may also rotate, so prefer the post-login token.
        String postLoginCookies = String.join("; ",
                loginResp.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));
        String mergedCookies = postLoginCookies.isBlank() ? primedCookies : postLoginCookies;
        String mergedXsrf = extractXsrfToken(mergedCookies);
        if (mergedXsrf == null) mergedXsrf = xsrf;
        return new SessionState(mergedCookies, mergedXsrf);
    }

    private ResponseEntity<String> postConnect(String projectId, String body, SessionState session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (session.xsrfToken() != null) headers.add("X-XSRF-TOKEN", session.xsrfToken());
        if (session.cookieHeader() != null) headers.add("Cookie", session.cookieHeader());
        return restTemplate.exchange(
                "/api/v1/projects/" + projectId + "/bot/connect",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private static String extractXsrfToken(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split("[;,]")) {
            String p = part.trim();
            if (p.startsWith("XSRF-TOKEN=")) {
                int end = p.indexOf(';');
                return end > 0
                        ? p.substring("XSRF-TOKEN=".length(), end)
                        : p.substring("XSRF-TOKEN=".length());
            }
        }
        return null;
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
}
