package com.botfunnel.subscriber;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.botfunnel.webhook.ProcessTelegramUpdateJob;
import com.botfunnel.webhook.RawUpdate;
import com.botfunnel.webhook.RawUpdateRepository;
import com.botfunnel.webhook.RawUpdateStatus;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubscriberManualUnsubscribeIT extends AbstractIntegrationTest {

    private static final String USER_ID = "unsub-it-user";
    private static final Long TELEGRAM_BOT_ID = 7777001L;

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired SubscriberEventRepository subscriberEventRepository;
    @Autowired BotRepository botRepository;
    @Autowired RawUpdateRepository rawUpdateRepository;
    @Autowired ProcessTelegramUpdateJob job;

    private String projectId;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        subscriberRepository.deleteAll();
        subscriberEventRepository.deleteAll();
        botRepository.deleteAll();
        rawUpdateRepository.deleteAll();

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("unsub@test.com");
        u.setName("Owner");
        u.setPasswordHash("x");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);

        Project p = new Project();
        p.setOwnerId(USER_ID);
        p.setName("Proj-" + System.nanoTime());
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectId = projectRepository.save(p).getId();

        Bot bot = new Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setStatus(BotStatus.CONNECTED);
        bot.setConnectedAt(Instant.now());
        botRepository.save(bot);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void unsubscribe_active_flipsToUnsubscribedAndWritesEvent() throws Exception {
        String id = seedSubscriber(300L, SubscriberStatus.ACTIVE);

        mockMvc.perform(post(url() + "/" + id + "/unsubscribe").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsubscribed"));

        Subscriber reread = subscriberRepository.findById(id).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(SubscriberStatus.UNSUBSCRIBED);
        assertThat(reread.getUnsubscribedAt()).isNotNull();

        List<SubscriberEvent> events = eventsFor(id, "subscriber_unsubscribed");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getMetadata()).containsEntry("reason", "manual");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void unsubscribe_alreadyUnsubscribed_returns409AlreadyUnsubscribed() throws Exception {
        String id = seedSubscriber(301L, SubscriberStatus.UNSUBSCRIBED);

        mockMvc.perform(post(url() + "/" + id + "/unsubscribe").with(csrf()))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.code").value("already_unsubscribed"));

        // No duplicate lifecycle event written for the no-op.
        assertThat(eventsFor(id, "subscriber_unsubscribed")).isEmpty();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void unsubscribe_thenReactivationViaStartCommand_statusFlipsBackActive() throws Exception {
        String id = seedSubscriber(302L, SubscriberStatus.ACTIVE);

        mockMvc.perform(post(url() + "/" + id + "/unsubscribe").with(csrf()))
                .andExpect(status().isOk());
        assertThat(subscriberRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(SubscriberStatus.UNSUBSCRIBED);

        // A fresh /start from the same Telegram user reactivates via the webhook worker (Task 3 flow).
        job.handle(seedStart(302L));

        Subscriber reread = subscriberRepository.findById(id).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(SubscriberStatus.ACTIVE);
        assertThat(eventsFor(id, "subscriber_reactivated")).hasSize(1);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String url() {
        return "/api/v1/projects/" + projectId + "/subscribers";
    }

    private String seedSubscriber(long tgUser, SubscriberStatus status) {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(tgUser);
        s.setTelegramChatId(tgUser);
        s.setTelegramBotId(TELEGRAM_BOT_ID);
        s.setStatus(status);
        s.setTags(new ArrayList<>());
        s.setSubscribedAt(Instant.parse("2026-01-01T00:00:00Z"));
        s.setLastSeenAt(Instant.parse("2026-01-01T00:00:00Z"));
        if (status == SubscriberStatus.UNSUBSCRIBED) {
            s.setUnsubscribedAt(Instant.parse("2026-02-01T00:00:00Z"));
        }
        return subscriberRepository.save(s).getId();
    }

    private String seedStart(long fromId) {
        Document chat = new Document().append("id", fromId).append("type", "private");
        Document message = new Document()
                .append("message_id", 1L)
                .append("chat", chat)
                .append("date", 1700000000L)
                .append("text", "/start")
                .append("from", new Document()
                        .append("id", fromId)
                        .append("is_bot", false)
                        .append("first_name", "Test")
                        .append("username", "testuser")
                        .append("language_code", "en"));
        RawUpdate raw = new RawUpdate();
        raw.setProjectId(projectId);
        raw.setUpdateId(fromId);
        raw.setPayload(new Document().append("update_id", fromId).append("message", message));
        raw.setProcessingStatus(RawUpdateStatus.PENDING);
        raw.setCreatedAt(Instant.now());
        return rawUpdateRepository.save(raw).getId();
    }

    private List<SubscriberEvent> eventsFor(String subscriberId, String type) {
        return subscriberEventRepository.findAll().stream()
                .filter(e -> subscriberId.equals(e.getSubscriberId()) && type.equals(e.getEventType()))
                .toList();
    }
}
