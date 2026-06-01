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

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("scf@test.com");
        u.setName("SCF Owner");
        u.setPasswordHash("not-used");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchCustomFields_behaviourUnchanged() throws Exception {
        // Two known fields + one unknown key in a single PATCH. Expected: 200, both known values
        // applied & echoed, unknown key dropped, and ONE aggregated audit event covering both keys.
        String projectId = saveProject(USER_ID,
                def("city", CustomFieldType.STRING), def("age", CustomFieldType.NUMBER)).getId();
        String subscriberId = saveSubscriber(projectId, 200L, new HashMap<>());

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

        // Aggregated audit invariant: ONE event for the whole PATCH, both changed keys in it.
        List<SubscriberEvent> events = customFieldSetEvents(subscriberId);
        assertThat(events).hasSize(1);
        @SuppressWarnings("unchecked")
        List<String> changedKeys = (List<String>) events.get(0).getMetadata().get("changedKeys");
        assertThat(changedKeys).containsExactlyInAnyOrder("city", "age");
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
