package com.botfunnel.profile;

import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.profile.dto.ProfileResponse;
import com.botfunnel.profile.dto.UpdateProfileRequest;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private static final String EVENT_PASSWORD_CHANGED = "password_changed";
    private static final String EVENT_ACCOUNT_DELETED = "account_deleted";

    // Brute-force gate on change-password. A hijacked session has authenticated access but does
    // not know the current password — without throttling, an attacker could grind BCrypt cost-12
    // verifications offline-style at ~4 attempts/sec per CPU. Mirrors AuthService.login keys but
    // is keyed on userId (not email) because the user is already authenticated.
    private static final int CHANGE_PWD_THRESHOLD = 5;
    private static final Duration CHANGE_PWD_TTL = Duration.ofSeconds(900);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ReactiveMongoTemplate reactiveMongoTemplate;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final EventService eventService;

    public ProfileService(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          ReactiveMongoTemplate reactiveMongoTemplate,
                          ReactiveRedisTemplate<String, String> redisTemplate,
                          EventService eventService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.reactiveMongoTemplate = reactiveMongoTemplate;
        this.redisTemplate = redisTemplate;
        this.eventService = eventService;
    }

    public Mono<ProfileResponse> getProfile(String userId) {
        return loadActiveUser(userId).map(ProfileService::toResponse);
    }

    public Mono<ProfileResponse> updateProfile(String userId, UpdateProfileRequest req) {
        return loadActiveUser(userId)
                .flatMap(user -> {
                    user.setName(req.getName());
                    user.setUpdatedAt(Instant.now());
                    return userRepository.save(user);
                })
                .map(ProfileService::toResponse);
    }

    public Mono<Void> changePassword(String userId, String currentPassword, String newPassword,
                                     WebSession currentSession, String ip, String userAgent) {
        // Mono.defer wraps loadActiveUser so the user lookup is skipped entirely when the rate
        // limiter aborts the chain — without defer, the lookup Mono is constructed eagerly.
        return checkChangePwdRate(userId)
                .then(Mono.defer(() -> loadActiveUser(userId)))
                .flatMap(user -> verifyAndRotate(user, currentPassword, newPassword, currentSession, ip, userAgent));
    }

    private Mono<Void> verifyAndRotate(User user, String currentPassword, String newPassword,
                                       WebSession currentSession, String ip, String userAgent) {
        // BCrypt verify and encode are CPU-bound (~250ms each at cost 12); pin to boundedElastic
        // so the Reactor event loop is not blocked. Mirrors AuthService.login / resetPassword.
        return Mono.fromCallable(() -> passwordEncoder.matches(currentPassword, user.getPasswordHash()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(matches -> {
                    if (Boolean.FALSE.equals(matches)) {
                        return registerChangePwdFailure(user.getId())
                                .then(Mono.<Void>error(AppException.badRequest("Поточний пароль невірний")));
                    }
                    return Mono.fromCallable(() -> passwordEncoder.encode(newPassword))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(newHash -> {
                                Instant now = Instant.now();
                                user.setPasswordHash(newHash);
                                user.setUpdatedAt(now);
                                return userRepository.save(user);
                            })
                            .flatMap(saved -> terminateAllSessionsExcept(saved.getId(), currentSession.getId())
                                    .then(resetChangePwdCounter(saved.getId()))
                                    .doOnSuccess(v -> eventService.logEvent(
                                            saved.getId(), EVENT_PASSWORD_CHANGED, ip, userAgent, null)))
                            .then();
                });
    }

    public Mono<Long> terminateAllSessions(String userId) {
        // sessions.principal field path verified at runtime by the Task 6 IT
        // (sessionsCollection_principalFieldPath_isAtTopLevel). Same query as
        // AuthService.terminateAllSessions used by reset-password — duplication accepted: the
        // single-field-path invariant is locked by the Task 6 IT and a shared helper would
        // pull AuthService into ProfileService's dependency graph for one query.
        Query q = Query.query(Criteria.where("principal").is(userId));
        return reactiveMongoTemplate.remove(q, "sessions").map(r -> r.getDeletedCount());
    }

    public Mono<Long> terminateAllSessionsExcept(String userId, String currentSessionId) {
        // Spring Session stores the session ID literally in the `_id` field of the sessions
        // collection (see MongoSession.MONGO_ID). Excluding by session id keeps the device
        // that initiated change-password logged in while signing out every other device.
        Query q = Query.query(Criteria.where("principal").is(userId)
                .and("_id").ne(currentSessionId));
        return reactiveMongoTemplate.remove(q, "sessions").map(r -> r.getDeletedCount());
    }

    public Mono<Void> deleteAccount(String userId, WebSession session, String ip, String userAgent) {
        return loadActiveUser(userId)
                .flatMap(user -> {
                    Instant now = Instant.now();
                    user.setStatus(UserStatus.deleted);
                    user.setDeletedAt(now);
                    user.setUpdatedAt(now);
                    return userRepository.save(user);
                })
                // Account deletion must invalidate ALL of this user's sessions across every
                // device — leaving sibling sessions alive defeats the purpose. terminate-all
                // also removes the current session document; session.invalidate() then becomes
                // a no-op for the cookie clean-up but is still called for symmetry with the
                // WebSession lifecycle (downstream Set-Cookie removal etc.).
                .flatMap(saved -> terminateAllSessions(saved.getId())
                        .then(session.invalidate())
                        .doOnSuccess(v -> eventService.logEvent(
                                saved.getId(), EVENT_ACCOUNT_DELETED, ip, userAgent, null)))
                .then();
    }

    // --- helpers ---

    private Mono<User> loadActiveUser(String userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(AppException.unauthorized("Not authenticated")))
                // Status gate (security-auditor critical): a session whose underlying account
                // was blocked or soft-deleted on another device or by an admin must NOT retain
                // profile access. AuthService.login gates these statuses on the way in;
                // ProfileService mirrors that policy on every authenticated profile call.
                // Returning 401 forces the client back through login (which will redirect on
                // the proper status-based message).
                .flatMap(user -> {
                    if (user.getStatus() != UserStatus.active && user.getStatus() != UserStatus.pending) {
                        return Mono.<User>error(AppException.unauthorized("Not authenticated"));
                    }
                    return Mono.just(user);
                });
    }

    private Mono<Void> checkChangePwdRate(String userId) {
        String key = changePwdKey(userId);
        return redisTemplate.opsForValue().get(key)
                .map(Long::parseLong)
                .defaultIfEmpty(0L)
                .flatMap(count -> count >= CHANGE_PWD_THRESHOLD
                        ? Mono.<Void>error(AppException.tooManyRequests(
                                "Забагато спроб зміни пароля. Спробуйте за 15 хвилин."))
                        : Mono.<Void>empty())
                // Decision 4 fail-open: Redis outage must not block legitimate password changes.
                .onErrorResume(err -> err instanceof AppException
                        ? Mono.error(err)
                        : Mono.fromRunnable(() ->
                                log.warn("Redis change-pwd rate-limit check failed, allowing: {}", err.getMessage())));
    }

    private Mono<Void> registerChangePwdFailure(String userId) {
        String key = changePwdKey(userId);
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    if (count != null && count == 1L) {
                        return redisTemplate.expire(key, CHANGE_PWD_TTL).then();
                    }
                    return Mono.empty();
                })
                .onErrorResume(err -> {
                    log.warn("Redis change-pwd increment failed: {}", err.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> resetChangePwdCounter(String userId) {
        return redisTemplate.delete(changePwdKey(userId))
                .then()
                .onErrorResume(err -> {
                    log.warn("Redis change-pwd reset failed: {}", err.getMessage());
                    return Mono.empty();
                });
    }

    private static String changePwdKey(String userId) {
        return "change-pwd:fail:" + userId;
    }

    private static ProfileResponse toResponse(User user) {
        return new ProfileResponse(user.getId(), user.getEmail(), user.getName(),
                user.getStatus() == null ? null : user.getStatus().name());
    }
}
