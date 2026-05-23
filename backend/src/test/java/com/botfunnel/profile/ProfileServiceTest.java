package com.botfunnel.profile;

import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.mongodb.client.result.DeleteResult;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "current-session-id";
    private static final String IP = "10.0.0.1";
    private static final String UA = "JUnit";

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock MongoTemplate mongoTemplate;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    StringRedisTemplate redisTemplate;
    @Mock EventService eventService;
    @Mock HttpSession session;

    ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(userRepository, passwordEncoder,
                mongoTemplate, redisTemplate, eventService);
        // Default-allow change-pwd rate limiter (no prior failures): get → null (count 0).
        // Tests that exercise the over-threshold branch override this stub explicitly.
        lenient().when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);
    }

    private User activeUser() {
        User u = new User();
        u.setId(USER_ID);
        u.setEmail("user@test.com");
        u.setName("Alice");
        u.setStatus(UserStatus.active);
        u.setPasswordHash("$2a$12$existing-hash");
        return u;
    }

    @Test
    void changePassword_wrongCurrentPassword_throwsAppException_andIncrementsCounter() {
        User user = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(eq("WrongCurrent"), eq(user.getPasswordHash()))).thenReturn(false);
        when(redisTemplate.opsForValue().increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        assertThatThrownBy(() -> profileService.changePassword(
                        USER_ID, "WrongCurrent", "NewStr0ngPass", session, IP, UA))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getStatus().value()).isEqualTo(400));

        verify(userRepository, never()).save(any());
        verify(mongoTemplate, never()).remove(any(Query.class), anyString());
        verify(eventService, never()).logEvent(anyString(), anyString(), anyString(), anyString(), any());
        // Failure must register on the brute-force counter so a hijacked session cannot grind
        // BCrypt verifications without bound.
        verify(redisTemplate.opsForValue()).increment(eq("change-pwd:fail:" + USER_ID));
    }

    @Test
    void changePassword_correctPassword_excludesCurrentSession_andResetsCounter() {
        User user = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(eq("CurrentPass1"), eq(user.getPasswordHash()))).thenReturn(true);
        when(passwordEncoder.encode(eq("NewStr0ngPass"))).thenReturn("$2a$12$newhash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(session.getId()).thenReturn(SESSION_ID);
        DeleteResult dr = DeleteResult.acknowledged(2L);
        when(mongoTemplate.remove(any(Query.class), eq("sessions"))).thenReturn(dr);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        profileService.changePassword(USER_ID, "CurrentPass1", "NewStr0ngPass", session, IP, UA);

        ArgumentCaptor<Query> qc = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).remove(qc.capture(), eq("sessions"));
        // The query must filter by principal=userId AND _id != currentSessionId so the
        // initiating device stays signed in (Decision 14).
        String queryJson = qc.getValue().getQueryObject().toJson();
        assertThat(queryJson).contains("\"principal\"").contains(USER_ID);
        assertThat(queryJson).contains("\"_id\"").contains("$ne").contains(SESSION_ID);

        verify(eventService).logEvent(eq(USER_ID), eq("password_changed"), eq(IP), eq(UA), eq(null));
        verify(redisTemplate).delete(eq("change-pwd:fail:" + USER_ID));

        ArgumentCaptor<User> uc = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(uc.capture());
        assertThat(uc.getValue().getPasswordHash()).isEqualTo("$2a$12$newhash");
    }

    @Test
    void changePassword_overRateLimit_returns429_skipsBcryptAndDb() {
        // Pre-existing 5 failures must short-circuit before BCrypt verify is even called —
        // closes the cost-12 grinding window per security audit major.
        when(redisTemplate.opsForValue().get(eq("change-pwd:fail:" + USER_ID)))
                .thenReturn("5");

        assertThatThrownBy(() -> profileService.changePassword(
                        USER_ID, "anything", "AlsoStr0ng", session, IP, UA))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getStatus().value()).isEqualTo(429));

        verify(userRepository, never()).findById(anyString());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void terminateAllSessions_deletesAllSessionsForUser() {
        DeleteResult dr = DeleteResult.acknowledged(3L);
        ArgumentCaptor<Query> qc = ArgumentCaptor.forClass(Query.class);
        when(mongoTemplate.remove(qc.capture(), eq("sessions"))).thenReturn(dr);

        long count = profileService.terminateAllSessions(USER_ID);
        assertThat(count).isEqualTo(3L);

        // Pin the field path — the Task 6 IT verifies this against a real Mongo schema.
        String queryJson = qc.getValue().getQueryObject().toJson();
        assertThat(queryJson).contains("\"principal\"").contains(USER_ID);
        assertThat(queryJson).doesNotContain("$ne");
    }

    @Test
    void deleteAccount_setsStatusDeleted_terminatesAllSessions_invalidatesSession_logsEvent() {
        User user = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        DeleteResult dr = DeleteResult.acknowledged(2L);
        when(mongoTemplate.remove(any(Query.class), eq("sessions"))).thenReturn(dr);
        // HttpSession.invalidate() is void — default Mockito stub is no-op.

        profileService.deleteAccount(USER_ID, session, IP, UA);

        ArgumentCaptor<User> uc = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(uc.capture());
        assertThat(uc.getValue().getStatus()).isEqualTo(UserStatus.deleted);
        assertThat(uc.getValue().getDeletedAt()).isNotNull();
        // Account deletion MUST also kill sibling sessions on other devices, otherwise the
        // session you were "deleting" the account from is gone but the laptop next to you is
        // still authenticated.
        verify(mongoTemplate).remove(any(Query.class), eq("sessions"));
        verify(session).invalidate();
        verify(eventService).logEvent(eq(USER_ID), eq("account_deleted"), eq(IP), eq(UA), eq(null));
    }

    @Test
    void getProfile_blockedUser_returns401_doesNotLeakStatus() {
        // Status gate (security-auditor critical): a blocked user with a still-valid session
        // must NOT pull profile data — force re-login through the auth flow which gates blocked.
        User u = activeUser();
        u.setStatus(UserStatus.blocked);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> profileService.getProfile(USER_ID))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getStatus().value()).isEqualTo(401));
    }

    @Test
    void getProfile_deletedUser_returns401() {
        User u = activeUser();
        u.setStatus(UserStatus.deleted);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> profileService.getProfile(USER_ID))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getStatus().value()).isEqualTo(401));
    }

    @Test
    void getProfile_unknownUser_returns401() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.getProfile(USER_ID))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getStatus().value()).isEqualTo(401));
    }

    @Test
    void updateProfile_setsName_doesNotTouchOtherFields() {
        User user = activeUser();
        Instant before = user.getUpdatedAt();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        com.botfunnel.profile.dto.UpdateProfileRequest req = new com.botfunnel.profile.dto.UpdateProfileRequest();
        req.setName("New Name");

        com.botfunnel.profile.dto.ProfileResponse resp = profileService.updateProfile(USER_ID, req);
        assertThat(resp.name()).isEqualTo("New Name");
        assertThat(resp.email()).isEqualTo("user@test.com");
        assertThat(resp.status()).isEqualTo("active");

        ArgumentCaptor<User> uc = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(uc.capture());
        assertThat(uc.getValue().getName()).isEqualTo("New Name");
        assertThat(uc.getValue().getUpdatedAt()).isNotEqualTo(before);
        // Status, email, isSuperAdmin must NOT be modified by updateProfile.
        assertThat(uc.getValue().isSuperAdmin()).isFalse();
        assertThat(uc.getValue().getStatus()).isEqualTo(UserStatus.active);
    }
}
