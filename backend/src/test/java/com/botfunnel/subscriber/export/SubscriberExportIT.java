package com.botfunnel.subscriber.export;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberRepository;
import com.botfunnel.subscriber.SubscriberStatus;
import com.botfunnel.subscriber.jobs.ExportSubscribersJob;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubscriberExportIT extends AbstractIntegrationTest {

    private static final String USER_ID = "exp-it-user";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired SubscriberExportRepository exportRepository;
    @Autowired EventRepository eventRepository;
    @Autowired GridFsOperations gridFsOperations;
    @Autowired ExportSubscribersJob exportJob;
    @MockitoSpyBean MongoTemplate mongoTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String projectId;
    private long telegramUserSeq;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        subscriberRepository.deleteAll();
        eventRepository.deleteAll();
        mongoTemplate.remove(new Query(), SubscriberExport.class);
        gridFsOperations.delete(new Query());
        telegramUserSeq = 5000L;

        seedUser();
        projectId = saveProject(USER_ID).getId();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void happyPath_donePersistedFileEmailEvent() throws Exception {
        seedSubscribers(3);
        String exportId = postExport();

        exportJob.handle(exportId);

        SubscriberExport export = exportRepository.findById(exportId).orElseThrow();
        assertThat(export.getStatus()).isEqualTo(ExportStatus.DONE);
        assertThat(export.getRowCount()).isEqualTo(3L);
        assertThat(export.getFileId()).isNotBlank();
        assertThat(export.getExpiresAt()).isCloseTo(
                export.getCompletedAt().plus(Duration.ofHours(24)), within10s());

        // F10 integrity probe: GridFS metadata carries exportId + projectId at store() time and the
        // rowCount back-filled after the writer thread closes.
        GridFSFile file = gridFsOperations.findOne(
                Query.query(Criteria.where("metadata.exportId").is(exportId)));
        assertThat(file).isNotNull();
        Document metadata = file.getMetadata();
        assertThat(metadata.getString("exportId")).isEqualTo(exportId);
        assertThat(metadata.getString("projectId")).isEqualTo(projectId);
        assertThat(((Number) metadata.get("rowCount")).longValue()).isEqualTo(3L);

        // Email captured by Mailpit with the signed URL + expiry in the body.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> MAILPIT.getClient().getMessageCount() >= 1);
        String html = MAILPIT.getClient().getMessageHtml(MAILPIT.getClient().getAllMessages().get(0).id());
        assertThat(html).contains("download?token=").contains(exportId);

        assertThat(eventByType("subscribers_export_completed")).isNotNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void exportJob_internalError_writesFailedAndEmail() throws Exception {
        seedSubscribers(2);
        String exportId = postExport();

        // Inject a streaming failure mid-export carrying a Telegram-token-shaped fragment so the
        // scrub-on-failure path (F8) is exercised.
        String rawTokenMessage = "stream blew up for 123456789:AAHabcdefghijklmnopqrstuvwxyz012345 oops";
        doThrow(new RuntimeException(rawTokenMessage))
                .when(mongoTemplate).stream(any(Query.class), eq(Subscriber.class));

        assertThatThrownBy(() -> exportJob.handle(exportId)).isInstanceOf(RuntimeException.class);

        SubscriberExport export = exportRepository.findById(exportId).orElseThrow();
        assertThat(export.getStatus()).isEqualTo(ExportStatus.FAILED);
        assertThat(export.getErrorMessage())
                .contains("[REDACTED_TOKEN]")
                .doesNotContain("AAHabcdefghijklmnopqrstuvwxyz");
        assertThat(export.getErrorMessage().length()).isLessThanOrEqualTo(1024);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> MAILPIT.getClient().getMessageCount() >= 1);
        assertThat(eventByType("subscribers_export_failed")).isNotNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void emptyFilter_zeroSubscribers_producesValidHeaderOnly() throws Exception {
        String exportId = postExport();

        exportJob.handle(exportId);

        SubscriberExport export = exportRepository.findById(exportId).orElseThrow();
        assertThat(export.getStatus()).isEqualTo(ExportStatus.DONE);
        assertThat(export.getRowCount()).isZero();

        GridFSFile file = gridFsOperations.findOne(
                Query.query(Criteria.where("metadata.exportId").is(exportId)));
        byte[] bytes = gridFsOperations.getResource(file).getInputStream().readAllBytes();
        assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
        assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
        assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);
        String body = new String(bytes, 3, bytes.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = body.split("\n", -1);
        assertThat(lines[0]).startsWith("id,telegram_user_id,");
        // header line + trailing — no data rows.
        assertThat(java.util.Arrays.stream(lines).filter(l -> !l.isEmpty()).count()).isEqualTo(1L);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void filterTooLarge_returns422() throws Exception {
        doReturn(200_001L).when(mongoTemplate).count(any(Query.class), eq(Subscriber.class));

        mockMvc.perform(post(exportUrl()).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("export_filter_too_large"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void estimateDropsTextSearchCriterion() throws Exception {
        seedSubscribers(2);

        mockMvc.perform(post(exportUrl()).with(csrf())
                        .contentType("application/json")
                        .content("{\"filter\":{\"search\":\"ivan\"}}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(captor.capture(), eq(Subscriber.class));
        assertThat(captor.getValue().getQueryObject().containsKey("$text"))
                .as("estimate must drop the text-search criterion (indexed-only count)")
                .isFalse();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private String exportUrl() {
        return "/api/v1/projects/" + projectId + "/subscribers/export";
    }

    private String postExport() throws Exception {
        MvcResult res = mockMvc.perform(post(exportUrl()).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        return body.get("exportId").asText();
    }

    private Event eventByType(String type) {
        return eventRepository.findAll().stream()
                .filter(e -> type.equals(e.getEventType()))
                .findFirst().orElse(null);
    }

    private static org.assertj.core.data.TemporalUnitOffset within10s() {
        return org.assertj.core.api.Assertions.within(10, ChronoUnit.SECONDS);
    }

    private void seedUser() {
        User u = new User();
        u.setId(USER_ID);
        u.setEmail("owner@test.com");
        u.setName("Owner");
        u.setPasswordHash("x");
        u.setStatus(com.botfunnel.user.UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
    }

    private Project saveProject(String ownerId) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName("Proj-" + System.nanoTime());
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p.setDeletedAt(null);
        return projectRepository.save(p);
    }

    private void seedSubscribers(int count) {
        for (int i = 0; i < count; i++) {
            Subscriber s = new Subscriber();
            s.setProjectId(projectId);
            s.setTelegramUserId(telegramUserSeq++);
            s.setTelegramChatId(s.getTelegramUserId());
            s.setTelegramBotId(9000L);
            s.setStatus(SubscriberStatus.ACTIVE);
            s.setTags(new ArrayList<>());
            s.setSubscribedAt(Instant.now());
            s.setLastSeenAt(Instant.now());
            subscriberRepository.save(s);
        }
    }
}
