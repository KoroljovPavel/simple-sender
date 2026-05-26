package com.botfunnel.subscriber.export;

import com.botfunnel.subscriber.Subscriber;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Streams {@link Subscriber} rows to a CSV byte stream (Decision 7). Pure formatter — no Mongo, no
 * GridFS: callers feed an {@link Iterator} (the JobRunr job passes a {@code MongoTemplate.stream}
 * cursor) and an {@link OutputStream} (the job wires a {@code PipedOutputStream} into GridFS). Heap
 * stays at single-row size regardless of dataset (R1).
 *
 * <p>Format: UTF-8 BOM once at the head, fixed column order, RFC 4180 escaping, ISO-8601 UTC dates.
 * <strong>Formula-injection neutralization is mandatory (F1):</strong> any cell whose first
 * character is one of {@code = + - @ \t \r} is prefixed with a single apostrophe BEFORE RFC 4180
 * escaping — defusing spreadsheet formula execution from subscriber-supplied identity / custom
 * fields (Telegram first/last/username and NUMBER-type custom fields are unbounded by the slug regex).
 */
@Component
public class SubscriberCsvWriter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    static final String HEADER = "id,telegram_user_id,telegram_chat_id,telegram_bot_id,"
            + "first_name,last_name,username,language_code,status,tags,custom_fields,"
            + "subscribed_at,unsubscribed_at,blocked_at,deleted_at,last_seen_at";

    // Row separator: LF. Cell content containing CR/LF is RFC-4180 quoted, so the separator stays
    // unambiguous; spreadsheet importers (Excel / Sheets / LibreOffice / Numbers) all accept LF.
    private static final char ROW_SEP = '\n';

    private final ObjectMapper objectMapper;

    public SubscriberCsvWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Writes BOM + header + one line per subscriber to {@code out}, returning the data-row count.
     * Does NOT close {@code out} — the caller (job writer thread) owns the pipe lifecycle.
     */
    public long write(Iterator<Subscriber> rows, OutputStream out) throws IOException {
        // BOM is raw bytes written before any text so it lands exactly once at the file head.
        out.write(UTF8_BOM);
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write(HEADER);
        w.write(ROW_SEP);

        long rowCount = 0;
        while (rows.hasNext()) {
            writeRow(w, rows.next());
            rowCount++;
        }
        w.flush();
        return rowCount;
    }

    private void writeRow(Writer w, Subscriber s) throws IOException {
        StringBuilder line = new StringBuilder(256);
        appendCell(line, s.getId(), false);
        appendCell(line, str(s.getTelegramUserId()), false);
        appendCell(line, str(s.getTelegramChatId()), false);
        appendCell(line, str(s.getTelegramBotId()), false);
        appendCell(line, s.getFirstName(), false);
        appendCell(line, s.getLastName(), false);
        appendCell(line, s.getUsername(), false);
        appendCell(line, s.getLanguageCode(), false);
        appendCell(line, s.getStatus() == null ? null : s.getStatus().name().toLowerCase(Locale.ROOT), false);
        appendCell(line, joinTags(s.getTags()), false);
        appendCell(line, customFieldsJson(s.getCustomFields()), false);
        appendCell(line, iso(s.getSubscribedAt()), false);
        appendCell(line, iso(s.getUnsubscribedAt()), false);
        appendCell(line, iso(s.getBlockedAt()), false);
        appendCell(line, iso(s.getDeletedAt()), false);
        appendCell(line, iso(s.getLastSeenAt()), true);
        line.append(ROW_SEP);
        w.write(line.toString());
    }

    private void appendCell(StringBuilder line, String raw, boolean last) {
        line.append(escape(neutralizeFormula(raw)));
        if (!last) {
            line.append(',');
        }
    }

    // F1: prefix a leading-trigger cell with an apostrophe so a spreadsheet treats it as text.
    static String neutralizeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    // RFC 4180: wrap in quotes (doubling internal quotes) when the cell carries a delimiter,
    // quote, CR or LF.
    static String escape(String value) {
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String str(Long value) {
        return value == null ? null : value.toString();
    }

    private static String iso(Instant value) {
        return value == null ? null : value.toString(); // Instant.toString() is ISO-8601 UTC with trailing Z
    }

    private static String joinTags(List<String> tags) {
        return (tags == null || tags.isEmpty()) ? "" : String.join(";", tags);
    }

    private String customFieldsJson(Map<String, Object> customFields) {
        if (customFields == null || customFields.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(customFields);
        } catch (JsonProcessingException e) {
            // A field that cannot be serialized is a data integrity error — fail the export so the
            // job's FAILED branch records it rather than emitting a silently-truncated cell.
            throw new IllegalStateException("custom_fields serialization failed", e);
        }
    }
}
