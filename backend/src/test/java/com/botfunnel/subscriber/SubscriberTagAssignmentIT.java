package com.botfunnel.subscriber;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.common.test.ConcurrencyTestUtils;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.tag.Tag;
import com.botfunnel.tag.TagRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// HTTP coverage for the subscriber↔tag association endpoints (POST/DELETE /subscribers/{id}/tags),
// plus the Task-8 concurrency invariant (10 concurrent attaches of the same slug to the same
// subscriber → counter == 1). The concurrency case runs at the service layer because @WithMockAppUser
// only populates the SecurityContext on the test thread, so parallel MockMvc calls would be unauthorized.
class SubscriberTagAssignmentIT extends AbstractIntegrationTest {

    private static final String USER_ID = "tagassign-it-user";
    private static final String OTHER_USER_ID = "tagassign-it-other";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired SubscriberEventRepository subscriberEventRepository;
    @Autowired TagRepository tagRepository;
    @Autowired SubscriberService subscriberService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String projectId;
    private String subscriberId;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        subscriberRepository.deleteAll();
        subscriberEventRepository.deleteAll();
        tagRepository.deleteAll();

        seedUser(USER_ID, "ta@test.com");
        projectId = saveProject(USER_ID, null).getId();
        subscriberId = seedSubscriber(projectId, 600L, new ArrayList<>());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void addTag_happyPath_attachesBumpsCounterAndWritesEvent() throws Exception {
        mockMvc.perform(post(tagsUrl()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("slug", "vip"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0]").value("vip"));

        assertThat(subscriberRepository.findById(subscriberId).orElseThrow().getTags()).containsExactly("vip");
        assertThat(tagRepository.findByProjectIdAndSlug(projectId, "vip").orElseThrow().getSubscriberCount())
                .isEqualTo(1L);
        assertThat(eventsFor("subscriber_tag_added")).hasSize(1);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void addTag_idempotentReattach_doesNotDoubleCount() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(tagsUrl()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("slug", "vip"))))
                    .andExpect(status().isOk());
        }

        assertThat(subscriberRepository.findById(subscriberId).orElseThrow().getTags()).containsExactly("vip");
        assertThat(tagRepository.findByProjectIdAndSlug(projectId, "vip").orElseThrow().getSubscriberCount())
                .as("re-attach must not double-count")
                .isEqualTo(1L);
        assertThat(eventsFor("subscriber_tag_added")).hasSize(1);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void removeTag_detachesDecrementsCounterAndWritesEvent() throws Exception {
        seedSubscriberWithTags(601L, List.of("vip"));
        String subWithTag = subscriberRepository.findByProjectIdAndTelegramUserId(projectId, 601L)
                .orElseThrow().getId();
        seedTag("vip", 1L);

        mockMvc.perform(delete(tagsUrl(subWithTag) + "/vip").with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(subscriberRepository.findById(subWithTag).orElseThrow().getTags()).doesNotContain("vip");
        assertThat(tagRepository.findByProjectIdAndSlug(projectId, "vip").orElseThrow().getSubscriberCount())
                .isEqualTo(0L);
        assertThat(eventsFor("subscriber_tag_removed")).hasSize(1);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void addTag_invalidSlug_returns400() throws Exception {
        mockMvc.perform(post(tagsUrl()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("slug", "VIP"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void addTag_foreignProject_returns404() throws Exception {
        seedUser(OTHER_USER_ID, "o@test.com");
        String foreign = saveProject(OTHER_USER_ID, null).getId();
        String foreignSub = seedSubscriber(foreign, 1L, new ArrayList<>());

        mockMvc.perform(post("/api/v1/projects/" + foreign + "/subscribers/" + foreignSub + "/tags").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("slug", "vip"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void addTag_unknownSubscriber_returns404() throws Exception {
        mockMvc.perform(post(tagsUrl("0123456789abcdef01234567")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("slug", "vip"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void addSameSlug_concurrentlyToSameSubscriber_counterEqualsOne() {
        // Decision-9/edge-case invariant: 10 concurrent attaches of the same slug to the same
        // subscriber must leave the counter at 1 — $addToSet is atomic and the $inc is conditional on
        // a modifiedCount > 0. Runs at the service layer (the controller's actual entry-point logic).
        seedTag("vip", 0L);

        ConcurrencyTestUtils.parallelInvoke(10, () -> {
            subscriberService.addTag(projectId, subscriberId, "vip");
            return null;
        });

        assertThat(subscriberRepository.findById(subscriberId).orElseThrow().getTags()).containsExactly("vip");
        assertThat(tagRepository.findByProjectIdAndSlug(projectId, "vip").orElseThrow().getSubscriberCount())
                .as("atomic $addToSet + conditional $inc → exactly one increment under 10x contention")
                .isEqualTo(1L);
        assertThat(eventsFor("subscriber_tag_added")).hasSize(1);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String tagsUrl() {
        return tagsUrl(subscriberId);
    }

    private String tagsUrl(String subId) {
        return "/api/v1/projects/" + projectId + "/subscribers/" + subId + "/tags";
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private void seedUser(String id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setName("Owner");
        u.setPasswordHash("x");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
    }

    private Project saveProject(String ownerId, Instant deletedAt) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName("Proj-" + System.nanoTime());
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p.setDeletedAt(deletedAt);
        return projectRepository.save(p);
    }

    private String seedSubscriber(String project, long tgUser, List<String> tags) {
        Subscriber s = new Subscriber();
        s.setProjectId(project);
        s.setTelegramUserId(tgUser);
        s.setTelegramChatId(tgUser);
        s.setTelegramBotId(9000L);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setTags(new ArrayList<>(tags));
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        return subscriberRepository.save(s).getId();
    }

    private void seedSubscriberWithTags(long tgUser, List<String> tags) {
        seedSubscriber(projectId, tgUser, tags);
    }

    private void seedTag(String slug, long count) {
        Tag tag = new Tag();
        tag.setProjectId(projectId);
        tag.setSlug(slug);
        tag.setLabel(slug);
        tag.setSubscriberCount(count);
        tag.setCreatedAt(Instant.now());
        tagRepository.save(tag);
    }

    private List<SubscriberEvent> eventsFor(String type) {
        return subscriberEventRepository.findAll().stream()
                .filter(e -> type.equals(e.getEventType()))
                .toList();
    }
}
