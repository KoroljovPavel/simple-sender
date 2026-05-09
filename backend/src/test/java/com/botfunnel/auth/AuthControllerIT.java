package com.botfunnel.auth;

import ch.martinelli.oss.testcontainers.mailpit.MailpitClient;
import ch.martinelli.oss.testcontainers.mailpit.Message;
import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
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
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

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
    ReactiveRedisTemplate<String, String> redisTemplate;

    @Autowired
    ReactiveMongoTemplate reactiveMongoTemplate;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([A-Za-z0-9_\\-]+)");

    private MailpitClient mailpit() {
        return MAILPIT.getClient();
    }

    @BeforeEach
    void cleanState() {
        userRepository.deleteAll().block();
        eventRepository.deleteAll().block();
        // Wipe Redis so brute-force, resend-rate, and register-rate keys do not leak between tests.
        redisTemplate.delete(redisTemplate.scan()).block();
        // Wipe spring-session-data-mongodb sessions so logout/reset session-count assertions are deterministic.
        reactiveMongoTemplate.remove(new Query(), "sessions").block();
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

    // ---------- register ----------

    @Test
    void register_validData_201_userPendingInDB_emailInMailpit() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "alice@test.com", "name", "Alice", "password", "Strong1Pass"))
                .exchange()
                .expectStatus().isCreated()
                // Auto-login after register: the response must carry a SESSION cookie so the
                // SPA can land on /dashboard without a separate /api/auth/login round-trip.
                .expectCookie().exists("SESSION")
                .expectBody()
                .jsonPath("$.id").isNotEmpty();

        User saved = userRepository.findByEmail("alice@test.com").block();
        assertThat(saved).isNotNull();
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
    void register_duplicateEmail_409() {
        seedUser("dup@test.com", UserStatus.active, null);

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "dup@test.com", "name", "Bob", "password", "Strong1Pass"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Користувач з таким email вже існує");
    }

    @Test
    void register_softDeletedEmailWithin30Days_409() {
        seedUser("recent@test.com", UserStatus.deleted, Instant.now().minus(Duration.ofDays(15)));

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "recent@test.com", "name", "Bob", "password", "Strong1Pass"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.message").value(msg -> assertThat((String) msg).contains("підтримки"));
    }

    @Test
    void register_invalidPassword_noDigit_400() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "alice@test.com", "name", "Alice", "password", "OnlyLetters"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").value(msg -> assertThat((String) msg).contains("password"));
    }

    // ---------- verifyEmail ----------

    @Test
    void verifyEmail_validToken_userActive_tokenCleared() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "verify@test.com", "name", "Carol", "password", "Strong1Pass"))
                .exchange()
                .expectStatus().isCreated();
        waitForMessage();

        String rawToken = extractToken();
        webTestClient.get().uri("/api/auth/verify-email?token=" + rawToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.redirect").isEqualTo("/login");

        User saved = userRepository.findByEmail("verify@test.com").block();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.active);
        assertThat(saved.getEmailVerificationTokenHash()).isNull();
        assertThat(saved.getEmailVerificationExpiresAt()).isNull();

        // End-to-end audit assertion: EventService -> EventRepository -> Mongo. Acceptance
        // criterion line 97: "email_verified event logged in events collection after successful
        // verification". The fire-and-forget logEvent path can lag, so poll briefly.
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findAll()
                        .filter(e -> "email_verified".equals(e.getEventType())
                                && saved.getId().equals(e.getUserId()))
                        .hasElements()
                        .block());
        Event evt = eventRepository.findAll()
                .filter(e -> "email_verified".equals(e.getEventType()))
                .blockFirst();
        assertThat(evt).isNotNull();
        assertThat(evt.getUserId()).isEqualTo(saved.getId());
    }

    @Test
    void register_softDeletedEmail_olderThan30Days_succeeds_andRepurposesDocument() {
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
        String oldId = userRepository.save(existing).block().getId();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "repurpose@test.com", "name", "NewName", "password", "Strong1Pass"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.id").isEqualTo(oldId);

        User saved = userRepository.findByEmail("repurpose@test.com").block();
        assertThat(saved).isNotNull();
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
    void verifyEmail_expiredToken_400WithTokenExpiredCode() {
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
        userRepository.save(u).block();

        webTestClient.get().uri("/api/auth/verify-email?token=" + raw)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void verifyEmail_invalidToken_400NotServerError() {
        webTestClient.get().uri("/api/auth/verify-email?token=garbage-not-issued")
                .exchange()
                .expectStatus().isBadRequest()
                // Lock in identical message + missing TOKEN_EXPIRED code so the response
                // does not become an invalid-vs-expired enumeration oracle.
                .expectBody()
                .jsonPath("$.message").isEqualTo("Посилання недійсне")
                .jsonPath("$.code").doesNotExist();
    }

    // ---------- resendVerification ----------

    @Test
    void resendVerification_within60s_429() {
        seedUser("resend@test.com", UserStatus.pending, null);

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/resend-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "resend@test.com"))
                .exchange()
                .expectStatus().isOk();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/resend-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "resend@test.com"))
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void resendVerification_unknownEmail_returns200_andSendsNoEmail_andSetsRedisKey() {
        // Anti-enumeration: unknown email must (a) return 200 like a known email, (b) NOT
        // dispatch a real email, and (c) STILL set the Redis rate-limit key to remove the
        // timing oracle on subsequent requests (Decision 7).
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/resend-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "ghost@test.com"))
                .exchange()
                .expectStatus().isOk();

        // Sanity: no Mailpit message arrived. The unknown-email branch never enters EmailService,
        // so any message would indicate a real defect. Asserting "stays at zero for a short
        // window" rules out an asynchronous send leaking through.
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(1))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(mailpit().getMessageCount()).isZero());

        // Redis key must be set so the next call within 60s returns 429 even for unknown email.
        Boolean keyExists = redisTemplate.hasKey("resend:rate:ghost@test.com").block();
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
        userRepository.save(u).block();
    }

    // ---------- logout ----------

    // Login via webTestClient (bindToApplicationContext + csrf()). The Set-Cookie header is not
    // propagated through the bind-to-context test infrastructure even though spring-session-data-mongodb
    // does persist the session document — so we verify session lifecycle through MongoDB queries
    // on the `sessions` collection rather than via cookie introspection.
    private void doLogin(String email, String password) {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password, "rememberMe", false))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void logout_endpoint_returns200() {
        // IT smoke test: the endpoint is wired and returns 200 even with no active session
        // (idempotent logout on an empty WebSession is a no-op).
        // Full behaviour (current-session invalidation only, other devices untouched) is
        // verified at the unit level in AuthServicePasswordResetTest.logout_*.
        // This split exists because WebTestClient.bindToApplicationContext does not propagate
        // the SESSION cookie set by spring-session-data-mongodb back to the test client, so a
        // real cookie-based logout flow cannot be exercised through this IT pipeline.
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/logout")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void logout_secondLoginCreatesIndependentSessionsDocument() {
        // Independent verification of the spring-session-data-mongodb multi-session model that
        // logout's "other-device-untouched" behaviour relies on: two sequential logins for the
        // same user must produce TWO distinct session documents, not collapse onto one.
        seedUser("logout@test.com", UserStatus.active, null);
        String userId = userRepository.findByEmail("logout@test.com").block().getId();

        doLogin("logout@test.com", "Strong1Pass");
        doLogin("logout@test.com", "Strong1Pass");

        Long sessions = reactiveMongoTemplate.count(
                Query.query(Criteria.where("principal").is(userId)), "sessions").block();
        assertThat(sessions)
                .as("each login must produce its own session — required so logout can target one without affecting the other")
                .isEqualTo(2L);
    }

    // ---------- forgot password ----------

    @Test
    void forgotPassword_anyEmail_always200() {
        // Anti-enumeration: unknown email must return 200 like a known email (no 404, no message diff).
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "ghost@test.com"))
                .exchange()
                .expectStatus().isOk();

        // Sanity: no Mailpit message arrived for unknown email.
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(1))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(mailpit().getMessageCount()).isZero());
    }

    @Test
    void forgotPassword_validEmail_emailInMailpit() {
        seedUser("reset@test.com", UserStatus.active, null);

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "reset@test.com"))
                .exchange()
                .expectStatus().isOk();

        waitForMessage();
        Message m = mailpit().getAllMessages().get(0);
        assertThat(m.subject()).isEqualTo("Скидання пароля");
        // Token is dispatched on the URL — must extract via the same regex used for verify.
        String token = extractToken();
        assertThat(token).hasSize(43);

        // Hash must be persisted on the user, raw token must NOT (Decision 2).
        User saved = userRepository.findByEmail("reset@test.com").block();
        assertThat(saved.getPasswordResetTokenHash()).matches("[0-9a-f]{64}");
        assertThat(saved.getPasswordResetExpiresAt())
                .isAfter(Instant.now().plus(Duration.ofMinutes(55)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(65)));
        assertThat(saved.getPasswordResetUsedAt()).isNull();
    }

    // ---------- reset password ----------

    @Test
    void resetPassword_validToken_oldPasswordRejected_allSessionsInvalidated() {
        seedUser("reset@test.com", UserStatus.active, null);

        // Open two device sessions before the reset, so we can verify ALL of them are deleted.
        doLogin("reset@test.com", "Strong1Pass");
        doLogin("reset@test.com", "Strong1Pass");
        String userId = userRepository.findByEmail("reset@test.com").block().getId();
        long sessionCountBefore = reactiveMongoTemplate.count(
                Query.query(Criteria.where("principal").is(userId)), "sessions").block();
        assertThat(sessionCountBefore)
                .as("two logins must produce two indexed sessions before reset")
                .isEqualTo(2L);

        // Forgot-password to issue a real token, then extract it from Mailpit.
        // forgot-password and reset-password are stateless POSTs — bindToApplicationContext
        // (with csrf() mutator) is sufficient because no SESSION cookie is needed in the response.
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "reset@test.com"))
                .exchange()
                .expectStatus().isOk();
        waitForMessage();
        String rawToken = extractToken();

        // Reset-password.
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", rawToken, "newPassword", "NewStr0ngPass"))
                .exchange()
                .expectStatus().isOk();

        // After reset, ALL prior sessions for this user must be gone.
        long sessionCountAfter = reactiveMongoTemplate.count(
                Query.query(Criteria.where("principal").is(userId)), "sessions").block();
        assertThat(sessionCountAfter)
                .as("reset-password must terminate ALL sessions for the user")
                .isZero();

        // Old password no longer works.
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "reset@test.com", "password", "Strong1Pass", "rememberMe", false))
                .exchange()
                .expectStatus().isUnauthorized();

        // New password works.
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "reset@test.com", "password", "NewStr0ngPass", "rememberMe", false))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void resetPassword_expiredToken_400() {
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
        userRepository.save(u).block();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", raw, "newPassword", "NewStr0ngPass"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void resetPassword_alreadyUsedToken_400() {
        // Seed a user with a usable token, consume it once, attempt to reuse.
        seedUser("reuse@test.com", UserStatus.active, null);

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "reuse@test.com"))
                .exchange()
                .expectStatus().isOk();
        waitForMessage();
        String rawToken = extractToken();

        // First use → 200.
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", rawToken, "newPassword", "NewStr0ngPass"))
                .exchange()
                .expectStatus().isOk();

        // Second use of the SAME token → 400 (token consumed by passwordResetUsedAt OR cleared by
        // reset; in either case the lookup fails).
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", rawToken, "newPassword", "OtherStr0ngPass"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void resetPassword_invalidToken_400NotServerError() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", "garbage-not-issued", "newPassword", "NewStr0ngPass"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void passwordChanged_event_logged() {
        seedUser("evt@test.com", UserStatus.active, null);

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "evt@test.com"))
                .exchange()
                .expectStatus().isOk();
        waitForMessage();
        String rawToken = extractToken();

        String userId = userRepository.findByEmail("evt@test.com").block().getId();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", rawToken, "newPassword", "NewStr0ngPass"))
                .exchange()
                .expectStatus().isOk();

        // Fire-and-forget log path: poll briefly.
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findAll()
                        .filter(e -> "password_changed".equals(e.getEventType())
                                && userId.equals(e.getUserId()))
                        .hasElements()
                        .block());
        Event evt = eventRepository.findAll()
                .filter(e -> "password_changed".equals(e.getEventType()))
                .blockFirst();
        assertThat(evt).isNotNull();
        assertThat(evt.getUserId()).isEqualTo(userId);
    }

    @Test
    void sessionsCollection_principalFieldPath_isAtTopLevel() {
        // Risk-area diagnostic (Task 6 spec lines 116-121). Verifies the spring-session-data-mongodb
        // schema actually stores the principal at the top-level `principal` field — without this,
        // resetPassword's terminate-all query would silently delete zero documents.
        seedUser("schema@test.com", UserStatus.active, null);
        String userId = userRepository.findByEmail("schema@test.com").block().getId();
        doLogin("schema@test.com", "Strong1Pass");

        Document doc = reactiveMongoTemplate.findAll(Document.class, "sessions").blockFirst();
        assertThat(doc).as("a session document must exist after login").isNotNull();
        // If this fails, the field name has changed in spring-session-data-mongodb and the
        // terminate-all-sessions query must be updated.
        assertThat(doc.get("principal"))
                .as("session document must expose principal at top level (verifies query field)")
                .isEqualTo(userId);
    }
}
