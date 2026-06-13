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
 * Phase 8 (17-funnel-multi-entry / Decision 7): the reconciliation
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} DROPS the OLD flat-trio trigger index
 * ({@code projectId_triggerType_triggerValue_unique_active}, key {@code {projectId,triggerType,triggerValue}})
 * so auto-index-creation can lay down the NEW {@code {projectId, onStartTriggerValue}} partial-unique shape
 * without an {@code IndexKeySpecsConflict}. It must be idempotent (driven by the PRESENCE of the OLD index
 * by name — the new index has a different key and name, so it is never mistaken for the old one) and
 * log-but-never-throw.
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

    // OLD flat-trio trigger index — the one the reconciliation drops.
    private static final String OLD_INDEX_NAME = "projectId_triggerType_triggerValue_unique_active";
    private static final Document OLD_KEY =
            new Document("projectId", 1).append("triggerType", 1).append("triggerValue", 1);

    // NEW onStartTriggerValue partial-unique index — the Task-1 annotation shape (auto-index-creation owns
    // its creation at startup; the helper here only simulates that for the test branches).
    private static final String NEW_INDEX_NAME = "projectId_onStartTriggerValue_unique_active";
    private static final Document NEW_KEY =
            new Document("projectId", 1).append("onStartTriggerValue", 1);
    private static final Document NEW_PARTIAL_FILTER =
            new Document("status", "active").append("onStartTriggerValue", new Document("$exists", true));

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

    private Document indexByName(String name) {
        return indexes().stream()
                .filter(ix -> name.equals(ix.getString("name")))
                .findFirst()
                .orElse(null);
    }

    private void dropIndexIfPresent(String name) {
        if (indexByName(name) != null) {
            mongoTemplate.getCollection("funnels").dropIndex(name);
        }
    }

    /** Lays the OLD flat-trio index ({projectId,triggerType,triggerValue}) — the index reconciliation drops. */
    private void installOldTrioIndex() {
        dropIndexIfPresent(OLD_INDEX_NAME);
        mongoTemplate.getCollection("funnels").createIndex(
                OLD_KEY,
                new IndexOptions()
                        .name(OLD_INDEX_NAME)
                        .unique(true)
                        .partialFilterExpression(new Document("status", "active")));
    }

    /**
     * Lays the NEW {projectId,onStartTriggerValue} partial-unique shape (simulates auto-index-creation of
     * the Task-1 annotation). Distinct key AND name from the old index, so the two never collide on a name.
     */
    private void installNewShapeIndex() {
        dropIndexIfPresent(NEW_INDEX_NAME);
        mongoTemplate.getCollection("funnels").createIndex(
                NEW_KEY,
                new IndexOptions()
                        .name(NEW_INDEX_NAME)
                        .unique(true)
                        .partialFilterExpression(NEW_PARTIAL_FILTER));
    }

    @Test
    void migrationDropsOldBroadFilterIndex() {
        installOldTrioIndex();
        assertThat(indexByName(OLD_INDEX_NAME))
                .as("old flat-trio index present precondition")
                .isNotNull();

        runReconciliation();

        // The reconciliation owns only the DROP (auto-index-creation, which lays the new shape, runs only at
        // context startup, not here). After the drop the old flat-trio index is gone.
        assertThat(indexByName(OLD_INDEX_NAME))
                .as("old flat-trio index must be gone after the reconciliation drops it")
                .isNull();

        // Simulate auto-index-creation laying the annotation shape; assert its key + partial filter.
        installNewShapeIndex();
        Document recreated = indexByName(NEW_INDEX_NAME);
        assertThat(recreated).isNotNull();
        assertThat(recreated.get("key", Document.class)).isEqualTo(NEW_KEY);
        Document pfe = recreated.get("partialFilterExpression", Document.class);
        assertThat(pfe.getString("status")).isEqualTo("active");
        assertThat(pfe.get("onStartTriggerValue", Document.class))
                .as("partial filter scopes uniqueness to funnels that HAVE an on_start payload")
                .isEqualTo(new Document("$exists", true));
    }

    @Test
    void reconciliationIsIdempotentNoOpOnSecondBoot() {
        // Already migrated: only the new onStartTriggerValue index exists, old index absent.
        dropIndexIfPresent(OLD_INDEX_NAME);
        installNewShapeIndex();
        Document before = indexByName(NEW_INDEX_NAME);
        assertThat(before).isNotNull();
        assertThat(indexByName(OLD_INDEX_NAME)).as("old index absent precondition").isNull();

        runReconciliation();

        // Old index still absent (NO-OP), and the new index is left untouched (no drop+recreate churn).
        assertThat(indexByName(OLD_INDEX_NAME))
                .as("reconciliation must not recreate the old index")
                .isNull();
        Document after = indexByName(NEW_INDEX_NAME);
        assertThat(after).as("new onStartTriggerValue index must be left untouched").isNotNull();
        assertThat(after.get("key", Document.class)).isEqualTo(before.get("key", Document.class));
        assertThat(after.get("partialFilterExpression", Document.class))
                .isEqualTo(before.get("partialFilterExpression", Document.class));
    }

    @Test
    void reconciliationNoOpOnFreshDatabase() {
        dropIndexIfPresent(OLD_INDEX_NAME);
        assertThat(indexByName(OLD_INDEX_NAME)).as("old index absent precondition").isNull();

        assertThatCode(this::runReconciliation).doesNotThrowAnyException();

        // Still absent — the reconciliation owns only the drop; auto-index-creation owns creation.
        assertThat(indexByName(OLD_INDEX_NAME))
                .as("reconciliation must not create any trigger index on a fresh DB")
                .isNull();
    }

    @Test
    void reconciliationNeverThrowsOnMongoFault() {
        // A factory whose driver points at a dead address: getCollection/listIndexes throws, and the
        // body's try/catch must swallow it so the app still boots. try-with-resources closes the client.
        try (com.mongodb.client.MongoClient client =
                     com.mongodb.client.MongoClients.create(
                             "mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=200")) {
            MongoDatabaseFactory broken =
                    new org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory(
                            client, "botfunnel-broken");
            FunnelTriggerIndexReconciliation faulting = new FunnelTriggerIndexReconciliation();

            assertThatCode(() -> faulting.postProcessAfterInitialization(broken, "mongoDatabaseFactory"))
                    .as("a transient Mongo fault must be swallowed — the app must boot")
                    .doesNotThrowAnyException();
        }
    }

    /**
     * End-to-end proof against the new-shape index: two active funnels with the same non-null
     * {@code onStartTriggerValue} collide; two active event-only funnels (null {@code onStartTriggerValue})
     * sharing an event {@code triggerValue} coexist (the partial {@code {$exists:true}} filter never binds
     * a null on_start scalar). Uses the same shape auto-index-creation produces.
     */
    @Test
    void twoActiveEventFunnelsCoexistAfterMigration() {
        installNewShapeIndex();
        String projectId = "proj-" + UUID.randomUUID();
        try {
            // Event-only funnels carry null onStartTriggerValue → never bound by the partial-unique index.
            mongoTemplate.insert(eventOnlyFunnel(projectId, "purchase"));
            assertThatCode(() -> mongoTemplate.insert(eventOnlyFunnel(projectId, "purchase")))
                    .as("two active event-only funnels with the same event triggerValue must coexist")
                    .doesNotThrowAnyException();

            // on_start funnels carry a non-null onStartTriggerValue → bound by the unique index.
            mongoTemplate.insert(onStartFunnel(projectId, "promo"));
            assertThatThrownBy(() -> mongoTemplate.insert(onStartFunnel(projectId, "promo")))
                    .as("two active funnels with the same onStartTriggerValue must collide")
                    .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);
        } finally {
            mongoTemplate.getCollection("funnels").deleteMany(new Document("projectId", projectId));
        }
    }

    /** Active funnel carrying a single on_start trigger; onStartTriggerValue is the denormalized payload. */
    private Funnel onStartFunnel(String projectId, String onStartValue) {
        Trigger t = new Trigger();
        t.setTriggerType("on_start");
        t.setTriggerValue(onStartValue);
        Funnel f = activeFunnel(projectId);
        f.setTriggers(List.of(t));
        f.setOnStartTriggerValue(onStartValue);
        return f;
    }

    /** Active funnel carrying a single event trigger; onStartTriggerValue stays null (event-only). */
    private Funnel eventOnlyFunnel(String projectId, String eventValue) {
        Trigger t = new Trigger();
        t.setTriggerType("event");
        t.setTriggerValue(eventValue);
        Funnel f = activeFunnel(projectId);
        f.setTriggers(List.of(t));
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
