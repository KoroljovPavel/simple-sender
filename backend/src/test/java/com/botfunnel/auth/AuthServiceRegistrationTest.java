package com.botfunnel.auth;

import com.botfunnel.auth.dto.RegisterRequest;
import com.botfunnel.common.AppException;
import com.botfunnel.email.EmailService;
import com.botfunnel.events.EventService;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceRegistrationTest {

    private static final String EMAIL = "user@test.com";
    private static final String SUPPORT_EMAIL = "support@botfunnel.test";

    @Mock
    UserRepository userRepository;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    ServerSecurityContextRepository securityContextRepository;

    @Mock
    EventService eventService;

    @Mock
    EmailService emailService;

    TokenService tokenService;

    AuthService authService;

    @BeforeEach
    void setUp() {
        // Real TokenService (no deps); avoids stubbing every hashToken/generateRawToken call.
        tokenService = new TokenService();
        authService = new AuthService(userRepository, redisTemplate, passwordEncoder,
                securityContextRepository, eventService, emailService, tokenService,
                SUPPORT_EMAIL, 24L, 30L);
        // Permissive default for the per-IP register rate-limit (Redis INCR + EXPIRE). Tests that
        // exercise the rate-limit branch override these explicitly.
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue().increment(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(1L));
        org.mockito.Mockito.lenient().when(redisTemplate.expire(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(Mono.just(true));
    }

    private ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/auth/register")
                .header("User-Agent", "JUnit")
                .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 12345))
                .build());
    }

    private RegisterRequest request(String email, String password, String name) {
        RegisterRequest r = new RegisterRequest();
        r.setEmail(email);
        r.setPassword(password);
        r.setName(name);
        return r;
    }

    // ---------- register ----------

    @Test
    void register_newEmail_savesPendingUser_dispatchesEmail_returnsId() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
        when(passwordEncoder.encode("Strong1Pass")).thenReturn("hashed-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId("user-id-1");
            return Mono.just(u);
        });

        StepVerifier.create(authService.register(request(EMAIL, "Strong1Pass", "Alice"), exchange()))
                .assertNext(resp -> assertThat(resp.id()).isEqualTo("user-id-1"))
                .verifyComplete();

        ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCap.capture());
        User saved = userCap.getValue();
        assertThat(saved.getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getName()).isEqualTo("Alice");
        assertThat(saved.getPasswordHash()).isEqualTo("hashed-pw");
        assertThat(saved.getStatus()).isEqualTo(UserStatus.pending);
        assertThat(saved.getEmailVerificationTokenHash())
                .as("must store SHA-256 hash, not raw token (Decision 2)")
                .matches("[0-9a-f]{64}");
        assertThat(saved.getEmailVerificationExpiresAt())
                .isAfter(Instant.now().plus(Duration.ofHours(23)))
                .isBefore(Instant.now().plus(Duration.ofHours(25)));
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        // EmailService receives the RAW token (43 chars base64url), never the hash.
        ArgumentCaptor<String> tokenCap = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendVerificationEmail(eq(EMAIL), eq("Alice"), tokenCap.capture());
        assertThat(tokenCap.getValue()).hasSize(43).doesNotContain("=");
        assertThat(tokenCap.getValue())
                .as("raw token must not equal stored hash")
                .isNotEqualTo(saved.getEmailVerificationTokenHash());
    }

    @Test
    void register_canonicalizesEmailToLowercase() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId("user-id-1");
            return Mono.just(u);
        });

        StepVerifier.create(authService.register(request("USER@Test.com", "Strong1Pass", "Alice"), exchange()))
                .expectNextCount(1)
                .verifyComplete();

        verify(userRepository).findByEmail(EMAIL);
        ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCap.capture());
        assertThat(userCap.getValue().getEmail()).isEqualTo(EMAIL);
    }

    @Test
    void register_existingActiveEmail_returns409() {
        User existing = new User();
        existing.setEmail(EMAIL);
        existing.setStatus(UserStatus.active);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(existing));

        StepVerifier.create(authService.register(request(EMAIL, "Strong1Pass", "Alice"), exchange()))
                .expectErrorMatches(e -> {
                    if (!(e instanceof AppException ae)) return false;
                    return ae.getStatus() == HttpStatus.CONFLICT
                            && ae.getMessage().equals("Користувач з таким email вже існує");
                })
                .verify();

        verify(userRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    void register_softDeletedEmail_within30Days_returns409WithSupportContact() {
        User existing = new User();
        existing.setEmail(EMAIL);
        existing.setStatus(UserStatus.deleted);
        existing.setDeletedAt(Instant.now().minus(Duration.ofDays(15)));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(existing));

        StepVerifier.create(authService.register(request(EMAIL, "Strong1Pass", "Alice"), exchange()))
                .expectErrorMatches(e -> {
                    if (!(e instanceof AppException ae)) return false;
                    return ae.getStatus() == HttpStatus.CONFLICT
                            && ae.getMessage().contains(SUPPORT_EMAIL);
                })
                .verify();

        verify(userRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    void register_softDeletedEmail_olderThan30Days_succeedsAndRepurposesDocument() {
        User existing = new User();
        existing.setId("old-id");
        existing.setEmail(EMAIL);
        existing.setStatus(UserStatus.deleted);
        existing.setDeletedAt(Instant.now().minus(Duration.ofDays(31)));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(existing));
        when(passwordEncoder.encode("Strong1Pass")).thenReturn("hashed-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(authService.register(request(EMAIL, "Strong1Pass", "Alice"), exchange()))
                .assertNext(resp -> assertThat(resp.id()).isEqualTo("old-id"))
                .verifyComplete();

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(cap.capture());
        User saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.pending);
        assertThat(saved.getDeletedAt()).as("deletedAt must be cleared").isNull();
        assertThat(saved.getPasswordHash()).isEqualTo("hashed-pw");
    }

    @Test
    void register_softDeletedSuperAdmin_olderThan30Days_doesNotInheritSuperAdminFlag() {
        // Privilege-escalation regression guard (security-auditor finding): if a superadmin
        // was soft-deleted >30 days ago, a fresh registration on the same email must NOT
        // inherit isSuperAdmin=true. Only SuperAdminSeeder may grant the flag.
        User existing = new User();
        existing.setId("old-admin-id");
        existing.setEmail(EMAIL);
        existing.setStatus(UserStatus.deleted);
        existing.setDeletedAt(Instant.now().minus(Duration.ofDays(31)));
        existing.setSuperAdmin(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(existing));
        when(passwordEncoder.encode("Strong1Pass")).thenReturn("hashed-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(authService.register(request(EMAIL, "Strong1Pass", "Alice"), exchange()))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(cap.capture());
        assertThat(cap.getValue().isSuperAdmin())
                .as("superadmin flag must be reset on repurposed registration")
                .isFalse();
    }

    @Test
    void register_perIpRateLimit_returns429_andSkipsBcrypt() {
        // 11th attempt from same IP within window must be rejected before BCrypt runs.
        when(redisTemplate.opsForValue().increment("register:rate:ip:127.0.0.1"))
                .thenReturn(Mono.just(11L));

        StepVerifier.create(authService.register(request(EMAIL, "Strong1Pass", "Alice"), exchange()))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.TOO_MANY_REQUESTS)
                .verify();

        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void register_redisDown_failsOpen_andProceeds() {
        // Decision 4 fail-open: Redis outage must not block legitimate registration.
        when(redisTemplate.opsForValue().increment(anyString()))
                .thenReturn(Mono.error(new RuntimeException("redis down")));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId("user-id-1");
            return Mono.just(u);
        });

        StepVerifier.create(authService.register(request(EMAIL, "Strong1Pass", "Alice"), exchange()))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void register_emailDispatchFailure_doesNotPropagate_returns201() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId("user-id-1");
            return Mono.just(u);
        });
        // EmailService.send is fire-and-forget by design; even if the dispatch path threw a
        // synchronous exception (which it should not), the registration must still succeed.
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendVerificationEmail(anyString(), anyString(), anyString());

        StepVerifier.create(authService.register(request(EMAIL, "Strong1Pass", "Alice"), exchange()))
                .expectNextCount(1)
                .verifyComplete();
    }

    // ---------- verifyEmail ----------

    @Test
    void verifyEmail_validToken_pendingUser_setsActive_clearsToken_logsEvent() {
        String raw = "raw-token-abc";
        String hash = tokenService.hashToken(raw);

        User user = new User();
        user.setId("user-id-1");
        user.setEmail(EMAIL);
        user.setStatus(UserStatus.pending);
        user.setEmailVerificationTokenHash(hash);
        user.setEmailVerificationExpiresAt(Instant.now().plus(Duration.ofHours(1)));

        when(userRepository.findByEmailVerificationTokenHash(hash)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(authService.verifyEmail(raw, exchange()))
                .assertNext(resp -> assertThat(resp.redirect()).isEqualTo("/login"))
                .verifyComplete();

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(cap.capture());
        User saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.active);
        assertThat(saved.getEmailVerificationTokenHash()).isNull();
        assertThat(saved.getEmailVerificationExpiresAt()).isNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        verify(eventService).logEvent(eq("user-id-1"), eq("email_verified"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void verifyEmail_blankToken_returns400_withoutDbCall() {
        // Guard against NPE / blank-token path that would otherwise propagate to a 500.
        StepVerifier.create(authService.verifyEmail("", exchange()))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.BAD_REQUEST)
                .verify();
        verifyNoInteractions(userRepository);
    }

    @Test
    void verifyEmail_invalidToken_returns400_notServerError() {
        String raw = "garbage-token";
        when(userRepository.findByEmailVerificationTokenHash(tokenService.hashToken(raw)))
                .thenReturn(Mono.empty());

        StepVerifier.create(authService.verifyEmail(raw, exchange()))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(userRepository, never()).save(any());
        verifyNoInteractions(eventService);
    }

    @Test
    void verifyEmail_expiredToken_returns400WithTokenExpiredCode() {
        String raw = "raw-expired";
        String hash = tokenService.hashToken(raw);

        User user = new User();
        user.setId("user-id-1");
        user.setStatus(UserStatus.pending);
        user.setEmailVerificationTokenHash(hash);
        // expiresAt before now
        user.setEmailVerificationExpiresAt(Instant.now().minus(Duration.ofMinutes(5)));

        when(userRepository.findByEmailVerificationTokenHash(hash)).thenReturn(Mono.just(user));

        StepVerifier.create(authService.verifyEmail(raw, exchange()))
                .expectErrorMatches(e -> {
                    if (!(e instanceof AppException ae)) return false;
                    return ae.getStatus() == HttpStatus.BAD_REQUEST
                            && "TOKEN_EXPIRED".equals(ae.getCode());
                })
                .verify();

        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyEmail_deletedUser_returns400() {
        // Per spec: lookup is "status IN (pending, active)" — a token belonging to a deleted user
        // must read like an invalid token, not a 200.
        String raw = "raw";
        String hash = tokenService.hashToken(raw);

        User user = new User();
        user.setStatus(UserStatus.deleted);
        user.setEmailVerificationTokenHash(hash);
        user.setEmailVerificationExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        when(userRepository.findByEmailVerificationTokenHash(hash)).thenReturn(Mono.just(user));

        StepVerifier.create(authService.verifyEmail(raw, exchange()))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void verifyEmail_alreadyActive_validToken_returnsRedirectIdempotent() {
        // If the user clicks the link twice quickly, the token hash is still on the document
        // until it's saved. The status filter (pending|active) lets the second click also succeed.
        String raw = "raw";
        String hash = tokenService.hashToken(raw);

        User user = new User();
        user.setId("user-id-1");
        user.setStatus(UserStatus.active);
        user.setEmailVerificationTokenHash(hash);
        user.setEmailVerificationExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        when(userRepository.findByEmailVerificationTokenHash(hash)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(authService.verifyEmail(raw, exchange()))
                .assertNext(resp -> assertThat(resp.redirect()).isEqualTo("/login"))
                .verifyComplete();
    }

    // ---------- resendVerification ----------

    @Test
    void resendVerification_pendingUser_firstCall_setsRateLimitKey_sendsEmail() {
        when(redisTemplate.opsForValue()
                .setIfAbsent(eq("resend:rate:" + EMAIL), eq("1"), eq(Duration.ofSeconds(60))))
                .thenReturn(Mono.just(true));

        User user = new User();
        user.setId("user-id-1");
        user.setEmail(EMAIL);
        user.setName("Alice");
        user.setStatus(UserStatus.pending);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(authService.resendVerification(EMAIL))
                .verifyComplete();

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(cap.capture());
        User saved = cap.getValue();
        assertThat(saved.getEmailVerificationTokenHash()).matches("[0-9a-f]{64}");
        assertThat(saved.getEmailVerificationExpiresAt())
                .isAfter(Instant.now().plus(Duration.ofHours(23)));

        ArgumentCaptor<String> tokenCap = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendVerificationEmail(eq(EMAIL), eq("Alice"), tokenCap.capture());
        assertThat(tokenCap.getValue()).hasSize(43);
    }

    @Test
    void resendVerification_within60s_returns429() {
        when(redisTemplate.opsForValue()
                .setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(false));

        StepVerifier.create(authService.resendVerification(EMAIL))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.TOO_MANY_REQUESTS)
                .verify();

        verifyNoInteractions(userRepository);
        verifyNoInteractions(emailService);
    }

    @Test
    void resendVerification_unknownEmail_completes_doesNotRevealNonExistence() {
        when(redisTemplate.opsForValue()
                .setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());

        StepVerifier.create(authService.resendVerification(EMAIL))
                .verifyComplete();

        verifyNoInteractions(emailService);
    }

    @Test
    void resendVerification_alreadyActiveUser_doesNotSendEmail_completesQuietly() {
        when(redisTemplate.opsForValue()
                .setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));

        User user = new User();
        user.setEmail(EMAIL);
        user.setStatus(UserStatus.active);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));

        StepVerifier.create(authService.resendVerification(EMAIL))
                .verifyComplete();

        verify(userRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    void resendVerification_canonicalizesEmail() {
        when(redisTemplate.opsForValue()
                .setIfAbsent(eq("resend:rate:" + EMAIL), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());

        StepVerifier.create(authService.resendVerification("USER@Test.com"))
                .verifyComplete();

        verify(userRepository).findByEmail(EMAIL);
    }
}
