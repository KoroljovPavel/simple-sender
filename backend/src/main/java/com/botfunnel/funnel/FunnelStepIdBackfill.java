package com.botfunnel.funnel;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time-per-startup backfill of the Phase-2 graph model onto Phase-1 data (Decision 7). Stamps a
 * stable, <b>distinct</b> {@code id} onto every step that lacks one — in both the {@code funnels}
 * definitions and the in-flight {@code funnel_executions.stepsSnapshot} — and seeds
 * {@code currentStepId} from {@code currentStepIndex} for in-flight executions whose cursor is still
 * index-based.
 *
 * <p>Patterned on {@code SuperAdminSeeder}: {@code @Component implements ApplicationRunner}, runs once
 * at startup, idempotent, and <b>logs-but-never-throws</b> so the app boots even if Mongo is
 * transiently unavailable. Re-running never overwrites or duplicates an existing {@code id}.
 *
 * <p>Persistence strategy: a partial {@code $set} of the whole {@code steps}/{@code stepsSnapshot}
 * array (with distinct ids assigned in memory), NOT a full-document repository save — a full save would
 * re-serialize the entire legacy document through the current mapping schema and risk dropping fields
 * absent from the current model. Working at the raw {@link Document} level keeps the change surgical
 * (touches only {@code id}/{@code currentStepId}) and tolerant of legacy step shapes. A positional-all
 * {@code steps.$[].id} update is deliberately avoided: it would stamp the SAME id onto every element.
 */
@Component
public class FunnelStepIdBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FunnelStepIdBackfill.class);

    private final MongoTemplate mongoTemplate;

    public FunnelStepIdBackfill(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int funnels = backfillFunnels();
            int executions = backfillExecutions();
            if (funnels > 0 || executions > 0) {
                log.info("Funnel step-id backfill: updated {} funnel(s), {} execution(s)", funnels, executions);
            }
        } catch (Exception ex) {
            // Never propagate — the app must boot even if backfill fails (mirrors SuperAdminSeeder).
            log.error("Funnel step-id backfill failed: {}", ex.getMessage(), ex);
        }
    }

    private int backfillFunnels() {
        var collection = mongoTemplate.getCollection("funnels");
        int updated = 0;
        for (Document funnel : collection.find()) {
            List<Document> steps = stepsList(funnel.get("steps"));
            if (steps == null) {
                continue;
            }
            boolean changed = mintMissingIds(steps);
            if (changed) {
                collection.updateOne(
                        new Document("_id", funnel.get("_id")),
                        new Document("$set", new Document("steps", steps)));
                updated++;
            }
        }
        return updated;
    }

    private int backfillExecutions() {
        var collection = mongoTemplate.getCollection("funnel_executions");
        int updated = 0;
        for (Document exec : collection.find()) {
            List<Document> snapshot = stepsList(exec.get("stepsSnapshot"));
            boolean stampedIds = snapshot != null && mintMissingIds(snapshot);

            Document set = new Document();
            if (stampedIds) {
                set.append("stepsSnapshot", snapshot);
            }
            // Seed currentStepId from currentStepIndex if the cursor is still index-only.
            if (exec.getString("currentStepId") == null && snapshot != null) {
                Integer index = exec.getInteger("currentStepIndex");
                if (index != null && index >= 0 && index < snapshot.size()) {
                    String stepId = snapshot.get(index).getString("id");
                    if (stepId != null) {
                        set.append("currentStepId", stepId);
                    }
                }
            }
            if (!set.isEmpty()) {
                collection.updateOne(new Document("_id", exec.get("_id")), new Document("$set", set));
                updated++;
            }
        }
        return updated;
    }

    // Returns the step list as Documents, or null if the field is absent / not a list. An empty list
    // is returned as an empty list (distinct from null) so callers can no-op cleanly.
    @SuppressWarnings("unchecked")
    private List<Document> stepsList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<Document> steps = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof Document doc) {
                steps.add(doc);
            }
        }
        return steps;
    }

    // Assigns a distinct id to every step missing one (idempotent: existing ids are left untouched).
    // Returns true iff at least one id was minted.
    private boolean mintMissingIds(List<Document> steps) {
        boolean changed = false;
        for (Document step : steps) {
            if (step.getString("id") == null) {
                step.put("id", new ObjectId().toHexString());
                changed = true;
            }
        }
        return changed;
    }
}
