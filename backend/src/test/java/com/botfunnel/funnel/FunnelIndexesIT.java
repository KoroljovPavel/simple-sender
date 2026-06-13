package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the annotation-driven indexes for {@code funnels} and {@code funnel_executions} are
 * actually created at context startup (auto-index-creation=true) — keys, uniqueness, and the
 * lowercase partial-filter expressions that gate the trigger-conflict and re-enter guards.
 *
 * <p>Phase 8 (17-funnel-multi-entry / Decision 6): the {@code funnels} trigger guard is now the
 * {@code {projectId, onStartTriggerValue}} partial-unique index filtered
 * {@code {status:'active', onStartTriggerValue:{$exists:true}}}; the old flat-trio index is gone (the
 * Task-2 reconciliation dropped it at startup). This class also covers the array-aware repository derived
 * queries against real embedded Mongo.
 *
 * <p>Tagged "slow" because it boots the full Testcontainers context; run with {@code -PrunSlow=true}.
 */
@Tag("slow")
class FunnelIndexesIT extends AbstractIntegrationTest {

    @Autowired
    MongoTemplate mongoTemplate;

    @Autowired
    FunnelRepository funnelRepository;

    private List<Document> indexes(String collection) {
        List<Document> result = new ArrayList<>();
        mongoTemplate.getCollection(collection).listIndexes().into(result);
        return result;
    }

    private Document byKey(List<Document> indexes, Document expectedKey) {
        return indexes.stream()
                .filter(ix -> expectedKey.equals(ix.get("key", Document.class)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No index with key " + expectedKey.toJson() + " in " + indexes));
    }

    private Optional<Document> findByKey(List<Document> indexes, Document expectedKey) {
        return indexes.stream()
                .filter(ix -> expectedKey.equals(ix.get("key", Document.class)))
                .findFirst();
    }

    @Test
    void contextStartsAndCollectionsExistWithoutError() {
        // Touch both collections so they are materialised; auto-index-creation runs on startup.
        assertThat(mongoTemplate.getCollectionNames()).contains("funnels", "funnel_executions");

        // Non-vacuous: force real collection + auto-index creation by listing indexes. Every
        // collection always carries at least the _id_ index, so a non-empty list proves the
        // collection (and its index set) was actually created rather than silently absent.
        assertThat(indexes("funnels")).isNotEmpty();
        assertThat(indexes("funnel_executions")).isNotEmpty();
    }

    @Test
    void funnelsCollectionHasExpectedIndexes() {
        List<Document> idx = indexes("funnels");

        // (projectId, status) lookup/list index — byKey throws if absent.
        byKey(idx, new Document("projectId", 1).append("status", 1));

        // partial-unique (projectId, onStartTriggerValue) filtered {status:'active', onStartTriggerValue:{$exists:true}}
        Document onStartUnique = byKey(idx,
                new Document("projectId", 1).append("onStartTriggerValue", 1));
        assertThat(onStartUnique.getBoolean("unique", false)).isTrue();

        Document pfe = onStartUnique.get("partialFilterExpression", Document.class);
        assertThat(pfe).as("onStartUnique partialFilterExpression").isNotNull();
        assertThat(pfe.getString("status")).isEqualTo("active");
        assertThat(pfe.get("onStartTriggerValue", Document.class))
                .as("uniqueness is scoped to funnels that HAVE an on_start payload ($exists guard)")
                .isEqualTo(new Document("$exists", true));

        // The old flat-trio trigger index must be gone (dropped by the Task-2 reconciliation at startup).
        assertThat(findByKey(idx,
                new Document("projectId", 1).append("triggerType", 1).append("triggerValue", 1)))
                .as("old {projectId,triggerType,triggerValue} index must be absent")
                .isEmpty();
    }

    @Test
    void funnelExecutionsCollectionHasExpectedIndexes() {
        List<Document> idx = indexes("funnel_executions");

        // (status, nextRunAt) sweep-claim index — byKey throws if absent.
        byKey(idx, new Document("status", 1).append("nextRunAt", 1));

        // (projectId) cascade index — byKey throws if absent.
        byKey(idx, new Document("projectId", 1));

        // unique partial (funnelId, subscriberId) filtered status IN [running, waiting]
        Document reenter = byKey(idx,
                new Document("funnelId", 1).append("subscriberId", 1));
        assertThat(reenter.getBoolean("unique", false))
                .as("re-enter guard must be unique").isTrue();

        Document pfe = reenter.get("partialFilterExpression", Document.class);
        assertThat(pfe).as("re-enter partialFilterExpression").isNotNull();

        Document statusClause = pfe.get("status", Document.class);
        assertThat(statusClause).as("status clause in partialFilterExpression").isNotNull();

        @SuppressWarnings("unchecked")
        List<String> in = (List<String>) statusClause.get("$in");
        assertThat(in)
                .as("partialFilter status $in literals (lowercase, declared order)")
                .containsExactly("running", "waiting", "waiting_for_reply");
    }

    /**
     * The partial-unique guard fires for two active funnels sharing the same non-null
     * {@code onStartTriggerValue} (including the bare "/start" empty-string payload), but never binds
     * event-only funnels whose {@code onStartTriggerValue} is null — a partial {@code {$exists:true}} index
     * does not index documents whose key is absent (Decision 6, Variant A).
     */
    @Test
    void onStartUniquenessHoldsAndNullDoesNotBind() {
        String projectId = "proj-" + UUID.randomUUID();
        try {
            // Two active funnels with the same non-null onStartTriggerValue → duplicate key.
            mongoTemplate.insert(onStartFunnel(projectId, "promo"));
            assertThatThrownBy(() -> mongoTemplate.insert(onStartFunnel(projectId, "promo")))
                    .as("two active funnels with the same onStartTriggerValue must collide")
                    .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);

            // Bare /start (empty-string onStartTriggerValue) is still indexed (not null) → uniqueness fires.
            mongoTemplate.insert(onStartFunnel(projectId, ""));
            assertThatThrownBy(() -> mongoTemplate.insert(onStartFunnel(projectId, "")))
                    .as("empty-string onStartTriggerValue is indexed (not null) → guard fires")
                    .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);

            // Multiple active event-only funnels (null onStartTriggerValue) sharing an event value coexist.
            mongoTemplate.insert(eventOnlyFunnel(projectId, "purchase"));
            assertThatCode(() -> mongoTemplate.insert(eventOnlyFunnel(projectId, "purchase")))
                    .as("event-only funnels (null onStartTriggerValue) never bind the partial-unique index")
                    .doesNotThrowAnyException();
        } finally {
            mongoTemplate.remove(new Query(Criteria.where("projectId").is(projectId)), Funnel.class);
        }
    }

