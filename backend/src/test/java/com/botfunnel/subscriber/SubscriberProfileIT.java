package com.botfunnel.subscriber;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubscriberProfileIT extends AbstractIntegrationTest {

    private static final String USER_ID = "profile-it-user";
    private static final String OTHER_USER_ID = "profile-it-other";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired SubscriberEventRepository subscriberEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String projectId;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        subscriberRepository.deleteAll();
        subscriberEventRepository.deleteAll();

        seedUser(USER_ID, "p@test.com");
        projectId = saveProject(USER_ID, null).getId();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void get_existing_returnsIdentityTagsCustomFieldsStatusDates() throws Exception {
        Map<String, Object> cf = new LinkedHashMap<>();
        cf.put("city", "Київ");
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(555L);
        s.setTelegramChatId(555L);
        s.setTelegramBotId(9000L);
        s.setFirstName("Ivanna");
        s.setUsername("ivanna_p");
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setTags(new ArrayList<>(List.of("vip")));
        s.setCustomFields(cf);
        s.setSubscribedAt(Instant.parse("2026-01-01T00:00:00Z"));
        s.setLastSeenAt(Instant.parse("2026-02-01T00:00:00Z"));
        String id = subscriberRepository.save(s).getId();

        mockMvc.perform(get(url() + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.telegramUserId").value(555))
                .andExpect(jsonPath("$.firstName").value("Ivanna"))
                .andExpect(jsonPath("$.username").value("ivanna_p"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.tags[0]").value("vip"))
                .andExpect(jsonPath("$.customFields.city").value("Київ"))
                .andExpect(jsonPath("$.subscribedAt").exists())
                .andExpect(jsonPath("$.lastSeenAt").exists());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void get_foreignOwner_returns404() throws Exception {
        seedUser(OTHER_USER_ID, "o@test.com");
        String foreign = saveProject(OTHER_USER_ID, null).getId();
        String id = seedSubscriber(foreign, 1L);

        mockMvc.perform(get("/api/v1/projects/" + foreign + "/subscribers/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void get_unknownSubscriberId_returns404() throws Exception {
        mockMvc.perform(get(url() + "/0123456789abcdef01234567"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void events_returnsLast50SortedDesc() throws Exception {
        String subId = seedSubscriber(projectId, 700L);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 60; i++) {
            seedEvent(subId, "subscriber_tag_added", base.plusSeconds(i));
        }

        MvcResult res = mockMvc.perform(get(url() + "/" + subId + "/events?limit=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(50))
                .andReturn();

        // newest first: createdAt must be non-increasing across the feed (ISO-8601 sorts lexically).
        JsonNode feed = objectMapper.readTree(res.getResponse().getContentAsString());
        List<String> createdAt = new ArrayList<>();
        feed.forEach(n -> createdAt.add(n.get("createdAt").asText()));
        assertThat(createdAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // The latest seeded event (base + 59s) is the newest → first row.
        assertThat(createdAt.get(0)).startsWith("2026-01-01T00:00:59");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void events_limitMax200_clamped() throws Exception {
        String subId = seedSubscriber(projectId, 800L);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 201; i++) {
            seedEvent(subId, "subscriber_tag_added", base.plusSeconds(i));
        }

        mockMvc.perform(get(url() + "/" + subId + "/events?limit=500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(200));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String url() {
        return "/api/v1/projects/" + projectId + "/subscribers";
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

    private String seedSubscriber(String project, long tgUser) {
        Subscriber s = new Subscriber();
        s.setProjectId(project);
        s.setTelegramUserId(tgUser);
        s.setTelegramChatId(tgUser);
        s.setTelegramBotId(9000L);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setTags(new ArrayList<>());
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        return subscriberRepository.save(s).getId();
    }

    private void seedEvent(String subscriberId, String type, Instant createdAt) {
        SubscriberEvent e = new SubscriberEvent();
        e.setSubscriberId(subscriberId);
        e.setProjectId(projectId);
        e.setEventType(type);
        e.setMetadata(Map.of());
        e.setCreatedAt(createdAt);
        subscriberEventRepository.save(e);
    }
}
