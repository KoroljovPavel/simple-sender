package com.botfunnel.auth;

import com.botfunnel.auth.dto.AuthResponse;
import com.botfunnel.auth.dto.LoginRequest;
import com.botfunnel.auth.dto.MeResponse;
import com.botfunnel.auth.dto.RegisterRequest;
import com.botfunnel.auth.dto.RegisterResponse;
import com.botfunnel.auth.dto.VerifyEmailResponse;
import com.botfunnel.common.AppException;
import com.botfunnel.email.EmailService;
import com.botfunnel.events.EventService;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
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
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // Pre-computed bcrypt(cost=12) hash. Used only to consume ~250ms when a user is not found,
    // so non-existent-email response time matches wrong-password response time (Decision 13).
    static final String DUMMY_HASH = "$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj/nGYG/U.1S";

    private static final int EMAIL_THRESHOLD = 5;
    private static final int IP_THRESHOLD = 20;
    private static final Duration BRUTE_TTL = Duration.ofSeconds(900);
    private static final int USER_AGENT_MAX = 500;

    private static final Duration SOFT_DELETE_WINDOW = Duration.ofDays(30);
    private static final Duration EMAIL_VERIFICATION_TTL = Duration.ofHours(24);
    private static final Duration PASSWORD_RESET_TTL = Duration.ofHours(1);
    private static final Duration RESEND_RATE_TTL = Duration.ofSeconds(60);
    private static final Duration REGISTER_RATE_TTL = Duration.ofSeconds(60);
    private static final Duration FORGOT_RATE_TTL = Duration.ofSeconds(60);
    private static final int REGISTER_IP_THRESHOLD = 10;
    // Calibrated baseline for the forgot-password unknown/deleted branch. Without this, the
    // known-user path incurs the Mongo save round-trip (~10–50ms) while the unknown path returns
    // in ~0ms — a reliable enumeration oracle. The delay does not need to match exactly; it just
    // needs to be of the same order so timing differences fall below practical measurement noise.
    private static final Duration FORGOT_DUMMY_DELAY = Duration.ofMillis(40);

    private static final String EVENT_LOGIN_SUCCESS = "login_success";
    private static final String EVENT_LOGIN_FAILED = "login_failed";
    private static final String EVENT_EMAIL_VERIFIED = "email_verified";
    private static final String EVENT_PASSWORD_RESET_REQUESTED = "password_reset_requested";
    private static final String EVENT_PASSWORD_CHANGED = "password_changed";

    private static final String INVALID_RESET_LINK = "Посилання недійсне або прострочено";

    private final UserRepository userRepository;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ServerSecurityContextRepository securityContextRepository;
    private final EventService eventService;
    private final EmailService emailService;
    private final TokenService tokenService;
    private final ReactiveMongoTemplate reactiveMongoTemplate;
    private final String supportEmail;
    private final long defaultHours;
    private final long rememberMeDays;

    public AuthService(UserRepository userRepository,
                       ReactiveRedisTemplate<String, String> redisTemplate,
                       PasswordEncoder passwordEncoder,
                       ServerSecurityContextRepository securityContextRepository,
                       EventService eventService,
                       EmailService emailService,
                       TokenService tokenService,
                       ReactiveMongoTemplate reactiveMongoTemplate,
                       @Value("${app.support-email}") String supportEmail,
                       @Value("${app.session.ttl-default-hours:24}") long defaultHours,
                       @Value("${app.session.ttl-remember-me-days:30}") long rememberMeDays) {
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = passwordEncoder;
        this.securityContextRepository = securityContextRepository;
        this.eventService = eventService;
        this.emailService = emailService;
        this.tokenService = tokenService;
        this.reactiveMongoTemplate = reactiveMongoTemplate;
        this.supportEmail = supportEmail;
        this.defaultHours = defaultHours;
        this.rememberMeDays = rememberMeDays;
    }

    public Mono<AuthResponse> login(LoginRequest request, ServerWebExchange exchange) {
        // Email is canonicalized to lowercase so case variants share a single brute-force bucket
        // and a single user lookup. Without this, "User@x.com" and "user@x.com" would consume
        // independent attempt budgets.
        // Locale.ROOT prevents Turkish-locale dotless-i folding from re-opening the case-variant bypass.
        String email = canonicalize(request.getEmail());
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));
        String emailKey = bruteEmailKey(email);
        String ipKey = bruteIpKey(ip);

        // Mono.defer wraps findByEmail so the user lookup is skipped entirely when brute-force rejects the request.
        return checkBruteForce(emailKey, ipKey, email, ip, userAgent)
                .then(Mono.defer(() -> userRepository.findByEmail(email)
                        .switchIfEmpty(Mono.defer(() -> handleUserNotFound(
                                request.getPassword(), emailKey, ipKey, ip, userAgent)))))
                .flatMap(user -> authenticate(user, request, exchange, emailKey, ipKey, ip, userAgent));
    }

    public Mono<MeResponse> me() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(a -> a != null && a.isAuthenticated() && a.getPrincipal() instanceof AppUserDetails)
                .map(a -> (AppUserDetails) a.getPrincipal())
                .switchIfEmpty(Mono.error(AppException.unauthorized("Not authenticated")))
                .map(p -> new MeResponse(p.id(), p.email(), p.name(), p.status()));
    }

    public Mono<RegisterResponse> register(RegisterRequest request, ServerWebExchange exchange) {
        String email = canonicalize(request.getEmail());
        String ip = extractIp(exchange);
        // Per-IP register rate limit gates BCrypt CPU before it runs — without this an attacker
        // could spend all backend CPU on cost-12 hashes (security-auditor finding).
        // Mono.defer wraps resolveRegistrationSlot so the DB lookup is skipped entirely when the
        // rate-limit aborts the chain — otherwise the call would be evaluated eagerly.
        return checkRegisterRate(ip)
                .then(Mono.defer(() -> resolveRegistrationSlot(email)))
                .flatMap(slot -> applyRegistrationAsync(slot, request, email))
                .flatMap(prep -> userRepository.save(prep.user())
                        .map(saved -> {
                            // Fire-and-forget: EmailService never throws on its own, but if a synchronous
                            // failure ever bubbled up, it must not break a successful registration.
                            try {
                                emailService.sendVerificationEmail(saved.getEmail(), saved.getName(), prep.rawToken());
                            } catch (RuntimeException ex) {
                                // Log userId, NOT email — avoid PII in warn-level logs (matches the
                                // pattern locked down for forgotPassword in Task 6 / security-auditor #4).
                                log.warn("Verification email dispatch failed for userId={}: {}",
                                        saved.getId(), ex.getMessage());
                            }
                            return new RegisterResponse(saved.getId());
                        }));
    }

    public Mono<VerifyEmailResponse> verifyEmail(String rawToken, ServerWebExchange exchange) {
        if (rawToken == null || rawToken.isBlank()) {
            return Mono.error(AppException.badRequest("Посилання недійсне"));
        }
        String hash = tokenService.hashToken(rawToken);
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));

        return userRepository.findByEmailVerificationTokenHash(hash)
                .switchIfEmpty(Mono.error(AppException.badRequest("Посилання недійсне")))
                .flatMap(user -> {
                    // Status filter is applied here (not in the repository query) so that the same lookup
                    // can detect the deleted-user case and fall through to the same generic 400 response.
                    if (user.getStatus() != UserStatus.pending && user.getStatus() != UserStatus.active) {
                        return Mono.<VerifyEmailResponse>error(AppException.badRequest("Посилання недійсне"));
                    }
                    if (user.getEmailVerificationExpiresAt() == null
                            || !user.getEmailVerificationExpiresAt().isAfter(Instant.now())) {
                        return Mono.<VerifyEmailResponse>error(new AppException(
                                HttpStatus.BAD_REQUEST, "TOKEN_EXPIRED", "Посилання прострочено"));
                    }
                    user.setStatus(UserStatus.active);
                    user.setEmailVerificationTokenHash(null);
                    user.setEmailVerificationExpiresAt(null);
                    user.setUpdatedAt(Instant.now());
                    return userRepository.save(user)
                            .doOnSuccess(saved -> eventService.logEvent(
                                    saved.getId(), EVENT_EMAIL_VERIFIED, ip, userAgent, null))
                            .thenReturn(new VerifyEmailResponse("/login"));
                });
    }

    public Mono<Void> resendVerification(String emailRaw) {
        String email = canonicalize(emailRaw);
        // SET NX EX 60 is performed unconditionally — otherwise the request time would leak whether
        // the email exists (timing oracle). Decision 7 / Risks: anti-enumeration.
        // Decision 4: Redis fail-open for the rate-limit branch — a Redis outage must not let an
        // attacker bypass the rate limit OR block legitimate resends. Treating Redis-unavailable as
        // "no rate-limit hit, proceed" mirrors the login-path semantics.
        return redisTemplate.opsForValue()
                .setIfAbsent(resendKey(email), "1", RESEND_RATE_TTL)
                .onErrorResume(err -> {
                    log.warn("Redis resend rate-limit check failed, allowing: {}", err.getMessage());
                    return Mono.just(true);
                })
                .flatMap(success -> {
                    if (Boolean.FALSE.equals(success)) {
                        return Mono.<Void>error(AppException.tooManyRequests(
                                "Зачекайте 60 секунд перед повторною відправкою"));
                    }
                    return userRepository.findByEmail(email)
                            .flatMap(user -> {
                                // Only pending users can have a fresh verification token issued. Active /
                                // blocked / deleted accounts are silently ignored — the response is identical.
                                if (user.getStatus() != UserStatus.pending) {
                                    return Mono.<Void>empty();
                                }
                                String raw = tokenService.generateRawToken();
                                user.setEmailVerificationTokenHash(tokenService.hashToken(raw));
                                user.setEmailVerificationExpiresAt(Instant.now().plus(EMAIL_VERIFICATION_TTL));
                                user.setUpdatedAt(Instant.now());
                                return userRepository.save(user)
                                        .doOnSuccess(saved -> {
                                            try {
                                                emailService.sendVerificationEmail(
                                                        saved.getEmail(), saved.getName(), raw);
                                            } catch (RuntimeException ex) {
                                                // Log userId, NOT email — see register() for rationale.
                                                log.warn("Resend verification email dispatch failed for userId={}: {}",
                                                        saved.getId(), ex.getMessage());
                                            }
                                        })
                                        .then();
                            })
                            .then();
                });
    }

    public Mono<Void> logout(ServerWebExchange exchange) {
        // Only the current WebSession is invalidated. spring-session-data-mongodb removes the
        // matching `sessions` document on invalidate; other-device sessions for the same user
        // are NOT touched (per user-spec line 53). Use terminate-all-sessions for the global case.
        return exchange.getSession().flatMap(WebSession::invalidate);
    }

    public Mono<Void> forgotPassword(String emailRaw, ServerWebExchange exchange) {
        String email = canonicalize(emailRaw);
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));

        // Per-IP rate limit BEFORE the user lookup. Forgot-password is unauthenticated and
        // triggers an outbound email + a DB write — without throttling, it is a vector for
        // email-bombing a known victim, silent reset-token DoS by overwriting the pending token,
        // and SMTP-egress abuse. Mirrors the SET-NX-EX 60s pattern used by resend-verification
        // (Decision 7). Over-limit requests still return 200 so the response is identical to the
        // success path (anti-enumeration). Decision 4 fail-open: if Redis is unreachable we
        // proceed; brute-force DoS is preferable to blocking legitimate resets.
        return redisTemplate.opsForValue()
                .setIfAbsent(forgotKey(ip), "1", FORGOT_RATE_TTL)
                .onErrorResume(err -> {
                    log.warn("Redis forgot-password rate-limit check failed, allowing: {}", err.getMessage());
                    return Mono.just(true);
                })
                .flatMap(allowed -> Boolean.FALSE.equals(allowed)
                        ? Mono.empty()
                        : doForgotPassword(email, ip, userAgent));
    }

    private Mono<Void> doForgotPassword(String email, String ip, String userAgent) {
        // Anti-enumeration: response is identical for known, unknown, and deleted emails.
        // Audit logs use userId=null AND null metadata when the email does not match an active
        // account, so the events log itself cannot be used to enumerate (per task spec lines 41-42
        // and security-auditor finding #3 — never persist user-supplied email into events.metadata).
        // Wrapping in Optional lets a single flatMap distinguish missing-user from deleted-user
        // without the switchIfEmpty double-firing on a deliberately-empty downstream branch.
        return userRepository.findByEmail(email)
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(opt -> {
                    // pending users CAN reset (forgot password before verifying email is a normal flow).
                    // blocked + deleted users get the same no-op as unknown email — anti-enumeration.
                    if (opt.isEmpty()
                            || opt.get().getStatus() == UserStatus.deleted
                            || opt.get().getStatus() == UserStatus.blocked) {
                        // Calibrated delay equalises wall-clock with the known-user save path —
                        // closes the timing oracle (security-auditor finding #2 / CWE-208).
                        return Mono.<Void>delay(FORGOT_DUMMY_DELAY)
                                .doOnSuccess(v -> eventService.logEvent(
                                        null, EVENT_PASSWORD_RESET_REQUESTED, ip, userAgent, null))
                                .then();
                    }
                    User user = opt.get();
                    String raw = tokenService.generateRawToken();
                    user.setPasswordResetTokenHash(tokenService.hashToken(raw));
                    user.setPasswordResetExpiresAt(Instant.now().plus(PASSWORD_RESET_TTL));
                    user.setPasswordResetUsedAt(null);
                    user.setUpdatedAt(Instant.now());
                    return userRepository.save(user)
                            .doOnSuccess(saved -> {
                                try {
                                    emailService.sendPasswordResetEmail(saved.getEmail(), saved.getName(), raw);
                                } catch (RuntimeException ex) {
                                    // Log userId, NOT email — avoid PII in warn-level logs (security-auditor #4).
                                    log.warn("Reset email dispatch failed for userId={}: {}",
                                            saved.getId(), ex.getMessage());
                                }
                                eventService.logEvent(saved.getId(), EVENT_PASSWORD_RESET_REQUESTED,
                                        ip, userAgent, null);
                            })
                            .then();
                });
    }

    private static String forgotKey(String ip) {
        return "forgot:rate:ip:" + ip;
    }

    public Mono<Void> resetPassword(String rawToken, String newPassword, ServerWebExchange exchange) {
        if (rawToken == null || rawToken.isBlank()) {
            return Mono.error(AppException.badRequest(INVALID_RESET_LINK));
        }
        String hash = tokenService.hashToken(rawToken);
        String ip = extractIp(exchange);
        String userAgent = capUserAgent(exchange.getRequest().getHeaders().getFirst("User-Agent"));

        return userRepository.findByPasswordResetTokenHash(hash)
                .switchIfEmpty(Mono.error(AppException.badRequest(INVALID_RESET_LINK)))
                .flatMap(user -> {
                    // Status gate (code-reviewer major finding): a token issued just before an
                    // admin blocked or a user deleted the account must NOT rotate the password.
                    // Login already gates these statuses; reset must mirror that to keep the same
                    // account-lifecycle invariant. Same generic message — no enumeration oracle.
                    if (user.getStatus() != UserStatus.pending
                            && user.getStatus() != UserStatus.active) {
                        return Mono.<Void>error(AppException.badRequest(INVALID_RESET_LINK));
                    }
                    // Expiry check next — gives the most relevant error message before the
                    // already-used check (per Details/edge-cases note in the task).
                    if (user.getPasswordResetExpiresAt() == null
                            || !user.getPasswordResetExpiresAt().isAfter(Instant.now())) {
                        return Mono.<Void>error(AppException.badRequest(INVALID_RESET_LINK));
                    }
                    if (user.getPasswordResetUsedAt() != null) {
                        return Mono.<Void>error(AppException.badRequest(INVALID_RESET_LINK));
                    }
                    return rotatePasswordAndTerminateSessions(user, newPassword, ip, userAgent);
                });
    }

    private Mono<Void> rotatePasswordAndTerminateSessions(User user, String newPassword,
                                                          String ip, String userAgent) {
        // BCrypt cost-12 takes ~250ms; pin to boundedElastic so the Reactor event loop is not blocked
        // (mirrors the login + register patterns).
        return Mono.fromCallable(() -> passwordEncoder.encode(newPassword))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(newHash -> {
                    Instant now = Instant.now();
                    user.setPasswordHash(newHash);
                    // Both clear the hash AND mark usedAt — defense in depth: even if a stored
                    // copy of the token is somehow re-attached to the document, the lookup-by-hash
                    // returns nothing because the hash field is cleared.
                    user.setPasswordResetTokenHash(null);
                    user.setPasswordResetExpiresAt(null);
                    user.setPasswordResetUsedAt(now);
                    user.setUpdatedAt(now);
                    return userRepository.save(user);
                })
                .flatMap(saved -> terminateAllSessions(saved.getId())
                        .doOnSuccess(deleted -> eventService.logEvent(
                                saved.getId(), EVENT_PASSWORD_CHANGED, ip, userAgent, null)))
                .then();
    }

    // Sessions collection field path verified at runtime by the IT
    // `sessionsCollection_principalFieldPath_isAtTopLevel` (Task 6 risk area). spring-session-data-mongodb
    // 3.x indexes the principal name at the top-level `principal` field; AppUserDetails.getUsername()
    // returns the user id, which is what Spring Session writes there.
    private Mono<Long> terminateAllSessions(String userId) {
        Query q = Query.query(Criteria.where("principal").is(userId));
        return reactiveMongoTemplate.remove(q, "sessions")
                .map(result -> result.getDeletedCount());
    }

    private Mono<RegistrationSlot> resolveRegistrationSlot(String email) {
        return userRepository.findByEmail(email)
                .flatMap(existing -> {
                    if (existing.getStatus() != UserStatus.deleted) {
                        return Mono.<RegistrationSlot>error(AppException.conflict(
                                "Користувач з таким email вже існує"));
                    }
                    Instant deletedAt = existing.getDeletedAt();
                    if (deletedAt != null && deletedAt.isAfter(Instant.now().minus(SOFT_DELETE_WINDOW))) {
                        return Mono.<RegistrationSlot>error(AppException.conflict(
                                "Акаунт з таким email вже існує або був нещодавно видалений. "
                                        + "Зверніться до підтримки: " + supportEmail));
                    }
                    // Soft-deleted longer than 30 days — repurpose the existing document so the
                    // unique-email index is not violated.
                    return Mono.just(new RegistrationSlot(existing, true));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> new RegistrationSlot(new User(), false)));
    }

    private Mono<RegistrationPrep> applyRegistrationAsync(RegistrationSlot slot, RegisterRequest req, String email) {
        // BCrypt cost-12 takes ~250ms on modern hardware. Running it on the event loop would
        // monopolise a Reactor thread and starve all other concurrent requests. Pin to
        // boundedElastic, mirroring the login-path pattern.
        return Mono.fromCallable(() -> passwordEncoder.encode(req.getPassword()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(hash -> {
                    User user = slot.user();
                    Instant now = Instant.now();
                    String rawToken = tokenService.generateRawToken();

                    user.setEmail(email);
                    // Name is no longer collected at registration. Left null on the new
                    // document; users can set it later via /profile if/when they want one.
                    user.setName(null);
                    user.setPasswordHash(hash);
                    user.setStatus(UserStatus.pending);
                    user.setEmailVerificationTokenHash(tokenService.hashToken(rawToken));
                    user.setEmailVerificationExpiresAt(now.plus(EMAIL_VERIFICATION_TTL));
                    user.setUpdatedAt(now);
                    if (!slot.repurposed()) {
                        user.setCreatedAt(now);
                    }
                    // Reset fields that may carry over from a soft-deleted document.
                    user.setDeletedAt(null);
                    user.setPasswordResetTokenHash(null);
                    user.setPasswordResetExpiresAt(null);
                    user.setPasswordResetUsedAt(null);
                    // CRITICAL: a soft-deleted superadmin must not pass admin rights to whoever
                    // re-registers the email after the 30-day window. Reset on every register path
                    // — only SuperAdminSeeder can grant superadmin (Task 7).
                    user.setSuperAdmin(false);
                    return new RegistrationPrep(user, rawToken);
                });
    }

    private Mono<Void> checkRegisterRate(String ip) {
        String key = registerIpKey(ip);
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    if (count != null && count == 1L) {
                        // Only set TTL on first hit — same window-stability semantic as login brute-force.
                        return redisTemplate.expire(key, REGISTER_RATE_TTL).then(Mono.just(count));
                    }
                    return Mono.just(count);
                })
                .flatMap(count -> count > REGISTER_IP_THRESHOLD
                        ? Mono.<Void>error(AppException.tooManyRequests(
                                "Забагато спроб реєстрації з цієї IP. Спробуйте за хвилину."))
                        : Mono.<Void>empty())
                // Decision 4 fail-open: Redis outage allows registration through (Bean Validation
                // and the unique-email index still gate abuse).
                .onErrorResume(err -> err instanceof AppException
                        ? Mono.error(err)
                        : Mono.fromRunnable(() ->
                                log.warn("Redis register rate-limit check failed, allowing: {}", err.getMessage())));
    }

    private static String registerIpKey(String ip) {
        return "register:rate:ip:" + ip;
    }

    private static String canonicalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String resendKey(String email) {
        return "resend:rate:" + email;
    }

    private record RegistrationSlot(User user, boolean repurposed) {}

    private record RegistrationPrep(User user, String rawToken) {}

    private Mono<Void> checkBruteForce(String emailKey, String ipKey, String email, String ip, String userAgent) {
        return Mono.zip(currentCount(emailKey), currentCount(ipKey))
                .flatMap(t -> {
                    if (t.getT1() >= EMAIL_THRESHOLD || t.getT2() >= IP_THRESHOLD) {
                        // Audit the threshold-trip — this is the highest-signal attack indicator.
                        return Mono.<Void>fromRunnable(() -> eventService.logEvent(
                                        null, EVENT_LOGIN_FAILED, ip, userAgent,
                                        Map.of("reason", "brute_force", "email", email)))
                                .then(Mono.<Void>error(AppException.tooManyRequests(
                                        "Too many login attempts. Try again later.")));
                    }
                    return Mono.<Void>empty();
                })
                // Decision 4: fail open if Redis is unreachable — brute-force is DoS mitigation, not auth gate.
                .onErrorResume(err -> err instanceof AppException
                        ? Mono.error(err)
                        : Mono.fromRunnable(() ->
                                log.warn("Redis brute-force check failed, allowing: {}", err.getMessage())));
    }

    private Mono<Long> currentCount(String key) {
        return redisTemplate.opsForValue().get(key)
                .map(Long::parseLong)
                .defaultIfEmpty(0L);
    }

    private Mono<User> handleUserNotFound(String password, String emailKey, String ipKey,
                                          String ip, String userAgent) {
        // (1) consume ~250ms in bcrypt to match wrong-password timing,
        // (2) increment brute-force counters so the response code (429 vs 401) does not become an
        //     "email exists" oracle once the threshold is reached, and
        // (3) log a login_failed event with reason=user_not_found for audit.
        return Mono.fromCallable(() -> passwordEncoder.matches(password, DUMMY_HASH))
                .subscribeOn(Schedulers.boundedElastic())
                .then(registerFailure(emailKey, ipKey))
                .then(Mono.fromRunnable(() -> eventService.logEvent(
                        null, EVENT_LOGIN_FAILED, ip, userAgent, Map.of("reason", "user_not_found"))))
                .then(Mono.error(AppException.unauthorized("Invalid credentials")));
    }

    private Mono<AuthResponse> authenticate(User user, LoginRequest request, ServerWebExchange exchange,
                                            String emailKey, String ipKey, String ip, String userAgent) {
        return Mono.fromCallable(() -> passwordEncoder.matches(request.getPassword(), user.getPasswordHash()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(matches -> {
                    if (Boolean.FALSE.equals(matches)) {
                        return registerFailure(emailKey, ipKey)
                                .then(Mono.fromRunnable(() -> eventService.logEvent(
                                        user.getId(), EVENT_LOGIN_FAILED, ip, userAgent,
                                        Map.of("reason", "wrong_password"))))
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
                    // Crash between INCR and EXPIRE is a known small race (no Lua); on next failure
                    // the counter just keeps growing without TTL — Redis MEMORY-policy still bounds it.
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
            return Mono.fromRunnable(() -> eventService.logEvent(
                            user.getId(), EVENT_LOGIN_FAILED, ip, userAgent, Map.of("reason", "blocked")))
                    .then(Mono.error(AppException.forbidden(
                            "Your account has been blocked. Contact " + supportEmail)));
        }
        if (status == UserStatus.deleted) {
            return Mono.fromRunnable(() -> eventService.logEvent(
                            user.getId(), EVENT_LOGIN_FAILED, ip, userAgent, Map.of("reason", "deleted")))
                    .then(Mono.error(AppException.unauthorized("Invalid credentials")));
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
        AppUserDetails principal = new AppUserDetails(
                user.getId(), user.getEmail(), user.getName(), user.getStatus().name());
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext context = new SecurityContextImpl(auth);

        // ServerWebExchange.getSession() is cached for the lifetime of the exchange (Mono.cache
        // inside DefaultServerWebExchange), so calling invalidate() and then getSession() again
        // returns the SAME zombie session — attributes set on it are never persisted and the
        // SESSION cookie is never written. Anonymous → authenticated transition starts with no
        // session anyway, so we just set TTL and save the SecurityContext on the live session.
        return exchange.getSession()
                .doOnNext(session -> session.setMaxIdleTime(ttl))
                .then(securityContextRepository.save(exchange, context));
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

    private static String capUserAgent(String userAgent) {
        if (userAgent == null) return null;
        return userAgent.length() > USER_AGENT_MAX ? userAgent.substring(0, USER_AGENT_MAX) : userAgent;
    }

    // Trust note: X-Forwarded-For is honoured unconditionally. Production deployments must front
    // the backend with a reverse proxy (nginx/traefik) that overwrites this header — without one,
    // clients can forge it and bypass the per-IP brute-force counter (per task Security note).
    private static String extractIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For chains through proxies; the leftmost entry is the original client.
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
    }
}
