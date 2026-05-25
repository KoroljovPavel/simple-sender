package com.botfunnel.project;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberRepository;
import com.botfunnel.subscriber.SubscriberStatus;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.botfunnel.common.test.ConcurrencyTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomFieldDefinitionIT extends AbstractIntegrationTest {

    private static final String USER_ID = "cf-it-user";
    private static final String OTHER_USER_ID = "cf-it-other-user";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberRepository subscriberRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String ownedProjectId;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        subscriberRepository.deleteAll();

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("cf@test.com");
        u.setName("CF Owner");
        u.setPasswordHash("not-used");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);

        ownedProjectId = saveProject(USER_ID, null).getId();
    }

    // ─── CREATE ────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_happyPath_returns201_andPersistsDefinition() throws Exception {
        mockMvc.perform(post(url(ownedProjectId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "city", "label", "Місто",
                                "type", "STRING", "defaultValue", "Kyiv"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("city"))
                .andExpect(jsonPath("$.label").value("Місто"))
                .andExpect(jsonPath("$.type").value("STRING"))
                .andExpect(jsonPath("$.defaultValue").value("Kyiv"))
                .andExpect(jsonPath("$.createdAt").exists());

        Project p = projectRepository.findById(ownedProjectId).orElseThrow();
        assertThat(p.getCustomFieldDefinitions()).hasSize(1);
        assertThat(p.getCustomFieldDefinitions().get(0).name()).isEqualTo("city");
        assertThat(p.getCustomFieldDefinitions().get(0).type()).isEqualTo(CustomFieldType.STRING);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_duplicateName_returns409_customFieldNameTaken() throws Exception {
        createField("city", "STRING");

        mockMvc.perform(post(url(ownedProjectId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "city", "label", "Other", "type", "STRING"))))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.code").value("custom_field_name_taken"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_invalidSlug_returns400() throws Exception {
        mockMvc.perform(post(url(ownedProjectId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "BAD SLUG", "label", "x", "type", "STRING"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_at20Cap_21stPostReturns422_customFieldLimitReached() throws Exception {
        for (int i = 0; i < 20; i++) {
            createField("cf" + i, "STRING");
        }

        mockMvc.perform(post(url(ownedProjectId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "cf20", "label", "L", "type", "STRING"))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("custom_field_limit_reached"));

        assertThat(projectRepository.findById(ownedProjectId).orElseThrow()
                .getCustomFieldDefinitions()).hasSize(20);
    }

    @Test
    void parallel21Posts_acceptsOnly20() {
        AtomicInteger idx = new AtomicInteger(0);
        List<Integer> statuses = ConcurrencyTestUtils.parallelInvoke(21, () -> {
            int i = idx.getAndIncrement();
            return mockMvc.perform(post(url(ownedProjectId))
                            .with(authUser(USER_ID)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("name", "f" + i, "label", "F" + i, "type", "STRING"))))
                    .andReturn().getResponse().getStatus();
        });

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(20L);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(1L);
        assertThat(projectRepository.findById(ownedProjectId).orElseThrow()
                .getCustomFieldDefinitions()).hasSize(20);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_typeMismatchedDefault_returns422_customFieldTypeMismatch() throws Exception {
        // STRING vs numeric, NUMBER vs object, DATE vs free text, BOOLEAN vs arbitrary string.
        assertTypeMismatch(Map.of("name", "a1", "label", "L", "type", "STRING", "defaultValue", 42));
        assertTypeMismatch(mapWith("name", "a2", "label", "L", "type", "NUMBER",
                "defaultValue", Map.of("nested", "obj")));
        assertTypeMismatch(Map.of("name", "a3", "label", "L", "type", "DATE", "defaultValue", "tomorrow"));
        assertTypeMismatch(Map.of("name", "a4", "label", "L", "type", "BOOLEAN", "defaultValue", "maybe"));
    }

    // ─── UPDATE ──────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void update_labelOnly_returns200_andPersists() throws Exception {
        seedDefinition(new CustomFieldDefinition("city", "City", CustomFieldType.STRING, null, Instant.now()));

        mockMvc.perform(patch(url(ownedProjectId) + "/city").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("label", "Місто"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("city"))
                .andExpect(jsonPath("$.label").value("Місто"))
                .andExpect(jsonPath("$.type").value("STRING"));

        CustomFieldDefinition reread = findDef(ownedProjectId, "city");
        assertThat(reread.label()).isEqualTo("Місто");
        assertThat(reread.name()).isEqualTo("city");
        assertThat(reread.type()).isEqualTo(CustomFieldType.STRING);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void update_attemptToChangeType_silentlyIgnored_existingTypeUnchanged() throws Exception {
        seedDefinition(new CustomFieldDefinition("city", "City", CustomFieldType.STRING, null, Instant.now()));

        // Raw body carries a stray "type" — @JsonIgnoreProperties drops it silently.
        mockMvc.perform(patch(url(ownedProjectId) + "/city").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"x\",\"type\":\"NUMBER\"}"))
                .andExpect(status().isOk());

        assertThat(findDef(ownedProjectId, "city").type()).isEqualTo(CustomFieldType.STRING);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void update_attemptToChangeName_silentlyIgnored_existingNameUnchanged() throws Exception {
        seedDefinition(new CustomFieldDefinition("city", "City", CustomFieldType.STRING, null, Instant.now()));

        mockMvc.perform(patch(url(ownedProjectId) + "/city").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"x\",\"name\":\"renamed\"}"))
                .andExpect(status().isOk());

        Project p = projectRepository.findById(ownedProjectId).orElseThrow();
        assertThat(p.getCustomFieldDefinitions()).hasSize(1);
        assertThat(p.getCustomFieldDefinitions().get(0).name()).isEqualTo("city");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void update_defaultValueWithTypeMismatch_returns422_customFieldTypeMismatch() throws Exception {
        seedDefinition(new CustomFieldDefinition("age", "Age", CustomFieldType.NUMBER, null, Instant.now()));

        mockMvc.perform(patch(url(ownedProjectId) + "/age").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(mapWith("defaultValue", "abc"))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("custom_field_type_mismatch"));
    }

    // ─── DELETE ────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void delete_removesDefinition_andCascadesValuesFromSubscribers() throws Exception {
        seedDefinition(new CustomFieldDefinition("city", "City", CustomFieldType.STRING, null, Instant.now()));
        for (long i = 0; i < 12; i++) {
            seedSubscriberWithCity(ownedProjectId, 7000L + i, "Kyiv");
        }

        mockMvc.perform(delete(url(ownedProjectId) + "/city").with(csrf()))
                .andExpect(status().isNoContent());

        Project p = projectRepository.findById(ownedProjectId).orElseThrow();
        assertThat(p.getCustomFieldDefinitions()).isEmpty();
        assertThat(subscriberRepository.findAll())
                .hasSize(12)
                .allSatisfy(s -> assertThat(s.getCustomFields()).doesNotContainKey("city"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void delete_notFound_returns404() throws Exception {
        mockMvc.perform(delete(url(ownedProjectId) + "/ghost").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ─── Access guards (AC21 requireOwned parity) ──────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void requestFromForeignOwner_returns404() throws Exception {
        String foreign = saveProject(OTHER_USER_ID, null).getId();
        mockMvc.perform(get(url(foreign))).andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void requestForSoftDeletedProject_returns404() throws Exception {
        String deleted = saveProject(USER_ID, Instant.now()).getId();
        mockMvc.perform(get(url(deleted))).andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void malformedProjectId_returns404() throws Exception {
        mockMvc.perform(get(url("not-an-objectid"))).andExpect(status().isNotFound());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void assertTypeMismatch(Map<String, Object> body) throws Exception {
        mockMvc.perform(post(url(ownedProjectId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("custom_field_type_mismatch"));
    }

    private void createField(String name, String type) throws Exception {
        mockMvc.perform(post(url(ownedProjectId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "label", name.toUpperCase(), "type", type))))
                .andExpect(status().isCreated());
    }

    private void seedDefinition(CustomFieldDefinition def) {
        Project p = projectRepository.findById(ownedProjectId).orElseThrow();
        List<CustomFieldDefinition> defs = p.getCustomFieldDefinitions() == null
                ? new ArrayList<>() : new ArrayList<>(p.getCustomFieldDefinitions());
        defs.add(def);
        p.setCustomFieldDefinitions(defs);
        projectRepository.save(p);
    }

    private CustomFieldDefinition findDef(String projectId, String name) {
        return projectRepository.findById(projectId).orElseThrow()
                .getCustomFieldDefinitions().stream()
                .filter(d -> d.name().equals(name))
                .findFirst().orElseThrow();
    }

    private void seedSubscriberWithCity(String projectId, long telegramUserId, String city) {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(telegramUserId);
        s.setTelegramChatId(telegramUserId);
        s.setStatus(SubscriberStatus.ACTIVE);
        Map<String, Object> cf = new HashMap<>();
        cf.put("city", city);
        s.setCustomFields(cf);
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        subscriberRepository.save(s);
    }

    private Project saveProject(String ownerId, Instant deletedAt) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName("Proj-" + System.nanoTime());
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p.setDeletedAt(deletedAt);
        return projectRepository.save(p);
    }

    private String url(String projectId) {
        return "/api/v1/projects/" + projectId + "/custom-fields";
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    // Map.of rejects null values; this helper preserves explicit nulls for PATCH bodies.
    private static Map<String, Object> mapWith(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static RequestPostProcessor authUser(String userId) {
        AppUserDetails principal = new AppUserDetails(userId, "cf@test.com", "CF Owner", "active");
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
    }
}
