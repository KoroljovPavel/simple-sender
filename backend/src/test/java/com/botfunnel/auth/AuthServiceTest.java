package com.botfunnel.auth;

import com.botfunnel.auth.dto.LoginRequest;
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
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, redisTemplate, passwordEncoder,
                securityContextRepository, eventService, emailService, new TokenService(),
                reactiveMongoTemplate, SUPPORT_EMAIL, 24L, 30L);
    }

    private ServerWebExchange exchangeWithIp(String ip) {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/auth/login")
                .header("User-Agent", "JUnit-Test")
                .remoteAddress(new InetSocketAddress(ip, 12345))
                .build();
        return MockServerWebExchange.from(request);
    }

    private void stubBruteCounters(String emailValue, String ipValue) {
        when(redisTemplate.opsForValue().get(EMAIL_KEY))
                .thenReturn(emailValue == null ? Mono.empty() : Mono.just(emailValue));
        when(redisTemplate.opsForValue().get(IP_KEY))
                .thenReturn(ipValue == null ? Mono.empty() : Mono.just(ipValue));
    }

    private void stubIncrement(long emailNew, long ipNew) {
        when(redisTemplate.opsForValue().increment(EMAIL_KEY)).thenReturn(Mono.just(emailNew));
        when(redisTemplate.opsForValue().increment(IP_KEY)).thenReturn(Mono.just(ipNew));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
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

    private ServerWebExchange mockExchangeWithSessions(WebSession preAuth, WebSession fresh) {
        ServerWebExchange exchange = org.mockito.Mockito.mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(MockServerHttpRequest
                .post("/api/auth/login")
                .header("User-Agent", "JUnit")
                .remoteAddress(new InetSocketAddress(IP, 12345))
                .build());
        when(exchange.getSession()).thenReturn(Mono.just(preAuth), Mono.just(fresh));
        return exchange;
    }

    @Test
    void login_nonExistentEmail_runsDummyBcrypt_andIncrementsCounters_returns401() {
        stubBruteCounters(null, null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
        when(passwordEncoder.matches(eq("anyPassword"), eq(AuthService.DUMMY_HASH))).thenReturn(false);
        stubIncrement(1L, 1L);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "anyPassword", false), exchangeWithIp(IP)))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.UNAUTHORIZED)
                .verify();

        verify(passwordEncoder).matches("anyPassword", AuthService.DUMMY_HASH);
        // Increment-on-not-found closes the 429 status oracle (security-auditor finding #1).
        verify(redisTemplate.opsForValue()).increment(EMAIL_KEY);
        verify(redisTemplate.opsForValue()).increment(IP_KEY);
        verify(eventService).logEvent(isNull(), eq("login_failed"), eq(IP), eq("JUnit-Test"),
                eq(Map.of("reason", "user_not_found")));
    }

    @Test
    void login_canonicalizesEmailToLowercase_forBruteForceKeyAndLookup() {
        // Mixed-case email must produce the same Redis bucket as lowercase to prevent case-variant bypass.
        when(redisTemplate.opsForValue().get(EMAIL_KEY)).thenReturn(Mono.empty());
        when(redisTemplate.opsForValue().get(IP_KEY)).thenReturn(Mono.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
        when(passwordEncoder.matches(any(), eq(AuthService.DUMMY_HASH))).thenReturn(false);
        stubIncrement(1L, 1L);

        StepVerifier.create(authService.login(
                new LoginRequest("USER@Test.com", "anyPassword", false), exchangeWithIp(IP)))
                .expectError(AppException.class)
                .verify();

        verify(userRepository).findByEmail(EMAIL);
        verify(redisTemplate.opsForValue()).get(EMAIL_KEY);
    }

    @Test
    void login_wrongPassword_incrementsRedisCounters_andLogsFailedEvent() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("badpass", user.getPasswordHash())).thenReturn(false);
        stubIncrement(1L, 1L);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "badpass", false), exchangeWithIp(IP)))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.UNAUTHORIZED)
                .verify();

        verify(redisTemplate.opsForValue()).increment(EMAIL_KEY);
        verify(redisTemplate.opsForValue()).increment(IP_KEY);
        verify(redisTemplate).expire(eq(EMAIL_KEY), eq(Duration.ofSeconds(900)));
        verify(redisTemplate).expire(eq(IP_KEY), eq(Duration.ofSeconds(900)));
        verify(eventService).logEvent(eq("user-id-1"), eq("login_failed"), eq(IP), eq("JUnit-Test"),
                eq(Map.of("reason", "wrong_password")));
    }

    @Test
    void login_wrongPassword_secondAttempt_doesNotResetExpiry() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("badpass", user.getPasswordHash())).thenReturn(false);
        // Second failure: counter already exists, INCR returns 2, EXPIRE must NOT be called.
        stubIncrement(2L, 2L);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "badpass", false), exchangeWithIp(IP)))
                .expectError(AppException.class)
                .verify();

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void login_blockedUser_returns403WithSupportEmail_andLogsFailedEvent() {
        stubBruteCounters(null, null);
        User user = activeUser();
        user.setStatus(UserStatus.blocked);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", false), exchangeWithIp(IP)))
                .expectErrorMatches(e -> {
                    if (!(e instanceof AppException ae)) return false;
                    return ae.getStatus() == HttpStatus.FORBIDDEN
                            && ae.getMessage().contains(SUPPORT_EMAIL);
                })
                .verify();

        verify(eventService).logEvent(eq("user-id-1"), eq("login_failed"), eq(IP), eq("JUnit-Test"),
                eq(Map.of("reason", "blocked")));
    }

    @Test
    void login_emailBruteForceLimitReached_returns429_beforeUserLookup() {
        stubBruteCounters("5", "0");

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "anything", false), exchangeWithIp(IP)))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.TOO_MANY_REQUESTS)
                .verify();

        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void login_ipBruteForceLimitReached_returns429() {
        stubBruteCounters("0", "20");

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "anything", false), exchangeWithIp(IP)))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.TOO_MANY_REQUESTS)
                .verify();

        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void login_emailBruteForceLimitReached_logsBruteForceEvent() {
        // Audit the threshold-trip moment — highest-signal indicator of an attack.
        stubBruteCounters("5", "0");

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "anything", false), exchangeWithIp(IP)))
                .expectError(AppException.class)
                .verify();

        verify(eventService).logEvent(isNull(), eq("login_failed"), eq(IP), eq("JUnit-Test"),
                eq(Map.of("reason", "brute_force", "email", EMAIL)));
    }

    @Test
    void login_success_redisDeleteFails_stillReturnsAuthResponse() {
        // Decision 4: fail-open also covers the success-path counter reset.
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(EMAIL_KEY, IP_KEY))
                .thenReturn(Mono.error(new RuntimeException("redis down")));
        when(securityContextRepository.save(any(), any(SecurityContext.class))).thenReturn(Mono.empty());

        WebSession s1 = org.mockito.Mockito.mock(WebSession.class);
        WebSession s2 = org.mockito.Mockito.mock(WebSession.class);
        when(s1.invalidate()).thenReturn(Mono.empty());

        ServerWebExchange exchange = mockExchangeWithSessions(s1, s2);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", false), exchange))
                .assertNext(r -> assertThat(r.id()).isEqualTo("user-id-1"))
                .verifyComplete();

        verify(eventService).logEvent(eq("user-id-1"), eq("login_success"), eq(IP), eq("JUnit"), isNull());
    }

    @Test
    void login_redisCheckFails_failsOpen_andContinuesToUserLookup() {
        // Decision 4: Redis unavailability must not block login. With both GETs erroring, the
        // service must skip the threshold check and proceed to the user lookup.
        when(redisTemplate.opsForValue().get(anyString()))
                .thenReturn(Mono.error(new RuntimeException("redis down")));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
        when(passwordEncoder.matches(any(), eq(AuthService.DUMMY_HASH))).thenReturn(false);
        when(redisTemplate.opsForValue().increment(anyString()))
                .thenReturn(Mono.error(new RuntimeException("redis down")));

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "x", false), exchangeWithIp(IP)))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.UNAUTHORIZED)
                .verify();

        verify(userRepository).findByEmail(EMAIL);
    }

    @Test
    void login_success_invalidatesPreAuthSession_andSetsRememberMeTtl() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(EMAIL_KEY, IP_KEY)).thenReturn(Mono.just(2L));
        when(securityContextRepository.save(any(), any(SecurityContext.class))).thenReturn(Mono.empty());

        WebSession preAuth = org.mockito.Mockito.mock(WebSession.class);
        WebSession fresh = org.mockito.Mockito.mock(WebSession.class);
        when(preAuth.invalidate()).thenReturn(Mono.empty());

        ServerWebExchange exchange = mockExchangeWithSessions(preAuth, fresh);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", true), exchange))
                .assertNext(resp -> {
                    assertThat(resp.id()).isEqualTo("user-id-1");
                    assertThat(resp.warning()).isNull();
                })
                .verifyComplete();

        verify(preAuth).invalidate();
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(fresh).setMaxIdleTime(ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofDays(30));

        ArgumentCaptor<SecurityContext> ctxCap = ArgumentCaptor.forClass(SecurityContext.class);
        verify(securityContextRepository).save(eq(exchange), ctxCap.capture());
        Object principal = ctxCap.getValue().getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOf(AppUserDetails.class);
        AppUserDetails p = (AppUserDetails) principal;
        // Principal must NOT carry credentials material — sessions collection in MongoDB serializes this.
        assertThat(p.getPassword()).isNull();
        assertThat(p.id()).isEqualTo("user-id-1");
        assertThat(p.email()).isEqualTo(EMAIL);
    }

    @Test
    void login_success_noRememberMe_setsTtl24Hours() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(EMAIL_KEY, IP_KEY)).thenReturn(Mono.just(2L));
        when(securityContextRepository.save(any(), any(SecurityContext.class))).thenReturn(Mono.empty());

        WebSession preAuth = org.mockito.Mockito.mock(WebSession.class);
        WebSession fresh = org.mockito.Mockito.mock(WebSession.class);
        when(preAuth.invalidate()).thenReturn(Mono.empty());

        ServerWebExchange exchange = mockExchangeWithSessions(preAuth, fresh);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", false), exchange))
                .assertNext(r -> assertThat(r.warning()).isNull())
                .verifyComplete();

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(fresh).setMaxIdleTime(ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void login_success_resetsBruteForceCountersAndLogsEvent() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(EMAIL_KEY, IP_KEY)).thenReturn(Mono.just(2L));
        when(securityContextRepository.save(any(), any(SecurityContext.class))).thenReturn(Mono.empty());

        WebSession s1 = org.mockito.Mockito.mock(WebSession.class);
        WebSession s2 = org.mockito.Mockito.mock(WebSession.class);
        when(s1.invalidate()).thenReturn(Mono.empty());

        ServerWebExchange exchange = mockExchangeWithSessions(s1, s2);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", false), exchange))
                .expectNextCount(1)
                .verifyComplete();

        verify(redisTemplate, atLeastOnce()).delete(EMAIL_KEY, IP_KEY);
        verify(eventService).logEvent(eq("user-id-1"), eq("login_success"), eq(IP), eq("JUnit"), isNull());
    }

    @Test
    void login_pendingUser_succeedsWithEmailWarning() {
        stubBruteCounters(null, null);
        User user = activeUser();
        user.setStatus(UserStatus.pending);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(EMAIL_KEY, IP_KEY)).thenReturn(Mono.just(2L));
        when(securityContextRepository.save(any(), any(SecurityContext.class))).thenReturn(Mono.empty());

        WebSession s1 = org.mockito.Mockito.mock(WebSession.class);
        WebSession s2 = org.mockito.Mockito.mock(WebSession.class);
        when(s1.invalidate()).thenReturn(Mono.empty());

        ServerWebExchange exchange = mockExchangeWithSessions(s1, s2);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", false), exchange))
                .assertNext(r -> {
                    assertThat(r.warning()).isEqualTo("email_not_verified");
                    assertThat(r.status()).isEqualTo("pending");
                })
                .verifyComplete();
    }

    @Test
    void login_deletedUser_returns401_andLogsFailedEvent() {
        stubBruteCounters(null, null);
        User user = activeUser();
        user.setStatus(UserStatus.deleted);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", false), exchangeWithIp(IP)))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.UNAUTHORIZED)
                .verify();

        verify(eventService).logEvent(eq("user-id-1"), eq("login_failed"), eq(IP), eq("JUnit-Test"),
                eq(Map.of("reason", "deleted")));
    }

    @Test
    void login_extractsIpFromXForwardedForHeader() {
        when(redisTemplate.opsForValue().get("brute:fail:" + EMAIL)).thenReturn(Mono.empty());
        when(redisTemplate.opsForValue().get("brute:fail:ip:203.0.113.99")).thenReturn(Mono.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
        when(passwordEncoder.matches(any(), eq(AuthService.DUMMY_HASH))).thenReturn(false);
        when(redisTemplate.opsForValue().increment(anyString())).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/auth/login")
                .header("X-Forwarded-For", "203.0.113.99, 10.0.0.1")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 12345))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "x", false), exchange))
                .expectError(AppException.class)
                .verify();

        verify(redisTemplate.opsForValue()).get("brute:fail:ip:203.0.113.99");
        verify(redisTemplate.opsForValue(), never()).get("brute:fail:ip:10.0.0.5");
    }

    @Test
    void login_fallsBackToRemoteAddress_whenXffAbsent() {
        when(redisTemplate.opsForValue().get("brute:fail:" + EMAIL)).thenReturn(Mono.empty());
        when(redisTemplate.opsForValue().get("brute:fail:ip:10.0.0.5")).thenReturn(Mono.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
        when(passwordEncoder.matches(any(), eq(AuthService.DUMMY_HASH))).thenReturn(false);
        when(redisTemplate.opsForValue().increment(anyString())).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/auth/login")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 12345))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "x", false), exchange))
                .expectError(AppException.class)
                .verify();

        verify(redisTemplate.opsForValue()).get("brute:fail:ip:10.0.0.5");
    }

    // -------- me() unit tests (4 branches) --------

    @Test
    void me_emptySecurityContext_returns401() {
        StepVerifier.create(authService.me())
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.UNAUTHORIZED)
                .verify();
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

        StepVerifier.create(authService.me().contextWrite(
                        ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx))))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.UNAUTHORIZED)
                .verify();
    }

    @Test
    void me_principalNotAppUserDetails_returns401() {
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                "string-principal", null, java.util.Collections.emptyList());
        SecurityContext ctx = new SecurityContextImpl(auth);

        StepVerifier.create(authService.me().contextWrite(
                        ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx))))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.UNAUTHORIZED)
                .verify();
    }

    @Test
    void me_validAppUserDetails_returnsMeResponse() {
        AppUserDetails principal = new AppUserDetails("u-1", "user@test.com", "Alice", "active");
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext ctx = new SecurityContextImpl(auth);

        StepVerifier.create(authService.me().contextWrite(
                        ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx))))
                .assertNext(r -> {
                    assertThat(r.id()).isEqualTo("u-1");
                    assertThat(r.email()).isEqualTo("user@test.com");
                    assertThat(r.name()).isEqualTo("Alice");
                    assertThat(r.status()).isEqualTo("active");
                })
                .verifyComplete();
    }
}
