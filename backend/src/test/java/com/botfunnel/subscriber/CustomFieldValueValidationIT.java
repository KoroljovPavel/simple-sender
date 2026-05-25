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

class CustomFieldValueValidationIT extends AbstractIntegrationTest {

    private static final String USER_ID = "cfv-it-user";
    private static final String OTHER_USER_ID = "cfv-it-other-user";

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
        u.setEmail("cfv@test.com");
        u.setName("CFV Owner");
        u.setPasswordHash("not-used");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patch_validValues_persistsNormalized_andEmitsEvent() throws Exception {
        String projectId = saveProject(USER_ID, null,
                def("city", CustomFieldType.STRING), def("age", CustomFieldType.NUMBER)).getId();
        String subscriberId = saveSubscriber(projectId, 100L, new HashMap<>());

        mockMvc.perform(patch(url(projectId, subscriberId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(map("city", "  Kyiv  ", "age", "42"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Kyiv"))
                .andExpect(jsonPath("$.age").value(42.0));

        Subscriber reread = subscriberRepository.findById(subscriberId).orElseThrow();
        assertThat(reread.getCustomFields()).containsEntry("city", "Kyiv").containsEntry("age", 42.0d);

        List<SubscriberEvent> events = customFieldSetEvents(subscriberId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getProjectId()).isEqualTo(projectId);
        @SuppressWarnings("unchecked")
        List<String> changedKeys = (List<String>) events.get(0).getMetadata().get("changedKeys");
        assertThat(changedKeys).containsExactlyInAnyOrder("city", "age");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patch_unknownKeys_silentlyDropped_noError_noPersist() throws Exception {
        String projectId = saveProject(USER_ID, null, def("city", CustomFieldType.STRING)).getId();
        String subscriberId = saveSubscriber(projectId, 101L, new HashMap<>());

        mockMvc.perform(patch(url(projectId, subscriberId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"city\":\"Kyiv\",\"evil_unknown_key\":\"x\",\"another_unknown\":42}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Kyiv"))
                .andExpect(jsonPath("$.evil_unknown_key").doesNotExist())
                .andExpect(jsonPath("$.another_unknown").doesNotExist());

        Subscriber reread = subscriberRepository.findById(subscriberId).orElseThrow();
        assertThat(reread.getCustomFields()).containsOnlyKeys("city");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patch_allKeysUnknown_returns200_noPersist_noEvent() throws Exception {
        String projectId = saveProject(USER_ID, null, def("city", CustomFieldType.STRING)).getId();
        String subscriberId = saveSubscriber(projectId, 102L, new HashMap<>());

        mockMvc.perform(patch(url(projectId, subscriberId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"only_unknown\":\"x\"}}"))
                .andExpect(status().isOk());

        assertThat(subscriberRepository.findById(subscriberId).orElseThrow().getCustomFields()).isEmpty();
        assertThat(customFieldSetEvents(subscriberId)).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patch_perTypeValidation_returns422_customFieldTypeMismatch() throws Exception {
        String projectId = saveProject(USER_ID, null, def("age", CustomFieldType.NUMBER)).getId();
        String subscriberId = saveSubscriber(projectId, 103L, new HashMap<>());

        mockMvc.perform(patch(url(projectId, subscriberId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(map("age", "abc"))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("custom_field_type_mismatch"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patch_clearsValue_whenValueIsNull() throws Exception {
        String projectId = saveProject(USER_ID, null, def("city", CustomFieldType.STRING)).getId();
        Map<String, Object> seeded = new HashMap<>();
        seeded.put("city", "Kyiv");
        String subscriberId = saveSubscriber(projectId, 104L, seeded);

        Map<String, Object> values = new HashMap<>();
        values.put("city", null);
        mockMvc.perform(patch(url(projectId, subscriberId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(values)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").doesNotExist());

        assertThat(subscriberRepository.findById(subscriberId).orElseThrow()
                .getCustomFields()).doesNotContainKey("city");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patch_crossProjectSubscriber_returns404() throws Exception {
        String projectA = saveProject(USER_ID, null, def("city", CustomFieldType.STRING)).getId();
        String projectB = saveProject(USER_ID, null, def("city", CustomFieldType.STRING)).getId();
        String subscriberInA = saveSubscriber(projectA, 105L, new HashMap<>());

        // Subscriber belongs to A; PATCH against B (also owned) must 404 on the projectId scope.
        mockMvc.perform(patch(url(projectB, subscriberInA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(map("city", "Kyiv"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patch_foreignOwner_returns404() throws Exception {
        String foreign = saveProject(OTHER_USER_ID, null, def("city", CustomFieldType.STRING)).getId();
        String subscriberId = saveSubscriber(foreign, 106L, new HashMap<>());

        mockMvc.perform(patch(url(foreign, subscriberId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(map("city", "Kyiv"))))
                .andExpect(status().isNotFound());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private CustomFieldDefinition def(String name, CustomFieldType type) {
        return new CustomFieldDefinition(name, name.toUpperCase(), type, null, Instant.now());
    }

    private Project saveProject(String ownerId, Instant deletedAt, CustomFieldDefinition... defs) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName("Proj-" + System.nanoTime());
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p.setDeletedAt(deletedAt);
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

    private String body(Map<String, Object> values) throws Exception {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("values", values);
        return objectMapper.writeValueAsString(envelope);
    }

    // Map.of rejects null values; this preserves explicit nulls.
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
