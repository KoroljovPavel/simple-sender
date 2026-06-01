package com.botfunnel.subscriber;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.CustomFieldDefinition;
import com.botfunnel.project.CustomFieldType;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Behaviour-lock for the PATCH custom-fields endpoint AFTER the inline validate→update→record cycle
// was extracted into SubscriberCustomFieldsService (Task 4). Asserts the externally observable
// contract is byte-identical: 200 + current customFields, mass-assignment defense (unknown keys
// silently ignored), 422 on type mismatch, uniform 404 on a foreign/missing subscriber, and EXACTLY
// ONE aggregated subscriber_custom_field_set audit event per PATCH (not per key).
class SubscriberCustomFieldsControllerTest extends AbstractIntegrationTest {

    private static final String USER_ID = "scf-ctrl-user";
    private static final String OTHER_USER_ID = "scf-ctrl-other";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired SubscriberEventRepository subscriberEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        subscriberRepository.deleteAll();
        subscriberEventRepository.deleteAll();

        userRepository.save(user(USER_ID, "scf@test.com", "SCF Owner"));
        // Foreign owner seeded for patchCustomFields_foreignOwner_returns404 robustness (the owner of
        // the foreign project actually exists, so the 404 is the IDOR guard, not a missing-user fluke).
        userRepository.save(user(OTHER_USER_ID, "scf-other@test.com", "SCF Other"));
    }

    private User user(String id, String email, String name) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setName(name);
        u.setPasswordHash("not-used");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        return u;
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchCustomFields_behaviourUnchanged() throws Exception {
        // Two known fields (with PRIOR values) + one unknown key in a single PATCH. Expected: 200,
        // both known values overwritten & echoed, unknown key dropped, and ONE aggregated audit event
        // whose oldValues capture the prior state and newValues the normalized new state.
        String projectId = saveProject(USER_ID,
                def("city", CustomFieldType.STRING), def("age", CustomFieldType.NUMBER)).getId();
        Map<String, Object> prior = new HashMap<>();
        prior.put("city", "OldCity");
        prior.put("age", 25.0d);
        String subscriberId = saveSubscriber(projectId, 200L, prior);

        mockMvc.perform(patch(url(projectId, subscriberId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"city\":\"  Kyiv  \",\"age\":\"42\",\"unknown_evil\":\"x\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Kyiv"))
                .andExpect(jsonPath("$.age").value(42.0))
                .andExpect(jsonPath("$.unknown_evil").doesNotExist());

        Subscriber reread = subscriberRepository.findById(subscriberId).orElseThrow();
        assertThat(reread.getCustomFields()).containsEntry("city", "Kyiv").containsEntry("age", 42.0d)
                .doesNotContainKey("unknown_evil");

        // Aggregated audit invariant: ONE event for the whole PATCH, both changed keys in it, and the
        // old/new value capture is pinned — oldValues = prior state, newValues = normalized new state.
        List<SubscriberEvent> events = customFieldSetEvents(subscriberId);
        assertThat(events).hasSize(1);
        Map<String, Object> meta = events.get(0).getMetadata();
        @SuppressWarnings("unchecked")
        List<String> changedKeys = (List<String>) meta.get("changedKeys");
        assertThat(changedKeys).containsExactlyInAnyOrder("city", "age");
        @SuppressWarnings("unchecked")
        Map<String, Object> oldValues = (Map<String, Object>) meta.get("oldValues");
        @SuppressWarnings("unchecked")
        Map<String, Object> newValues = (Map<String, Object>) meta.get("newValues");
        assertThat(oldValues).containsEntry("city", "OldCity").containsEntry("age", 25.0d);
        assertThat(newValues).containsEntry("city", "Kyiv").containsEntry("age", 42.0d);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchCustomFields_partialKeyOrder_422_writesNothing() throws Exception {
        // Multi-key PATCH where the FIRST key is valid and the SECOND is invalid. All-or-nothing
        // semantics: the 422 must leave the subscriber untouched — the first (valid) key must NOT be
        // persisted, and NO audit event may be emitted (validate-all-then-apply-once).
        String projectId = saveProject(USER_ID,
                def("city", CustomFieldType.STRING), def("age", CustomFieldType.NUMBER)).getId();
        String subscriberId = saveSubscriber(projectId, 204L, new HashMap<>());

        mockMvc.perform(patch(url(projectId, subscriberId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"city\":\"Kyiv\",\"age\":\"not-a-number\"}}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("custom_field_type_mismatch"));

        // Nothing written: the earlier valid key did NOT leak into the document.
        Subscriber reread = subscriberRepository.findById(subscriberId).orElseThrow();
        assertThat(reread.getCustomFields()).doesNotContainKey("city").doesNotContainKey("age");
        // And no audit event for the rejected PATCH.
        assertThat(customFieldSetEvents(subscriberId)).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchCustomFields_onlyUnknownKeys_200_noWriteNoAudit() throws Exception {
        // PATCH containing ONLY unknown keys → 200, keys silently ignored, no DB write, and
        // recordCustomFieldsSet NOT called (newValues empty).
        String projectId = saveProject(USER_ID, def("city", CustomFieldType.STRING)).getId();
        String subscriberId = saveSubscriber(projectId, 205L, new HashMap<>());

        mockMvc.perform(patch(url(projectId, subscriberId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"ghost\":\"x\",\"other_unknown\":\"y\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ghost").doesNotExist())
                .andExpect(jsonPath("$.other_unknown").doesNotExist());

        Subscriber reread = subscriberRepository.findById(subscriberId).orElseThrow();
        assertThat(reread.getCustomFields()).isEmpty();
        assertThat(customFieldSetEvents(subscriberId)).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchCustomFields_invalidType_returns422() throws Exception {
        String projectId = saveProject(USER_ID, def("age", CustomFieldType.NUMBER)).getId();
        String subscriberId = saveSubscriber(projectId, 201L, new HashMap<>());

        mockMvc.perform(patch(url(projectId, subscriberId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"age\":\"abc\"}}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("custom_field_type_mismatch"));

        // A rejected PATCH writes nothing.
        assertThat(customFieldSetEvents(subscriberId)).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchCustomFields_crossProjectSubscriber_returns404() throws Exception {
        String projectA = saveProject(USER_ID, def("city", CustomFieldType.STRING)).getId();
        String projectB = saveProject(USER_ID, def("city", CustomFieldType.STRING)).getId();
        String subscriberInA = saveSubscriber(projectA, 202L, new HashMap<>());

        mockMvc.perform(patch(url(projectB, subscriberInA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"city\":\"Kyiv\"}}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchCustomFields_foreignOwner_returns404() throws Exception {
        String foreign = saveProject(OTHER_USER_ID, def("city", CustomFieldType.STRING)).getId();
        String subscriberId = saveSubscriber(foreign, 203L, new HashMap<>());

        mockMvc.perform(patch(url(foreign, subscriberId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"city\":\"Kyiv\"}}"))
                .andExpect(status().isNotFound());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private CustomFieldDefinition def(String name, CustomFieldType type) {
        return new CustomFieldDefinition(name, name.toUpperCase(), type, null, Instant.now());
    }

    private Project saveProject(String ownerId, CustomFieldDefinition... defs) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName("Proj-" + System.nanoTime());
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p.setCustomFieldDefinitions(List.of(defs));
        return projectRepository.save(p);
    }

    private String saveSubscriber(String projectId, long telegramUserId, Map<String, Object> customFields) {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(telegramUserId);
        s.setTelegramChatId(telegramUserId);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setCustomFields(customFields);
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        return subscriberRepository.save(s).getId();
    }

    private List<SubscriberEvent> customFieldSetEvents(String subscriberId) {
        return subscriberEventRepository.findAll().stream()
                .filter(e -> "subscriber_custom_field_set".equals(e.getEventType()))
                .filter(e -> subscriberId.equals(e.getSubscriberId()))
                .toList();
    }

    private String url(String projectId, String subscriberId) {
        return "/api/v1/projects/" + projectId + "/subscribers/" + subscriberId + "/custom-fields";
    }
}
