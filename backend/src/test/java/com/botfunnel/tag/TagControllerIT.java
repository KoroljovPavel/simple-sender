package com.botfunnel.tag;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberRepository;
import com.botfunnel.subscriber.SubscriberStatus;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TagControllerIT extends AbstractIntegrationTest {

    private static final String USER_ID = "tag-it-user";
    private static final String OTHER_USER_ID = "tag-it-other-user";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired TagRepository tagRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired EventRepository eventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String ownedProjectId;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        tagRepository.deleteAll();
        subscriberRepository.deleteAll();
        eventRepository.deleteAll();

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("tags@test.com");
        u.setName("Tag Owner");
        u.setPasswordHash("not-used");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);

        ownedProjectId = saveProject(USER_ID, null).getId();
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

    private Tag seedTag(String projectId, String slug, long subscriberCount) {
        Tag tag = new Tag();
        tag.setProjectId(projectId);
        tag.setSlug(slug);
        tag.setLabel(slug.toUpperCase());
        tag.setSubscriberCount(subscriberCount);
        tag.setCreatedAt(Instant.now());
        return tagRepository.save(tag);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private String url(String projectId) {
        return "/api/v1/projects/" + projectId + "/tags";
    }

    // ─── POST ────────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_validSlugAndLabel_returns201AndPersisted() throws Exception {
        mockMvc.perform(post(url(ownedProjectId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("slug", "vip", "label", "VIP клієнти"))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.slug").value("vip"))
                .andExpect(jsonPath("$.label").value("VIP клієнти"))
                .andExpect(jsonPath("$.subscriberCount").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.projectId").doesNotExist());

        Tag persisted = tagRepository.findByProjectIdAndSlug(ownedProjectId, "vip").orElseThrow();
        assertThat(persisted.getProjectId()).isEqualTo(ownedProjectId);
        assertThat(persisted.getSubscriberCount()).isZero();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_duplicateSlug_returns409TagNameTaken() throws Exception {
        mockMvc.perform(post(url(ownedProjectId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("slug", "vip", "label", "VIP"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post(url(ownedProjectId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("slug", "vip", "label", "VIP 2"))))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.code").value("tag_name_taken"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_invalidSlugRegex_returns400() throws Exception {
        mockMvc.perform(post(url(ownedProjectId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("slug", "VIP", "label", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("slug")))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_hostileBodyWithOwnerId_ignoresMassAssignment() throws Exception {
        // Raw JSON so the hostile fields actually reach the wire (a typed DTO would never carry them).
        String hostile = "{\"slug\":\"vip\",\"label\":\"x\",\"ownerId\":\"" + OTHER_USER_ID
                + "\",\"projectId\":\"OTHER_PROJ\",\"subscriberCount\":9999}";

        mockMvc.perform(post(url(ownedProjectId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hostile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subscriberCount").value(0))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.projectId").doesNotExist());

        Tag persisted = tagRepository.findByProjectIdAndSlug(ownedProjectId, "vip").orElseThrow();
        assertThat(persisted.getProjectId())
                .as("projectId comes from requireOwned path, never the body")
                .isEqualTo(ownedProjectId);
        assertThat(persisted.getSubscriberCount())
                .as("subscriberCount always starts at 0, never from the body")
                .isZero();
        // No tag leaked into the hostile project id.
        assertThat(tagRepository.findByProjectIdAndSlug("OTHER_PROJ", "vip")).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_foreignProject_returns404() throws Exception {
        String foreign = saveProject(OTHER_USER_ID, null).getId();

        mockMvc.perform(post(url(foreign)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("slug", "vip", "label", "x"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void create_softDeletedProject_returns404() throws Exception {
        String deleted = saveProject(USER_ID, Instant.now()).getId();

        mockMvc.perform(post(url(deleted)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("slug", "vip", "label", "x"))))
                .andExpect(status().isNotFound());
    }

    // ─── GET list ──────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_emptyProject_returnsEmptyArray() throws Exception {
        mockMvc.perform(get(url(ownedProjectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_threeSeededTags_returnsAllSortedBySlug() throws Exception {
        seedTag(ownedProjectId, "zebra", 0L);
        seedTag(ownedProjectId, "alpha", 0L);
        seedTag(ownedProjectId, "mid", 0L);

        mockMvc.perform(get(url(ownedProjectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].slug").value("alpha"))
                .andExpect(jsonPath("$[1].slug").value("mid"))
                .andExpect(jsonPath("$[2].slug").value("zebra"))
                .andExpect(jsonPath("$[0].projectId").doesNotExist());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_foreignProject_returns404() throws Exception {
        String foreign = saveProject(OTHER_USER_ID, null).getId();

        mockMvc.perform(get(url(foreign)))
                .andExpect(status().isNotFound());
    }

    // ─── PATCH ───────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patch_labelOnly_updatesLabel() throws Exception {
        seedTag(ownedProjectId, "vip", 4L);
        // Mongo truncates Instant to millis on store; re-read the persisted value to compare apples
        // to apples and prove updateLabel leaves createdAt untouched.
        Instant storedCreatedAt = tagRepository.findByProjectIdAndSlug(ownedProjectId, "vip")
                .orElseThrow().getCreatedAt();

        mockMvc.perform(patch(url(ownedProjectId) + "/vip").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("label", "new label"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("vip"))
                .andExpect(jsonPath("$.label").value("new label"))
                .andExpect(jsonPath("$.subscriberCount").value(4));

        Tag reread = tagRepository.findByProjectIdAndSlug(ownedProjectId, "vip").orElseThrow();
        assertThat(reread.getLabel()).isEqualTo("new label");
        assertThat(reread.getSlug()).isEqualTo("vip");
        assertThat(reread.getSubscriberCount()).isEqualTo(4L);
        assertThat(reread.getCreatedAt()).isEqualTo(storedCreatedAt);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patch_unknownSlugInBody_silentlyDropped() throws Exception {
        seedTag(ownedProjectId, "vip", 0L);

        // Hostile attempt to mutate the immutable slug — @JsonIgnoreProperties silently drops it.
        mockMvc.perform(patch(url(ownedProjectId) + "/vip").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"newslug\",\"label\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("vip"));

        assertThat(tagRepository.findByProjectIdAndSlug(ownedProjectId, "vip")).isPresent();
        assertThat(tagRepository.findByProjectIdAndSlug(ownedProjectId, "newslug")).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patch_nonexistentTag_returns404() throws Exception {
        mockMvc.perform(patch(url(ownedProjectId) + "/ghost").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("label", "x"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patch_foreignProject_returns404() throws Exception {
        String foreign = saveProject(OTHER_USER_ID, null).getId();
        seedTag(foreign, "vip", 0L);

        mockMvc.perform(patch(url(foreign) + "/vip").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("label", "x"))))
                .andExpect(status().isNotFound());
    }

    // ─── DELETE ────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void delete_existingTagWithSubscribers_cascadesAndRemoves() throws Exception {
        seedTag(ownedProjectId, "vip", 10L);
        for (long i = 0; i < 10; i++) {
            seedSubscriber(ownedProjectId, 5000L + i, List.of("vip", "other"));
        }

        mockMvc.perform(delete(url(ownedProjectId) + "/vip").with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(tagRepository.findByProjectIdAndSlug(ownedProjectId, "vip")).isEmpty();
        assertThat(subscriberRepository.findAll())
                .hasSize(10)
                .allSatisfy(s -> assertThat(s.getTags()).containsExactly("other"));

        List<Event> tagDeleted = eventRepository.findAll().stream()
                .filter(e -> "tag_deleted".equals(e.getEventType()))
                .toList();
        assertThat(tagDeleted).hasSize(1);
        assertThat(tagDeleted.get(0).getUserId()).isEqualTo(USER_ID);
        assertThat(tagDeleted.get(0).getMetadata())
                .containsEntry("tagSlug", "vip")
                .containsEntry("projectId", ownedProjectId)
                .containsEntry("subscriberCountAtDelete", 10L);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void delete_nonexistentTag_returns204Idempotent() throws Exception {
        mockMvc.perform(delete(url(ownedProjectId) + "/ghost").with(csrf()))
                .andExpect(status().isNoContent());

        // No spurious audit event for a tag that never existed.
        assertThat(eventRepository.findAll())
                .noneMatch(e -> "tag_deleted".equals(e.getEventType()));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void delete_foreignProject_returns404() throws Exception {
        String foreign = saveProject(OTHER_USER_ID, null).getId();
        seedTag(foreign, "vip", 0L);

        mockMvc.perform(delete(url(foreign) + "/vip").with(csrf()))
                .andExpect(status().isNotFound());
    }

    private void seedSubscriber(String projectId, long telegramUserId, List<String> tags) {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(telegramUserId);
        s.setTelegramChatId(telegramUserId);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setTags(new ArrayList<>(tags));
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        subscriberRepository.save(s);
    }
}
