package com.botfunnel.subscriber.export;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.common.ErrorResponse;
import com.botfunnel.common.HttpRequestUtils;
import com.botfunnel.common.crypto.SignedDownloadToken;
import com.botfunnel.events.EventService;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectService;
import com.botfunnel.subscriber.SegmentFilter;
import com.botfunnel.subscriber.SegmentFilterBuilder;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.dto.CreateExportRequest;
import com.botfunnel.subscriber.dto.ExportResponse;
import com.botfunnel.subscriber.dto.RefreshUrlResponse;
import com.botfunnel.subscriber.jobs.ExportSubscribersJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.gridfs.model.GridFSFile;
import jakarta.servlet.http.HttpServletRequest;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Async subscriber-CSV export surface (Decisions 7, 8, 9, 15, 16). Four endpoints under
 * {@code /api/v1/projects/{projectId}/subscribers/...}: enqueue an export, list recent exports,
 * download via a signed HMAC URL, and refresh an expired URL.
 *
 * <p>The session-mutating endpoints (POST /export, POST /refresh-url) and the list endpoint run
 * {@code requireOwned} first (AC21 anti-IDOR). The download endpoint is reached only by an
 * authenticated session (Spring Security guards {@code /api/**}) but is additionally bound to a
 * signed token: order is token-verify → rate-limit → requireOwned → load → branch, so no DB hit
 * happens before the token is trusted. Every non-200 download outcome writes a
 * {@code subscribers_export_download_denied} audit event to the platform {@code events} collection
 * BEFORE the response is returned (Decision 16 / F4).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/subscribers")
public class SubscriberExportController {

    private static final Logger log = LoggerFactory.getLogger(SubscriberExportController.class);

    // Estimated-count cap before an export is even enqueued (AC18).
    private static final long MAX_EXPORT_ROWS = 200_000L;
    private static final int REFRESH_RATE_PER_HOUR = 5;

    private static final String DOWNLOAD_RATE_KEY_PREFIX = "bf:download:export:";
    private static final String REFRESH_RATE_KEY_PREFIX = "bf:refresh:export:";

    private static final String EVT_DOWNLOADED = "subscribers_export_downloaded";
    private static final String EVT_DOWNLOAD_DENIED = "subscribers_export_download_denied";

    private static final String CODE_EXPORT_IN_FLIGHT = "export_in_flight";
    private static final String CODE_EXPORT_FILTER_TOO_LARGE = "export_filter_too_large";
    private static final String CODE_EXPORT_EXPIRED = "export_expired";
    private static final String CODE_EXPORT_PURGED = "export_purged";
    private static final String CODE_EXPORT_FAILED = "export_failed";
    private static final String CODE_EXPORT_PROJECT_UNAVAILABLE = "export_project_unavailable";
    private static final String CODE_INVALID_TOKEN = "invalid_token";
    private static final String CODE_RATE_LIMITED = "rate_limited";
    private static final String CODE_REFRESH_RATE_LIMITED = "refresh_rate_limited";
    private static final String CODE_SERVICE_UNAVAILABLE = "service_unavailable";

    private static final String REASON_INVALID_TOKEN = "invalid_token";
    private static final String REASON_EXPIRED = "expired";
    private static final String REASON_RATE_LIMITED = "rate_limited";
    private static final String REASON_PROJECT_UNAVAILABLE = "project_unavailable";
    private static final String REASON_PURGED = "purged";
    private static final String REASON_FAILED = "failed";
    private static final String REASON_FOREIGN_OWNER = "foreign_owner";

    // Greppable WARN constants — stable so log-based alerts and tests can pin to them.
    static final String EXPORT_DOWNLOAD_RATE_REDIS_FAIL_OPEN =
            "EXPORT_DOWNLOAD_RATE_REDIS_FAIL_OPEN: Redis rate-limit unavailable — failing open ({})";
    static final String EXPORT_REFRESH_REDIS_FAIL_CLOSED =
            "EXPORT_REFRESH_REDIS_FAIL_CLOSED: Redis unavailable — failing closed on refresh-url ({})";

    private final ProjectService projectService;
    private final SubscriberExportRepository exportRepository;
    private final MongoTemplate mongoTemplate;
    private final JobScheduler jobScheduler;
    private final SignedDownloadToken signedDownloadToken;
    private final StringRedisTemplate redisTemplate;
    private final EventService eventService;
    private final GridFsOperations gridFsOperations;
    private final SegmentFilterBuilder segmentFilterBuilder;
    private final ObjectMapper objectMapper;
    private final ExportUrlBuilder urlBuilder;
    private final int downloadRatePerMin;
    private final int urlTtlHours;

    public SubscriberExportController(ProjectService projectService,
                                      SubscriberExportRepository exportRepository,
                                      MongoTemplate mongoTemplate,
                                      JobScheduler jobScheduler,
                                      SignedDownloadToken signedDownloadToken,
                                      StringRedisTemplate redisTemplate,
                                      EventService eventService,
                                      GridFsOperations gridFsOperations,
                                      SegmentFilterBuilder segmentFilterBuilder,
                                      ObjectMapper objectMapper,
                                      ExportUrlBuilder urlBuilder,
                                      @Value("${app.subscriber.export.download-rate-per-min}") int downloadRatePerMin,
                                      @Value("${app.subscriber.export.url-ttl-hours}") int urlTtlHours) {
        this.projectService = projectService;
        this.exportRepository = exportRepository;
        this.mongoTemplate = mongoTemplate;
        this.jobScheduler = jobScheduler;
        this.signedDownloadToken = signedDownloadToken;
        this.redisTemplate = redisTemplate;
        this.eventService = eventService;
        this.gridFsOperations = gridFsOperations;
        this.segmentFilterBuilder = segmentFilterBuilder;
        this.objectMapper = objectMapper;
        this.urlBuilder = urlBuilder;
        this.downloadRatePerMin = downloadRatePerMin;
        this.urlTtlHours = urlTtlHours;
    }

    // ─── enqueue export ─────────────────────────────────────────────────────

    @PostMapping("/export")
    public ResponseEntity<ExportResponse> createExport(@PathVariable String projectId,
                                                       @RequestBody(required = false) CreateExportRequest request) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);

        SegmentFilter filter = (request == null || request.filter() == null) ? emptyFilter() : request.filter();

        // Estimated count uses an indexed-only filter (text-search dropped) so the cap check stays
        // under the AC18 P95<200ms budget.
        long estimated = estimatedCount(project.getId(), filter);
        if (estimated > MAX_EXPORT_ROWS) {
            throw AppException.unprocessableEntity(CODE_EXPORT_FILTER_TOO_LARGE,
                    "Export filter matches too many subscribers (limit " + MAX_EXPORT_ROWS + ")");
        }

        SubscriberExport export = new SubscriberExport();
        export.setProjectId(project.getId());
        export.setOwnerId(project.getOwnerId());
        export.setStatus(ExportStatus.PENDING);
        export.setFilter(serializeFilter(filter));
        export.setCreatedAt(Instant.now());

        SubscriberExport saved;
        try {
            // The (projectId) partial-unique index (PENDING/RUNNING) surfaces a concurrent export as
            // a DuplicateKeyException → 409 export_in_flight (Decision 2).
            saved = exportRepository.save(export);
        } catch (DuplicateKeyException e) {
            throw AppException.conflict(CODE_EXPORT_IN_FLIGHT, "An export is already in progress for this project");
        }

        // Deterministic UUID mirrors TelegramWebhookController:85-87 so JobRunr retries are idempotent.
        String exportId = saved.getId();
        UUID jobId = UUID.nameUUIDFromBytes(exportId.getBytes(StandardCharsets.UTF_8));
        jobScheduler.<ExportSubscribersJob>enqueue(jobId, j -> j.handle(exportId));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(saved, null));
    }

    // ─── list recent exports ────────────────────────────────────────────────

    @GetMapping("/exports")
    public ResponseEntity<List<ExportResponse>> listExports(@PathVariable String projectId) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        List<SubscriberExport> rows = exportRepository
                .findByProjectIdAndStatusOrderByCreatedAtDesc(project.getId(), ExportStatus.DONE);
        List<ExportResponse> body = rows.stream()
                .map(e -> toResponse(e, signedUrlIfValid(e)))
                .toList();
        return ResponseEntity.ok(body);
    }

    // ─── download (signed URL) ────────────────────────────────────────────────

    @GetMapping("/exports/{exportId}/download")
    public ResponseEntity<?> download(@PathVariable String projectId,
                                      @PathVariable String exportId,
                                      @RequestParam(required = false) String token,
                                      HttpServletRequest request) {
        String ip = HttpRequestUtils.extractIp(request);
        String userAgent = HttpRequestUtils.extractUserAgent(request);
        String tokenPrefix = tokenSegmentPrefix(token);

        // 1. Token verify FIRST — constant-time HMAC, NO DB hit yet.
        SignedDownloadToken.VerificationResult vr = signedDownloadToken.verify(token);
        if (vr instanceof SignedDownloadToken.Failed failed) {
            if (failed.reason() == SignedDownloadToken.FailureReason.EXPIRED) {
                return deny(HttpStatus.GONE, CODE_EXPORT_EXPIRED, REASON_EXPIRED,
                        projectId, null, tokenPrefix, ip, userAgent, "Download link expired");
            }
            return deny(HttpStatus.UNAUTHORIZED, CODE_INVALID_TOKEN, REASON_INVALID_TOKEN,
                    projectId, null, tokenPrefix, ip, userAgent, "Invalid download token");
        }
        SignedDownloadToken.Verified verified = (SignedDownloadToken.Verified) vr;
        if (!projectId.equals(verified.projectId())) {
            // Cross-project substitution caught at verify level — no DB hit.
            return deny(HttpStatus.UNAUTHORIZED, CODE_INVALID_TOKEN, REASON_INVALID_TOKEN,
                    projectId, null, tokenPrefix, ip, userAgent, "Invalid download token");
        }
        String tokenExportId = verified.exportId();

        // 2. Redis rate-limit — fail-OPEN (cheap check before any Mongo hit beyond the audit write).
        if (downloadRateLimited(projectId)) {
            return deny(HttpStatus.TOO_MANY_REQUESTS, CODE_RATE_LIMITED, REASON_RATE_LIMITED,
                    projectId, tokenExportId, tokenPrefix, ip, userAgent, "Too many download requests");
        }

        // 3. Ownership — include soft-deleted so a deleted project maps to 410 (not the uniform 404).
        Project project;
        try {
            project = projectService.requireOwned(currentUserId(), projectId, true);
        } catch (AppException e) {
            return deny(HttpStatus.NOT_FOUND, null, REASON_FOREIGN_OWNER,
                    projectId, tokenExportId, tokenPrefix, ip, userAgent, "Project not found");
        }
        if (project.getDeletedAt() != null) {
            return deny(HttpStatus.GONE, CODE_EXPORT_PROJECT_UNAVAILABLE, REASON_PROJECT_UNAVAILABLE,
                    projectId, tokenExportId, tokenPrefix, ip, userAgent, "Project no longer available");
        }

        // 4. Load export + branch on status.
        SubscriberExport export = exportRepository.findById(tokenExportId)
                .filter(e -> projectId.equals(e.getProjectId()))
                .orElse(null);
        if (export == null) {
            return deny(HttpStatus.UNAUTHORIZED, CODE_INVALID_TOKEN, REASON_INVALID_TOKEN,
                    projectId, tokenExportId, tokenPrefix, ip, userAgent, "Invalid download token");
        }
        switch (export.getStatus()) {
            case DONE -> {
                // Server-side expiry double-check defends against payload-expiry drift.
                if (export.getExpiresAt() != null && export.getExpiresAt().isBefore(Instant.now())) {
                    return deny(HttpStatus.GONE, CODE_EXPORT_EXPIRED, REASON_EXPIRED,
                            projectId, tokenExportId, tokenPrefix, ip, userAgent, "Download link expired");
                }
                return streamFile(project.getId(), tokenExportId, export, ip, userAgent, tokenPrefix);
            }
            case PURGED -> {
                return deny(HttpStatus.GONE, CODE_EXPORT_PURGED, REASON_PURGED,
                        projectId, tokenExportId, tokenPrefix, ip, userAgent, "Export file purged");
            }
            case FAILED -> {
                return deny(HttpStatus.GONE, CODE_EXPORT_FAILED, REASON_FAILED,
                        projectId, tokenExportId, tokenPrefix, ip, userAgent, "Export failed");
            }
            default -> {
                // PENDING / RUNNING: not yet downloadable. Surfacing as invalid_token keeps the
                // not-ready state opaque to a token holder polling the URL.
                return deny(HttpStatus.UNAUTHORIZED, CODE_INVALID_TOKEN, REASON_INVALID_TOKEN,
                        projectId, tokenExportId, tokenPrefix, ip, userAgent, "Invalid download token");
            }
        }
    }

    private ResponseEntity<?> streamFile(String projectId, String exportId, SubscriberExport export,
                                         String ip, String userAgent, String tokenPrefix) {
        GridFSFile file = export.getFileId() == null ? null : gridFsOperations.findOne(
                Query.query(Criteria.where("_id").is(new ObjectId(export.getFileId()))));
        if (file == null) {
            // DONE row but the blob is gone (purge race / lost file) — treat as purged.
            return deny(HttpStatus.GONE, CODE_EXPORT_PURGED, REASON_PURGED,
                    projectId, exportId, tokenPrefix, ip, userAgent, "Export file purged");
        }
        GridFsResource resource = gridFsOperations.getResource(file);
        eventService.logEvent(currentUserIdOrNull(), EVT_DOWNLOADED, ip, userAgent,
                Map.of("projectId", projectId, "exportId", exportId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"subscribers-export-" + exportId + ".csv\"")
                .body(resource);
    }

    // ─── refresh URL ──────────────────────────────────────────────────────────

    @PostMapping("/exports/{exportId}/refresh-url")
    public ResponseEntity<RefreshUrlResponse> refreshUrl(@PathVariable String projectId,
                                                         @PathVariable String exportId) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);

        // Fail-CLOSED rate-limit: re-minting a token during a Redis outage would defeat the 24h TTL
        // hardening (F2), so an outage returns 503 rather than failing open like the download bucket.
        enforceRefreshRateLimit(project.getId());

        SubscriberExport export = exportRepository.findById(exportId)
                .filter(e -> project.getId().equals(e.getProjectId()))
                .orElseThrow(() -> AppException.notFound("Export not found"));

        switch (export.getStatus()) {
            case DONE -> {
                Instant expiresAt = Instant.now().plus(Duration.ofHours(urlTtlHours));
                String token = signedDownloadToken.mint(project.getId(), exportId, expiresAt);
                mongoTemplate.updateFirst(
                        Query.query(Criteria.where("_id").is(exportId)),
                        new Update().set("expiresAt", expiresAt),
                        SubscriberExport.class);
                return ResponseEntity.ok(new RefreshUrlResponse(
                        urlBuilder.downloadUrl(project.getId(), exportId, token), expiresAt));
            }
            case PENDING, RUNNING ->
                    throw AppException.conflict(CODE_EXPORT_IN_FLIGHT, "Export still in progress");
            case PURGED -> throw AppException.gone(CODE_EXPORT_PURGED, "Export file purged");
            case FAILED -> throw AppException.gone(CODE_EXPORT_FAILED, "Export failed");
            // Exhaustive over the 5-value enum; an added constant surfaces here instead of silently
            // masquerading as export_failed.
            default -> throw new IllegalStateException("Unhandled export status: " + export.getStatus());
        }
    }

    // ─── rate-limit helpers ─────────────────────────────────────────────────

    private boolean downloadRateLimited(String projectId) {
        // Decision 9 shape: INCR every attempt, EXPIRE only on the first bucket, fail-OPEN with a
        // greppable WARN on any Redis transport error.
        String key = DOWNLOAD_RATE_KEY_PREFIX + projectId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(1));
            }
            return count != null && count > downloadRatePerMin;
        } catch (Exception e) {
            log.warn(EXPORT_DOWNLOAD_RATE_REDIS_FAIL_OPEN, e.getMessage());
            return false;
        }
    }

    private void enforceRefreshRateLimit(String projectId) {
        String key = REFRESH_RATE_KEY_PREFIX + projectId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofHours(1));
            }
            if (count != null && count > REFRESH_RATE_PER_HOUR) {
                throw new AppException(HttpStatus.TOO_MANY_REQUESTS, CODE_REFRESH_RATE_LIMITED,
                        "Too many refresh-URL requests — try again later");
            }
        } catch (RedisConnectionFailureException | RedisSystemException e) {
            log.warn(EXPORT_REFRESH_REDIS_FAIL_CLOSED, e.getMessage());
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, CODE_SERVICE_UNAVAILABLE,
                    "Service temporarily unavailable — try again later");
        }
    }

    // ─── estimate + filter serialization ──────────────────────────────────────

    // Package-private so the AC18 latency probe (SubscriberExportEstimateLatencyIT) can time the
    // indexed-only count path directly.
    long estimatedCount(String projectId, SegmentFilter filter) {
        // Drop text-search + cursor + limit → indexed-only count (AC18). count() ignores the sort
        // the builder applies, so the indexed predicate alone drives the latency.
        SegmentFilter indexedOnly = new SegmentFilter(null, filter.status(), filter.tagsInclude(),
                filter.tagsExclude(), filter.subscribedFrom(), filter.subscribedTo(),
                filter.sort(), null, 0);
        Query query = segmentFilterBuilder.build(projectId, indexedOnly);
        query.limit(0);
        return mongoTemplate.count(query, Subscriber.class);
    }

    private Document serializeFilter(SegmentFilter filter) {
        try {
            // ObjectMapper round-trips Instants as ISO strings both ways (the job reads it back with
            // convertValue), so the stored segment is reproducible without BSON Date coercion.
            return Document.parse(objectMapper.writeValueAsString(filter));
        } catch (Exception e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, null, "Failed to serialize export filter");
        }
    }

    private static SegmentFilter emptyFilter() {
        return new SegmentFilter(null, null, null, null, null, null,
                SegmentFilter.SortKey.CREATED_DESC, null, 0);
    }

    // ─── response mapping ─────────────────────────────────────────────────────

    private String signedUrlIfValid(SubscriberExport export) {
        if (export.getExpiresAt() == null || export.getExpiresAt().isBefore(Instant.now())) {
            return null; // UI flips the button to "Refresh URL".
        }
        // Deterministic for a fixed (projectId, exportId, expiresAt) — the listed URL stays stable.
        String token = signedDownloadToken.mint(export.getProjectId(), export.getId(), export.getExpiresAt());
        return urlBuilder.downloadUrl(export.getProjectId(), export.getId(), token);
    }

    private static ExportResponse toResponse(SubscriberExport e, String downloadUrl) {
        return new ExportResponse(
                e.getId(),
                e.getStatus() == null ? null : e.getStatus().name(),
                e.getRowCount(),
                e.getCreatedAt(),
                e.getCompletedAt(),
                downloadUrl,
                e.getExpiresAt());
    }

    private ResponseEntity<ErrorResponse> deny(HttpStatus status, String code, String reason,
                                               String projectId, String exportId, String tokenSegmentPrefix,
                                               String ip, String userAgent, String message) {
        // Decision 16 / F4: audit the denial to the platform `events` collection BEFORE returning the
        // response, so recon-attack visibility survives a downstream response failure. userId is null
        // — denied downloads are attributed by IP/UA + token prefix, not by session identity.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", reason);
        metadata.put("projectId", projectId);
        if (exportId != null) {
            metadata.put("exportId", exportId);
        }
        if (tokenSegmentPrefix != null) {
            metadata.put("tokenSegmentPrefix", tokenSegmentPrefix);
        }
        eventService.logEvent(null, EVT_DOWNLOAD_DENIED, ip, userAgent, metadata);
        return ResponseEntity.status(status).body(new ErrorResponse(message, code));
    }

    // First 8 chars of the payload segment (NOT the signature) — recon cross-reference without
    // leaking the full token (Decision 16).
    private static String tokenSegmentPrefix(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        int dot = token.indexOf('.');
        String payloadSegment = dot > 0 ? token.substring(0, dot) : token;
        return payloadSegment.substring(0, Math.min(8, payloadSegment.length()));
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw AppException.unauthorized("Not authenticated");
        }
        return details.id();
    }

    private static String currentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AppUserDetails details) {
            return details.id();
        }
        return null;
    }
}
