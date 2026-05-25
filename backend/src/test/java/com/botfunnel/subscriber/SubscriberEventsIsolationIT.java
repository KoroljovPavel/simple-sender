package com.botfunnel.subscriber;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.webhook.ProcessTelegramUpdateJob;
import com.botfunnel.webhook.RawUpdate;
import com.botfunnel.webhook.RawUpdateRepository;
import com.botfunnel.webhook.RawUpdateStatus;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Decision 10 dual-write invariant: CRM lifecycle events land ONLY in subscriber_events, while the
// platform events collection still gets the webhook telegram_command_* row — and never a duplicate
// subscriber_* row.
class SubscriberEventsIsolationIT extends AbstractIntegrationTest {

    private static final String OWNER_ID = "owner-isolation";
    private static final Long TELEGRAM_BOT_ID = 9000001L;
    private static final Long CHAT_ID = 300L;

    @Autowired RawUpdateRepository rawUpdateRepository;
    @Autowired BotRepository botRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired SubscriberEventRepository subscriberEventRepository;
    @Autowired EventRepository eventRepository;
    @Autowired ProcessTelegramUpdateJob job;

    private String projectId;

    @BeforeEach
    void cleanAndSeed() {
        rawUpdateRepository.deleteAll();
        botRepository.deleteAll();
        projectRepository.deleteAll();
        subscriberRepository.deleteAll();
        subscriberEventRepository.deleteAll();
        eventRepository.deleteAll();

        Project p = new Project();
        p.setOwnerId(OWNER_ID);
        p.setName("Isolation Project");
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
    void registerWritesOnlyToSubscriberEvents() {
        job.handle(seed("/start", 1L));

        assertThat(subscriberEventsOfType("subscriber_registered")).hasSize(1);
        assertThat(platformEventsOfType("telegram_command_start")).hasSize(1);
        assertThat(platformEventsWithSubscriberPrefix())
                .as("platform events must NOT carry any subscriber_* lifecycle row")
                .isEmpty();
    }

    @Test
    void unsubscribeWritesOnlyToSubscriberEvents() {
        job.handle(seed("/start", 1L));
        job.handle(seed("/stop", 2L));

        assertThat(subscriberEventsOfType("subscriber_unsubscribed")).hasSize(1);
        assertThat(platformEventsOfType("telegram_command_stop")).hasSize(1);
        assertThat(platformEventsWithSubscriberPrefix())
                .as("platform events must NOT carry any subscriber_* lifecycle row")
                .isEmpty();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String seed(String text, long updateId) {
        RawUpdate raw = new RawUpdate();
        raw.setProjectId(projectId);
        raw.setUpdateId(updateId);
        raw.setPayload(payload(text));
        raw.setProcessingStatus(RawUpdateStatus.PENDING);
        raw.setCreatedAt(Instant.now());
        return rawUpdateRepository.save(raw).getId();
    }

    private Document payload(String text) {
        Document chat = new Document().append("id", CHAT_ID).append("type", "private");
        Document message = new Document()
                .append("message_id", 1L)
                .append("chat", chat)
                .append("date", 1700000000L)
                .append("text", text)
                .append("from", new Document()
                        .append("id", CHAT_ID)
                        .append("is_bot", false)
                        .append("first_name", "Test")
                        .append("language_code", "en"));
        return new Document().append("update_id", 1L).append("message", message);
    }

    private List<SubscriberEvent> subscriberEventsOfType(String type) {
        return subscriberEventRepository.findAll().stream()
                .filter(e -> type.equals(e.getEventType()))
                .toList();
    }

    private List<Event> platformEventsOfType(String type) {
        return eventRepository.findAll().stream()
                .filter(e -> type.equals(e.getEventType()))
                .toList();
    }

    private List<Event> platformEventsWithSubscriberPrefix() {
        return eventRepository.findAll().stream()
                .filter(e -> e.getEventType() != null && e.getEventType().startsWith("subscriber_"))
                .toList();
    }
}
