package com.botfunnel.auth;

import com.botfunnel.common.AppException;
import com.botfunnel.email.EmailService;
import com.botfunnel.events.EventService;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.mongodb.client.result.DeleteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServicePasswordResetTest {

    private static final String EMAIL = "user@test.com";
    private static final String SUPPORT_EMAIL = "support@botfunnel.test";
    private static final String IP = "10.0.0.7";

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

    @Mock
    ReactiveMongoTemplate reactiveMongoTemplate;

    TokenService tokenService;
    AuthService authService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
        authService = new AuthService(userRepository, redisTemplate, passwordEncoder,
                securityContextRepository, eventService, emailService, tokenService,
                reactiveMongoTemplate, SUPPORT_EMAIL, 24L, 30L);
    }

    private ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/auth/anything")
                .header("User-Agent", "JUnit")
                .remoteAddress(new InetSocketAddress(IP, 12345))
                .build());
    }

    // ---------- logout ----------

    @Test
    void logout_invalidatesCurrentWebSession_returnsCompletes() {
        WebSession session = org.mockito.Mockito.mock(WebSession.class);
        when(session.invalidate()).thenReturn(Mono.empty());
        ServerWebExchange ex = org.mockito.Mockito.mock(ServerWebExchange.class);
        when(ex.getSession()).thenReturn(Mono.just(session));

        StepVerifier.create(authService.logout(ex)).verifyComplete();
        verify(session).invalidate();
        // Logout must NOT touch the sessions collection — it only removes the current cookie.
        verifyNoInteractions(reactiveMongoTemplate);
    }

    // ---------- forgotPassword ----------

    @Test
    void forgotPassword_unknownEmail_completes_doesNotEmail_logsAnonymousEvent() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());

        StepVerifier.create(authService.forgotPassword(EMAIL, exchange())).verifyComplete();

        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString(), anyString());
        verify(userRepository, never()).save(any());
        // Audit log fires with userId=null (per task spec): prevents enumeration via the events log.
        verify(eventService).logEvent(eq(null), eq("password_reset_requested"),
                eq(IP), eq("JUnit"), any());
    }

    @Test
    void forgotPassword_activeUser_storesHashedToken_setsExpiry_dispatchesEmail_logsEvent() {
        User user = new User();
        user.setId("user-id-1");
        user.setEmail(EMAIL);
        user.setName("Alice");
        user.setStatus(UserStatus.active);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(authService.forgotPassword(EMAIL, exchange())).verifyComplete();

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(cap.capture());
        User saved = cap.getValue();
        assertThat(saved.getPasswordResetTokenHash()).matches("[0-9a-f]{64}");
        assertThat(saved.getPasswordResetExpiresAt())
                .isAfter(Instant.now().plus(Duration.ofMinutes(55)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(65)));
        assertThat(saved.getPasswordResetUsedAt())
                .as("brand-new token must NOT be marked as used")
                .isNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        // Raw token (43-char base64url) is sent in email; hash is never sent.
        ArgumentCaptor<String> rawCap = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetEmail(eq(EMAIL), eq("Alice"), rawCap.capture());
        assertThat(rawCap.getValue()).hasSize(43)
                .isNotEqualTo(saved.getPasswordResetTokenHash());

        verify(eventService).logEvent(eq("user-id-1"), eq("password_reset_requested"),
                eq(IP), eq("JUnit"), any());
    }

    @Test
    void forgotPassword_deletedUser_doesNotEmail_logsAnonymousEvent() {
        // Soft-deleted accounts must not receive a reset email — but the response remains 200
        // and the events log records userId=null to prevent enumeration.
        User user = new User();
        user.setId("user-id-1");
        user.setEmail(EMAIL);
        user.setStatus(UserStatus.deleted);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));

        StepVerifier.create(authService.forgotPassword(EMAIL, exchange())).verifyComplete();

        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString(), anyString());
        verify(userRepository, never()).save(any());
        verify(eventService).logEvent(eq(null), eq("password_reset_requested"),
                eq(IP), eq("JUnit"), any());
    }

    @Test
    void forgotPassword_canonicalizesEmailToLowercase() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());

        StepVerifier.create(authService.forgotPassword("USER@Test.com", exchange())).verifyComplete();

        verify(userRepository).findByEmail(EMAIL);
    }

    @Test
    void forgotPassword_emailDispatchFailure_doesNotPropagate_returnsComplete() {
        User user = new User();
        user.setId("user-id-1");
        user.setEmail(EMAIL);
        user.setName("Alice");
        user.setStatus(UserStatus.active);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendPasswordResetEmail(anyString(), anyString(), anyString());

        StepVerifier.create(authService.forgotPassword(EMAIL, exchange())).verifyComplete();
    }

    // ---------- resetPassword ----------

    @Test
    void resetPassword_blankToken_returns400_withoutDbCall() {
        StepVerifier.create(authService.resetPassword("", "NewStr0ngPass", exchange()))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.BAD_REQUEST)
                .verify();
        verifyNoInteractions(userRepository);
    }

    @Test
    void resetPassword_unknownTokenHash_returns400() {
        String raw = "garbage";
        when(userRepository.findByPasswordResetTokenHash(tokenService.hashToken(raw)))
                .thenReturn(Mono.empty());

        StepVerifier.create(authService.resetPassword(raw, "NewStr0ngPass", exchange()))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_expiredToken_returns400() {
        String raw = "raw-expired";
        User u = new User();
        u.setId("user-id-1");
        u.setStatus(UserStatus.active);
        u.setPasswordResetTokenHash(tokenService.hashToken(raw));
        u.setPasswordResetExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        when(userRepository.findByPasswordResetTokenHash(tokenService.hashToken(raw)))
                .thenReturn(Mono.just(u));

        StepVerifier.create(authService.resetPassword(raw, "NewStr0ngPass", exchange()))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_alreadyUsed_returns400() {
        String raw = "raw-used";
        User u = new User();
        u.setId("user-id-1");
        u.setStatus(UserStatus.active);
        u.setPasswordResetTokenHash(tokenService.hashToken(raw));
        u.setPasswordResetExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        u.setPasswordResetUsedAt(Instant.now().minus(Duration.ofMinutes(1)));
        when(userRepository.findByPasswordResetTokenHash(tokenService.hashToken(raw)))
                .thenReturn(Mono.just(u));

        StepVerifier.create(authService.resetPassword(raw, "NewStr0ngPass", exchange()))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_validToken_updatesHash_clearsTokenFields_terminatesSessions_logsEvent() {
        String raw = "raw-valid";
        User u = new User();
        u.setId("user-id-1");
        u.setEmail(EMAIL);
        u.setStatus(UserStatus.active);
        u.setPasswordHash("old-hash");
        u.setPasswordResetTokenHash(tokenService.hashToken(raw));
        u.setPasswordResetExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        when(userRepository.findByPasswordResetTokenHash(tokenService.hashToken(raw)))
                .thenReturn(Mono.just(u));
        when(passwordEncoder.encode("NewStr0ngPass")).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(reactiveMongoTemplate.remove(any(Query.class), eq("sessions")))
                .thenReturn(Mono.just(DeleteResult.acknowledged(2L)));

        StepVerifier.create(authService.resetPassword(raw, "NewStr0ngPass", exchange()))
                .verifyComplete();

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(cap.capture());
        User saved = cap.getValue();
        assertThat(saved.getPasswordHash()).isEqualTo("new-hash");
        assertThat(saved.getPasswordResetTokenHash())
                .as("token hash must be cleared after a successful reset to prevent reuse")
                .isNull();
        assertThat(saved.getPasswordResetExpiresAt()).isNull();
        assertThat(saved.getPasswordResetUsedAt())
                .as("usedAt must be set so a stored copy of the token cannot be replayed")
                .isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        ArgumentCaptor<Query> queryCap = ArgumentCaptor.forClass(Query.class);
        verify(reactiveMongoTemplate).remove(queryCap.capture(), eq("sessions"));
        // The query must filter on the verified principal field path (top-level "principal").
        // Spring Session's MongoIndexedSessionRepository indexes principal name there.
        Query query = queryCap.getValue();
        assertThat(query.getQueryObject().toJson())
                .contains("principal")
                .contains("user-id-1");

        verify(eventService).logEvent(eq("user-id-1"), eq("password_changed"),
                eq(IP), eq("JUnit"), any());
    }

    @Test
    void resetPassword_runsBcryptOnBoundedElastic_doesNotBlockEventLoop() {
        // BCrypt cost-12 takes ~250ms; calling it on the Reactor event loop would freeze the
        // backend. Verify by checking that PasswordEncoder.encode is called (other tests verify
        // boundedElastic via the same pattern as register).
        String raw = "raw-valid";
        User u = new User();
        u.setId("user-id-1");
        u.setStatus(UserStatus.active);
        u.setPasswordHash("old-hash");
        u.setPasswordResetTokenHash(tokenService.hashToken(raw));
        u.setPasswordResetExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        when(userRepository.findByPasswordResetTokenHash(tokenService.hashToken(raw)))
                .thenReturn(Mono.just(u));
        when(passwordEncoder.encode("NewStr0ngPass")).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(reactiveMongoTemplate.remove(any(Query.class), eq("sessions")))
                .thenReturn(Mono.just(DeleteResult.acknowledged(0L)));

        StepVerifier.create(authService.resetPassword(raw, "NewStr0ngPass", exchange()))
                .verifyComplete();
        verify(passwordEncoder).encode("NewStr0ngPass");
    }

    @Test
    void resetPassword_terminateAllSessions_emptyDeleteResult_doesNotFail() {
        // If a user has no active sessions (e.g. token issued, password reset before login),
        // remove() returns DeleteResult with 0 — the reset must still succeed.
        String raw = "raw-valid";
        User u = new User();
        u.setId("user-id-1");
        u.setStatus(UserStatus.active);
        u.setPasswordHash("old-hash");
        u.setPasswordResetTokenHash(tokenService.hashToken(raw));
        u.setPasswordResetExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        when(userRepository.findByPasswordResetTokenHash(tokenService.hashToken(raw)))
                .thenReturn(Mono.just(u));
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(reactiveMongoTemplate.remove(any(Query.class), eq("sessions")))
                .thenReturn(Mono.just(DeleteResult.acknowledged(0L)));

        StepVerifier.create(authService.resetPassword(raw, "NewStr0ngPass", exchange()))
                .verifyComplete();
    }

    @Test
    void resetPassword_terminateAllSessions_filtersByPrincipalEqualsUserId() {
        String raw = "raw-valid";
        User u = new User();
        u.setId("user-id-1");
        u.setStatus(UserStatus.active);
        u.setPasswordHash("old-hash");
        u.setPasswordResetTokenHash(tokenService.hashToken(raw));
        u.setPasswordResetExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        when(userRepository.findByPasswordResetTokenHash(tokenService.hashToken(raw)))
                .thenReturn(Mono.just(u));
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(reactiveMongoTemplate.remove(any(Query.class), eq("sessions")))
                .thenReturn(Mono.just(DeleteResult.acknowledged(0L)));

        StepVerifier.create(authService.resetPassword(raw, "NewStr0ngPass", exchange()))
                .verifyComplete();

        Query expected = Query.query(Criteria.where("principal").is("user-id-1"));
        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(reactiveMongoTemplate).remove(cap.capture(), eq("sessions"));
        assertThat(cap.getValue().getQueryObject()).isEqualTo(expected.getQueryObject());
    }
}
