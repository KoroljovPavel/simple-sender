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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private static final String EVENT_PASSWORD_CHANGED = "password_changed";
    private static final String EVENT_ACCOUNT_DELETED = "account_deleted";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ReactiveMongoTemplate reactiveMongoTemplate;
    private final EventService eventService;

    public ProfileService(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          ReactiveMongoTemplate reactiveMongoTemplate,
                          EventService eventService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.reactiveMongoTemplate = reactiveMongoTemplate;
        this.eventService = eventService;
    }

    public Mono<ProfileResponse> getProfile(String userId) {
        return loadUser(userId).map(ProfileService::toResponse);
    }

    public Mono<ProfileResponse> updateProfile(String userId, UpdateProfileRequest req) {
        return loadUser(userId)
                .flatMap(user -> {
                    user.setName(req.getName());
                    user.setUpdatedAt(Instant.now());
                    return userRepository.save(user);
                })
                .map(ProfileService::toResponse);
    }

    public Mono<Void> changePassword(String userId, String currentPassword, String newPassword,
                                     WebSession currentSession, String ip, String userAgent) {
        return loadUser(userId)
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
                        return Mono.<Void>error(AppException.badRequest("Поточний пароль невірний"));
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
                                    .doOnSuccess(deleted -> eventService.logEvent(
                                            saved.getId(), EVENT_PASSWORD_CHANGED, ip, userAgent, null)))
                            .then();
                });
    }

    public Mono<Long> terminateAllSessions(String userId) {
        // sessions.principal field path verified at runtime by the Task 6 IT
        // (sessionsCollection_principalFieldPath_isAtTopLevel).
        Query q = Query.query(Criteria.where("principal").is(userId));
        return reactiveMongoTemplate.remove(q, "sessions").map(r -> r.getDeletedCount());
    }

    public Mono<Long> terminateAllSessionsExcept(String userId, String currentSessionId) {
        // Spring Session stores its session document with the session ID in the `_id` field.
        // Excluding by session id keeps the device that initiated change-password logged in
        // while signing out every other device. spring-session-data-mongodb hashes the session
        // ID before storing it, so equality on the hash representation must match exactly what
        // WebSession.getId() returns at runtime — Spring Session does this hashing on read/write
        // transparently, so the criterion below works against the same value.
        Query q = Query.query(Criteria.where("principal").is(userId)
                .and("_id").ne(currentSessionId));
        return reactiveMongoTemplate.remove(q, "sessions").map(r -> r.getDeletedCount());
    }

    public Mono<Void> deleteAccount(String userId, WebSession session, String ip, String userAgent) {
        return loadUser(userId)
                .flatMap(user -> {
                    Instant now = Instant.now();
                    user.setStatus(UserStatus.deleted);
                    user.setDeletedAt(now);
                    user.setUpdatedAt(now);
                    return userRepository.save(user);
                })
                .flatMap(saved -> session.invalidate()
                        .doOnSuccess(v -> eventService.logEvent(
                                saved.getId(), EVENT_ACCOUNT_DELETED, ip, userAgent, null)))
                .then();
    }

    private Mono<User> loadUser(String userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(AppException.unauthorized("Not authenticated")));
    }

    private static ProfileResponse toResponse(User user) {
        return new ProfileResponse(user.getId(), user.getEmail(), user.getName(),
                user.getStatus() == null ? null : user.getStatus().name());
    }
}
