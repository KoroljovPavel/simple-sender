package com.botfunnel.subscriber.jobs;

import com.botfunnel.bot.TelegramApiClient;
import com.botfunnel.email.EmailService;
import com.botfunnel.events.EventService;
import com.botfunnel.subscriber.SegmentFilter;
import com.botfunnel.subscriber.SegmentFilterBuilder;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.common.crypto.SignedDownloadToken;
import com.botfunnel.subscriber.export.ExportStatus;
import com.botfunnel.subscriber.export.ExportUrlBuilder;
import com.botfunnel.subscriber.export.SubscriberCsvWriter;
import com.botfunnel.subscriber.export.SubscriberExport;
import com.botfunnel.subscriber.export.SubscriberExportRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.stereotype.Component;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JobRunr worker that runs a subscriber-CSV export end-to-end (Decisions 7, 8, 15, 16). Enqueued by
 * {@code SubscriberExportController} with a deterministic UUID so retries are idempotent. Streams the
 * matching subscribers through {@link SubscriberCsvWriter} into GridFS with heap bounded at single-row
 * size (R1), back-fills {@code metadata.rowCount} for the F10 integrity probe, then flips the export to
 * DONE / FAILED following the {@code ProcessTelegramUpdateJob} re-entry-guard + atomic-write + scrubbed-
 * rethrow precedent.
 */
@Component
public class ExportSubscribersJob {

    private static final Logger log = LoggerFactory.getLogger(ExportSubscribersJob.class);

    private static final String CONTENT_TYPE = "text/csv";
    private static final String GRIDFS_FILES_COLLECTION = "fs.files";
    private static final String EVT_COMPLETED = "subscribers_export_completed";
    private static final String EVT_FAILED = "subscribers_export_failed";
    private static final int ERROR_MAX_LEN = 1024;
    // 64 KiB pipe so the writer thread is not woken per-row; blocking on the pipe is cheap on a
    // virtual thread regardless.
    private static final int PIPE_BUFFER = 64 * 1024;

    private final SubscriberExportRepository exportRepository;
    private final MongoTemplate mongoTemplate;
    private final GridFsOperations gridFsOperations;
    private final SubscriberCsvWriter csvWriter;
    private final SegmentFilterBuilder segmentFilterBuilder;
    private final ObjectMapper objectMapper;
    private final EmailService emailService;
    private final EventService eventService;
    private final UserRepository userRepository;
    private final SignedDownloadToken signedDownloadToken;
    private final ExportUrlBuilder urlBuilder;
    private final int urlTtlHours;

    public ExportSubscribersJob(SubscriberExportRepository exportRepository,
                                MongoTemplate mongoTemplate,
                                GridFsOperations gridFsOperations,
                                SubscriberCsvWriter csvWriter,
                                SegmentFilterBuilder segmentFilterBuilder,
                                ObjectMapper objectMapper,
                                EmailService emailService,
                                EventService eventService,
                                UserRepository userRepository,
                                SignedDownloadToken signedDownloadToken,
                                ExportUrlBuilder urlBuilder,
                                @Value("${app.subscriber.export.url-ttl-hours}") int urlTtlHours) {
        this.exportRepository = exportRepository;
        this.mongoTemplate = mongoTemplate;
        this.gridFsOperations = gridFsOperations;
        this.csvWriter = csvWriter;
        this.segmentFilterBuilder = segmentFilterBuilder;
        this.objectMapper = objectMapper;
        this.emailService = emailService;
        this.eventService = eventService;
        this.userRepository = userRepository;
        this.signedDownloadToken = signedDownloadToken;
        this.urlBuilder = urlBuilder;
        this.urlTtlHours = urlTtlHours;
    }

