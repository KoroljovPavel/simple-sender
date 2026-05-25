package com.botfunnel.subscriber;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.botfunnel.common.AppException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Pure translator unit test (no Spring, no Mongo). Asserts the produced Query document/sort/limit and
// the strict cursor codec (Decision 5). The build() output wraps field criteria in a top-level $and.
class SegmentFilterBuilderTest {

    private final SegmentFilterBuilder builder = new SegmentFilterBuilder();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PROJECT = "proj-1";
    private static final String HEX_ID = "0123456789abcdef01234567";

    private SegmentFilter filter(SubscriberStatus status, List<String> include, List<String> exclude,
                                 Instant from, Instant to, String search,
                                 SegmentFilter.SortKey sort, SegmentFilter.Cursor cursor, int limit) {
        return new SegmentFilter(search, status, include, exclude, from, to, sort, cursor, limit);
    }

    private SegmentFilter empty() {
        return filter(null, null, null, null, null, null, SegmentFilter.SortKey.CREATED_DESC, null, 0);
    }

    @SuppressWarnings("unchecked")
    private List<Document> and(Query q) {
        return (List<Document>) q.getQueryObject().get("$and");
    }

    private Document andEntryWith(Query q, String key) {
        return and(q).stream().filter(d -> d.containsKey(key)).findFirst().orElseThrow();
    }

    @Test
    void build_emptyFilter_returnsBareProjectScopedQuery() {
        Query q = builder.build(PROJECT, empty());
        assertThat(and(q)).hasSize(1);
        assertThat(andEntryWith(q, "projectId")).containsEntry("projectId", PROJECT);
    }

    @Test
    void build_statusOnly_addsStatusCriterion() {
        Query q = builder.build(PROJECT, filter(SubscriberStatus.UNSUBSCRIBED, null, null, null, null,
                null, SegmentFilter.SortKey.CREATED_DESC, null, 0));
        assertThat(andEntryWith(q, "status")).containsEntry("status", SubscriberStatus.UNSUBSCRIBED);
    }

    @Test
    void build_tagsInclude_appliesAll() {
        Query q = builder.build(PROJECT, filter(null, List.of("vip", "gold"), null, null, null,
                null, SegmentFilter.SortKey.CREATED_DESC, null, 0));
        Document tags = (Document) andEntryWith(q, "tags").get("tags");
        assertThat(tags.get("$all")).isEqualTo(List.of("vip", "gold"));
        assertThat(tags).doesNotContainKey("$nin");
    }

    @Test
    void build_tagsExclude_appliesNin() {
        Query q = builder.build(PROJECT, filter(null, null, List.of("spam"), null, null,
                null, SegmentFilter.SortKey.CREATED_DESC, null, 0));
        Document tags = (Document) andEntryWith(q, "tags").get("tags");
        assertThat(tags.get("$nin")).isEqualTo(List.of("spam"));
        assertThat(tags).doesNotContainKey("$all");
    }

    @Test
    void build_tagsBoth_combinesAllAndNin() {
        Query q = builder.build(PROJECT, filter(null, List.of("vip"), List.of("spam"), null, null,
                null, SegmentFilter.SortKey.CREATED_DESC, null, 0));
        Document tags = (Document) andEntryWith(q, "tags").get("tags");
        assertThat(tags.get("$all")).isEqualTo(List.of("vip"));
        assertThat(tags.get("$nin")).isEqualTo(List.of("spam"));
    }

    @Test
    void build_dateRange_appliesBetween() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");

        Document both = (Document) andEntryWith(builder.build(PROJECT, filter(null, null, null, from, to,
                null, SegmentFilter.SortKey.CREATED_DESC, null, 0)), "subscribedAt").get("subscribedAt");
        assertThat(both).containsEntry("$gte", from).containsEntry("$lte", to);

        Document fromOnly = (Document) andEntryWith(builder.build(PROJECT, filter(null, null, null, from, null,
                null, SegmentFilter.SortKey.CREATED_DESC, null, 0)), "subscribedAt").get("subscribedAt");
        assertThat(fromOnly).containsEntry("$gte", from).doesNotContainKey("$lte");

