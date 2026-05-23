package com.botfunnel.profile;

import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.profile.dto.ProfileResponse;
import com.botfunnel.profile.dto.UpdateProfileRequest;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.mongodb.client.result.DeleteResult;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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
    private final MongoTemplate mongoTemplate;
    private final StringRedisTemplate redisTemplate;
    private final EventService eventService;

    public ProfileService(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          MongoTemplate mongoTemplate,
                          StringRedisTemplate redisTemplate,
                          EventService eventService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mongoTemplate = mongoTemplate;
        this.redisTemplate = redisTemplate;
        this.eventService = eventService;
    }

    public ProfileResponse getProfile(String userId) {
        return toResponse(loadActiveUser(userId));
    }

    public ProfileResponse updateProfile(String userId, UpdateProfileRequest req) {
        User user = loadActiveUser(userId);
        user.setName(req.getName());
        user.setUpdatedAt(Instant.now());
        return toResponse(userRepository.save(user));
    }

    public void changePassword(String userId, String currentPassword, String newPassword,
                               HttpSession currentSession, String ip, String userAgent) {
        // Rate-limit check first — the user lookup is skipped entirely when the limiter aborts.
        checkChangePwdRate(userId);
        User user = loadActiveUser(userId);
        verifyAndRotate(user, currentPassword, newPassword, currentSession, ip, userAgent);
    }

    private void verifyAndRotate(User user, String currentPassword, String newPassword,
                                 HttpSession currentSession, String ip, String userAgent) {
        // BCrypt verify and encode are CPU-bound (~250ms each at cost 12). On the virtual-thread
        // servlet stack the carrier-thread pinning is bounded and acceptable per AC7.
        boolean matches = passwordEncoder.matches(currentPassword, user.getPasswordHash());
        if (!matches) {
            registerChangePwdFailure(user.getId());
            throw AppException.badRequest("Поточний пароль невірний");
        }
        String newHash = passwordEncoder.encode(newPassword);
        Instant now = Instant.now();
        user.setPasswordHash(newHash);
        user.setUpdatedAt(now);
        User saved = userRepository.save(user);
        terminateAllSessionsExcept(saved.getId(), currentSession.getId());
        resetChangePwdCounter(saved.getId());
        eventService.logEvent(saved.getId(), EVENT_PASSWORD_CHANGED, ip, userAgent, null);
    }

    public long terminateAllSessions(String userId) {
        // sessions.principal field path verified at runtime by the Task 6 IT
        // (sessionsCollection_principalFieldPath_isAtTopLevel). Same query as
        // AuthService.terminateAllSessions used by reset-password — duplication accepted: the
        // single-field-path invariant is locked by the Task 6 IT and a shared helper would
        // pull AuthService into ProfileService's dependency graph for one query.
        Query q = Query.query(Criteria.where("principal").is(userId));
        DeleteResult result = mongoTemplate.remove(q, "sessions");
        return result.getDeletedCount();
    }

    public long terminateAllSessionsExcept(String userId, String currentSessionId) {
        // Spring Session stores the session ID literally in the `_id` field of the sessions
        // collection (see MongoSession.MONGO_ID). Excluding by session id keeps the device
        // that initiated change-password logged in while signing out every other device.
        Query q = Query.query(Criteria.where("principal").is(userId)
                .and("_id").ne(currentSessionId));
        DeleteResult result = mongoTemplate.remove(q, "sessions");
        return result.getDeletedCount();
    }

    public void deleteAccount(String userId, HttpSession session, String ip, String userAgent) {
        User user = loadActiveUser(userId);
        Instant now = Instant.now();
        user.setStatus(UserStatus.deleted);
        user.setDeletedAt(now);
        user.setUpdatedAt(now);
        User saved = userRepository.save(user);
        // Account deletion must invalidate ALL of this user's sessions across every device —
        // leaving sibling sessions alive defeats the purpose. terminate-all also removes the
        // current session document; session.invalidate() then becomes a no-op for the cookie
        // clean-up but is still called for the Set-Cookie removal signal.
        terminateAllSessions(saved.getId());
        session.invalidate();
        eventService.logEvent(saved.getId(), EVENT_ACCOUNT_DELETED, ip, userAgent, null);
    }

    // --- helpers ---

    private User loadActiveUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.unauthorized("Not authenticated"));
        // Status gate (security-auditor critical): a session whose underlying account
        // was blocked or soft-deleted on another device or by an admin must NOT retain
        // profile access. AuthService.login gates these statuses on the way in;
        // ProfileService mirrors that policy on every authenticated profile call.
        // Returning 401 forces the client back through login (which will redirect on
        // the proper status-based message).
        if (user.getStatus() != UserStatus.active && user.getStatus() != UserStatus.pending) {
            throw AppException.unauthorized("Not authenticated");
        }
        return user;
    }

    private void checkChangePwdRate(String userId) {
        String key = changePwdKey(userId);
        try {
            String raw = redisTemplate.opsForValue().get(key);
            long count = raw == null ? 0L : Long.parseLong(raw);
            if (count >= CHANGE_PWD_THRESHOLD) {
                throw AppException.tooManyRequests(
                        "Забагато спроб зміни пароля. Спробуйте за 15 хвилин.");
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception err) {
            // Decision 4 fail-open: Redis outage must not block legitimate password changes.
            log.warn("Redis change-pwd rate-limit check failed, allowing: {}", err.getMessage());
        }
    }

    private void registerChangePwdFailure(String userId) {
        String key = changePwdKey(userId);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, CHANGE_PWD_TTL);
            }
        } catch (Exception err) {
            log.warn("Redis change-pwd increment failed: {}", err.getMessage());
        }
    }

    private void resetChangePwdCounter(String userId) {
        try {
            redisTemplate.delete(changePwdKey(userId));
        } catch (Exception err) {
            log.warn("Redis change-pwd reset failed: {}", err.getMessage());
        }
    }

    private static String changePwdKey(String userId) {
        return "change-pwd:fail:" + userId;
    }

    private static ProfileResponse toResponse(User user) {
        return new ProfileResponse(user.getId(), user.getEmail(), user.getName(),
                user.getStatus() == null ? null : user.getStatus().name());
    }
}
