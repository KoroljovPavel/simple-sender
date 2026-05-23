package com.botfunnel.auth;

import ch.martinelli.oss.testcontainers.mailpit.MailpitClient;
import ch.martinelli.oss.testcontainers.mailpit.Message;
import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerIT extends AbstractIntegrationTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    TokenService tokenService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    MongoTemplate mongoTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([A-Za-z0-9_\\-]+)");

    private MailpitClient mailpit() {
        return MAILPIT.getClient();
    }

    @BeforeEach
    void cleanState() {
        userRepository.deleteAll();
        eventRepository.deleteAll();
        // Wipe Redis so brute-force, resend-rate, and register-rate keys do not leak between tests.
        java.util.Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        // Wipe spring-session-data-mongodb sessions so logout/reset session-count assertions are deterministic.
        mongoTemplate.remove(new Query(), "sessions");
        mailpit().deleteAllMessages();
    }

    private void waitForMessage() {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> mailpit().getMessageCount() >= 1);
    }

    private String extractToken() {
        Message latest = mailpit().getAllMessages().get(0);
        String html = mailpit().getMessageHtml(latest.id());
        Matcher m = TOKEN_PATTERN.matcher(html);
        assertThat(m.find()).as("token URL must be present in email HTML body").isTrue();
        return m.group(1);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    // ---------- register ----------

    @Test
    void register_validData_201_userPendingInDB_emailInMailpit() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "alice@test.com", "name", "Alice", "password", "Strong1Pass"))))
                .andExpect(status().isCreated())
                // Auto-login after register: the response must carry a SESSION cookie so the
                // SPA can land on /dashboard without a separate /api/auth/login round-trip.
                .andExpect(cookie().exists("SESSION"))
                .andExpect(jsonPath("$.id").isNotEmpty());

        User saved = userRepository.findByEmail("alice@test.com").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.pending);
        assertThat(saved.getEmailVerificationTokenHash()).matches("[0-9a-f]{64}");
        assertThat(saved.getEmailVerificationExpiresAt())
                .isAfter(Instant.now().plus(Duration.ofHours(23)));

        waitForMessage();
        List<Message> all = mailpit().getAllMessages();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).subject()).isEqualTo("Підтвердіть email");
    }

    @Test
    void register_duplicateEmail_409() throws Exception {
        seedUser("dup@test.com", UserStatus.active, null);

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "dup@test.com", "name", "Bob", "password", "Strong1Pass"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Користувач з таким email вже існує"));
    }

    @Test
    void register_softDeletedEmailWithin30Days_409() throws Exception {
        seedUser("recent@test.com", UserStatus.deleted, Instant.now().minus(Duration.ofDays(15)));

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "recent@test.com", "name", "Bob", "password", "Strong1Pass"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("підтримки")));
    }

    @Test
    void register_invalidPassword_noDigit_400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "alice@test.com", "name", "Alice", "password", "OnlyLetters"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("password")));
    }

    // ---------- verifyEmail ----------

    @Test
    void verifyEmail_validToken_userActive_tokenCleared() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "verify@test.com", "name", "Carol", "password", "Strong1Pass"))))
                .andExpect(status().isCreated());
        waitForMessage();

        String rawToken = extractToken();
        mockMvc.perform(get("/api/auth/verify-email").param("token", rawToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirect").value("/login"));

        User saved = userRepository.findByEmail("verify@test.com").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.active);
        assertThat(saved.getEmailVerificationTokenHash()).isNull();
        assertThat(saved.getEmailVerificationExpiresAt()).isNull();

        // End-to-end audit assertion: EventService -> EventRepository -> Mongo. Acceptance
        // criterion line 97: "email_verified event logged in events collection after successful
        // verification". EventService.logEvent is synchronous post-Task-5 — no polling needed,
        // but a brief wait keeps the assertion stable across test runs.
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findAll().stream()
                        .anyMatch(e -> "email_verified".equals(e.getEventType())
                                && saved.getId().equals(e.getUserId())));
        Event evt = eventRepository.findAll().stream()
                .filter(e -> "email_verified".equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertThat(evt.getUserId()).isEqualTo(saved.getId());
    }

    @Test
    void register_softDeletedEmail_olderThan30Days_succeeds_andRepurposesDocument() throws Exception {
        // IT-level coverage of the >30-day repurpose edge case (test-reviewer finding).
        // Verifies that the unique-email index does NOT block re-registration and that
        // carry-over fields (deletedAt, isSuperAdmin) are reset on the existing document.
        User existing = new User();
        existing.setEmail("repurpose@test.com");
        existing.setName("OldName");
        existing.setPasswordHash(passwordEncoder.encode("OldPass1!"));
        existing.setStatus(UserStatus.deleted);
        existing.setSuperAdmin(true);
        existing.setDeletedAt(Instant.now().minus(Duration.ofDays(31)));
        existing.setCreatedAt(Instant.now().minus(Duration.ofDays(60)));
        existing.setUpdatedAt(Instant.now().minus(Duration.ofDays(31)));
        String oldId = userRepository.save(existing).getId();

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "repurpose@test.com", "name", "NewName", "password", "Strong1Pass"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(oldId));

        User saved = userRepository.findByEmail("repurpose@test.com").orElseThrow();
        assertThat(saved.getId()).isEqualTo(oldId);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.pending);
        assertThat(saved.getName()).as("name no longer collected on register, must be cleared on repurpose").isNull();
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.isSuperAdmin())
                .as("superadmin flag must NOT carry over from a soft-deleted account")
                .isFalse();
        // Defense-in-depth: any stale password-reset state from the prior account must be
        // cleared so a leaked old reset link cannot affect the new account.
        assertThat(saved.getPasswordResetTokenHash()).isNull();
        assertThat(saved.getPasswordResetExpiresAt()).isNull();
        assertThat(saved.getPasswordResetUsedAt()).isNull();
    }

    @Test
    void verifyEmail_expiredToken_400WithTokenExpiredCode() throws Exception {
        // Seed directly (bypass register) so we can fast-forward expiry.
        String raw = tokenService.generateRawToken();
        User u = new User();
        u.setEmail("expired@test.com");
        u.setName("Dan");
        u.setPasswordHash(passwordEncoder.encode("Strong1Pass"));
        u.setStatus(UserStatus.pending);
        u.setEmailVerificationTokenHash(tokenService.hashToken(raw));
        u.setEmailVerificationExpiresAt(Instant.now().minus(Duration.ofMinutes(5)));
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);

        mockMvc.perform(get("/api/auth/verify-email").param("token", raw))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void verifyEmail_invalidToken_400NotServerError() throws Exception {
        mockMvc.perform(get("/api/auth/verify-email").param("token", "garbage-not-issued"))
                .andExpect(status().isBadRequest())
                // Lock in identical message + missing TOKEN_EXPIRED code so the response
                // does not become an invalid-vs-expired enumeration oracle.
                .andExpect(jsonPath("$.message").value("Посилання недійсне"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    // ---------- resendVerification ----------

    @Test
    void resendVerification_within60s_429() throws Exception {
        seedUser("resend@test.com", UserStatus.pending, null);

        mockMvc.perform(post("/api/auth/resend-verification")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "resend@test.com"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/resend-verification")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "resend@test.com"))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void resendVerification_unknownEmail_returns200_andSendsNoEmail_andSetsRedisKey() throws Exception {
        // Anti-enumeration: unknown email must (a) return 200 like a known email, (b) NOT
        // dispatch a real email, and (c) STILL set the Redis rate-limit key to remove the
        // timing oracle on subsequent requests (Decision 7).
        mockMvc.perform(post("/api/auth/resend-verification")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "ghost@test.com"))))
                .andExpect(status().isOk());

        // Sanity: no Mailpit message arrived. The unknown-email branch never enters EmailService,
        // so any message would indicate a real defect.
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(1))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(mailpit().getMessageCount()).isZero());

        // Redis key must be set so the next call within 60s returns 429 even for unknown email.
        Boolean keyExists = redisTemplate.hasKey("resend:rate:ghost@test.com");
        assertThat(keyExists).isTrue();
    }

    private void seedUser(String email, UserStatus status, Instant deletedAt) {
        User u = new User();
        u.setEmail(email);
        u.setName("Seed");
        u.setPasswordHash(passwordEncoder.encode("Strong1Pass"));
        u.setStatus(status);
        u.setDeletedAt(deletedAt);
        u.setCreatedAt(Instant.now().minus(Duration.ofDays(1)));
        u.setUpdatedAt(Instant.now().minus(Duration.ofDays(1)));
        userRepository.save(u);
    }

    // ---------- logout ----------

    private void doLogin(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password, "rememberMe", false))))
                .andExpect(status().isOk());
    }

    @Test
    void logout_endpoint_returns200() throws Exception {
        // IT smoke test: the endpoint is wired and returns 200 even with no active session
        // (idempotent logout on an empty HttpSession is a no-op).
        // Full behaviour (current-session invalidation only, other devices untouched) is
        // verified at the unit level in AuthServicePasswordResetTest.logout_*.
        mockMvc.perform(post("/api/auth/logout").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void logout_secondLoginCreatesIndependentSessionsDocument() throws Exception {
        // Independent verification of the spring-session-data-mongodb multi-session model that
        // logout's "other-device-untouched" behaviour relies on: two sequential logins for the
        // same user must produce TWO distinct session documents, not collapse onto one.
        seedUser("logout@test.com", UserStatus.active, null);
        String userId = userRepository.findByEmail("logout@test.com").orElseThrow().getId();

        doLogin("logout@test.com", "Strong1Pass");
        doLogin("logout@test.com", "Strong1Pass");

        long sessions = mongoTemplate.count(
                Query.query(Criteria.where("principal").is(userId)), "sessions");
        assertThat(sessions)
                .as("each login must produce its own session — required so logout can target one without affecting the other")
                .isEqualTo(2L);
    }

    // ---------- forgot password ----------

    @Test
    void forgotPassword_anyEmail_always200() throws Exception {
        // Anti-enumeration: unknown email must return 200 like a known email (no 404, no message diff).
        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "ghost@test.com"))))
                .andExpect(status().isOk());

        // Sanity: no Mailpit message arrived for unknown email.
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(1))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(mailpit().getMessageCount()).isZero());
    }

    @Test
    void forgotPassword_validEmail_emailInMailpit() throws Exception {
        seedUser("reset@test.com", UserStatus.active, null);

        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "reset@test.com"))))
                .andExpect(status().isOk());

        waitForMessage();
        Message m = mailpit().getAllMessages().get(0);
        assertThat(m.subject()).isEqualTo("Скидання пароля");
        // Token is dispatched on the URL — must extract via the same regex used for verify.
        String token = extractToken();
        assertThat(token).hasSize(43);

        // Hash must be persisted on the user, raw token must NOT (Decision 2).
        User saved = userRepository.findByEmail("reset@test.com").orElseThrow();
        assertThat(saved.getPasswordResetTokenHash()).matches("[0-9a-f]{64}");
        assertThat(saved.getPasswordResetExpiresAt())
                .isAfter(Instant.now().plus(Duration.ofMinutes(55)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(65)));
        assertThat(saved.getPasswordResetUsedAt()).isNull();
    }

    // ---------- reset password ----------

    @Test
    void resetPassword_validToken_oldPasswordRejected_allSessionsInvalidated() throws Exception {
        seedUser("reset@test.com", UserStatus.active, null);

        // Open two device sessions before the reset, so we can verify ALL of them are deleted.
        doLogin("reset@test.com", "Strong1Pass");
        doLogin("reset@test.com", "Strong1Pass");
        String userId = userRepository.findByEmail("reset@test.com").orElseThrow().getId();
        long sessionCountBefore = mongoTemplate.count(
                Query.query(Criteria.where("principal").is(userId)), "sessions");
        assertThat(sessionCountBefore)
                .as("two logins must produce two indexed sessions before reset")
                .isEqualTo(2L);

        // Forgot-password to issue a real token, then extract it from Mailpit.
        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "reset@test.com"))))
                .andExpect(status().isOk());
        waitForMessage();
        String rawToken = extractToken();

        // Reset-password.
        mockMvc.perform(post("/api/auth/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", rawToken, "newPassword", "NewStr0ngPass"))))
                .andExpect(status().isOk());

        // After reset, ALL prior sessions for this user must be gone.
        long sessionCountAfter = mongoTemplate.count(
                Query.query(Criteria.where("principal").is(userId)), "sessions");
        assertThat(sessionCountAfter)
                .as("reset-password must terminate ALL sessions for the user")
                .isZero();

        // Old password no longer works.
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "reset@test.com", "password", "Strong1Pass", "rememberMe", false))))
                .andExpect(status().isUnauthorized());

        // New password works.
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "reset@test.com", "password", "NewStr0ngPass", "rememberMe", false))))
                .andExpect(status().isOk());
    }

    @Test
    void resetPassword_expiredToken_400() throws Exception {
        // Seed with an already-expired reset token.
        String raw = tokenService.generateRawToken();
        User u = new User();
        u.setEmail("expired-reset@test.com");
        u.setName("Dan");
        u.setPasswordHash(passwordEncoder.encode("Strong1Pass"));
        u.setStatus(UserStatus.active);
        u.setPasswordResetTokenHash(tokenService.hashToken(raw));
        u.setPasswordResetExpiresAt(Instant.now().minus(Duration.ofMinutes(5)));
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);

        mockMvc.perform(post("/api/auth/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", raw, "newPassword", "NewStr0ngPass"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPassword_alreadyUsedToken_400() throws Exception {
        // Seed a user with a usable token, consume it once, attempt to reuse.
        seedUser("reuse@test.com", UserStatus.active, null);

        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "reuse@test.com"))))
                .andExpect(status().isOk());
        waitForMessage();
        String rawToken = extractToken();

        // First use → 200.
        mockMvc.perform(post("/api/auth/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", rawToken, "newPassword", "NewStr0ngPass"))))
                .andExpect(status().isOk());

        // Second use of the SAME token → 400 (token consumed by passwordResetUsedAt OR cleared by
        // reset; in either case the lookup fails).
        mockMvc.perform(post("/api/auth/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", rawToken, "newPassword", "OtherStr0ngPass"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPassword_invalidToken_400NotServerError() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", "garbage-not-issued", "newPassword", "NewStr0ngPass"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordChanged_event_logged() throws Exception {
        seedUser("evt@test.com", UserStatus.active, null);

        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "evt@test.com"))))
                .andExpect(status().isOk());
        waitForMessage();
        String rawToken = extractToken();

        String userId = userRepository.findByEmail("evt@test.com").orElseThrow().getId();

        mockMvc.perform(post("/api/auth/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", rawToken, "newPassword", "NewStr0ngPass"))))
                .andExpect(status().isOk());

        // EventService.logEvent is synchronous post-Task-5; a brief poll keeps the test stable
        // against repository write latency.
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findAll().stream()
                        .anyMatch(e -> "password_changed".equals(e.getEventType())
                                && userId.equals(e.getUserId())));
        Event evt = eventRepository.findAll().stream()
                .filter(e -> "password_changed".equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertThat(evt.getUserId()).isEqualTo(userId);
    }

    @Test
    void sessionsCollection_principalFieldPath_isAtTopLevel() throws Exception {
        // Risk-area diagnostic (Task 6 spec lines 116-121). Verifies the spring-session-data-mongodb
        // schema actually stores the principal at the top-level `principal` field — without this,
        // resetPassword's terminate-all query would silently delete zero documents.
        seedUser("schema@test.com", UserStatus.active, null);
        String userId = userRepository.findByEmail("schema@test.com").orElseThrow().getId();
        doLogin("schema@test.com", "Strong1Pass");

        Document doc = mongoTemplate.findAll(Document.class, "sessions").stream().findFirst().orElse(null);
        assertThat(doc).as("a session document must exist after login").isNotNull();
        // If this fails, the field name has changed in spring-session-data-mongodb and the
        // terminate-all-sessions query must be updated.
        assertThat(doc.get("principal"))
                .as("session document must expose principal at top level (verifies query field)")
                .isEqualTo(userId);
    }
}
