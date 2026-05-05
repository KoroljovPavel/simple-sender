package com.botfunnel.auth;

import com.botfunnel.auth.dto.AuthResponse;
import com.botfunnel.auth.dto.LoginRequest;
import com.botfunnel.auth.dto.MeResponse;
import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // Pre-computed bcrypt(cost=12) hash. Used only to consume ~250ms when a user is not found,
    // so non-existent-email response time matches wrong-password response time (Decision 13).
    // Never matches any real password — the hash input is unrelated to any real account.
    static final String DUMMY_HASH = "$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj/nGYG/U.1S";

    private static final int EMAIL_THRESHOLD = 5;
    private static final int IP_THRESHOLD = 20;
    private static final Duration BRUTE_TTL = Duration.ofSeconds(900);
    private static final String EVENT_LOGIN_SUCCESS = "login_success";

    private final UserRepository userRepository;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ServerSecurityContextRepository securityContextRepository;
    private final EventService eventService;
    private final String supportEmail;
    private final long defaultHours;
    private final long rememberMeDays;

    public AuthService(UserRepository userRepository,
                       ReactiveRedisTemplate<String, String> redisTemplate,
                       PasswordEncoder passwordEncoder,
                       ServerSecurityContextRepository securityContextRepository,
                       EventService eventService,
                       @Value("${app.support-email}") String supportEmail,
                       @Value("${app.session.ttl-default-hours:24}") long defaultHours,
                       @Value("${app.session.ttl-remember-me-days:30}") long rememberMeDays) {
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = passwordEncoder;
        this.securityContextRepository = securityContextRepository;
        this.eventService = eventService;
        this.supportEmail = supportEmail;
        this.defaultHours = defaultHours;
        this.rememberMeDays = rememberMeDays;
    }

    public Mono<AuthResponse> login(LoginRequest request, ServerWebExchange exchange) {
        String email = request.getEmail();
        String ip = extractIp(exchange);
        String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");
        String emailKey = bruteEmailKey(email);
        String ipKey = bruteIpKey(ip);

        // Mono.defer wraps findByEmail so the user lookup is skipped entirely when brute-force rejects the request.
        return checkBruteForce(emailKey, ipKey)
                .then(Mono.defer(() -> userRepository.findByEmail(email)
                        .switchIfEmpty(Mono.defer(() -> handleUserNotFound(request.getPassword())))))
                .flatMap(user -> authenticate(user, request, exchange, emailKey, ipKey, ip, userAgent));
    }

    public Mono<MeResponse> me() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(a -> a != null && a.isAuthenticated() && a.getPrincipal() instanceof AppUserDetails)
                .map(a -> ((AppUserDetails) a.getPrincipal()).user())
                .switchIfEmpty(Mono.error(AppException.unauthorized("Not authenticated")))
                .map(u -> new MeResponse(u.getId(), u.getEmail(), u.getName(), u.getStatus().name()));
    }

    private Mono<Void> checkBruteForce(String emailKey, String ipKey) {
        return Mono.zip(currentCount(emailKey), currentCount(ipKey))
                .flatMap(t -> {
                    if (t.getT1() >= EMAIL_THRESHOLD || t.getT2() >= IP_THRESHOLD) {
                        return Mono.<Void>error(AppException.tooManyRequests(
                                "Too many login attempts. Try again later."));
                    }
                    return Mono.<Void>empty();
                })
                // Decision 4: fail open if Redis is unreachable — brute-force is DoS mitigation, not auth gate.
                .onErrorResume(err -> err instanceof AppException
                        ? Mono.error(err)
                        : Mono.fromRunnable(() -> log.warn("Redis brute-force check failed, allowing: {}", err.getMessage())));
    }

    private Mono<Long> currentCount(String key) {
        return redisTemplate.opsForValue().get(key)
                .map(Long::parseLong)
                .defaultIfEmpty(0L);
    }

    private Mono<User> handleUserNotFound(String password) {
        return Mono.fromCallable(() -> passwordEncoder.matches(password, DUMMY_HASH))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.error(AppException.unauthorized("Invalid credentials")));
    }

    private Mono<AuthResponse> authenticate(User user, LoginRequest request, ServerWebExchange exchange,
                                            String emailKey, String ipKey, String ip, String userAgent) {
        return Mono.fromCallable(() -> passwordEncoder.matches(request.getPassword(), user.getPasswordHash()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(matches -> {
                    if (Boolean.FALSE.equals(matches)) {
                        return registerFailure(emailKey, ipKey)
                                .then(Mono.error(AppException.unauthorized("Invalid credentials")));
                    }
                    return checkStatusAndAuthorize(user, request, exchange, emailKey, ipKey, ip, userAgent);
                });
    }

    private Mono<Void> registerFailure(String emailKey, String ipKey) {
        return Mono.when(incrementWithTtl(emailKey), incrementWithTtl(ipKey))
                .onErrorResume(err -> {
                    log.warn("Redis brute-force increment failed: {}", err.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> incrementWithTtl(String key) {
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    // Only set TTL on the first increment so the window isn't reset on each failure.
                    if (count != null && count == 1L) {
                        return redisTemplate.expire(key, BRUTE_TTL).then();
                    }
                    return Mono.empty();
                });
    }

    private Mono<AuthResponse> checkStatusAndAuthorize(User user, LoginRequest request, ServerWebExchange exchange,
                                                       String emailKey, String ipKey, String ip, String userAgent) {
        UserStatus status = user.getStatus();
        if (status == UserStatus.blocked) {
            return Mono.error(AppException.forbidden(
                    "Your account has been blocked. Contact " + supportEmail));
        }
        if (status == UserStatus.deleted) {
            return Mono.error(AppException.unauthorized("Invalid credentials"));
        }
        String warning = status == UserStatus.pending ? "email_not_verified" : null;

        return openSession(user, request.isRememberMe(), exchange)
                .then(resetBruteCounters(emailKey, ipKey))
                .then(Mono.fromRunnable(() ->
                        eventService.logEvent(user.getId(), EVENT_LOGIN_SUCCESS, ip, userAgent, null)))
                .thenReturn(new AuthResponse(user.getId(), user.getEmail(), user.getName(),
                        user.getStatus().name(), warning));
    }

    private Mono<Void> openSession(User user, boolean rememberMe, ServerWebExchange exchange) {
        Duration ttl = rememberMe ? Duration.ofDays(rememberMeDays) : Duration.ofHours(defaultHours);
        AppUserDetails principal = new AppUserDetails(user);
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext context = new SecurityContextImpl(auth);

        return exchange.getSession()
                .flatMap(WebSession::invalidate)
                .then(exchange.getSession())
                .flatMap(newSession -> {
                    newSession.setMaxIdleTime(ttl);
                    return securityContextRepository.save(exchange, context);
                });
    }

    private Mono<Void> resetBruteCounters(String emailKey, String ipKey) {
        return redisTemplate.delete(emailKey, ipKey)
                .then()
                .onErrorResume(err -> {
                    log.warn("Redis brute-force reset failed: {}", err.getMessage());
                    return Mono.empty();
                });
    }

    private static String bruteEmailKey(String email) {
        return "brute:fail:" + email;
    }

    private static String bruteIpKey(String ip) {
        return "brute:fail:ip:" + ip;
    }

    private static String extractIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For may chain through multiple proxies; the leftmost entry is the original client.
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
    }
}
