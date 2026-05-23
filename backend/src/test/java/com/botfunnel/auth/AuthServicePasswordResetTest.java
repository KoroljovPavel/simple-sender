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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    RedisTemplate<String, String> redisTemplate;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    SecurityContextRepository securityContextRepository;

    @Mock
    EventService eventService;

    @Mock
    EmailService emailService;

    @Mock
    MongoTemplate mongoTemplate;

    TokenService tokenService;
    AuthService authService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
        authService = new AuthService(userRepository, redisTemplate, passwordEncoder,
                securityContextRepository, eventService, emailService, tokenService,
                mongoTemplate, SUPPORT_EMAIL, 24L, 30L);
        // Default-allow forgot-password rate limiter (Redis SET NX returns true ⇒ first hit).
        // Tests that exercise the over-limit branch override this stub explicitly.
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()
                        .setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/auth/anything");
        r.addHeader("User-Agent", "JUnit");
        r.setRemoteAddr(IP);
        return r;
    }

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    // ---------- logout ----------

    @Test
    void logout_invalidatesCurrentSession_returnsNormally() {
        MockHttpServletRequest req = request();
        // Materialise a session so logout has something to invalidate.
        req.getSession(true);
        assertThat(req.getSession(false)).isNotNull();

        assertThatCode(() -> authService.logout(req, response())).doesNotThrowAnyException();

        // After invalidate, MockHttpServletRequest returns null from getSession(false).
        assertThat(req.getSession(false)).isNull();
        // Logout must NOT touch the sessions collection — it only removes the current cookie.
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void logout_noActiveSession_isNoOp() {
        MockHttpServletRequest req = request();
        assertThat(req.getSession(false)).as("no session before logout").isNull();

        assertThatCode(() -> authService.logout(req, response())).doesNotThrowAnyException();
        verifyNoInteractions(mongoTemplate);
    }

    // ---------- forgotPassword ----------

    @Test
    void forgotPassword_unknownEmail_completes_doesNotEmail_logsAnonymousEventWithNullMetadata() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());

        authService.forgotPassword(EMAIL, request());

        verify(emailService, never()).sendPasswordResetEmail(anyString(), any(), anyString());
        verify(userRepository, never()).save(any());
        // userId=null AND metadata=null: storing the user-supplied email in the audit log would
        // turn the events collection itself into the enumeration oracle the response shape was
        // meant to prevent (security-auditor finding #3 / code-reviewer major #1).
        verify(eventService).logEvent(eq(null), eq("password_reset_requested"),
                eq(IP), eq("JUnit"), eq(null));
    }

    @Test
    void forgotPassword_activeUser_storesHashedToken_setsExpiry_dispatchesEmail_logsEvent() {
        User user = new User();
        user.setId("user-id-1");
        user.setEmail(EMAIL);
        user.setName("Alice");
        user.setStatus(UserStatus.active);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.forgotPassword(EMAIL, request());

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
    void forgotPassword_deletedUser_doesNotEmail_logsAnonymousEventWithNullMetadata() {
        // Soft-deleted accounts must not receive a reset email — and the events log records
        // userId=null AND metadata=null (no leaked email) to prevent enumeration.
        User user = new User();
        user.setId("user-id-1");
        user.setEmail(EMAIL);
        user.setStatus(UserStatus.deleted);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));

        authService.forgotPassword(EMAIL, request());

        verify(emailService, never()).sendPasswordResetEmail(anyString(), any(), anyString());
        verify(userRepository, never()).save(any());
        verify(eventService).logEvent(eq(null), eq("password_reset_requested"),
                eq(IP), eq("JUnit"), eq(null));
    }

    @Test
    void forgotPassword_blockedUser_doesNotEmail_logsAnonymousEvent() {
        // Blocked accounts must NOT be allowed to reset — login already gates blocked status, and
        // reset must mirror that or admins lose the block (a malicious actor with the account's
        // email could regain access by changing the password). Same anti-enumeration response.
        User user = new User();
        user.setId("user-id-1");
        user.setEmail(EMAIL);
        user.setStatus(UserStatus.blocked);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));

        authService.forgotPassword(EMAIL, request());

        verify(emailService, never()).sendPasswordResetEmail(anyString(), any(), anyString());
        verify(userRepository, never()).save(any());
        verify(eventService).logEvent(eq(null), eq("password_reset_requested"),
                eq(IP), eq("JUnit"), eq(null));
    }

    @Test
    void forgotPassword_perIpRateLimit_returns200Silently_andSkipsLookup() {
        // Over-limit returns 200 (anti-enumeration) but does NOT touch the DB or email service.
        when(redisTemplate.opsForValue()
                .setIfAbsent(eq("forgot:rate:ip:" + IP), eq("1"), eq(Duration.ofSeconds(60))))
                .thenReturn(false);

        authService.forgotPassword(EMAIL, request());

        verifyNoInteractions(userRepository);
        verifyNoInteractions(emailService);
        verifyNoInteractions(eventService);
    }

    @Test
    void forgotPassword_redisDown_failsOpen_andProceedsWithLookup() {
        // Decision 4 fail-open: Redis outage must not block legitimate reset attempts.
        when(redisTemplate.opsForValue()
                .setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());

        authService.forgotPassword(EMAIL, request());

        verify(userRepository).findByEmail(EMAIL);
    }

    @Test
    void forgotPassword_canonicalizesEmailToLowercase() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());

        authService.forgotPassword("USER@Test.com", request());

        verify(userRepository).findByEmail(EMAIL);
    }

    @Test
    void forgotPassword_unknownEmail_incursDummyDelay_forEnumerationOracleSuppression() {
        // FORGOT_DUMMY_DELAY = 40ms. The unknown-email branch must spend at least that long so
        // the response wall-clock is comparable to the known-user save path — closing the timing
        // oracle (security-auditor finding #2 / CWE-208). Lower bound is loose to avoid clock
        // jitter flakes; the only invariant is "non-trivial delay was incurred".
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());

        Instant start = Instant.now();
        authService.forgotPassword(EMAIL, request());
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed)
                .as("forgot-password unknown-email branch must run the calibrated dummy delay")
                .isGreaterThanOrEqualTo(Duration.ofMillis(30));
    }

    @Test
    void forgotPassword_emailDispatchFailure_doesNotPropagate_andStillLogsAuditEvent() {
        // SMTP failure must not (a) fail the API response or (b) skip the audit event — operators
        // need the audit trail even when delivery breaks.
        User user = new User();
        user.setId("user-id-1");
        user.setEmail(EMAIL);
        user.setName("Alice");
        user.setStatus(UserStatus.active);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendPasswordResetEmail(anyString(), any(), anyString());

        authService.forgotPassword(EMAIL, request());

        verify(eventService).logEvent(eq("user-id-1"), eq("password_reset_requested"),
                eq(IP), eq("JUnit"), eq(null));
    }

    // ---------- resetPassword ----------

    @Test
    void resetPassword_blankToken_returns400_withoutDbCall() {
        assertThatThrownBy(() -> authService.resetPassword("", "NewStr0ngPass", request()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(userRepository);
    }

    @Test
    void resetPassword_unknownTokenHash_returns400() {
        String raw = "garbage";
        when(userRepository.findByPasswordResetTokenHash(tokenService.hashToken(raw)))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(raw, "NewStr0ngPass", request()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_blockedUser_returns400_doesNotRotate() {
        // Token issued just before admin block. Reset must NOT bypass the block.
        String raw = "raw-blocked";
        User u = new User();
        u.setId("user-id-1");
        u.setStatus(UserStatus.blocked);
        u.setPasswordResetTokenHash(tokenService.hashToken(raw));
        u.setPasswordResetExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        when(userRepository.findByPasswordResetTokenHash(tokenService.hashToken(raw)))
                .thenReturn(java.util.Optional.of(u));

        assertThatThrownBy(() -> authService.resetPassword(raw, "NewStr0ngPass", request()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void resetPassword_deletedUser_returns400_doesNotRotate() {
        // Token issued just before user deletion. Same gate: deleted accounts can never reset.
        String raw = "raw-deleted";
        User u = new User();
        u.setId("user-id-1");
        u.setStatus(UserStatus.deleted);
        u.setPasswordResetTokenHash(tokenService.hashToken(raw));
        u.setPasswordResetExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        when(userRepository.findByPasswordResetTokenHash(tokenService.hashToken(raw)))
                .thenReturn(java.util.Optional.of(u));

        assertThatThrownBy(() -> authService.resetPassword(raw, "NewStr0ngPass", request()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(mongoTemplate);
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
                .thenReturn(java.util.Optional.of(u));

        assertThatThrownBy(() -> authService.resetPassword(raw, "NewStr0ngPass", request()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

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
                .thenReturn(java.util.Optional.of(u));

        assertThatThrownBy(() -> authService.resetPassword(raw, "NewStr0ngPass", request()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

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
                .thenReturn(java.util.Optional.of(u));
        when(passwordEncoder.encode("NewStr0ngPass")).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mongoTemplate.remove(any(Query.class), eq("sessions")))
                .thenReturn(DeleteResult.acknowledged(2L));

        authService.resetPassword(raw, "NewStr0ngPass", request());

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
        verify(mongoTemplate).remove(queryCap.capture(), eq("sessions"));
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
    void resetPassword_callsPasswordEncoderEncodeOnce_withNewPassword() {
        // Under the servlet stack BCrypt runs synchronously on the calling VT — no scheduler
        // gymnastics. This test only locks in that encode() IS called with the new password
        // (not the old hash).
        String raw = "raw-valid";
        User u = new User();
        u.setId("user-id-1");
        u.setStatus(UserStatus.active);
        u.setPasswordHash("old-hash");
        u.setPasswordResetTokenHash(tokenService.hashToken(raw));
        u.setPasswordResetExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        when(userRepository.findByPasswordResetTokenHash(tokenService.hashToken(raw)))
                .thenReturn(java.util.Optional.of(u));
        when(passwordEncoder.encode("NewStr0ngPass")).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mongoTemplate.remove(any(Query.class), eq("sessions")))
                .thenReturn(DeleteResult.acknowledged(0L));

        authService.resetPassword(raw, "NewStr0ngPass", request());
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
                .thenReturn(java.util.Optional.of(u));
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mongoTemplate.remove(any(Query.class), eq("sessions")))
                .thenReturn(DeleteResult.acknowledged(0L));

        assertThatCode(() -> authService.resetPassword(raw, "NewStr0ngPass", request()))
                .doesNotThrowAnyException();
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
                .thenReturn(java.util.Optional.of(u));
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mongoTemplate.remove(any(Query.class), eq("sessions")))
                .thenReturn(DeleteResult.acknowledged(0L));

        authService.resetPassword(raw, "NewStr0ngPass", request());

        Query expected = Query.query(Criteria.where("principal").is("user-id-1"));
        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).remove(cap.capture(), eq("sessions"));
        assertThat(cap.getValue().getQueryObject()).isEqualTo(expected.getQueryObject());
    }
}