    /**
     * Array-aware fan-out derived query: {@code findByProjectIdAndTriggersTriggerTypeAndTriggersTriggerValueAndStatus}
     * matches funnels whose {@code triggers[]} contains a matching {@code (triggerType, triggerValue)}
     * element (multikey element-match) and excludes non-matching funnels.
     */
    @Test
    void findByTriggersTriggerTypeAndValueMatchesArrayElement() {
        String projectId = "proj-" + UUID.randomUUID();
        try {
            // A funnel whose triggers[] contains the event element AND an unrelated keyword element.
            Funnel match = activeFunnel(projectId);
            match.setTriggers(List.of(
                    trigger("keyword", null, List.of("hi")),
                    trigger("event", "purchase", null)));
            mongoTemplate.insert(match);

            // A funnel with a non-matching event value.
            Funnel other = activeFunnel(projectId);
            other.setTriggers(List.of(trigger("event", "refund", null)));
            mongoTemplate.insert(other);

            List<Funnel> found = funnelRepository
                    .findByProjectIdAndTriggersTriggerTypeAndTriggersTriggerValueAndStatus(
                            projectId, "event", "purchase", FunnelStatus.active);

            assertThat(found)
                    .as("only the funnel whose triggers[] contains (event, purchase) matches")
                    .extracting(Funnel::getId)
                    .containsExactly(match.getId());
        } finally {
            mongoTemplate.remove(new Query(Criteria.where("projectId").is(projectId)), Funnel.class);
        }
    }

