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
 * Phase 3 (Decision 9): idempotent startup migration that drops the OLD trigger-conflict index so
 * Spring Data's {@code auto-index-creation=true} recreates it from the {@link Funnel} annotation's new
 * {@code on_start}-only partial filter.
 *
 * <p><b>Why a runner is needed.</b> {@code auto-index-creation} only <i>creates</i> indexes — it never
 * drops or alters an index whose definition changed. Task 1 relaxed the {@code @CompoundIndex}
 * partialFilter from {@code {status:'active'}} to {@code {status:'active', triggerType:'on_start'}}, but
 * on an already-migrated DB the old broad-filter index physically survives.
 *
 * <p><b>Why a {@link BeanPostProcessor}, not an {@code ApplicationRunner}.</b> The original plan
 * (Decision 9, {@code FunnelStepIdBackfill} pattern) was an {@code ApplicationRunner} — but a live boot
 * smoke (Task 2) proved that ordering fails: with {@code auto-index-creation=true}, Spring Data creates
 * the annotation-driven indexes <i>eagerly during {@code MongoTemplate} bean instantiation</i>, long
 * before any {@code ApplicationRunner} (or even {@code ContextRefreshedEvent}) runs. Because the OLD and
 * NEW indexes share the name {@code projectId_triggerType_triggerValue_unique_active} but differ in their
 * partial filter, auto-creation hits MongoDB error 86 (IndexKeySpecsConflict) and the context
 * <b>fails to start</b> — the runner's drop never gets a chance. So the drop MUST happen before
 * {@code MongoTemplate} initializes. This {@code BeanPostProcessor} hooks the {@link MongoDatabaseFactory}
 * bean (created and connected before {@code MongoTemplate}, which depends on it): at
 * {@code postProcessAfterInitialization} of the factory the DB connection is live but no Spring Data
 * index creation has run yet, so dropping here clears the conflict before auto-creation lays down the new
 * shape. (Deviation from Decision 9's mechanism, recorded in decisions.md; the intent — idempotent
 * startup drop, log-but-never-throw — is preserved.)
 *
 * <p><b>Idempotency.</b> The decision is driven by the actual {@code partialFilterExpression}, NOT by the
 * index name. The OLD shape has only a {@code status} key; the NEW shape additionally carries
 * {@code triggerType}. A blind unconditional drop is avoided — it would drop+recreate the correct index
 * on every boot, churning the index and racing auto-index-creation. So: drop only when the filter lacks
 * {@code triggerType}; no-op (with a distinct greppable marker) when the index is absent (fresh DB) or
 * already in the new shape.
 *
 * <p><b>Never throws.</b> The whole body is {@code try/catch(Exception)} and logs-and-swallows so a
 * transient Mongo fault never blocks app boot (mirrors {@code FunnelStepIdBackfill}). The returned bean
 * is always the unmodified original.
 */
@Component
public class FunnelTriggerIndexReconciliation implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(FunnelTriggerIndexReconciliation.class);

    // Stable, greppable startup markers (the Verify-smoke step greps these). Do not reword casually —
    // the post-deploy verification task pins these strings.
    static final String MARKER_APPLIED =
            "Funnel trigger-index reconciliation: APPLIED — dropped old broad-filter index "
                    + "projectId_triggerType_triggerValue_unique_active (auto-index-creation will recreate the on_start-only shape)";
    static final String MARKER_NOOP =
            "Funnel trigger-index reconciliation: NO-OP — trigger index already in the on_start-only shape (or absent on a fresh DB)";

    static final String INDEX_NAME = "projectId_triggerType_triggerValue_unique_active";
    static final String COLLECTION = "funnels";

    // Guards against running more than once per boot: a single MongoDatabaseFactory bean exists, but the
    // flag keeps the drop a strict no-op if the BPP is ever invoked again for any reason.
    private boolean reconciled = false;

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

            Document target = indexes.stream()
                    .filter(ix -> INDEX_NAME.equals(ix.getString("name")))
                    .findFirst()
                    .orElse(null);

            // Fresh DB / index absent → auto-index-creation will lay down the new shape directly.
            if (target == null) {
                log.info(MARKER_NOOP);
                return;
            }

            Document partialFilter = target.get("partialFilterExpression", Document.class);

            // Already in the new shape (partial filter carries triggerType) → nothing to do.
            if (partialFilter != null && partialFilter.containsKey("triggerType")) {
                log.info(MARKER_NOOP);
                return;
            }

            // Old broad shape ({status:'active'} only, no triggerType clause) → drop it; auto-index-
            // creation recreates the on_start-only shape from the Funnel annotation on this same boot.
            collection.dropIndex(INDEX_NAME);
            log.info(MARKER_APPLIED);
        } catch (Exception ex) {
            // Never propagate — the app must boot even if reconciliation fails (mirrors FunnelStepIdBackfill).
            log.error("Funnel trigger-index reconciliation failed: {}", ex.getMessage(), ex);
        }
    }
}
