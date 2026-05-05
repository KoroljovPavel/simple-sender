package com.botfunnel.auth;

import com.botfunnel.auth.dto.AuthResponse;
import com.botfunnel.auth.dto.LoginRequest;
import com.botfunnel.common.AppException;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, redisTemplate, passwordEncoder,
                securityContextRepository, eventService, SUPPORT_EMAIL, 24L, 30L);
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

    private User activeUser() {
        User user = new User();
        user.setId("user-id-1");
        user.setEmail(EMAIL);
        user.setName("Alice");
        user.setStatus(UserStatus.active);
        user.setPasswordHash("$2a$12$realhashplaceholder");
        return user;
    }

    @Test
    void login_nonExistentEmail_runsDummyBcrypt_returns401() {
        stubBruteCounters(null, null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
        when(passwordEncoder.matches(eq("anyPassword"), eq(AuthService.DUMMY_HASH))).thenReturn(false);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "anyPassword", false), exchangeWithIp(IP)))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.UNAUTHORIZED)
                .verify();

        verify(passwordEncoder).matches("anyPassword", AuthService.DUMMY_HASH);
        verify(passwordEncoder, never()).matches(eq("anyPassword"), eq("$2a$12$realhashplaceholder"));
    }

    @Test
    void login_wrongPassword_incrementsRedisCounters() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("badpass", user.getPasswordHash())).thenReturn(false);
        when(redisTemplate.opsForValue().increment(EMAIL_KEY)).thenReturn(Mono.just(1L));
        when(redisTemplate.opsForValue().increment(IP_KEY)).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "badpass", false), exchangeWithIp(IP)))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.UNAUTHORIZED)
                .verify();

        verify(redisTemplate.opsForValue()).increment(EMAIL_KEY);
        verify(redisTemplate.opsForValue()).increment(IP_KEY);
        verify(redisTemplate).expire(eq(EMAIL_KEY), eq(Duration.ofSeconds(900)));
        verify(redisTemplate).expire(eq(IP_KEY), eq(Duration.ofSeconds(900)));
    }

    @Test
    void login_wrongPassword_secondAttempt_doesNotResetExpiry() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("badpass", user.getPasswordHash())).thenReturn(false);
        // Second failure: counter already exists, INCR returns 2, EXPIRE must NOT be called.
        when(redisTemplate.opsForValue().increment(EMAIL_KEY)).thenReturn(Mono.just(2L));
        when(redisTemplate.opsForValue().increment(IP_KEY)).thenReturn(Mono.just(2L));

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "badpass", false), exchangeWithIp(IP)))
                .expectError(AppException.class)
                .verify();

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void login_blockedUser_returns403WithSupportEmail() {
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
    void login_success_invalidatesPreAuthSession_andSetsRememberMeTtl() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(EMAIL_KEY, IP_KEY)).thenReturn(Mono.just(2L));
        when(securityContextRepository.save(any(), any(SecurityContext.class))).thenReturn(Mono.empty());

        WebSession preAuthSession = org.mockito.Mockito.mock(WebSession.class);
        WebSession newSession = org.mockito.Mockito.mock(WebSession.class);
        when(preAuthSession.invalidate()).thenReturn(Mono.empty());

        ServerWebExchange exchange = org.mockito.Mockito.mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(MockServerHttpRequest
                .post("/api/auth/login").header("User-Agent", "JUnit").remoteAddress(new InetSocketAddress(IP, 12345)).build());
        // First getSession() call returns the pre-auth session (for invalidation),
        // second call returns the freshly created post-invalidate session.
        when(exchange.getSession()).thenReturn(Mono.just(preAuthSession), Mono.just(newSession));

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", true), exchange))
                .assertNext(resp -> {
                    assertThat(resp.id()).isEqualTo("user-id-1");
                    assertThat(resp.warning()).isNull();
                })
                .verifyComplete();

        verify(preAuthSession).invalidate();
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(newSession).setMaxIdleTime(ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofDays(30));
        verify(securityContextRepository).save(eq(exchange), any(SecurityContext.class));
    }

    @Test
    void login_success_noRememberMe_setsTtl24Hours() {
        stubBruteCounters(null, null);
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);
        when(redisTemplate.delete(EMAIL_KEY, IP_KEY)).thenReturn(Mono.just(2L));
        when(securityContextRepository.save(any(), any(SecurityContext.class))).thenReturn(Mono.empty());

        WebSession preAuthSession = org.mockito.Mockito.mock(WebSession.class);
        WebSession newSession = org.mockito.Mockito.mock(WebSession.class);
        when(preAuthSession.invalidate()).thenReturn(Mono.empty());

        ServerWebExchange exchange = org.mockito.Mockito.mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(MockServerHttpRequest
                .post("/api/auth/login").header("User-Agent", "JUnit").remoteAddress(new InetSocketAddress(IP, 12345)).build());
        when(exchange.getSession()).thenReturn(Mono.just(preAuthSession), Mono.just(newSession));

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", false), exchange))
                .assertNext(r -> assertThat(r.warning()).isNull())
                .verifyComplete();

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(newSession).setMaxIdleTime(ttl.capture());
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

        ServerWebExchange exchange = org.mockito.Mockito.mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(MockServerHttpRequest
                .post("/api/auth/login").header("User-Agent", "JUnit").remoteAddress(new InetSocketAddress(IP, 12345)).build());
        when(exchange.getSession()).thenReturn(Mono.just(s1), Mono.just(s2));

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", false), exchange))
                .expectNextCount(1)
                .verifyComplete();

        verify(redisTemplate, atLeastOnce()).delete(EMAIL_KEY, IP_KEY);
        verify(eventService).logEvent(eq("user-id-1"), eq("login_success"), eq(IP), eq("JUnit"), eq(null));
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

        ServerWebExchange exchange = org.mockito.Mockito.mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(MockServerHttpRequest
                .post("/api/auth/login").header("User-Agent", "JUnit").remoteAddress(new InetSocketAddress(IP, 12345)).build());
        when(exchange.getSession()).thenReturn(Mono.just(s1), Mono.just(s2));

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", false), exchange))
                .assertNext(r -> {
                    assertThat(r.warning()).isEqualTo("email_not_verified");
                    assertThat(r.status()).isEqualTo("pending");
                })
                .verifyComplete();
    }

    @Test
    void login_deletedUser_returns401() {
        stubBruteCounters(null, null);
        User user = activeUser();
        user.setStatus(UserStatus.deleted);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("rightpass", user.getPasswordHash())).thenReturn(true);

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "rightpass", false), exchangeWithIp(IP)))
                .expectErrorMatches(e -> e instanceof AppException
                        && ((AppException) e).getStatus() == HttpStatus.UNAUTHORIZED)
                .verify();
    }

    @Test
    void login_extractsIpFromXForwardedForHeader() {
        when(redisTemplate.opsForValue().get("brute:fail:" + EMAIL)).thenReturn(Mono.empty());
        when(redisTemplate.opsForValue().get("brute:fail:ip:203.0.113.99")).thenReturn(Mono.empty());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.empty());
        when(passwordEncoder.matches(any(), eq(AuthService.DUMMY_HASH))).thenReturn(false);

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
}
