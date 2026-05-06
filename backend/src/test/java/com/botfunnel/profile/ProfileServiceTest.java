package com.botfunnel.profile;

import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.mongodb.client.result.DeleteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock ReactiveMongoTemplate reactiveMongoTemplate;
    @Mock EventService eventService;
    @Mock WebSession session;

    ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(userRepository, passwordEncoder,
                reactiveMongoTemplate, eventService);
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
    void changePassword_wrongCurrentPassword_throwsAppException() {
        User user = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches(eq("WrongCurrent"), eq(user.getPasswordHash()))).thenReturn(false);

        StepVerifier.create(profileService.changePassword(
                        USER_ID, "WrongCurrent", "NewStr0ngPass", session, IP, UA))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    assertThat(((AppException) err).getStatus().value()).isEqualTo(400);
                })
                .verify();

        verify(userRepository, never()).save(any());
        verify(reactiveMongoTemplate, never()).remove(any(Query.class), anyString());
        verify(eventService, never()).logEvent(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void changePassword_correctPassword_excludesCurrentSession() {
        User user = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches(eq("CurrentPass1"), eq(user.getPasswordHash()))).thenReturn(true);
        when(passwordEncoder.encode(eq("NewStr0ngPass"))).thenReturn("$2a$12$newhash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(session.getId()).thenReturn(SESSION_ID);
        DeleteResult dr = DeleteResult.acknowledged(2L);
        when(reactiveMongoTemplate.remove(any(Query.class), eq("sessions"))).thenReturn(Mono.just(dr));

        StepVerifier.create(profileService.changePassword(
                        USER_ID, "CurrentPass1", "NewStr0ngPass", session, IP, UA))
                .verifyComplete();

        ArgumentCaptor<Query> qc = ArgumentCaptor.forClass(Query.class);
        verify(reactiveMongoTemplate).remove(qc.capture(), eq("sessions"));
        // The query must filter by principal=userId AND _id != currentSessionId so the
        // initiating device stays signed in (Decision 14).
        String queryJson = qc.getValue().getQueryObject().toJson();
        assertThat(queryJson).contains("\"principal\"").contains(USER_ID);
        assertThat(queryJson).contains("\"_id\"").contains("$ne").contains(SESSION_ID);

        verify(eventService).logEvent(eq(USER_ID), eq("password_changed"), eq(IP), eq(UA), eq(null));

        ArgumentCaptor<User> uc = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(uc.capture());
        assertThat(uc.getValue().getPasswordHash()).isEqualTo("$2a$12$newhash");
    }

    @Test
    void terminateAllSessions_deletesAllSessionsForUser() {
        DeleteResult dr = DeleteResult.acknowledged(3L);
        when(reactiveMongoTemplate.remove(any(Query.class), eq("sessions"))).thenReturn(Mono.just(dr));

        StepVerifier.create(profileService.terminateAllSessions(USER_ID))
                .expectNext(3L)
                .verifyComplete();
    }

    @Test
    void deleteAccount_setsStatusDeleted_invalidatesSession_logsEvent() {
        User user = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(session.invalidate()).thenReturn(Mono.empty());

        StepVerifier.create(profileService.deleteAccount(USER_ID, session, IP, UA))
                .verifyComplete();

        ArgumentCaptor<User> uc = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(uc.capture());
        assertThat(uc.getValue().getStatus()).isEqualTo(UserStatus.deleted);
        assertThat(uc.getValue().getDeletedAt()).isNotNull();
        verify(session).invalidate();
        verify(eventService).logEvent(eq(USER_ID), eq("account_deleted"), eq(IP), eq(UA), eq(null));
    }

    @Test
    void updateProfile_setsName_doesNotTouchOtherFields() {
        User user = activeUser();
        Instant before = user.getUpdatedAt();
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        com.botfunnel.profile.dto.UpdateProfileRequest req = new com.botfunnel.profile.dto.UpdateProfileRequest();
        req.setName("New Name");

        StepVerifier.create(profileService.updateProfile(USER_ID, req))
                .assertNext(resp -> {
                    assertThat(resp.name()).isEqualTo("New Name");
                    assertThat(resp.email()).isEqualTo("user@test.com");
                    assertThat(resp.status()).isEqualTo("active");
                })
                .verifyComplete();

        ArgumentCaptor<User> uc = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(uc.capture());
        assertThat(uc.getValue().getName()).isEqualTo("New Name");
        assertThat(uc.getValue().getUpdatedAt()).isNotEqualTo(before);
        // Status, email, isSuperAdmin must NOT be modified by updateProfile.
        assertThat(uc.getValue().isSuperAdmin()).isFalse();
        assertThat(uc.getValue().getStatus()).isEqualTo(UserStatus.active);
    }

    @Test
    void getProfile_returnsCurrentFields() {
        User user = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(user));

        StepVerifier.create(profileService.getProfile(USER_ID))
                .assertNext(resp -> {
                    assertThat(resp.id()).isEqualTo(USER_ID);
                    assertThat(resp.email()).isEqualTo("user@test.com");
                    assertThat(resp.name()).isEqualTo("Alice");
                    assertThat(resp.status()).isEqualTo("active");
                })
                .verifyComplete();
    }
}
