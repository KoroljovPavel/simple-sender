package com.botfunnel.auth;

import com.botfunnel.auth.dto.LoginRequest;
import com.botfunnel.auth.dto.RegisterRequest;
import com.botfunnel.common.AppException;
import com.botfunnel.common.SessionAttributes;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "user@test.com";
    private static final String EMAIL_KEY = "brute:fail:" + EMAIL;
    private static final String IP = "10.0.0.7";
    private static final String IP_KEY = "brute:fail:ip:" + IP;
    private static final String SUPPORT_EMAIL = "support@botfunnel.test";

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

    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, redisTemplate, passwordEncoder,
                securityContextRepository, eventService, emailService, new TokenService(),
                mongoTemplate, SUPPORT_EMAIL, 24L, 30L);
    }

    private MockHttpServletRequest requestFromIp(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("User-Agent", "JUnit-Test");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletRequest sessionFixtureRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("User-Agent", "JUnit");
        request.setRemoteAddr(IP);
        return request;
    }

    private void stubBruteCounters(String emailValue, String ipValue) {
        when(redisTemplate.opsForValue().get(EMAIL_KEY)).thenReturn(emailValue);
        when(redisTemplate.opsForValue().get(IP_KEY)).thenReturn(ipValue);
    }

    private void stubIncrement(long emailNew, long ipNew) {
        when(redisTemplate.opsForValue().increment(EMAIL_KEY)).thenReturn(emailNew);
        when(redisTemplate.opsForValue().increment(IP_KEY)).thenReturn(ipNew);
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);
    }

    private User activeUser() {
        User user = new User();
        user.setId("user-id-1");
        user.setEmail(EMAIL);
        user.setName("Alice");
        user.setStatus(UserStatus.active);
        user.setPasswordHash("$2a$12$realhashplaceholder");
        return user;
    }

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void login_nonExistentEmail_runsDummyBcrypt_andIncrementsCounters_returns401() {
        stubBruteCounters(null, null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());
        when(passwordEncoder.matches(eq("anyPassword"), eq(AuthService.DUMMY_HASH))).thenReturn(false);
        stubIncrement(1L, 1L);

        MockHttpServletRequest request = requestFromIp(IP);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "anyPassword", false), request, response))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(passwordEncoder).matches("anyPassword", AuthService.DUMMY_HASH);
        // Increment-on-not-found closes the 429 status oracle (security-auditor finding #1).
        verify(redisTemplate.opsForValue()).increment(EMAIL_KEY);
        verify(redisTemplate.opsForValue()).increment(IP_KEY);
        verify(eventService).logEvent(isNull(), eq("login_failed"), eq(IP), eq("JUnit-Test"),
                eq(java.util.Map.of("reason", "user_not_found")));
    }

    @Test
    void login_canonicalizesEmailToLowercase_forBruteForceKeyAndLookup() {
        // Mixed-case email must produce the same Redis bucket as lowercase to prevent case-variant bypass.
        when(redisTemplate.opsForValue().get(EMAIL_KEY)).thenReturn(null);
        when(redisTemplate.opsForValue().get(IP_KEY)).thenReturn(null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());
        when(passwordEncoder.matches(any(), eq(AuthService.DUMMY_HASH))).thenReturn(false);
        stubIncrement(1L, 1L);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("USER@Test.com", "anyPassword", false),
                requestFromIp(IP), new MockHttpServletResponse()))
                .isInstanceOf(AppException.class);

        verify(userRepository).findByEmail(EMAIL);
        verify(redisTemplate.opsForValue()).get(EMAIL_KEY);
    }

    @Test
    void login_wrongPassword_incrementsRedisCounters_andLogsFailedEvent() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("badpass", user.getPasswordHash())).thenReturn(false);
        stubIncrement(1L, 1L);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(EMAIL, "badpass", false),
                requestFromIp(IP), new MockHttpServletResponse()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(redisTemplate.opsForValue()).increment(EMAIL_KEY);
        verify(redisTemplate.opsForValue()).increment(IP_KEY);
        verify(redisTemplate).expire(eq(EMAIL_KEY), eq(java.time.Duration.ofSeconds(900)));
        verify(redisTemplate).expire(eq(IP_KEY), eq(java.time.Duration.ofSeconds(900)));
        verify(eventService).logEvent(eq("user-id-1"), eq("login_failed"), eq(IP), eq("JUnit-Test"),
                eq(java.util.Map.of("reason", "wrong_password")));
    }

    @Test
    void login_wrongPassword_secondAttempt_doesNotResetExpiry() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("badpass", user.getPasswordHash())).thenReturn(false);
        // Second failure: counter already exists, INCR returns 2, EXPIRE must NOT be called.
        when(redisTemplate.opsForValue().increment(EMAIL_KEY)).thenReturn(2L);
        when(redisTemplate.opsForValue().increment(IP_KEY)).thenReturn(2L);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(EMAIL, "badpass", false),
                requestFromIp(IP), new MockHttpServletResponse()))
                .isInstanceOf(AppException.class);

        verify(redisTemplate, never()).expire(anyString(), any());
    }

    @Test
    void login_blockedUser_returns403WithSupportEmail_andLogsFailedEvent() {
        stubBruteCounters(null, null);
        User user = activeUser();
        user.setStatus(UserStatus.blocked);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(EMAIL, "rightpass", false),
                requestFromIp(IP), new MockHttpServletResponse()))
                .isInstanceOfSatisfying(AppException.class, ae -> {
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ae.getMessage()).contains(SUPPORT_EMAIL);
                });

        verify(eventService).logEvent(eq("user-id-1"), eq("login_failed"), eq(IP), eq("JUnit-Test"),
                eq(java.util.Map.of("reason", "blocked")));
    }

    @Test
    void login_emailBruteForceLimitReached_returns429_beforeUserLookup() {
        stubBruteCounters("5", "0");

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(EMAIL, "anything", false),
                requestFromIp(IP), new MockHttpServletResponse()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void login_ipBruteForceLimitReached_returns429() {
        stubBruteCounters("0", "20");

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(EMAIL, "anything", false),
                requestFromIp(IP), new MockHttpServletResponse()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void login_emailBruteForceLimitReached_logsBruteForceEvent() {
        // Audit the threshold-trip moment — highest-signal indicator of an attack.
        stubBruteCounters("5", "0");

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(EMAIL, "anything", false),
                requestFromIp(IP), new MockHttpServletResponse()))
                .isInstanceOf(AppException.class);

        verify(eventService).logEvent(isNull(), eq("login_failed"), eq(IP), eq("JUnit-Test"),
                eq(java.util.Map.of("reason", "brute_force", "email", EMAIL)));
    }

    @Test
    void login_success_redisDeleteFails_stillReturnsAuthResponse() {
        // Decision 4: fail-open also covers the success-path counter reset.
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(anyCollection()))
                .thenThrow(new RuntimeException("redis down"));

        MockHttpServletRequest request = sessionFixtureRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        var r = authService.login(new LoginRequest(EMAIL, "rightpass", false), request, response);
        assertThat(r.id()).isEqualTo("user-id-1");

        verify(eventService).logEvent(eq("user-id-1"), eq("login_success"), eq(IP), eq("JUnit"), isNull());
    }

    @Test
    void login_redisCheckFails_failsOpen_andContinuesToUserLookup() {
        // Decision 4: Redis unavailability must not block login. With the GET erroring, the
        // service must skip the threshold check and proceed to the user lookup.
        when(redisTemplate.opsForValue().get(anyString()))
                .thenThrow(new RuntimeException("redis down"));
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());
        when(passwordEncoder.matches(any(), eq(AuthService.DUMMY_HASH))).thenReturn(false);
        when(redisTemplate.opsForValue().increment(anyString()))
                .thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(EMAIL, "x", false),
                requestFromIp(IP), new MockHttpServletResponse()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(userRepository).findByEmail(EMAIL);
    }

    @Test
    void login_success_setsRememberMeTtl_andPublishesRememberMeTrueAttribute() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(anyCollection())).thenReturn(2L);

        MockHttpServletRequest request = sessionFixtureRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        var resp = authService.login(new LoginRequest(EMAIL, "rightpass", true), request, response);
        assertThat(resp.id()).isEqualTo("user-id-1");
        assertThat(resp.warning()).isNull();

        // rememberMe=true → TTL == 30 days in seconds. setMaxInactiveInterval takes int seconds.
        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getMaxInactiveInterval())
                .isEqualTo((int) java.time.Duration.ofDays(30).getSeconds());

        // Resolver reads this attribute on cookie write — Boolean.TRUE → 30-day Max-Age cookie.
        assertThat(request.getAttribute(SessionAttributes.REMEMBER_ME_ATTR)).isEqualTo(Boolean.TRUE);

        ArgumentCaptor<SecurityContext> ctxCap = ArgumentCaptor.forClass(SecurityContext.class);
        verify(securityContextRepository).saveContext(ctxCap.capture(), eq(request), eq(response));
        Object principal = ctxCap.getValue().getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOf(AppUserDetails.class);
        AppUserDetails p = (AppUserDetails) principal;
        // Principal must NOT carry credentials material — sessions collection in MongoDB serializes this.
        assertThat(p.getPassword()).isNull();
        assertThat(p.id()).isEqualTo("user-id-1");
        assertThat(p.email()).isEqualTo(EMAIL);
    }

    @Test
    void login_success_noRememberMe_setsTtl24Hours_andPublishesRememberMeFalseAttribute() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(anyCollection())).thenReturn(2L);

        MockHttpServletRequest request = sessionFixtureRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        var resp = authService.login(new LoginRequest(EMAIL, "rightpass", false), request, response);
        assertThat(resp.warning()).isNull();

        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getMaxInactiveInterval())
                .isEqualTo((int) java.time.Duration.ofHours(24).getSeconds());

        // AC-7 regression lock: rememberMe=false must publish Boolean.FALSE so the resolver writes
        // a session-only cookie. Absent attribute would also fall through to session-cookie, but
        // tracking the boolean explicitly catches future changes that might silently flip the flag.
        assertThat(request.getAttribute(SessionAttributes.REMEMBER_ME_ATTR)).isEqualTo(Boolean.FALSE);
    }

    @Test
    void login_success_resetsBruteForceCountersAndLogsEvent() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(anyCollection())).thenReturn(2L);

        MockHttpServletRequest request = sessionFixtureRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        authService.login(new LoginRequest(EMAIL, "rightpass", false), request, response);

        ArgumentCaptor<Collection<String>> keysCap = ArgumentCaptor.forClass(Collection.class);
        verify(redisTemplate, atLeastOnce()).delete(keysCap.capture());
        assertThat(keysCap.getValue()).containsExactlyInAnyOrder(EMAIL_KEY, IP_KEY);
        verify(eventService).logEvent(eq("user-id-1"), eq("login_success"), eq(IP), eq("JUnit"), isNull());
    }

    @Test
    void login_success_callsChangeSessionId_beforeSaveContext_forSessionFixationDefense() {
        // NEW invariant: after a successful login, request.changeSessionId() must be invoked
        // BEFORE securityContextRepository.saveContext(...). Mirrors the prior reactive
        // session-invalidate defense (which existed in spirit but was a no-op due to the cached
        // session-fetch). Implemented for the servlet stack — assert call ordering via spy + InOrder.
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(anyCollection())).thenReturn(2L);

        MockHttpServletRequest baseRequest = sessionFixtureRequest();
        jakarta.servlet.http.HttpServletRequest spied = org.mockito.Mockito.spy(baseRequest);
        MockHttpServletResponse response = new MockHttpServletResponse();

        authService.login(new LoginRequest(EMAIL, "rightpass", false), spied, response);

        InOrder inOrder = org.mockito.Mockito.inOrder(spied, securityContextRepository);
        inOrder.verify(spied).changeSessionId();
        inOrder.verify(securityContextRepository).saveContext(
                any(SecurityContext.class), eq(spied), eq(response));
    }

    @Test
    void login_pendingUser_succeedsWithEmailWarning() {
        stubBruteCounters(null, null);
        User user = activeUser();
        user.setStatus(UserStatus.pending);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(anyCollection())).thenReturn(2L);

        MockHttpServletRequest request = sessionFixtureRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        var r = authService.login(new LoginRequest(EMAIL, "rightpass", false), request, response);
        assertThat(r.warning()).isEqualTo("email_not_verified");
        assertThat(r.status()).isEqualTo("pending");
    }

    @Test
    void login_deletedUser_returns401_andLogsFailedEvent() {
        stubBruteCounters(null, null);
        User user = activeUser();
        user.setStatus(UserStatus.deleted);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(EMAIL, "rightpass", false),
                requestFromIp(IP), new MockHttpServletResponse()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(eventService).logEvent(eq("user-id-1"), eq("login_failed"), eq(IP), eq("JUnit-Test"),
                eq(java.util.Map.of("reason", "deleted")));
    }

    @Test
    void login_extractsIpFromXForwardedForHeader() {
        when(redisTemplate.opsForValue().get("brute:fail:" + EMAIL)).thenReturn(null);
        when(redisTemplate.opsForValue().get("brute:fail:ip:203.0.113.99")).thenReturn(null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());
        when(passwordEncoder.matches(any(), eq(AuthService.DUMMY_HASH))).thenReturn(false);
        when(redisTemplate.opsForValue().increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("X-Forwarded-For", "203.0.113.99, 10.0.0.1");
        request.setRemoteAddr("10.0.0.5");

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(EMAIL, "x", false), request, new MockHttpServletResponse()))
                .isInstanceOf(AppException.class);

        verify(redisTemplate.opsForValue()).get("brute:fail:ip:203.0.113.99");
        verify(redisTemplate.opsForValue(), never()).get("brute:fail:ip:10.0.0.5");
    }

    @Test
    void login_fallsBackToRemoteAddress_whenXffAbsent() {
        when(redisTemplate.opsForValue().get("brute:fail:" + EMAIL)).thenReturn(null);
        when(redisTemplate.opsForValue().get("brute:fail:ip:10.0.0.5")).thenReturn(null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());
        when(passwordEncoder.matches(any(), eq(AuthService.DUMMY_HASH))).thenReturn(false);
        when(redisTemplate.opsForValue().increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("10.0.0.5");

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(EMAIL, "x", false), request, new MockHttpServletResponse()))
                .isInstanceOf(AppException.class);

        verify(redisTemplate.opsForValue()).get("brute:fail:ip:10.0.0.5");
    }

    // -------- register auto-login attribute (AC-6 regression lock) --------

    @Test
    void register_autoLogin_publishesRememberMeFalseAttribute() {
        // AC-6: register-auto-login must always be a session-only cookie. Locks the boolean
        // explicitly so a future change that flips this to TRUE is caught by tests, not by users
        // unexpectedly staying logged in for 30 days after a register flow.
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue().increment(anyString()))
                .thenReturn(1L);
        org.mockito.Mockito.lenient().when(redisTemplate.expire(anyString(), any()))
                .thenReturn(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId("user-id-1");
            return u;
        });

        RegisterRequest req = new RegisterRequest();
        req.setEmail(EMAIL);
        req.setPassword("Strong1Pass");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register");
        request.addHeader("User-Agent", "JUnit");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        var resp = authService.register(req, request, response);
        assertThat(resp.id()).isEqualTo("user-id-1");
        assertThat(request.getAttribute(SessionAttributes.REMEMBER_ME_ATTR)).isEqualTo(Boolean.FALSE);
    }

    // -------- me() unit tests (4 branches) --------

    @Test
    void me_emptySecurityContext_returns401() {
        // No authentication set on the holder.
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> authService.me())
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void me_unauthenticatedToken_returns401() {
        // Use AppUserDetails as principal AND set isAuthenticated=false — this isolates the
        // isAuthenticated() filter so that removing it would let the test fall through to a
        // success response (litmus test).
        AppUserDetails principal = new AppUserDetails("u-1", "x@y.z", "X", "active");
        Authentication unauth = new UsernamePasswordAuthenticationToken(principal, null);
        unauth.setAuthenticated(false);
        SecurityContext ctx = new SecurityContextImpl(unauth);
        SecurityContextHolder.setContext(ctx);

        assertThatThrownBy(() -> authService.me())
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void me_principalNotAppUserDetails_returns401() {
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                "string-principal", null, java.util.Collections.emptyList());
        SecurityContext ctx = new SecurityContextImpl(auth);
        SecurityContextHolder.setContext(ctx);

        assertThatThrownBy(() -> authService.me())
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void me_validAppUserDetails_returnsMeResponse() {
        AppUserDetails principal = new AppUserDetails("u-1", "user@test.com", "Alice", "active");
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext ctx = new SecurityContextImpl(auth);
        SecurityContextHolder.setContext(ctx);

        var r = authService.me();
        assertThat(r.id()).isEqualTo("u-1");
        assertThat(r.email()).isEqualTo("user@test.com");
        assertThat(r.name()).isEqualTo("Alice");
        assertThat(r.status()).isEqualTo("active");
    }
}
