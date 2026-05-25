package com.botfunnel.subscriber;

import com.botfunnel.common.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure translator: {@link SegmentFilter} → Spring Data {@link Query}. No Mongo I/O — only query
 * construction (covered by {@code SegmentFilterBuilderTest}). Also owns the strict cursor codec
 * (Decision 5), kept here so the validation rules sit next to the query they protect.
 */
@Component
public class SegmentFilterBuilder {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final int MIN_SEARCH_CHARS = 2;

    static final String CODE_INVALID_CURSOR = "invalid_cursor";
    private static final String MESSAGE_INVALID_CURSOR = "Invalid cursor";
    private static final Pattern OBJECT_ID_HEX = Pattern.compile("^[a-f0-9]{24}$");

    /**
     * Builds the project-scoped query. Text-search is applied as a FILTER (not a ranker): the result
     * order stays the cursor-deterministic {@code sortField desc, _id desc} so pagination never skips
     * or duplicates rows — text relevance ordering would break keyset pagination.
     */
    public Query build(String projectId, SegmentFilter filter) {
        List<Criteria> ands = new ArrayList<>();
        ands.add(Criteria.where("projectId").is(projectId));

        if (filter.status() != null) {
            ands.add(Criteria.where("status").is(filter.status()));
        }

        List<String> include = filter.tagsInclude();
        List<String> exclude = filter.tagsExclude();
        if (notEmpty(include) || notEmpty(exclude)) {
            Criteria tags = Criteria.where("tags");
            if (notEmpty(include)) {
                tags.all(include);
            }
            if (notEmpty(exclude)) {
                tags.nin(exclude);
            }
            ands.add(tags);
        }

        if (filter.subscribedFrom() != null || filter.subscribedTo() != null) {
            Criteria range = Criteria.where("subscribedAt");
            if (filter.subscribedFrom() != null) {
                range.gte(filter.subscribedFrom());
            }
            if (filter.subscribedTo() != null) {
                range.lte(filter.subscribedTo());
            }
            ands.add(range);
        }

        SegmentFilter.SortKey sort = filter.sort() == null ? SegmentFilter.SortKey.CREATED_DESC : filter.sort();
        String sortField = sort.field();

        if (filter.cursor() != null) {
            // Keyset tie-break: (sortField < v) OR (sortField == v AND _id < lastId).
            Instant v = Instant.ofEpochMilli(filter.cursor().v());
            ObjectId lastId = new ObjectId(filter.cursor().id());
            ands.add(new Criteria().orOperator(
                    Criteria.where(sortField).lt(v),
                    new Criteria().andOperator(
                            Criteria.where(sortField).is(v),
                            Criteria.where("_id").lt(lastId))));
        }

        Query query = new Query(new Criteria().andOperator(ands.toArray(new Criteria[0])));

        String search = filter.search();
        if (search != null && search.trim().length() >= MIN_SEARCH_CHARS) {
            // language="none" on the collection — whole-word match, no stemmer (Decision 6).
            query.addCriteria(TextCriteria.forDefaultLanguage().matchingAny(search.trim().split("\\s+")));
        }

        query.with(Sort.by(Sort.Order.desc(sortField), Sort.Order.desc("_id")));
        query.limit(clampLimit(filter.limit()));
        return query;
    }

    /** Single source of truth for page-size clamping — also used by the controller's fetch+1 probe. */
    public static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * Decodes + strictly validates an opaque base64url cursor (Decision 5, security F6). Any failure
     * is a uniform 400 {@code invalid_cursor} — never a 500, never a leaked row. Rules: valid base64url
     * → valid JSON object → keys exactly {@code {v, id}} → no {@code $}-prefixed key at any depth →
     * {@code v} is an integral number → {@code id} matches {@code ^[a-f0-9]{24}$}.
     */
    public static SegmentFilter.Cursor decodeCursor(String raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = java.util.Base64.getUrlDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(decoded);
        } catch (Exception e) {
            throw invalidCursor();
        }
        if (node == null || !node.isObject()) {
            throw invalidCursor();
        }
        assertExactlyVAndId(node);
        assertNoDollarKeys(node);

        JsonNode v = node.get("v");
        JsonNode id = node.get("id");
        if (v == null || !v.isNumber() || !v.canConvertToLong()) {
            throw invalidCursor();
        }
        if (id == null || !id.isTextual() || !OBJECT_ID_HEX.matcher(id.asText()).matches()) {
            throw invalidCursor();
        }
        return new SegmentFilter.Cursor(v.asLong(), id.asText());
    }

    /** Encodes a keyset cursor as compact base64url JSON {@code {"v":<epochMilli>,"id":<hex>}}. */
    public static String encodeCursor(long v, String id, ObjectMapper objectMapper) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            node.put("v", v);
            node.put("id", id);
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(node));
        } catch (Exception e) {
            // Never happens for a 2-field ObjectNode; surface as 500 rather than leak a half-cursor.
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, null, "cursor encode failed");
        }
    }

    private static void assertExactlyVAndId(JsonNode node) {
        int count = 0;
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!"v".equals(name) && !"id".equals(name)) {
                throw invalidCursor();
            }
            count++;
        }
        if (count != 2) {
            throw invalidCursor();
        }
    }

    // Recursively rejects any object field name starting with '$' — NoSQL operator-injection guard.
    private static void assertNoDollarKeys(JsonNode node) {
        if (node.isObject()) {
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                String name = it.next();
                if (name.startsWith("$")) {
                    throw invalidCursor();
                }
            }
            node.elements().forEachRemaining(SegmentFilterBuilder::assertNoDollarKeys);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(SegmentFilterBuilder::assertNoDollarKeys);
        }
    }

    private static AppException invalidCursor() {
        return new AppException(HttpStatus.BAD_REQUEST, CODE_INVALID_CURSOR, MESSAGE_INVALID_CURSOR);
    }

    private static boolean notEmpty(List<String> list) {
        return list != null && !list.isEmpty();
    }
}
