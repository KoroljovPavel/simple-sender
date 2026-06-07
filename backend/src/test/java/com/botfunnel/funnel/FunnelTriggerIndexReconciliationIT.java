package com.botfunnel.funnel;

import com.mongodb.client.model.IndexOptions;
import com.botfunnel.AbstractIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision 9 (mechanism revised — see {@link FunnelTriggerIndexReconciliation}): the reconciliation
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} drops the OLD broad-filter trigger
 * index ({@code {status:'active'}}) so auto-index-creation recreates the Phase-3
 * {@code {status:'active', triggerType:'on_start'}} shape. It must be idempotent (driven by the actual
 * partialFilterExpression, not the index name) and log-but-never-throw.
 *
 * <p>The BPP fires once during context startup against the {@link MongoDatabaseFactory}; these tests
 * re-invoke {@link FunnelTriggerIndexReconciliation#postProcessAfterInitialization} directly (on a fresh
 * runner instance, because the bean's once-per-boot guard has already tripped) to exercise each branch
 * against real Mongo.
 *
 * <p>Tagged "slow": boots the full Testcontainers context.
 */
@Tag("slow")
class FunnelTriggerIndexReconciliationIT extends AbstractIntegrationTest {

    private static final String INDEX_NAME = "projectId_triggerType_triggerValue_unique_active";
    private static final Document KEY =
            new Document("projectId", 1).append("triggerType", 1).append("triggerValue", 1);

    @Autowired
    MongoTemplate mongoTemplate;

    @Autowired
    MongoDatabaseFactory mongoDatabaseFactory;

    // A fresh runner per call: the Spring-managed singleton's once-per-boot guard tripped at startup.
    private void runReconciliation() {
        new FunnelTriggerIndexReconciliation()
                .postProcessAfterInitialization(mongoDatabaseFactory, "mongoDatabaseFactory");
    }

    private List<Document> indexes() {
        List<Document> result = new ArrayList<>();
        mongoTemplate.getCollection("funnels").listIndexes().into(result);
        return result;
    }

    private Document triggerIndex() {
        return indexes().stream()
                .filter(ix -> INDEX_NAME.equals(ix.getString("name")))
                .findFirst()
                .orElse(null);
    }

    private void dropTriggerIndex() {
        if (triggerIndex() != null) {
            mongoTemplate.getCollection("funnels").dropIndex(INDEX_NAME);
        }
    }

    /** Recreates the OLD broad-filter index ({status:'active'} only) to simulate an un-migrated DB. */
    private void installOldBroadFilterIndex() {
        dropTriggerIndex();
        mongoTemplate.getCollection("funnels").createIndex(
                KEY,
                new IndexOptions()
                        .name(INDEX_NAME)
                        .unique(true)
                        .partialFilterExpression(new Document("status", "active")));
    }

    /** Installs the NEW on_start-only shape directly (simulates an already-migrated DB). */
    private void installNewShapeIndex() {
        dropTriggerIndex();
        mongoTemplate.getCollection("funnels").createIndex(
                KEY,
                new IndexOptions()
                        .name(INDEX_NAME)
                        .unique(true)
                        .partialFilterExpression(
                                new Document("status", "active").append("triggerType", "on_start")));
    }

    @Test
    void migrationDropsOldBroadFilterIndex() {
        installOldBroadFilterIndex();
        Document before = triggerIndex();
        assertThat(before).isNotNull();
        assertThat(before.get("partialFilterExpression", Document.class).containsKey("triggerType")).isFalse();

        runReconciliation();

        // The reconciliation owns only the DROP (auto-index-creation, which recreates the new shape, runs
        // only at context startup, not here). After the drop the old broad-filter index is gone.
        assertThat(triggerIndex())
                .as("old broad-filter index must be gone after the reconciliation drops it")
                .isNull();

        // Simulate auto-index-creation recreating the annotation shape; assert it carries the new filter.
        installNewShapeIndex();
        Document recreated = triggerIndex();
        assertThat(recreated).isNotNull();
        Document pfe = recreated.get("partialFilterExpression", Document.class);
        assertThat(pfe.getString("status")).isEqualTo("active");
        assertThat(pfe.getString("triggerType")).isEqualTo("on_start");
    }

    @Test
    void reconciliationIsIdempotentNoOpOnSecondBoot() {
        installNewShapeIndex();
        Document before = triggerIndex();
        assertThat(before).isNotNull();

        runReconciliation();

        Document after = triggerIndex();
        assertThat(after)
                .as("index already in new shape must be left untouched")
                .isNotNull();
        Document pfe = after.get("partialFilterExpression", Document.class);
        assertThat(pfe.getString("status")).isEqualTo("active");
        assertThat(pfe.getString("triggerType")).isEqualTo("on_start");
        // The reconciliation did not drop+recreate: same key + filter.
        assertThat(after.get("key", Document.class)).isEqualTo(before.get("key", Document.class));
        assertThat(pfe).isEqualTo(before.get("partialFilterExpression", Document.class));
    }

    @Test
    void reconciliationNoOpOnFreshDatabase() {
        dropTriggerIndex();
        assertThat(triggerIndex()).as("index absent precondition").isNull();

        assertThatCode(this::runReconciliation).doesNotThrowAnyException();

        // Still absent — the reconciliation owns only the drop; auto-index-creation owns creation.
        assertThat(triggerIndex())
                .as("reconciliation must not create the index on a fresh DB")
                .isNull();
    }

    @Test
    void reconciliationNeverThrowsOnMongoFault() {
        // A factory whose driver points at a dead address: getCollection/listIndexes throws, and the
        // body's try/catch must swallow it so the app still boots.
        MongoDatabaseFactory broken = new org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory(
                com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=200"),
                "botfunnel-broken");
        FunnelTriggerIndexReconciliation faulting = new FunnelTriggerIndexReconciliation();

        assertThatCode(() -> faulting.postProcessAfterInitialization(broken, "mongoDatabaseFactory"))
                .as("a transient Mongo fault must be swallowed — the app must boot")
                .doesNotThrowAnyException();
    }

    /**
     * End-to-end fan-out proof: two active {@code event} funnels with the same triggerValue coexist;
     * two active {@code on_start} funnels with the same payload still collide. Uses the new-shape index
     * (the same shape auto-index-creation produces).
     */
    @Test
    void twoActiveEventFunnelsCoexistAfterMigration() {
        installNewShapeIndex();
        String projectId = "proj-" + UUID.randomUUID();
        try {
            mongoTemplate.insert(activeFunnel(projectId, "event", "purchase"));
            assertThatCode(() -> mongoTemplate.insert(activeFunnel(projectId, "event", "purchase")))
                    .as("two active event funnels with the same triggerValue must coexist")
                    .doesNotThrowAnyException();

            mongoTemplate.insert(activeFunnel(projectId, "on_start", "promo"));
            assertThatThrownBy(() -> mongoTemplate.insert(activeFunnel(projectId, "on_start", "promo")))
                    .as("two active on_start funnels with the same payload must still collide")
                    .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);
        } finally {
            mongoTemplate.getCollection("funnels").deleteMany(new Document("projectId", projectId));
        }
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
