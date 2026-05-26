package com.botfunnel.subscriber.export;

import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit coverage of the CSV formatter (Decision 7). Each test writes a small subscriber list to
 * a {@link ByteArrayOutputStream} and inspects the bytes / parsed cells — no Mongo, no GridFS.
 */
class SubscriberCsvWriterTest {

    private static final String HEADER = "id,telegram_user_id,telegram_chat_id,telegram_bot_id,"
            + "first_name,last_name,username,language_code,status,tags,custom_fields,"
            + "subscribed_at,unsubscribed_at,blocked_at,deleted_at,last_seen_at";

    private final SubscriberCsvWriter writer = new SubscriberCsvWriter(new ObjectMapper());

    @Test
    void utf8BomEmittedOnce() throws IOException {
        byte[] bytes = write(sub(s -> s.setFirstName("Iva")), sub(s -> s.setFirstName("Ola")));

        assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
        assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
        assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);

        // BOM must not repeat mid-stream — only one occurrence of the 3-byte sequence in the file.
        assertThat(countBom(bytes)).isEqualTo(1);
    }

    @Test
    void columnOrderStable() throws IOException {
        List<String> lines = lines(write());
        assertThat(lines.get(0)).isEqualTo(HEADER);
    }

    @Test
    void rfc4180EscapesQuotesAndSeparators() throws IOException {
        byte[] bytes = write(sub(s -> {
            s.setFirstName("a,b");
            s.setLastName("quote\"inside");
            s.setUsername("line\nbreak");
        }));
        List<List<String>> rows = parse(bytes);
        List<String> data = rows.get(1);
        assertThat(data.get(4)).isEqualTo("a,b");                 // first_name — comma preserved
        assertThat(data.get(5)).isEqualTo("quote\"inside");       // last_name — embedded quote round-trips
        assertThat(data.get(6)).isEqualTo("line\nbreak");         // username — newline preserved inside quotes
    }

    @Test
    void tagsJoinedWithSemicolon() throws IOException {
        byte[] bytes = write(sub(s -> s.setTags(new ArrayList<>(List.of("vip", "paid")))));
        assertThat(parse(bytes).get(1).get(9)).isEqualTo("vip;paid");
    }

    @Test
    void customFieldsSerializedAsJsonString() throws IOException {
        Map<String, Object> cf = new LinkedHashMap<>();
        cf.put("city", "Kyiv");
        cf.put("age", 30);
        byte[] bytes = write(sub(s -> s.setCustomFields(cf)));

        String cell = parse(bytes).get(1).get(10);
        // Valid JSON object round-trips back to the same map.
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(cell, Map.class);
        assertThat(parsed).containsEntry("city", "Kyiv").containsEntry("age", 30);
    }

    @Test
    void iso8601UtcDateFormat() throws IOException {
        Instant ts = Instant.parse("2026-01-02T03:04:05Z");
        byte[] bytes = write(sub(s -> s.setSubscribedAt(ts)));
        assertThat(parse(bytes).get(1).get(11)).isEqualTo("2026-01-02T03:04:05Z").endsWith("Z");
    }

    @Test
    void formulaInjection_equalsSign_prefixedWithApostrophe() throws IOException {
        byte[] bytes = write(sub(s -> s.setFirstName("=SUM(A1:A99)")));
        assertThat(parse(bytes).get(1).get(4)).isEqualTo("'=SUM(A1:A99)");
    }

    @Test
    void formulaInjection_atSign_username_prefixed() throws IOException {
        byte[] bytes = write(sub(s -> s.setUsername("@evil")));
        assertThat(parse(bytes).get(1).get(6)).isEqualTo("'@evil");
    }

    @Test
    void formulaInjection_minusSign_lastName_prefixed() throws IOException {
        byte[] bytes = write(sub(s -> s.setLastName("-cmd|notepad")));
        assertThat(parse(bytes).get(1).get(5)).isEqualTo("'-cmd|notepad");
    }

    @Test
    void formulaInjection_customFieldsObjectCell_notPrefixed() throws IOException {
        // Whole-cell neutralization (Decision 7, anchor's permitted interpretation): the customFields
        // cell is the Jackson-serialized JSON, neutralized as ONE cell. A JSON object always
        // serializes starting with '{' (not a trigger), so the cell is emitted WITHOUT an apostrophe
        // — the embedded "+1+1" is inert because Excel does not evaluate a formula that is not the
        // cell's first char. This proves the whole-cell path runs and correctly leaves '{' unprefixed.
        Map<String, Object> cf = new LinkedHashMap<>();
        cf.put("city", "+1+1");
        byte[] bytes = write(sub(s -> s.setCustomFields(cf)));
        assertThat(parse(bytes).get(1).get(10)).isEqualTo("{\"city\":\"+1+1\"}");
    }

    @Test
    void formulaInjection_wholeCellHelper_prefixesTriggerFirstChar() {
        // Direct contract check of the whole-cell neutralization that the customFields path relies on:
        // a value whose first char is a trigger IS prefixed; a '{'-leading JSON object is not.
        assertThat(SubscriberCsvWriter.neutralizeFormula("=DANGER()")).isEqualTo("'=DANGER()");
        assertThat(SubscriberCsvWriter.neutralizeFormula("{\"k\":\"v\"}")).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void formulaInjection_tabAndCR_prefixed() throws IOException {
        byte[] tab = write(sub(s -> s.setFirstName("\tsneaky")));
        assertThat(parse(tab).get(1).get(4)).isEqualTo("'\tsneaky");

        byte[] cr = write(sub(s -> s.setLastName("\rsneaky")));
        assertThat(parse(cr).get(1).get(5)).isEqualTo("'\rsneaky");
    }

    @Test
    void formulaInjection_controlCase_safeFirstName_notPrefixed() throws IOException {
        byte[] bytes = write(sub(s -> s.setFirstName("Iva")));
        assertThat(parse(bytes).get(1).get(4)).isEqualTo("Iva");
    }

    @Test
    void emptyIterator_writesBomAndHeaderOnly() throws IOException {
        byte[] bytes = write();
        List<String> lines = lines(bytes);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo(HEADER);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    @SafeVarargs
    private byte[] write(Subscriber... subs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(List.of(subs).iterator(), out);
        return out.toByteArray();
    }

    private static Subscriber sub(Consumer<Subscriber> customizer) {
        Subscriber s = new Subscriber();
        s.setId("507f1f77bcf86cd799439011");
        s.setTelegramUserId(1000L);
        s.setTelegramChatId(2000L);
        s.setTelegramBotId(3000L);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setTags(new ArrayList<>());
        s.setSubscribedAt(Instant.parse("2026-01-01T00:00:00Z"));
        s.setLastSeenAt(Instant.parse("2026-01-01T00:00:00Z"));
        customizer.accept(s);
        return s;
    }

    // Strip the BOM and split into UTF-8 lines on '\n'.
    private static List<String> lines(byte[] bytes) {
        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String l : body.split("\n", -1)) {
            if (!l.isEmpty()) out.add(l);
        }
        return out;
    }

    // Minimal RFC-4180 parser (handles quoted fields with embedded comma / quote / newline).
    private static List<List<String>> parse(byte[] bytes) {
        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < body.length() && body.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static int countBom(byte[] bytes) {
        int count = 0;
        for (int i = 0; i + 2 < bytes.length; i++) {
            if ((bytes[i] & 0xFF) == 0xEF && (bytes[i + 1] & 0xFF) == 0xBB && (bytes[i + 2] & 0xFF) == 0xBF) {
                count++;
            }
        }
        return count;
    }
}
