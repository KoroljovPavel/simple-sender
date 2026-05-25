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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubscriberControllerIT extends AbstractIntegrationTest {

    private static final String USER_ID = "sub-it-user";
    private static final String OTHER_USER_ID = "sub-it-other";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberRepository subscriberRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String projectId;
    private long telegramUserSeq;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        subscriberRepository.deleteAll();
        telegramUserSeq = 1000L;

        seedUser(USER_ID, "owner@test.com");
        projectId = saveProject(USER_ID, null).getId();
    }

    // ─── happy paths ─────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_emptyProject_returnsEmptyArrayAndNullCursor() throws Exception {
        mockMvc.perform(get(url() + "?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_fullPage50_returnsNextCursor() throws Exception {
        seedSubscribers(60);
        JsonNode page = getPage("?limit=50");
        assertThat(page.get("items")).hasSize(50);
        assertThat(page.get("nextCursor").isNull()).isFalse();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_finalPage_returnsNullCursor() throws Exception {
        seedSubscribers(60);
        String cursor = getPage("?limit=50").get("nextCursor").asText();
        JsonNode page2 = getPage("?limit=50&cursor=" + cursor);
        assertThat(page2.get("items")).hasSize(10);
        assertThat(page2.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_textSearch_matchesFirstNameLastNameUsername() throws Exception {
        seedNamed("Ivanna", "Petrenko", "ivanna_p");
        seedNamed("Bohdan", "Koval", "bohdan_k");
        seedNamed("Olena", "Shevchenko", "olena_marketing");

        // firstName token
        JsonNode byFirst = getPage("?search=ivanna");
        assertThat(byFirst.get("items")).hasSize(1);
        assertThat(byFirst.get("items").get(0).get("firstName").asText()).isEqualTo("Ivanna");

        // lastName token (whole-word, language=none)
        JsonNode byLast = getPage("?search=koval");
        assertThat(byLast.get("items")).hasSize(1);
        assertThat(byLast.get("items").get(0).get("lastName").asText()).isEqualTo("Koval");

        // username token
        JsonNode byUsername = getPage("?search=olena_marketing");
        assertThat(byUsername.get("items")).hasSize(1);
        assertThat(byUsername.get("items").get(0).get("username").asText()).isEqualTo("olena_marketing");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_textSearchUnder2Chars_returns400() throws Exception {
        mockMvc.perform(get(url() + "?search=a"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_statusFilter_returnsOnlyMatching() throws Exception {
        seedSubscriber(s -> s.setStatus(SubscriberStatus.ACTIVE));
        seedSubscriber(s -> s.setStatus(SubscriberStatus.UNSUBSCRIBED));

        JsonNode page = getPage("?status=unsubscribed");
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("status").asText()).isEqualTo("unsubscribed");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_tagsInclude_andSemantics() throws Exception {
        seedSubscriber(s -> s.setTags(new ArrayList<>(List.of("vip", "gold"))));
        seedSubscriber(s -> s.setTags(new ArrayList<>(List.of("vip"))));

        JsonNode page = getPage("?tagsInclude=vip&tagsInclude=gold");
        assertThat(page.get("items")).hasSize(1);
        assertThat(toList(page.get("items").get(0).get("tags"))).containsExactlyInAnyOrder("vip", "gold");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_tagsExclude_notSemantics() throws Exception {
        seedSubscriber(s -> s.setTags(new ArrayList<>(List.of("vip"))));
        seedSubscriber(s -> s.setTags(new ArrayList<>(List.of("spam"))));

        JsonNode page = getPage("?tagsExclude=spam");
        assertThat(page.get("items")).hasSize(1);
        assertThat(toList(page.get("items").get(0).get("tags"))).containsExactly("vip");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_dateRange_subscribedAtBetween() throws Exception {
        seedSubscriber(s -> s.setSubscribedAt(Instant.parse("2026-01-05T00:00:00Z")));
        seedSubscriber(s -> s.setSubscribedAt(Instant.parse("2026-01-15T00:00:00Z")));
        seedSubscriber(s -> s.setSubscribedAt(Instant.parse("2026-01-25T00:00:00Z")));

        JsonNode page = getPage("?subscribedFrom=2026-01-10T00:00:00Z&subscribedTo=2026-01-20T00:00:00Z");
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("subscribedAt").asText()).startsWith("2026-01-15");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_cursorForward3Pages_noDuplicatesNoSkips() throws Exception {
        seedSubscribers(120);

        List<String> orderedIds = new ArrayList<>();
        List<String> orderedSubscribedAt = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            JsonNode page = getPage("?limit=50" + (cursor == null ? "" : "&cursor=" + cursor));
            page.get("items").forEach(n -> {
                orderedIds.add(n.get("id").asText());
                orderedSubscribedAt.add(n.get("subscribedAt").asText());
            });
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            guard++;
        } while (cursor != null && guard < 50);

        // No skips: every seeded subscriber appears exactly once (full-set coverage, no duplicates).
        Set<String> seededIds = new HashSet<>();
        subscriberRepository.findAll().forEach(s -> seededIds.add(s.getId()));
        assertThat(orderedIds).hasSize(120);
        assertThat(new HashSet<>(orderedIds)).isEqualTo(seededIds);
        // Ordering continuity: created_desc → subscribedAt is non-increasing across page boundaries.
        assertThat(orderedSubscribedAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void cursor_paginates_across_identical_sortValue() throws Exception {
        Instant sameLastSeen = Instant.parse("2026-04-01T10:00:00Z");
        for (int i = 0; i < 60; i++) {
            seedSubscriber(s -> s.setLastSeenAt(sameLastSeen));
        }
        // sort by last_seen_desc — all 60 share lastSeenAt, so only the _id tie-break separates pages.
        Set<String> seen = new HashSet<>();
        String query = "?limit=50&sort=last_seen_desc";
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = getPage(cursor == null ? query : query + "&cursor=" + cursor);
            page.get("items").forEach(n -> seen.add(n.get("id").asText()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(seen).hasSize(60);
    }

    // ─── cursor validation (security F6) ─────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void cursor_invalidShape_returns400() throws Exception {
        seedSubscribers(5);
        mockMvc.perform(get(url() + "?cursor=" + encode("{\"v\":{\"$ne\":null},\"id\":\"abc\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"))
                .andExpect(jsonPath("$.items").doesNotExist()); // zero rows leaked
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void cursor_unknownKeys_returns400() throws Exception {
        mockMvc.perform(get(url() + "?cursor=" + encode("{\"v\":1,\"id\":\"0123456789abcdef01234567\",\"x\":2}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void cursor_dollarPrefixedKeys_returns400() throws Exception {
        // Keys are exactly {v,id} with a VALID 24-hex id, so the unknown-key + id-regex checks pass —
        // ONLY the recursive $-key walk (assertNoDollarKeys) can reject the nested $ne operator. This
        // isolates the NoSQL operator-injection guard (F6) that cursor_unknownKeys does not exercise.
        mockMvc.perform(get(url() + "?cursor="
                        + encode("{\"v\":{\"$ne\":null},\"id\":\"0123456789abcdef01234567\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void cursor_idNot24CharLowercaseHex_returns400() throws Exception {
        mockMvc.perform(get(url() + "?cursor=" + encode("{\"v\":1,\"id\":\"XYZ\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void cursor_vNotPrimitive_returns400() throws Exception {
        mockMvc.perform(get(url() + "?cursor=" + encode("{\"v\":[1,2],\"id\":\"0123456789abcdef01234567\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
    }

    // ─── access guards (AC21) ─────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_foreignOwner_returns404() throws Exception {
        seedUser(OTHER_USER_ID, "other@test.com");
        String foreign = saveProject(OTHER_USER_ID, null).getId();
        mockMvc.perform(get("/api/v1/projects/" + foreign + "/subscribers"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_softDeletedProject_returns404() throws Exception {
        String deleted = saveProject(USER_ID, Instant.now()).getId();
        mockMvc.perform(get("/api/v1/projects/" + deleted + "/subscribers"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void list_malformedProjectId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/projects/not-a-valid-objectid/subscribers"))
                .andExpect(status().isNotFound());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String url() {
        return "/api/v1/projects/" + projectId + "/subscribers";
    }

    private JsonNode getPage(String queryString) throws Exception {
        MvcResult res = mockMvc.perform(get(url() + queryString))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> toList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
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

    // Distinct subscribedAt per row (base + i seconds) so created_desc has a strict order to paginate.
    private void seedSubscribers(int count) {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            Instant at = base.plusSeconds(i);
            seedSubscriber(s -> {
                s.setSubscribedAt(at);
                s.setLastSeenAt(at);
            });
        }
    }

    private void seedNamed(String first, String last, String username) {
        seedSubscriber(s -> {
            s.setFirstName(first);
            s.setLastName(last);
            s.setUsername(username);
        });
    }

    private void seedSubscriber(java.util.function.Consumer<Subscriber> customizer) {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(telegramUserSeq++);
        s.setTelegramChatId(s.getTelegramUserId());
        s.setTelegramBotId(9000L);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setTags(new ArrayList<>());
        s.setSubscribedAt(Instant.parse("2026-01-01T00:00:00Z"));
        s.setLastSeenAt(Instant.parse("2026-01-01T00:00:00Z"));
        customizer.accept(s);
        subscriberRepository.save(s);
    }
}
