package com.botfunnel.bot;

import com.botfunnel.AbstractIntegrationTest;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Race tests live here (not in BotControllerIT) per D14 — MockMvc's in-process DispatcherServlet
// dispatch does NOT reliably interleave critical sections under a parked-VT release barrier. Real
// transport (loopback Tomcat at RANDOM_PORT) is required to genuinely race two POSTs through the
// security filter chain + service layer. Uses ConcurrencyTestUtils.parallelInvoke(...) (D10) to
// guarantee N VTs are parked at a CountDownLatch barrier before simultaneous release.
//
// Extends AbstractIntegrationTest to (a) share the singleton Mongo + Redis testcontainers via the
// parent's @DynamicPropertySource, and (b) inherit the RANDOM_PORT webEnvironment kept by Task 10
// after the @AutoConfigureMockMvc flip. We deliberately do not use the inherited MockMvc field —
// MockMvc's in-process dispatch cannot interleave critical sections per D14, so this class wires
// TestRestTemplate directly against real Tomcat.
class BotConnectRaceIT extends AbstractIntegrationTest {

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
        // Mongo + Redis URIs come from AbstractIntegrationTest's @DynamicPropertySource (which
        // Spring also invokes on subclasses) — no need to redeclare them here.
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
    // to the seeded test user. Maintains a cookie jar across three round-trips so cookies survive
    // Spring Security's session-fixation rotation (changeSessionId) AND CSRF-token rotation
    // (CsrfAuthenticationStrategy emits Set-Cookie: XSRF-TOKEN=; Max-Age=0 on login, a delete
    // cookie that does NOT carry a fresh token). A post-login session ping forces the
    // CsrfCookieMaterializer to mint a new XSRF-TOKEN bound to the authenticated session, which
    // the race POSTs then carry. Per Task 16 audit F-C1 #7/#8.
    private SessionState login() throws Exception {
        Map<String, String> jar = new LinkedHashMap<>();

        // Step 1: prime cookies via a safe GET. Spring Security materialises XSRF-TOKEN on the
        // first request through the chain.
        ResponseEntity<String> getResp = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        mergeSetCookies(jar, getResp.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));
        String primedXsrf = jar.get("XSRF-TOKEN");

        // Step 2: log in carrying primed cookies + X-XSRF-TOKEN header. Login rotates the session
        // (changeSessionId) and clears the CSRF token cookie via Set-Cookie deletion.
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        if (primedXsrf != null && !primedXsrf.isBlank()) {
            loginHeaders.add("X-XSRF-TOKEN", primedXsrf);
        }
        loginHeaders.add("Cookie", serializeJar(jar));

        String loginBody = objectMapper.writeValueAsString(Map.of(
                "email", EMAIL, "password", PASSWORD, "rememberMe", false));
        ResponseEntity<String> loginResp = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(loginBody, loginHeaders), String.class);
        assertThat(loginResp.getStatusCode().is2xxSuccessful())
                .as("seeded login must succeed: %s", loginResp.getBody())
                .isTrue();
        mergeSetCookies(jar, loginResp.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));

        // Step 3: session ping. With the rotated SESSION + (possibly empty) XSRF cookie, hit
        // /api/auth/me again so the CsrfCookieMaterializer mints a fresh XSRF-TOKEN bound to the
        // authenticated session. Also confirms the merged jar authenticates — without this gate,
        // a silent cookie-jar regression would surface as a misleading [403, 403] on the race
        // POSTs instead of a clear "session not authenticated" failure (Task 16 audit recommendation).
        HttpHeaders pingHeaders = new HttpHeaders();
        pingHeaders.add("Cookie", serializeJar(jar));
        ResponseEntity<String> pingResp = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(pingHeaders), String.class);
        assertThat(pingResp.getStatusCode().is2xxSuccessful())
                .as("post-login session ping must succeed before launching the race (status=%s, body=%s)",
                        pingResp.getStatusCode(), pingResp.getBody())
                .isTrue();
        mergeSetCookies(jar, pingResp.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));

        String mergedXsrf = jar.get("XSRF-TOKEN");
        assertThat(mergedXsrf)
                .as("post-login session ping must yield a non-blank XSRF-TOKEN cookie")
                .isNotNull().isNotBlank();
        return new SessionState(serializeJar(jar), mergedXsrf);
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

    // Folds a list of Set-Cookie headers into a name→value jar. Later writes overwrite earlier
    // values, which matches a browser's cookie store: post-login SESSION rotates the pre-login
    // SESSION, and the CsrfAuthenticationStrategy deletion cookie (Max-Age=0) clears XSRF-TOKEN
    // before a downstream materialization sets a new one.
    private static void mergeSetCookies(Map<String, String> jar, List<String> setCookieHeaders) {
        for (String setCookie : setCookieHeaders) {
            int semi = setCookie.indexOf(';');
            String pair = semi < 0 ? setCookie : setCookie.substring(0, semi);
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            jar.put(name, value);
        }
    }

    private static String serializeJar(Map<String, String> jar) {
        return jar.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
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
