package com.botfunnel.funnel;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 8 (17-funnel-multi-entry / Decision 7): idempotent startup migration that DROPS the OLD trigger
 * index {@code projectId_triggerType_triggerValue_unique_active} (key {@code {projectId,triggerType,
 * triggerValue}}) so Spring Data's {@code auto-index-creation=true} can lay down the NEW
 * {@code {projectId, onStartTriggerValue}} partial-unique shape (the {@link Funnel} annotation) without an
 * {@code IndexKeySpecsConflict}.
 *
 * <p><b>Why a runner is needed.</b> {@code auto-index-creation} only <i>creates</i> indexes — it never
 * drops or alters one. Task 1 swapped the {@code @CompoundIndex} from the flat trigger trio
 * {@code {projectId,triggerType,triggerValue}} to {@code {projectId,onStartTriggerValue}} (Decision 6,
 * Variant A), but on an already-populated DB the old index physically survives, indexing the now-removed
 * {@code triggerType}/{@code triggerValue} top-level fields — it is dead and must be retired.
 *
 * <p><b>Why a {@link BeanPostProcessor}, not an {@code ApplicationRunner}.</b> A live boot smoke (Phase 3)
 * proved that ordering fails: with {@code auto-index-creation=true}, Spring Data creates the
 * annotation-driven indexes <i>eagerly during {@code MongoTemplate} bean instantiation</i>, long before any
 * {@code ApplicationRunner} runs. So the stale index must be retired before {@code MongoTemplate}
 * initializes. This {@code BeanPostProcessor} hooks the {@link MongoDatabaseFactory} bean (created and
 * connected before {@code MongoTemplate}, which depends on it): at {@code postProcessAfterInitialization}
 * of the factory the DB connection is live but no Spring Data index creation has run yet, so dropping here
 * clears the conflict before auto-creation lays down the new shape.
 *
 * <p><b>Idempotency.</b> The decision is driven by the PRESENCE of the OLD index by name, not by inspecting
 * a partial filter. The old and new indexes have DIFFERENT keys AND different names, so the new shape can
 * never be mistaken for the old one. If the OLD-named index is present → drop it (APPLIED); if it is absent
 * (fresh/wiped DB, or already migrated so only the new {@code onStartTriggerValue} index exists) → NO-OP.
 * The new index is never touched.
 *
 * <p><b>Never throws.</b> The whole body is {@code try/catch(Exception)} and logs-and-swallows so a
 * transient Mongo fault never blocks app boot (mirrors {@code FunnelStepIdBackfill}). The returned bean
 * is always the unmodified original.
 */
@Component
public class FunnelTriggerIndexReconciliation implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(FunnelTriggerIndexReconciliation.class);

    // Stable, greppable startup markers (the post-deploy verification task greps these — PII-free, index/
    // collection names only). Do not reword casually.
    static final String MARKER_APPLIED =
            "Funnel trigger-index reconciliation: APPLIED — dropped old trigger index "
                    + "projectId_triggerType_triggerValue_unique_active (auto-index-creation will lay down the "
                    + "projectId_onStartTriggerValue_unique_active shape)";
    static final String MARKER_NOOP =
            "Funnel trigger-index reconciliation: NO-OP — old trigger index "
                    + "projectId_triggerType_triggerValue_unique_active absent (fresh DB or already migrated to "
                    + "the projectId_onStartTriggerValue_unique_active shape)";

    // The index being DROPPED — the OLD flat-trio trigger index keyed {projectId,triggerType,triggerValue}.
    // INDEX_NAME intentionally points at the OLD index, NOT the new onStartTriggerValue index (pointing it
    // at the new index would delete the very index auto-creation just laid down).
    static final String INDEX_NAME = "projectId_triggerType_triggerValue_unique_active";
    static final String COLLECTION = "funnels";

    // Guards against running more than once per boot: a single MongoDatabaseFactory bean exists, but the
    // flag keeps the drop a strict no-op if the BPP is ever invoked again for any reason.
    private volatile boolean reconciled = false;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // Trigger exactly on the MongoDatabaseFactory bean — fully connected, and created before the
        // MongoTemplate bean whose instantiation runs auto-index-creation.
        if (bean instanceof MongoDatabaseFactory factory && !reconciled) {
            reconciled = true;
            reconcile(factory);
        }
        return bean;
    }

    private void reconcile(MongoDatabaseFactory factory) {
        try {
            MongoCollection<Document> collection = factory.getMongoDatabase().getCollection(COLLECTION);

            List<Document> indexes = new ArrayList<>();
            collection.listIndexes().into(indexes);

            boolean oldIndexPresent = indexes.stream()
                    .anyMatch(ix -> INDEX_NAME.equals(ix.getString("name")));

            // Fresh/wiped DB, or already migrated (only the new {projectId,onStartTriggerValue} index
            // exists) → nothing to retire; auto-index-creation owns laying down the new shape.
            if (!oldIndexPresent) {
                log.info(MARKER_NOOP);
                return;
            }

            // Old flat-trio index present → drop it; auto-index-creation lays down the new
            // onStartTriggerValue shape from the Funnel annotation on this same boot.
            collection.dropIndex(INDEX_NAME);
            log.info(MARKER_APPLIED);
        } catch (Exception ex) {
            // Never propagate — the app must boot even if reconciliation fails (mirrors FunnelStepIdBackfill).
            log.error("Funnel trigger-index reconciliation failed: {}", ex.getMessage(), ex);
        }
    }
}