    @Job(name = "Export subscribers CSV")
    public void handle(String exportId) {
        SubscriberExport export = exportRepository.findById(exportId).orElse(null);
        if (export == null) {
            // Deterministic-UUID enqueue happens after the PENDING save commits, so a missing row at
            // worker time should not happen in production. Log and exit rather than loop forever.
            log.warn("ExportSubscribersJob - export not found, skipping (exportId={})", exportId);
            return;
        }
        if (isTerminal(export.getStatus())) {
            // Re-entry guard (mirror ProcessTelegramUpdateJob): a JobRunr retry whose prior attempt
            // already reached a terminal state must not re-run the export or re-fire events.
            return;
        }

        // Atomic PENDING → RUNNING claim. A null return means the predicate failed (another worker
        // already claimed it, or status drifted) — benign no-op. Persist enum.name() so the value
        // stays byte-identical with the in-flight partial-filter literal.
        SubscriberExport claimed = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(exportId).and("status").is(ExportStatus.PENDING.name())),
                new Update().set("status", ExportStatus.RUNNING.name()),
                SubscriberExport.class);
        if (claimed == null) {
            return;
        }

        // Holder so the GridFS file id is visible to the FAILED branch even when streamToGridFs
        // throws AFTER store() persisted a partial/0-byte blob — otherwise that blob orphans
        // permanently (ExportCleanupJob only sweeps DONE rows).
        AtomicReference<ObjectId> createdFileId = new AtomicReference<>();
        try {
            streamToGridFs(exportId, export, createdFileId);
            finishSuccess(exportId, export, createdFileId.get());
        } catch (Throwable t) {
            handleFailure(exportId, export, createdFileId.get(), t);
        }
    }

    // Writes the CSV to GridFS on a writer thread, blocking the calling worker on store() until the
    // pipe closes. Publishes the GridFS file id into {@code createdFileId} the instant store()
    // returns — even on writer failure, where the writer closes the pipe and store() then sees EOF,
    // storing a partial file the FAILED branch deletes. Rethrows the writer's root failure so the
    // caller's catch records it.
    private void streamToGridFs(String exportId, SubscriberExport export,
                                AtomicReference<ObjectId> createdFileId) {
        SegmentFilter filter = deserializeFilter(export.getFilter());
        Query query = exportQuery(export.getProjectId(), filter);
        String fileName = "subscribers-export-" + exportId + ".csv";
        Document metadata = new Document("exportId", exportId).append("projectId", export.getProjectId());

        AtomicReference<Throwable> writerError = new AtomicReference<>();
        AtomicLong rowCount = new AtomicLong();
        // try-with-resources closes the read end symmetrically with the writer's closeQuietly(pipeOut).
        try (PipedInputStream in = new PipedInputStream(PIPE_BUFFER)) {
            PipedOutputStream pipeOut = new PipedOutputStream(in);
            Thread writer = Thread.ofVirtual().name("export-csv-writer-" + exportId).unstarted(() -> {
                // MongoTemplate.stream returns a Stream backed by a live cursor (AutoCloseable);
                // try-with-resources closes the cursor even on a writer-thread throw.
                try (java.util.stream.Stream<Subscriber> cursor = mongoTemplate.stream(query, Subscriber.class)) {
                    rowCount.set(csvWriter.write(cursor.iterator(), pipeOut));
                } catch (Throwable t) {
                    writerError.set(t);
                } finally {
                    closeQuietly(pipeOut);
                }
            });
            writer.start();
            try {
                // Publish the id BEFORE the writer-error rethrow below, so handleFailure can delete
                // any partial blob store() persisted.
                createdFileId.set(gridFsOperations.store(in, fileName, CONTENT_TYPE, metadata));
            } finally {
                // If store() threw before draining the pipe, interrupt so the writer cannot block
                // forever on a full pipe; then always join so the writer's outcome is observed.
                if (writer.isAlive()) {
                    writer.interrupt();
                }
                writer.join();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("export writer join interrupted", ie);
        } catch (java.io.IOException ioe) {
            throw new IllegalStateException("export pipe setup failed", ioe);
        }

        Throwable we = writerError.get();
        if (we != null) {
            if (we instanceof RuntimeException re) throw re;
            if (we instanceof Error err) throw err;
            throw new IllegalStateException(we.getMessage(), we);
        }

        // F10: back-fill metadata.rowCount on the fs.files doc so the integrity probe can join
        // subscriber_exports.rowCount == fs.files.metadata.rowCount. Unknown at store() time because
        // the writer counts as it emits and store() blocks on the pipe until the writer closes it.
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(createdFileId.get())),
                new Update().set("metadata.rowCount", rowCount.get()),
                GRIDFS_FILES_COLLECTION);
        export.setRowCount(rowCount.get());
    }

    private void finishSuccess(String exportId, SubscriberExport export, ObjectId gridFsFileId) {
        long rowCount = export.getRowCount() == null ? 0L : export.getRowCount();
        Instant completedAt = Instant.now();
        Instant expiresAt = completedAt.plus(Duration.ofHours(urlTtlHours));
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(exportId)),
                new Update()
                        .set("status", ExportStatus.DONE.name())
                        .set("completedAt", completedAt)
                        .set("expiresAt", expiresAt)
                        .set("fileId", gridFsFileId.toHexString())
                        .set("rowCount", rowCount),
                SubscriberExport.class);

        User owner = userRepository.findById(export.getOwnerId()).orElse(null);
        if (owner != null) {
            String token = mintToken(export.getProjectId(), exportId, expiresAt);
            String url = urlBuilder.downloadUrl(export.getProjectId(), exportId, token);
            // Silent-failure contract on the mail send — the export STILL transitions to DONE and the
            // event STILL writes; the UI refresh-URL path is the recovery fallback if delivery fails.
            emailService.sendExportReadyEmail(owner.getEmail(), owner.getName(), url, expiresAt);
        }
        eventService.logEvent(export.getOwnerId(), EVT_COMPLETED, null, null,
                Map.of("projectId", export.getProjectId(), "exportId", exportId, "rowCount", rowCount));
        log.info("ExportSubscribersJob - export completed (exportId={}, rowCount={})", exportId, rowCount);
    }

    private void handleFailure(String exportId, SubscriberExport export, ObjectId gridFsFileId, Throwable t) {
        String message = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
        String scrubbed = TelegramApiClient.scrubTokens(message);
        String truncated = scrubbed.substring(0, Math.min(scrubbed.length(), ERROR_MAX_LEN));

        // Atomic FAILED write — never findById + setter + save here (two retries could trample).
        mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(exportId)),
                new Update()
                        .set("status", ExportStatus.FAILED.name())
                        .set("completedAt", Instant.now())
                        .set("errorMessage", truncated),
                SubscriberExport.class);

        // Delete any partial GridFS file the writer produced before the throw (avoids orphan blobs).
        if (gridFsFileId != null) {
            gridFsOperations.delete(Query.query(Criteria.where("_id").is(gridFsFileId)));
        }

        User owner = userRepository.findById(export.getOwnerId()).orElse(null);
        if (owner != null) {
            emailService.sendExportFailedEmail(owner.getEmail(), owner.getName());
        }
        eventService.logEvent(export.getOwnerId(), EVT_FAILED, null, null,
                Map.of("projectId", export.getProjectId(), "exportId", exportId));
        log.error("ExportSubscribersJob - export failed (exportId={}): {}", exportId, truncated);

        // Rethrow a NEW RuntimeException with ONLY the scrubbed+truncated message + stack trace and NO
        // cause chain, so JobRunr's raw-exception log path cannot re-leak the unscrubbed message.
        String rethrowMessage = t.getClass().getSimpleName() + ": " + truncated;
        if (rethrowMessage.length() > ERROR_MAX_LEN) {
            rethrowMessage = rethrowMessage.substring(0, ERROR_MAX_LEN);
        }
        RuntimeException toRethrow = new RuntimeException(rethrowMessage);
        toRethrow.setStackTrace(t.getStackTrace());
        throw toRethrow;
    }

    // Full-segment query: the export spans the whole filtered segment, so the page limit and the
    // pagination cursor are stripped (limit 0 = no limit in Spring Data). Text-search, status, tags
    // and date-range criteria are preserved — the owner exports exactly what they filtered.
    private Query exportQuery(String projectId, SegmentFilter filter) {
        SegmentFilter noCursor = new SegmentFilter(
                filter.search(), filter.status(), filter.tagsInclude(), filter.tagsExclude(),
                filter.subscribedFrom(), filter.subscribedTo(), filter.sort(), null, 0);
        Query query = segmentFilterBuilder.build(projectId, noCursor);
        query.limit(0);
        return query;
    }

    private SegmentFilter deserializeFilter(Document filterDoc) {
        if (filterDoc == null) {
            return new SegmentFilter(null, null, null, null, null, null,
                    SegmentFilter.SortKey.CREATED_DESC, null, 0);
        }
        return objectMapper.convertValue(filterDoc, SegmentFilter.class);
    }

    private String mintToken(String projectId, String exportId, Instant expiresAt) {
        return signedDownloadToken.mint(projectId, exportId, expiresAt);
    }

    private static boolean isTerminal(ExportStatus status) {
        return status == ExportStatus.DONE || status == ExportStatus.FAILED || status == ExportStatus.PURGED;
    }

    private static void closeQuietly(PipedOutputStream out) {
        try {
            out.close();
        } catch (Exception ignored) {
            // Closing the write end signals EOF to store(); a close failure is non-actionable here.
        }
    }
}
