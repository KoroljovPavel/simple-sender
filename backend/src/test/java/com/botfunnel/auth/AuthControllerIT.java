package com.botfunnel.auth;

import ch.martinelli.oss.testcontainers.mailpit.MailpitClient;
import ch.martinelli.oss.testcontainers.mailpit.Message;
import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
    PasswordEncoder passwordEncoder;

    @Autowired
    TokenService tokenService;

    @Autowired
    ReactiveRedisTemplate<String, String> redisTemplate;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([A-Za-z0-9_\\-]+)");

    private MailpitClient mailpit() {
        return MAILPIT.getClient();
    }

    @BeforeEach
    void cleanState() {
        userRepository.deleteAll().block();
        // Wipe Redis so brute-force and resend-rate keys do not leak between tests.
        redisTemplate.delete(redisTemplate.scan()).block();
        try {
            mailpit().deleteAllMessages();
        } catch (Exception ignore) {
            // Mailpit boot lag — retried by next test seeding.
        }
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
                .expectStatus().isBadRequest();
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
}
