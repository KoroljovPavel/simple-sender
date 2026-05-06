package com.botfunnel.profile;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

class ProfileControllerIT extends AbstractIntegrationTest {

    // Fixed user id matches the value injected by @WithMockAppUser so the seeded MongoDB
    // document and the test SecurityContext refer to the same user.
    private static final String USER_ID = "test-user-fixed-id";

    @Autowired UserRepository userRepository;
    @Autowired EventRepository eventRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ReactiveMongoTemplate reactiveMongoTemplate;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll().block();
        eventRepository.deleteAll().block();
        reactiveMongoTemplate.remove(new Query(), "sessions").block();

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("profile@test.com");
        u.setName("Alice");
        u.setPasswordHash(passwordEncoder.encode("Strong1Pass"));
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u).block();
    }

    private void seedSession(String sessionId, String principal) {
        // Seed the spring-session-data-mongodb document directly so terminate-all queries have
        // something to remove. Field path matches the live schema verified in
        // AuthControllerIT.sessionsCollection_principalFieldPath_isAtTopLevel.
        Document doc = new Document("_id", sessionId)
                .append("principal", principal)
                .append("created", Instant.now().toEpochMilli())
                .append("expireAt", java.util.Date.from(Instant.now().plus(Duration.ofHours(1))));
        reactiveMongoTemplate.getCollection("sessions")
                .flatMap(c -> reactor.core.publisher.Mono.from(c.insertOne(doc)))
                .block();
    }

    // ---------- GET /api/profile ----------

    @Test
    @WithMockAppUser(userId = USER_ID, email = "profile@test.com", name = "Alice", status = "active")
    void getProfile_returns200WithIdNameEmailStatus() {
        webTestClient.get().uri("/api/profile")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(USER_ID)
                .jsonPath("$.email").isEqualTo("profile@test.com")
                .jsonPath("$.name").isEqualTo("Alice")
                .jsonPath("$.status").isEqualTo("active");
    }

    // ---------- PATCH /api/profile ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProfile_isSuperAdminInBody_fieldIgnored() {
        // Mass-assignment attempt: client tries to elevate themselves via PATCH body.
        // @JsonIgnoreProperties(ignoreUnknown = true) on the DTO must silently discard the
        // unknown `isSuperAdmin` field — the saved user must NOT have isSuperAdmin=true.
        webTestClient.mutateWith(csrf())
                .patch().uri("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "New Name", "isSuperAdmin", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("New Name");

        User reread = userRepository.findById(USER_ID).block();
        assertThat(reread.getName()).isEqualTo("New Name");
        assertThat(reread.isSuperAdmin())
                .as("isSuperAdmin in PATCH body must be silently ignored (whitelist DTO)")
                .isFalse();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProfile_blankName_400() {
        webTestClient.mutateWith(csrf())
                .patch().uri("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", ""))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ---------- POST /api/profile/terminate-all-sessions ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void terminateAllSessions_removesAllSessionDocuments() {
        // Seed two sessions for this user plus one for a different user (which must NOT be touched).
        seedSession("sess-1", USER_ID);
        seedSession("sess-2", USER_ID);
        seedSession("sess-other", "other-user-id");

        webTestClient.mutateWith(csrf())
                .post().uri("/api/profile/terminate-all-sessions")
                .exchange()
                .expectStatus().isOk();

        long mine = reactiveMongoTemplate.count(
                Query.query(Criteria.where("principal").is(USER_ID)), "sessions").block();
        long others = reactiveMongoTemplate.count(
                Query.query(Criteria.where("principal").is("other-user-id")), "sessions").block();
        assertThat(mine).as("all of this user's sessions must be removed").isZero();
        assertThat(others).as("other users' sessions must be untouched").isEqualTo(1L);
    }

    // ---------- DELETE /api/profile ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteAccount_setsStatusDeleted_terminatesAllSessions_logsEvent() {
        // Seed sibling sessions on "other devices" + an unrelated user's session that must
        // survive (security-auditor major: deleteAccount must invalidate ALL the user's
        // sessions, not just the current one).
        seedSession("sess-laptop", USER_ID);
        seedSession("sess-phone", USER_ID);
        seedSession("sess-other-user", "another-user-id");

        webTestClient.mutateWith(csrf())
                .delete().uri("/api/profile")
                .exchange()
                .expectStatus().isOk();

        User reread = userRepository.findById(USER_ID).block();
        assertThat(reread.getStatus()).isEqualTo(UserStatus.deleted);
        assertThat(reread.getDeletedAt()).isNotNull();

        // ALL of this user's sessions across every device must be gone.
        long mySessions = reactiveMongoTemplate.count(
                Query.query(Criteria.where("principal").is(USER_ID)), "sessions").block();
        long otherUserSessions = reactiveMongoTemplate.count(
                Query.query(Criteria.where("principal").is("another-user-id")), "sessions").block();
        assertThat(mySessions).as("all of this user's sessions must be terminated on account delete").isZero();
        assertThat(otherUserSessions).as("other users' sessions must NOT be touched").isEqualTo(1L);

        // Audit event must be logged.
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findAll()
                        .filter(e -> "account_deleted".equals(e.getEventType())
                                && USER_ID.equals(e.getUserId()))
                        .hasElements()
                        .block());
        Event evt = eventRepository.findAll()
                .filter(e -> "account_deleted".equals(e.getEventType()))
                .blockFirst();
        assertThat(evt).isNotNull();
        assertThat(evt.getUserId()).isEqualTo(USER_ID);

        // Re-issuing GET /api/profile with a still-authenticated SecurityContext (in @WithMockAppUser
        // we never actually had a server-side cookie, but the user is now soft-deleted) must
        // return 401 due to the status gate in loadActiveUser.
        webTestClient.get().uri("/api/profile")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------- Auth gate ----------

    @Test
    void getProfile_unauthenticated_401() {
        webTestClient.get().uri("/api/profile")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------- change-password ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void changePassword_wrongCurrent_400_passwordHashUnchanged() {
        String originalHash = userRepository.findById(USER_ID).block().getPasswordHash();

        webTestClient.mutateWith(csrf())
                .post().uri("/api/profile/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("currentPassword", "WrongCurrent", "newPassword", "NewStr0ngPass"))
                .exchange()
                .expectStatus().isBadRequest();

        User reread = userRepository.findById(USER_ID).block();
        assertThat(reread.getPasswordHash())
                .as("password hash must NOT change when current password is wrong")
                .isEqualTo(originalHash);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void changePassword_correctCurrent_passwordRotated_otherSessionsKilled_eventLogged() {
        // Seed two sibling sessions (other devices) + an unrelated user's session that must
        // survive. The request's own session is not persisted under @WithMockAppUser
        // (no real cookie flow), so we cannot assert "current session survives" at the
        // document level here — the unit test ProfileServiceTest pins the `_id != currentSessionId`
        // clause of the terminate query. What this IT proves end-to-end is that the integration
        // delete path actually runs and removes the seeded sibling sessions.
        seedSession("sess-laptop", USER_ID);
        seedSession("sess-phone", USER_ID);
        seedSession("sess-other-user", "another-user-id");

        webTestClient.mutateWith(csrf())
                .post().uri("/api/profile/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("currentPassword", "Strong1Pass", "newPassword", "NewStr0ngPass"))
                .exchange()
                .expectStatus().isOk();

        User reread = userRepository.findById(USER_ID).block();
        assertThat(passwordEncoder.matches("NewStr0ngPass", reread.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("Strong1Pass", reread.getPasswordHash())).isFalse();

        // Other-user session must NOT be touched (Decision 14).
        long otherUserSessions = reactiveMongoTemplate.count(
                Query.query(Criteria.where("principal").is("another-user-id")), "sessions").block();
        assertThat(otherUserSessions)
                .as("change-password must only target the acting user's sessions")
                .isEqualTo(1L);

        // Both seeded sibling sessions for the acting user must be removed (their _id values
        // do not match the request's current session id, so they fall under the "except current"
        // delete).
        long surviving = reactiveMongoTemplate.count(
                Query.query(Criteria.where("principal").is(USER_ID)
                        .and("_id").in("sess-laptop", "sess-phone")), "sessions").block();
        assertThat(surviving)
                .as("sibling sessions must be invalidated by change-password")
                .isZero();

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findAll()
                        .filter(e -> "password_changed".equals(e.getEventType())
                                && USER_ID.equals(e.getUserId()))
                        .hasElements()
                        .block());
        List<Event> events = eventRepository.findAll().collectList().block();
        assertThat(events).extracting(Event::getEventType).contains("password_changed");
    }
}
