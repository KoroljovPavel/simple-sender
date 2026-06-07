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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the annotation-driven indexes for {@code funnels} and {@code funnel_executions} are
 * actually created at context startup (auto-index-creation=true) — keys, uniqueness, and the
 * lowercase partial-filter expressions that gate the trigger-conflict and re-enter guards.
 *
 * Tagged "slow" because it boots the full Testcontainers context only to inspect index metadata;
 * run with {@code -PrunSlow=true}.
 */
@Tag("slow")
class FunnelIndexesIT extends AbstractIntegrationTest {

    @Autowired
    MongoTemplate mongoTemplate;

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

        // partial-unique (projectId, triggerType, triggerValue) filtered status=active
        Document triggerUnique = byKey(idx,
                new Document("projectId", 1).append("triggerType", 1).append("triggerValue", 1));
        assertThat(triggerUnique.getBoolean("unique", false)).isTrue();

        // Phase 3 (Decision 1 + Task 2): the partial filter now carries BOTH status='active' AND
        // triggerType='on_start' — the old broad {status:'active'}-only filter is gone (the Task-2
        // reconciliation runner dropped it at context startup, and auto-index-creation recreated the
        // on_start-only shape from the Funnel annotation).
        Document pfe = triggerUnique.get("partialFilterExpression", Document.class);
        assertThat(pfe).as("triggerUnique partialFilterExpression").isNotNull();
        assertThat(pfe.getString("status")).isEqualTo("active");
        assertThat(pfe.getString("triggerType"))
                .as("Phase 3: uniqueness is scoped to on_start only")
                .isEqualTo("on_start");
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
     * The partial-unique guard must fire even for the bare "/start" case where triggerValue is the
     * empty string (""), not null — otherwise two active funnels could claim the same bare trigger.
     * Also proves the guard is scoped to (projectId, triggerType, triggerValue): two active funnels
     * with DIFFERENT triggerValue in the same project coexist.
     */
    @Test
    void activeFunnelTriggerUniquenessHoldsForEmptyTriggerValue() {
        String projectId = "proj-" + UUID.randomUUID();
        try {
            // First active funnel with empty-string triggerValue inserts cleanly.
            mongoTemplate.insert(activeFunnel(projectId, ""));

            // Second active funnel, SAME (projectId, triggerType, triggerValue="") -> duplicate key.
            assertThatThrownBy(() -> mongoTemplate.insert(activeFunnel(projectId, "")))
                    .as("partial-unique guard must fire for empty-string triggerValue")
                    .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);

            // Two active funnels with DIFFERENT triggerValue in the same project both insert.
            mongoTemplate.insert(activeFunnel(projectId, "promo"));
            mongoTemplate.insert(activeFunnel(projectId, "vip"));

            long active = mongoTemplate.count(
                    new Query(Criteria.where("projectId").is(projectId).and("status").is(FunnelStatus.active)),
                    Funnel.class);
            assertThat(active)
                    .as("one empty-trigger funnel + two distinct-trigger funnels")
                    .isEqualTo(3);
        } finally {
            mongoTemplate.remove(new Query(Criteria.where("projectId").is(projectId)), Funnel.class);
        }
    }

    /**
     * Phase 3 fan-out (Decision 1): two active {@code event} funnels in the same project sharing the SAME
     * triggerValue must coexist — the relaxed partial filter ({@code triggerType:'on_start'}) no longer
     * applies uniqueness to {@code event}. Proves the index migration unblocked fan-out.
     */
    @Test
    void twoActiveEventFunnelsWithSameTriggerValueCoexist() {
        String projectId = "proj-" + UUID.randomUUID();
        try {
            mongoTemplate.insert(activeFunnel(projectId, "event", "purchase"));

            assertThatCode(() -> mongoTemplate.insert(activeFunnel(projectId, "event", "purchase")))
                    .as("two active event funnels with the same triggerValue must coexist (fan-out)")
                    .doesNotThrowAnyException();

            long active = mongoTemplate.count(
                    new Query(Criteria.where("projectId").is(projectId)
                            .and("triggerType").is("event").and("status").is(FunnelStatus.active)),
                    Funnel.class);
            assertThat(active).as("both event funnels persisted").isEqualTo(2);
        } finally {
            mongoTemplate.remove(new Query(Criteria.where("projectId").is(projectId)), Funnel.class);
        }
    }

    /**
     * Phase 3 (Decision 1): the {@code on_start} uniqueness guard survives the relax — two active
     * {@code on_start} funnels in the same project with the same triggerValue still collide.
     */
    @Test
    void twoActiveOnStartFunnelsWithSamePayloadStillCollide() {
        String projectId = "proj-" + UUID.randomUUID();
        try {
            mongoTemplate.insert(activeFunnel(projectId, "on_start", "promo"));

            assertThatThrownBy(() -> mongoTemplate.insert(activeFunnel(projectId, "on_start", "promo")))
                    .as("two active on_start funnels with the same triggerValue must still collide")
                    .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);
        } finally {
            mongoTemplate.remove(new Query(Criteria.where("projectId").is(projectId)), Funnel.class);
        }
    }

    private Funnel activeFunnel(String projectId, String triggerValue) {
        return activeFunnel(projectId, "on_start", triggerValue);
    }

    private Funnel activeFunnel(String projectId, String triggerType, String triggerValue) {
        Funnel f = new Funnel();
        f.setProjectId(projectId);
        f.setName("funnel-" + UUID.randomUUID());
        f.setStatus(FunnelStatus.active);
        f.setTriggerType(triggerType);
        f.setTriggerValue(triggerValue);
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        return f;
    }
}
