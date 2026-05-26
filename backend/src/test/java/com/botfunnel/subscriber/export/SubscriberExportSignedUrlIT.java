package com.botfunnel.subscriber.export;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.crypto.SignedDownloadToken;
import com.botfunnel.common.test.ConcurrencyTestUtils;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubscriberExportSignedUrlIT extends AbstractIntegrationTest {

    private static final String USER_ID = "exp-url-user";
    private static final byte[] FILE_BYTES =
            {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'i', 'd', '\n', 'x', '\n'};

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberExportRepository exportRepository;
    @Autowired EventRepository eventRepository;
    @Autowired GridFsOperations gridFsOperations;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired SignedDownloadToken signedDownloadToken;
    @Autowired SubscriberExportController controller;
    @MockitoSpyBean StringRedisTemplate redisTemplate;

    private String projectId;
    private ListAppender<ILoggingEvent> appender;
    private Logger controllerLogger;

    @BeforeEach
    void cleanAndSeed() {
        Mockito.reset(redisTemplate);
        userRepository.deleteAll();
        projectRepository.deleteAll();
        eventRepository.deleteAll();
        mongoTemplate.remove(new Query(), SubscriberExport.class);
        gridFsOperations.delete(new Query());
        Set<String> keys = redisTemplate.keys("bf:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        seedUser(USER_ID, "owner@test.com");
        projectId = saveProject(USER_ID, null).getId();

        controllerLogger = (Logger) LoggerFactory.getLogger(SubscriberExportController.class);
        appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        if (controllerLogger != null && appender != null) {
            controllerLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // ─── download ──────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void happyDownload_streamsCsvAndWritesEvent() throws Exception {
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));
        SubscriberExport export = seedExport(projectId, ExportStatus.DONE, expiresAt, true);
        String token = signedDownloadToken.mint(projectId, export.getId(), expiresAt);

        byte[] body = mockMvc.perform(get(downloadUrl(export.getId())).param("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"subscribers-export-" + export.getId() + ".csv\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(FILE_BYTES);

        assertThat(eventByType("subscribers_export_downloaded")).isNotNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void tamperedToken_returns401_andAuditEvent() throws Exception {
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));
        SubscriberExport export = seedExport(projectId, ExportStatus.DONE, expiresAt, true);
        String token = signedDownloadToken.mint(projectId, export.getId(), expiresAt);
        String tampered = flipLastChar(token);

        mockMvc.perform(get(downloadUrl(export.getId())).param("token", tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));

        Event denied = deniedEvent();
        assertThat(denied.getMetadata()).containsEntry("reason", "invalid_token");
        assertThat(denied.getMetadata().get("tokenSegmentPrefix")).isNotNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void expiredToken_returns410_andAuditEvent() throws Exception {
        SubscriberExport export = seedExport(projectId, ExportStatus.DONE,
                Instant.now().plus(Duration.ofHours(24)), true);
        String expired = signedDownloadToken.mint(projectId, export.getId(),
                Instant.now().minus(Duration.ofHours(1)));

        mockMvc.perform(get(downloadUrl(export.getId())).param("token", expired))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("export_expired"));

        assertThat(deniedEvent().getMetadata()).containsEntry("reason", "expired");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void softDeletedProject_returns410_andAuditEvent() throws Exception {
        String deletedProject = saveProject(USER_ID, Instant.now()).getId();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));
        SubscriberExport export = seedExport(deletedProject, ExportStatus.DONE, expiresAt, true);
        String token = signedDownloadToken.mint(deletedProject, export.getId(), expiresAt);

        mockMvc.perform(get(downloadUrl(deletedProject, export.getId())).param("token", token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("export_project_unavailable"));

        assertThat(deniedEvent().getMetadata()).containsEntry("reason", "project_unavailable");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void purgedExport_returns410_andAuditEvent() throws Exception {
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));
        SubscriberExport export = seedExport(projectId, ExportStatus.PURGED, expiresAt, false);
        String token = signedDownloadToken.mint(projectId, export.getId(), expiresAt);

        mockMvc.perform(get(downloadUrl(export.getId())).param("token", token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("export_purged"));

        assertThat(deniedEvent().getMetadata()).containsEntry("reason", "purged");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void crossProjectSubstitution_returns401() throws Exception {
        String projectB = saveProject(USER_ID, null).getId();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));
        SubscriberExport export = seedExport(projectId, ExportStatus.DONE, expiresAt, true);
        // Token bound to projectId(A), requested against projectB → payload mismatch caught at verify.
        String token = signedDownloadToken.mint(projectId, export.getId(), expiresAt);

        mockMvc.perform(get(downloadUrl(projectB, export.getId())).param("token", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));

        assertThat(deniedEvent().getMetadata()).containsEntry("reason", "invalid_token");
    }

    @Test
    void downloadRateLimit_parallel31Requests_one429() throws Exception {
        // Race the controller method directly (real Redis INCR via the delegating spy) — 31 parallel
        // downloads, the 31st trips the 30/min bucket. MockMvc is avoided here for thread-safety.
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));
        SubscriberExport export = seedExport(projectId, ExportStatus.DONE, expiresAt, true);
        String token = signedDownloadToken.mint(projectId, export.getId(), expiresAt);

        List<Integer> statuses = ConcurrencyTestUtils.parallelInvoke(31, () -> {
            SecurityContextHolder.setContext(new SecurityContextImpl(authFor()));
            try {
                ResponseEntity<?> resp = controller.download(
                        projectId, export.getId(), token, new MockHttpServletRequest());
                return resp.getStatusCode().value();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(30L);
        assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(1L);
        assertThat(deniedEvent().getMetadata()).containsEntry("reason", "rate_limited");
    }

    // ─── refresh URL ─────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void refreshUrl_done_returns200FreshToken() throws Exception {
        SubscriberExport export = seedExport(projectId, ExportStatus.DONE,
                Instant.now().minus(Duration.ofHours(1)), true); // already-expired URL
        mockMvc.perform(post(refreshUrl(export.getId())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").value(org.hamcrest.Matchers.containsString("download?token=")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void refreshUrl_pendingOrRunning_returns409() throws Exception {
        SubscriberExport pending = seedExport(projectId, ExportStatus.PENDING, null, false);
        mockMvc.perform(post(refreshUrl(pending.getId())).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("export_in_flight"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void refreshUrl_purged_returns410() throws Exception {
        SubscriberExport purged = seedExport(projectId, ExportStatus.PURGED, null, false);
        mockMvc.perform(post(refreshUrl(purged.getId())).with(csrf()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("export_purged"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void refreshUrl_failed_returns410() throws Exception {
        SubscriberExport failed = seedExport(projectId, ExportStatus.FAILED, null, false);
        mockMvc.perform(post(refreshUrl(failed.getId())).with(csrf()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("export_failed"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void refreshUrl_sixthCallInOneHour_returns429() throws Exception {
        SubscriberExport export = seedExport(projectId, ExportStatus.DONE,
                Instant.now().plus(Duration.ofHours(24)), true);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post(refreshUrl(export.getId())).with(csrf()))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post(refreshUrl(export.getId())).with(csrf()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void refreshUrl_redisDown_returns503() throws Exception {
        SubscriberExport export = seedExport(projectId, ExportStatus.DONE,
                Instant.now().plus(Duration.ofHours(24)), true);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> failing = mock(ValueOperations.class);
        when(failing.increment(anyString())).thenThrow(new RedisConnectionFailureException("simulated"));
        doReturn(failing).when(redisTemplate).opsForValue();

        mockMvc.perform(post(refreshUrl(export.getId())).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("service_unavailable"));

        boolean warned = appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("EXPORT_REFRESH_REDIS_FAIL_CLOSED"));
        assertThat(warned).as("fail-closed path must emit the greppable WARN constant").isTrue();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private String downloadUrl(String exportId) {
        return downloadUrl(projectId, exportId);
    }

    private String downloadUrl(String project, String exportId) {
        return "/api/v1/projects/" + project + "/subscribers/exports/" + exportId + "/download";
    }

    private String refreshUrl(String exportId) {
        return "/api/v1/projects/" + projectId + "/subscribers/exports/" + exportId + "/refresh-url";
    }

    private SubscriberExport seedExport(String project, ExportStatus status, Instant expiresAt, boolean withFile) {
        String fileId = null;
        if (withFile) {
            fileId = gridFsOperations.store(new ByteArrayInputStream(FILE_BYTES),
                    "seed.csv", "text/csv", new Document("projectId", project)).toHexString();
        }
        SubscriberExport export = new SubscriberExport();
        export.setProjectId(project);
        export.setOwnerId(USER_ID);
        export.setStatus(status);
        export.setFileId(fileId);
        export.setRowCount(1L);
        export.setCreatedAt(Instant.now());
        if (status == ExportStatus.DONE) {
            export.setCompletedAt(Instant.now());
            export.setExpiresAt(expiresAt);
        }
        return exportRepository.save(export);
    }

    private Event eventByType(String type) {
        return eventRepository.findAll().stream()
                .filter(e -> type.equals(e.getEventType()))
                .findFirst().orElse(null);
    }

    private Event deniedEvent() {
        Event e = eventByType("subscribers_export_download_denied");
        assertThat(e).as("a download-denied audit event must be written").isNotNull();
        return e;
    }

    private static String flipLastChar(String token) {
        char last = token.charAt(token.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        return token.substring(0, token.length() - 1) + replacement;
    }

    private static Authentication authFor() {
        AppUserDetails principal = new AppUserDetails(USER_ID, "owner@test.com", "Owner", "active");
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
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
}
