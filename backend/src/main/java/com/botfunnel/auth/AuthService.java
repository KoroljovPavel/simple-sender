package com.botfunnel.auth;

import com.botfunnel.auth.dto.AuthResponse;
import com.botfunnel.auth.dto.LoginRequest;
import com.botfunnel.auth.dto.MeResponse;
import com.botfunnel.auth.dto.RegisterRequest;
import com.botfunnel.auth.dto.RegisterResponse;
import com.botfunnel.auth.dto.VerifyEmailResponse;
import com.botfunnel.common.AppException;
import com.botfunnel.common.HttpRequestUtils;
import com.botfunnel.common.SessionAttributes;
import com.botfunnel.email.EmailService;
import com.botfunnel.events.EventService;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // Pre-computed bcrypt(cost=12) hash. Used only to consume ~250ms when a user is not found,
    // so non-existent-email response time matches wrong-password response time (Decision 13).
    static final String DUMMY_HASH = "$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj/nGYG/U.1S";

    private static final int EMAIL_THRESHOLD = 5;
    private static final int IP_THRESHOLD = 20;
    private static final Duration BRUTE_TTL = Duration.ofSeconds(900);

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
    private final RedisTemplate<String, String> redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository;
    private final EventService eventService;
    private final EmailService emailService;
    private final TokenService tokenService;
    private final MongoTemplate mongoTemplate;
    private final String supportEmail;
    private final long defaultHours;
    private final long rememberMeDays;

    public AuthService(UserRepository userRepository,
                       RedisTemplate<String, String> redisTemplate,
                       PasswordEncoder passwordEncoder,
                       SecurityContextRepository securityContextRepository,
                       EventService eventService,
                       EmailService emailService,
                       TokenService tokenService,
                       MongoTemplate mongoTemplate,
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
        this.mongoTemplate = mongoTemplate;
        this.supportEmail = supportEmail;
        this.defaultHours = defaultHours;
        this.rememberMeDays = rememberMeDays;
    }

    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // Email is canonicalized to lowercase so case variants share a single brute-force bucket
        // and a single user lookup. Without this, "User@x.com" and "user@x.com" would consume
        // independent attempt budgets.
        // Locale.ROOT prevents Turkish-locale dotless-i folding from re-opening the case-variant bypass.
        String email = canonicalize(request.getEmail());
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);
        String emailKey = bruteEmailKey(email);
        String ipKey = bruteIpKey(ip);

        checkBruteForce(emailKey, ipKey, email, ip, userAgent);
        User user = userRepository.findByEmail(email)
                .orElseGet(() -> handleUserNotFound(request.getPassword(), emailKey, ipKey, ip, userAgent));
        return authenticate(user, request, httpRequest, httpResponse, emailKey, ipKey, ip, userAgent);
    }

    public MeResponse me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken
                || !(auth.getPrincipal() instanceof AppUserDetails principal)) {
            throw AppException.unauthorized("Not authenticated");
        }
        return new MeResponse(principal.id(), principal.email(), principal.name(), principal.status());
    }

    public RegisterResponse register(RegisterRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String email = canonicalize(request.getEmail());
        String ip = HttpRequestUtils.extractIp(httpRequest);
        // Per-IP register rate limit gates BCrypt CPU before it runs — without this an attacker
        // could spend all backend CPU on cost-12 hashes (security-auditor finding).
        checkRegisterRate(ip);

        RegistrationSlot slot = resolveRegistrationSlot(email);
        RegistrationPrep prep = applyRegistration(slot, request, email);
        User saved = userRepository.save(prep.user());

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
        // Auto-login after register: open a session right away so the SPA can land
        // on /dashboard without a second round-trip through /auth/login. The user
        // is in `pending` status — login flow allows pending too (status gate is in
        // login, not openSession). rememberMe defaults to false; user can toggle it
        // on a later real login.
        openSession(saved, false, httpRequest, httpResponse);
        return new RegisterResponse(saved.getId());
    }

    public VerifyEmailResponse verifyEmail(String rawToken, HttpServletRequest httpRequest) {
        if (rawToken == null || rawToken.isBlank()) {
            throw AppException.badRequest("Посилання недійсне");
        }
        String hash = tokenService.hashToken(rawToken);
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);

        User user = userRepository.findByEmailVerificationTokenHash(hash)
                .orElseThrow(() -> AppException.badRequest("Посилання недійсне"));
        // Status filter is applied here (not in the repository query) so that the same lookup
        // can detect the deleted-user case and fall through to the same generic 400 response.
        if (user.getStatus() != UserStatus.pending && user.getStatus() != UserStatus.active) {
            throw AppException.badRequest("Посилання недійсне");
        }
        if (user.getEmailVerificationExpiresAt() == null
                || !user.getEmailVerificationExpiresAt().isAfter(Instant.now())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "TOKEN_EXPIRED", "Посилання прострочено");
        }
        user.setStatus(UserStatus.active);
        user.setEmailVerificationTokenHash(null);
        user.setEmailVerificationExpiresAt(null);
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);
        eventService.logEvent(saved.getId(), EVENT_EMAIL_VERIFIED, ip, userAgent, null);
        return new VerifyEmailResponse("/login");
    }

    public void resendVerification(String emailRaw) {
        String email = canonicalize(emailRaw);
        // SET NX EX 60 is performed unconditionally — otherwise the request time would leak whether
        // the email exists (timing oracle). Decision 7 / Risks: anti-enumeration.
        // Decision 4: Redis fail-open for the rate-limit branch — a Redis outage must not let an
        // attacker bypass the rate limit OR block legitimate resends. Treating Redis-unavailable as
        // "no rate-limit hit, proceed" mirrors the login-path semantics.
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue()
                    .setIfAbsent(resendKey(email), "1", RESEND_RATE_TTL);
        } catch (Exception err) {
            log.warn("Redis resend rate-limit check failed, allowing: {}", err.getMessage());
            acquired = Boolean.TRUE;
        }
        if (Boolean.FALSE.equals(acquired)) {
            throw AppException.tooManyRequests("Зачекайте 60 секунд перед повторною відправкою");
        }
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return;
        }
        User user = userOpt.get();
        // Only pending users can have a fresh verification token issued. Active /
        // blocked / deleted accounts are silently ignored — the response is identical.
        if (user.getStatus() != UserStatus.pending) {
            return;
        }
        String raw = tokenService.generateRawToken();
        user.setEmailVerificationTokenHash(tokenService.hashToken(raw));
        user.setEmailVerificationExpiresAt(Instant.now().plus(EMAIL_VERIFICATION_TTL));
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);
        try {
            emailService.sendVerificationEmail(saved.getEmail(), saved.getName(), raw);
        } catch (RuntimeException ex) {
            // Log userId, NOT email — see register() for rationale.
            log.warn("Resend verification email dispatch failed for userId={}: {}",
                    saved.getId(), ex.getMessage());
        }
    }

    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // Only the current HTTP session is invalidated. spring-session-data-mongodb removes the
        // matching `sessions` document on invalidate; other-device sessions for the same user
        // are NOT touched (per user-spec line 53). Use terminate-all-sessions for the global case.
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        // Defensive: clear the SecurityContext on the current thread so a follow-up request served
        // by the same VT cannot see stale auth (VTs are per-request, but the explicit clear costs
        // nothing and matches Spring Security guidance).
        SecurityContextHolder.clearContext();
    }

    public void forgotPassword(String emailRaw, HttpServletRequest httpRequest) {
        String email = canonicalize(emailRaw);
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);

        // Per-IP rate limit BEFORE the user lookup. Forgot-password is unauthenticated and
        // triggers an outbound email + a DB write — without throttling, it is a vector for
        // email-bombing a known victim, silent reset-token DoS by overwriting the pending token,
        // and SMTP-egress abuse. Mirrors the SET-NX-EX 60s pattern used by resend-verification
        // (Decision 7). Over-limit requests still return 200 so the response is identical to the
        // success path (anti-enumeration). Decision 4 fail-open: if Redis is unreachable we
        // proceed; brute-force DoS is preferable to blocking legitimate resets.
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue()
                    .setIfAbsent(forgotKey(ip), "1", FORGOT_RATE_TTL);
        } catch (Exception err) {
            log.warn("Redis forgot-password rate-limit check failed, allowing: {}", err.getMessage());
            acquired = Boolean.TRUE;
        }
        if (Boolean.FALSE.equals(acquired)) {
            return;
        }
        doForgotPassword(email, ip, userAgent);
    }

    private void doForgotPassword(String email, String ip, String userAgent) {
        // Anti-enumeration: response is identical for known, unknown, and deleted emails.
        // Audit logs use userId=null AND null metadata when the email does not match an active
        // account, so the events log itself cannot be used to enumerate (per task spec lines 41-42
        // and security-auditor finding #3 — never persist user-supplied email into events.metadata).
        Optional<User> opt = userRepository.findByEmail(email);
        // pending users CAN reset (forgot password before verifying email is a normal flow).
        // blocked + deleted users get the same no-op as unknown email — anti-enumeration.
        if (opt.isEmpty()
                || opt.get().getStatus() == UserStatus.deleted
                || opt.get().getStatus() == UserStatus.blocked) {
            // Calibrated delay equalises wall-clock with the known-user save path —
            // closes the timing oracle (security-auditor finding #2 / CWE-208).
            try {
                Thread.sleep(FORGOT_DUMMY_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            eventService.logEvent(null, EVENT_PASSWORD_RESET_REQUESTED, ip, userAgent, null);
            return;
        }
        User user = opt.get();
        String raw = tokenService.generateRawToken();
        user.setPasswordResetTokenHash(tokenService.hashToken(raw));
        user.setPasswordResetExpiresAt(Instant.now().plus(PASSWORD_RESET_TTL));
        user.setPasswordResetUsedAt(null);
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);
        try {
            emailService.sendPasswordResetEmail(saved.getEmail(), saved.getName(), raw);
        } catch (RuntimeException ex) {
            // Log userId, NOT email — avoid PII in warn-level logs (security-auditor #4).
            log.warn("Reset email dispatch failed for userId={}: {}",
                    saved.getId(), ex.getMessage());
        }
        eventService.logEvent(saved.getId(), EVENT_PASSWORD_RESET_REQUESTED, ip, userAgent, null);
    }

    private static String forgotKey(String ip) {
        return "forgot:rate:ip:" + ip;
    }

    public void resetPassword(String rawToken, String newPassword, HttpServletRequest httpRequest) {
        if (rawToken == null || rawToken.isBlank()) {
            throw AppException.badRequest(INVALID_RESET_LINK);
        }
        String hash = tokenService.hashToken(rawToken);
        String ip = HttpRequestUtils.extractIp(httpRequest);
        String userAgent = HttpRequestUtils.extractUserAgent(httpRequest);

        User user = userRepository.findByPasswordResetTokenHash(hash)
                .orElseThrow(() -> AppException.badRequest(INVALID_RESET_LINK));
        // Status gate (code-reviewer major finding): a token issued just before an
        // admin blocked or a user deleted the account must NOT rotate the password.
        // Login already gates these statuses; reset must mirror that to keep the same
        // account-lifecycle invariant. Same generic message — no enumeration oracle.
        if (user.getStatus() != UserStatus.pending && user.getStatus() != UserStatus.active) {
            throw AppException.badRequest(INVALID_RESET_LINK);
        }
        // Expiry check next — gives the most relevant error message before the
        // already-used check (per Details/edge-cases note in the task).
        if (user.getPasswordResetExpiresAt() == null
                || !user.getPasswordResetExpiresAt().isAfter(Instant.now())) {
            throw AppException.badRequest(INVALID_RESET_LINK);
        }
        if (user.getPasswordResetUsedAt() != null) {
            throw AppException.badRequest(INVALID_RESET_LINK);
        }
        rotatePasswordAndTerminateSessions(user, newPassword, ip, userAgent);
    }

    private void rotatePasswordAndTerminateSessions(User user, String newPassword,
                                                    String ip, String userAgent) {
        // BCrypt cost-12 takes ~250ms. Under virtual threads this blocks only the current VT —
        // the carrier thread is released to schedule other VTs (D4 — implicit concurrency cap
        // accepted).
        String newHash = passwordEncoder.encode(newPassword);
        Instant now = Instant.now();
        user.setPasswordHash(newHash);
        // Both clear the hash AND mark usedAt — defense in depth: even if a stored
        // copy of the token is somehow re-attached to the document, the lookup-by-hash
        // returns nothing because the hash field is cleared.
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetExpiresAt(null);
        user.setPasswordResetUsedAt(now);
        user.setUpdatedAt(now);
        User saved = userRepository.save(user);
        terminateAllSessions(saved.getId());
        eventService.logEvent(saved.getId(), EVENT_PASSWORD_CHANGED, ip, userAgent, null);
    }

    // Sessions collection field path verified at runtime by the IT
    // `sessionsCollection_principalFieldPath_isAtTopLevel` (Task 6 risk area). spring-session-data-mongodb
    // 3.x indexes the principal name at the top-level `principal` field; AppUserDetails.getUsername()
    // returns the user id, which is what Spring Session writes there.
    private long terminateAllSessions(String userId) {
        Query q = Query.query(Criteria.where("principal").is(userId));
        return mongoTemplate.remove(q, "sessions").getDeletedCount();
    }

    private RegistrationSlot resolveRegistrationSlot(String email) {
        Optional<User> existingOpt = userRepository.findByEmail(email);
        if (existingOpt.isEmpty()) {
            return new RegistrationSlot(new User(), false);
        }
        User existing = existingOpt.get();
        if (existing.getStatus() != UserStatus.deleted) {
            throw AppException.conflict("Користувач з таким email вже існує");
        }
        Instant deletedAt = existing.getDeletedAt();
        if (deletedAt != null && deletedAt.isAfter(Instant.now().minus(SOFT_DELETE_WINDOW))) {
            throw AppException.conflict(
                    "Акаунт з таким email вже існує або був нещодавно видалений. "
                            + "Зверніться до підтримки: " + supportEmail);
        }
        // Soft-deleted longer than 30 days — repurpose the existing document so the
        // unique-email index is not violated.
        return new RegistrationSlot(existing, true);
    }

    private RegistrationPrep applyRegistration(RegistrationSlot slot, RegisterRequest req, String email) {
        // BCrypt cost-12 takes ~250ms. Under VT the cost is paid only on the current VT; carrier
        // is released to schedule others (D4).
        String hash = passwordEncoder.encode(req.getPassword());
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
    }

    private void checkRegisterRate(String ip) {
        String key = registerIpKey(ip);
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // Only set TTL on first hit — same window-stability semantic as login brute-force.
                redisTemplate.expire(key, REGISTER_RATE_TTL);
            }
        } catch (Exception err) {
            // Decision 4 fail-open: Redis outage allows registration through (Bean Validation
            // and the unique-email index still gate abuse).
            log.warn("Redis register rate-limit check failed, allowing: {}", err.getMessage());
            return;
        }
        if (count != null && count > REGISTER_IP_THRESHOLD) {
            throw AppException.tooManyRequests(
                    "Забагато спроб реєстрації з цієї IP. Спробуйте за хвилину.");
        }
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

    private void checkBruteForce(String emailKey, String ipKey, String email, String ip, String userAgent) {
        long emailCount;
        long ipCount;
        try {
            emailCount = currentCount(emailKey);
            ipCount = currentCount(ipKey);
        } catch (Exception err) {
            // Decision 4: fail open if Redis is unreachable — brute-force is DoS mitigation, not auth gate.
            log.warn("Redis brute-force check failed, allowing: {}", err.getMessage());
            return;
        }
        if (emailCount >= EMAIL_THRESHOLD || ipCount >= IP_THRESHOLD) {
            // Audit the threshold-trip — this is the highest-signal attack indicator.
            eventService.logEvent(null, EVENT_LOGIN_FAILED, ip, userAgent,
                    Map.of("reason", "brute_force", "email", email));
            throw AppException.tooManyRequests("Too many login attempts. Try again later.");
        }
    }

    private long currentCount(String key) {
        String raw = redisTemplate.opsForValue().get(key);
        return raw == null ? 0L : Long.parseLong(raw);
    }

    private User handleUserNotFound(String password, String emailKey, String ipKey,
                                    String ip, String userAgent) {
        // (1) consume ~250ms in bcrypt to match wrong-password timing,
        // (2) increment brute-force counters so the response code (429 vs 401) does not become an
        //     "email exists" oracle once the threshold is reached, and
        // (3) log a login_failed event with reason=user_not_found for audit.
        passwordEncoder.matches(password, DUMMY_HASH);
        registerFailure(emailKey, ipKey);
        eventService.logEvent(null, EVENT_LOGIN_FAILED, ip, userAgent,
                Map.of("reason", "user_not_found"));
        throw AppException.unauthorized("Invalid credentials");
    }

    private AuthResponse authenticate(User user, LoginRequest request, HttpServletRequest httpRequest,
                                      HttpServletResponse httpResponse, String emailKey, String ipKey,
                                      String ip, String userAgent) {
        boolean matches = passwordEncoder.matches(request.getPassword(), user.getPasswordHash());
        if (!matches) {
            registerFailure(emailKey, ipKey);
            eventService.logEvent(user.getId(), EVENT_LOGIN_FAILED, ip, userAgent,
                    Map.of("reason", "wrong_password"));
            throw AppException.unauthorized("Invalid credentials");
        }
        return checkStatusAndAuthorize(user, request, httpRequest, httpResponse, emailKey, ipKey, ip, userAgent);
    }

    private void registerFailure(String emailKey, String ipKey) {
        try {
            incrementWithTtl(emailKey);
            incrementWithTtl(ipKey);
        } catch (Exception err) {
            log.warn("Redis brute-force increment failed: {}", err.getMessage());
        }
    }

    private void incrementWithTtl(String key) {
        Long count = redisTemplate.opsForValue().increment(key);
        // Only set TTL on the first increment so the window isn't reset on each failure.
        // Crash between INCR and EXPIRE is a known small race (no Lua); on next failure
        // the counter just keeps growing without TTL — Redis MEMORY-policy still bounds it.
        if (count != null && count == 1L) {
            redisTemplate.expire(key, BRUTE_TTL);
        }
    }

    private AuthResponse checkStatusAndAuthorize(User user, LoginRequest request, HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse, String emailKey, String ipKey,
                                                 String ip, String userAgent) {
        UserStatus status = user.getStatus();
        if (status == UserStatus.blocked) {
            eventService.logEvent(user.getId(), EVENT_LOGIN_FAILED, ip, userAgent,
                    Map.of("reason", "blocked"));
            throw AppException.forbidden("Your account has been blocked. Contact " + supportEmail);
        }
        if (status == UserStatus.deleted) {
            eventService.logEvent(user.getId(), EVENT_LOGIN_FAILED, ip, userAgent,
                    Map.of("reason", "deleted"));
            throw AppException.unauthorized("Invalid credentials");
        }
        String warning = status == UserStatus.pending ? "email_not_verified" : null;

        openSession(user, request.isRememberMe(), httpRequest, httpResponse);
        resetBruteCounters(emailKey, ipKey);
        eventService.logEvent(user.getId(), EVENT_LOGIN_SUCCESS, ip, userAgent, null);
        return new AuthResponse(user.getId(), user.getEmail(), user.getName(),
                user.getStatus().name(), warning);
    }

    private void openSession(User user, boolean rememberMe, HttpServletRequest httpRequest,
                             HttpServletResponse httpResponse) {
        Duration ttl = rememberMe ? Duration.ofDays(rememberMeDays) : Duration.ofHours(defaultHours);
        AppUserDetails principal = new AppUserDetails(
                user.getId(), user.getEmail(), user.getName(), user.getStatus().name());
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext context = new SecurityContextImpl(auth);

        // Publish rememberMe to the request attribute BEFORE the session pipeline so
        // RememberMeCookieSerializer sees it on cookie flush regardless of when the
        // session is materialised. The attribute is request-scoped (servlet API) and the
        // serializer reads it inside writeCookieValue.
        httpRequest.setAttribute(SessionAttributes.REMEMBER_ME_ATTR, Boolean.valueOf(rememberMe));

        // Session-fixation defense: mint a fresh JSESSIONID on the authenticated session so a
        // token captured on the anonymous session cannot be reused. changeSessionId() requires an
        // existing session, so materialise one first if anonymous (mirrors the prior reactive
        // invalidate-and-recreate sequence, which was a no-op only because the cached
        // session-fetch produced the same zombie session).
        HttpSession session = httpRequest.getSession(true);
        httpRequest.changeSessionId();
        session.setMaxInactiveInterval((int) ttl.getSeconds());
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
    }

    private void resetBruteCounters(String emailKey, String ipKey) {
        try {
            redisTemplate.delete(java.util.List.of(emailKey, ipKey));
        } catch (Exception err) {
            log.warn("Redis brute-force reset failed: {}", err.getMessage());
        }
    }

    private static String bruteEmailKey(String email) {
        return "brute:fail:" + email;
    }

    private static String bruteIpKey(String ip) {
        return "brute:fail:ip:" + ip;
    }
}