        Document toOnly = (Document) andEntryWith(builder.build(PROJECT, filter(null, null, null, null, to,
                null, SegmentFilter.SortKey.CREATED_DESC, null, 0)), "subscribedAt").get("subscribedAt");
        assertThat(toOnly).containsEntry("$lte", to).doesNotContainKey("$gte");
    }

    @Test
    void build_textSearch_under2Chars_skipsTextCriterion() {
        Query q = builder.build(PROJECT, filter(null, null, null, null, null, "a",
                SegmentFilter.SortKey.CREATED_DESC, null, 0));
        assertThat(q.getQueryObject()).doesNotContainKey("$text");
    }

    @Test
    void build_textSearch_appliesTextCriteriaWithMatchingAny() {
        Query q = builder.build(PROJECT, filter(null, null, null, null, null, "iva kyiv",
                SegmentFilter.SortKey.CREATED_DESC, null, 0));
        Document text = (Document) q.getQueryObject().get("$text");
        assertThat(text).isNotNull();
        // matchingAny joins terms with a space (Mongo OR semantics).
        assertThat(text.getString("$search")).isEqualTo("iva kyiv");
        // forDefaultLanguage() emits no explicit $language → inherits the collection's language="none".
        assertThat(text).doesNotContainKey("$language");
    }

    @Test
    void build_textAndTags_combined_bothApplied() {
        Query q = builder.build(PROJECT, filter(null, List.of("vip"), null, null, null, "iva",
                SegmentFilter.SortKey.CREATED_DESC, null, 0));
        assertThat(andEntryWith(q, "tags").get("tags")).isNotNull();
        assertThat(q.getQueryObject().get("$text")).isNotNull();
    }

    @Test
    void build_sortCreatedDesc_byDescOnSubscribedAt() {
        Query q = builder.build(PROJECT, empty());
        Document sort = q.getSortObject();
        assertThat(sort.getInteger("subscribedAt")).isEqualTo(-1);
        assertThat(sort.getInteger("_id")).isEqualTo(-1);
    }

    @Test
    void build_sortLastSeenDesc_byDescOnLastSeenAt() {
        Query q = builder.build(PROJECT, filter(null, null, null, null, null, null,
                SegmentFilter.SortKey.LAST_SEEN_DESC, null, 0));
        Document sort = q.getSortObject();
        assertThat(sort.getInteger("lastSeenAt")).isEqualTo(-1);
        assertThat(sort.getInteger("_id")).isEqualTo(-1);
    }

    @Test
    void build_cursorAppliesTieBreakOr_lastSortLessThanOrEqualWithIdLess() {
        long v = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli();
        SegmentFilter.Cursor cursor = new SegmentFilter.Cursor(v, HEX_ID);
        Query q = builder.build(PROJECT, filter(null, null, null, null, null, null,
                SegmentFilter.SortKey.CREATED_DESC, cursor, 0));

        @SuppressWarnings("unchecked")
        List<Document> or = (List<Document>) andEntryWith(q, "$or").get("$or");
        assertThat(or).hasSize(2);
        // branch 1: subscribedAt < v
        Document lt = (Document) or.get(0).get("subscribedAt");
        assertThat(lt).containsEntry("$lt", Instant.ofEpochMilli(v));
        // branch 2: subscribedAt == v AND _id < lastId
        @SuppressWarnings("unchecked")
        List<Document> innerAnd = (List<Document>) or.get(1).get("$and");
        assertThat(innerAnd.get(0)).containsEntry("subscribedAt", Instant.ofEpochMilli(v));
        Document idLt = (Document) innerAnd.get(1).get("_id");
        assertThat(idLt.get("$lt")).isEqualTo(new ObjectId(HEX_ID));
    }

    @Test
    void build_limitDefault50_appliedToQuery() {
        assertThat(builder.build(PROJECT, empty()).getLimit()).isEqualTo(50);
    }

    @Test
    void build_limitMax200_clamped() {
        Query q = builder.build(PROJECT, filter(null, null, null, null, null, null,
                SegmentFilter.SortKey.CREATED_DESC, null, 500));
        assertThat(q.getLimit()).isEqualTo(200);
    }

    // ─── cursor codec (Decision 5, security F6) ─────────────────────────────

    private String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
    }

    @Test
    void decodeCursor_nullOrBlank_returnsNull() {
        assertThat(SegmentFilterBuilder.decodeCursor(null, objectMapper)).isNull();
        assertThat(SegmentFilterBuilder.decodeCursor("  ", objectMapper)).isNull();
    }

    @Test
    void decodeCursor_validRoundTrip_decodes() {
        String encoded = SegmentFilterBuilder.encodeCursor(1736942400000L, HEX_ID, objectMapper);
        SegmentFilter.Cursor cursor = SegmentFilterBuilder.decodeCursor(encoded, objectMapper);
        assertThat(cursor.v()).isEqualTo(1736942400000L);
        assertThat(cursor.id()).isEqualTo(HEX_ID);
    }

    @Test
    void decodeCursor_malformedBase64_throwsInvalidCursor() {
        assertInvalidCursor("!!!not-base64!!!");
    }

    @Test
    void decodeCursor_notAnObject_throwsInvalidCursor() {
        assertInvalidCursor(b64("[1,2,3]"));
    }

    @Test
    void decodeCursor_unknownKeys_throwsInvalidCursor() {
        assertInvalidCursor(b64("{\"v\":1,\"id\":\"" + HEX_ID + "\",\"extra\":2}"));
    }

    @Test
    void decodeCursor_dollarPrefixedKey_throwsInvalidCursor() {
        // NoSQL operator injection attempt — v is an object carrying a $-prefixed operator.
        assertInvalidCursor(b64("{\"v\":{\"$ne\":null},\"id\":\"" + HEX_ID + "\"}"));
    }

    @Test
    void decodeCursor_vNotPrimitive_throwsInvalidCursor() {
        assertInvalidCursor(b64("{\"v\":[1],\"id\":\"" + HEX_ID + "\"}"));
    }

    @Test
    void decodeCursor_idNot24LowercaseHex_throwsInvalidCursor() {
        assertInvalidCursor(b64("{\"v\":1,\"id\":\"ABC\"}"));
        assertInvalidCursor(b64("{\"v\":1,\"id\":\"0123456789ABCDEF01234567\"}")); // uppercase rejected
    }

    private void assertInvalidCursor(String raw) {
        assertThatThrownBy(() -> SegmentFilterBuilder.decodeCursor(raw, objectMapper))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo("invalid_cursor"));
    }
}
