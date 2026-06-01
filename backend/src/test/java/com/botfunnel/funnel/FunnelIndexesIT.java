package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    }

    @Test
    void funnelsCollectionHasExpectedIndexes() {
        List<Document> idx = indexes("funnels");

        // (projectId, status) lookup/list index
        Document lookup = byKey(idx, new Document("projectId", 1).append("status", 1));
        assertThat(lookup).isNotNull();

        // partial-unique (projectId, triggerType, triggerValue) filtered status=active
        Document triggerUnique = byKey(idx,
                new Document("projectId", 1).append("triggerType", 1).append("triggerValue", 1));
        assertThat(triggerUnique.getBoolean("unique", false)).isTrue();

        Document pfe = triggerUnique.get("partialFilterExpression", Document.class);
        assertThat(pfe).as("triggerUnique partialFilterExpression").isNotNull();
        assertThat(pfe.getString("status")).isEqualTo("active");
    }

    @Test
    void funnelExecutionsCollectionHasExpectedIndexes() {
        List<Document> idx = indexes("funnel_executions");

        // (status, nextRunAt) sweep-claim index
        Document sweep = byKey(idx, new Document("status", 1).append("nextRunAt", 1));
        assertThat(sweep).isNotNull();

        // (projectId) cascade index
        Document cascade = byKey(idx, new Document("projectId", 1));
        assertThat(cascade).isNotNull();

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
                .containsExactly("running", "waiting");
    }
}