    /**
     * Cross-element exclusion: the {@code $elemMatch} fan-out query must NOT return a funnel whose
     * {@code triggerType} and {@code triggerValue} match in DIFFERENT array elements. A funnel
     * {@code [{keyword,purchase},{event,other}]} would be a FALSE POSITIVE under Spring Data's flat
     * two-field derived predicate ({@code type='event'} matches element 1, {@code value='purchase'} matches
     * element 0). The {@code @Query} {@code $elemMatch} pins both conditions to the SAME element, so this
     * funnel is correctly excluded by a {@code (event, purchase)} query.
     */
    @Test
    void findByTriggersTriggerTypeAndValueExcludesCrossElementMatch() {
        String projectId = "proj-" + UUID.randomUUID();
        try {
            // No SINGLE element matches (event, purchase): purchase lives on the keyword element, event on the
            // 'other' element. Flat derived predicates would falsely match; $elemMatch must not.
            Funnel crossElement = activeFunnel(projectId);
            crossElement.setTriggers(List.of(
                    trigger("keyword", "purchase", List.of("purchase")),
                    trigger("event", "other", null)));
            mongoTemplate.insert(crossElement);

            List<Funnel> found = funnelRepository
                    .findByProjectIdAndTriggersTriggerTypeAndTriggersTriggerValueAndStatus(
                            projectId, "event", "purchase", FunnelStatus.active);

            assertThat(found)
                    .as("$elemMatch must NOT return a funnel whose type and value match in different elements")
                    .isEmpty();
        } finally {
            mongoTemplate.remove(new Query(Criteria.where("projectId").is(projectId)), Funnel.class);
        }
    }

    /**
     * {@code findByProjectIdAndOnStartTriggerValueAndStatus} resolves the on_start funnel by the
     * denormalized scalar (the Task-4 conflict pre-check / Task-5 fire lookup).
     */
    @Test
    void findByOnStartTriggerValueResolvesScalar() {
        String projectId = "proj-" + UUID.randomUUID();
        try {
            Funnel onStart = onStartFunnel(projectId, "promo");
            mongoTemplate.insert(onStart);
            // A noise funnel with a different on_start payload.
            mongoTemplate.insert(onStartFunnel(projectId, "vip"));

            Optional<Funnel> found = funnelRepository
                    .findByProjectIdAndOnStartTriggerValueAndStatus(projectId, "promo", FunnelStatus.active);

            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(onStart.getId());
        } finally {
            mongoTemplate.remove(new Query(Criteria.where("projectId").is(projectId)), Funnel.class);
        }
    }

    /**
     * {@code findByProjectIdAndTriggersTriggerTypeAndStatus} returns all active funnels carrying a
     * keyword-type trigger element (the candidate set for the in-code contains-match).
     */
    @Test
    void findByTriggersTriggerTypeReturnsAllKeywordFunnels() {
        String projectId = "proj-" + UUID.randomUUID();
        try {
            Funnel kw1 = activeFunnel(projectId);
            kw1.setTriggers(List.of(trigger("keyword", null, List.of("buy"))));
            mongoTemplate.insert(kw1);

            Funnel kw2 = activeFunnel(projectId);
            kw2.setTriggers(List.of(trigger("keyword", null, List.of("sale", "deal"))));
            mongoTemplate.insert(kw2);

            // An event-only funnel must NOT appear in the keyword candidate set.
            Funnel ev = activeFunnel(projectId);
            ev.setTriggers(List.of(trigger("event", "purchase", null)));
            mongoTemplate.insert(ev);

            List<Funnel> found = funnelRepository
                    .findByProjectIdAndTriggersTriggerTypeAndStatus(projectId, "keyword", FunnelStatus.active);

            assertThat(found)
                    .extracting(Funnel::getId)
                    .containsExactlyInAnyOrder(kw1.getId(), kw2.getId());
        } finally {
            mongoTemplate.remove(new Query(Criteria.where("projectId").is(projectId)), Funnel.class);
        }
    }

    private Trigger trigger(String triggerType, String triggerValue, List<String> keywords) {
        Trigger t = new Trigger();
        t.setTriggerType(triggerType);
        t.setTriggerValue(triggerValue);
        t.setKeywords(keywords);
        return t;
    }

    private Funnel onStartFunnel(String projectId, String onStartValue) {
        Funnel f = activeFunnel(projectId);
        f.setTriggers(List.of(trigger("on_start", onStartValue, null)));
        f.setOnStartTriggerValue(onStartValue);
        return f;
    }

    private Funnel eventOnlyFunnel(String projectId, String eventValue) {
        Funnel f = activeFunnel(projectId);
        f.setTriggers(List.of(trigger("event", eventValue, null)));
        f.setOnStartTriggerValue(null);
        return f;
    }

    private Funnel activeFunnel(String projectId) {
        Funnel f = new Funnel();
        f.setProjectId(projectId);
        f.setName("funnel-" + UUID.randomUUID());
        f.setStatus(FunnelStatus.active);
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        return f;
    }
}
