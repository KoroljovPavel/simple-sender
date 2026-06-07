package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision 7: {@link FunnelStepIdBackfill} (ApplicationRunner) stamps a stable, distinct {@code id}
 * onto every step that lacks one — in both the {@code funnels} definitions and the in-flight
 * {@code funnel_executions.stepsSnapshot} — and seeds {@code currentStepId} from {@code currentStepIndex}
 * for in-flight executions. It must be idempotent: a re-run never overwrites or duplicates existing ids.
 *
 * Tagged "slow": boots the full Testcontainers context to exercise the backfill against real Mongo.
 * The runner has already executed once at context startup; tests assert that effect, then invoke
 * {@code run()} again directly to prove idempotency.
 */
@Tag("slow")
class FunnelStepIdBackfillTest extends AbstractIntegrationTest {

    @Autowired
    MongoTemplate mongoTemplate;

    @Autowired
    FunnelStepIdBackfill backfill;

    private Document step(String stepType, int order) {
        // No "id" field — simulates a legacy Phase-1 step document.
        return new Document("stepType", stepType).append("order", order);
    }

    @Test
    void backfillStampsIdsOnFunnelSteps() throws Exception {
        String marker = "bf-funnel-" + UUID.randomUUID();
        Document funnel = new Document("name", marker)
                .append("status", "draft")
                .append("steps", List.of(step("SEND_MESSAGE", 0), step("DELAY", 1)));
        mongoTemplate.getCollection("funnels").insertOne(funnel);

        try {
            // Re-run the runner (it already ran at startup, but the doc was inserted after).
            backfill.run(null);

            Document reloaded = mongoTemplate.getCollection("funnels")
                    .find(new Document("name", marker)).first();
            assertThat(reloaded).isNotNull();
            @SuppressWarnings("unchecked")
            List<Document> steps = (List<Document>) reloaded.get("steps");
            assertThat(steps).hasSize(2);
            String id0 = steps.get(0).getString("id");
            String id1 = steps.get(1).getString("id");
            assertThat(id0).as("step 0 id minted").isNotBlank();
            assertThat(id1).as("step 1 id minted").isNotBlank();
            assertThat(id0).as("each step gets a DISTINCT id").isNotEqualTo(id1);
        } finally {
            mongoTemplate.getCollection("funnels").deleteMany(new Document("name", marker));
        }
    }

    @Test
    void backfillStampsIdsAndCurrentStepIdOnInFlightExecution() throws Exception {
        String marker = "bf-exec-" + UUID.randomUUID();
        // In-flight execution: snapshot steps without id, currentStepIndex=1, currentStepId absent.
        Document exec = new Document("funnelId", marker)
                .append("status", "waiting")
                .append("currentStepIndex", 1)
                .append("stepsSnapshot", List.of(step("SEND_MESSAGE", 0), step("DELAY", 1), step("SEND_MESSAGE", 2)));
        mongoTemplate.getCollection("funnel_executions").insertOne(exec);

        try {
            backfill.run(null);

            Document reloaded = mongoTemplate.getCollection("funnel_executions")
                    .find(new Document("funnelId", marker)).first();
            assertThat(reloaded).isNotNull();
            @SuppressWarnings("unchecked")
            List<Document> snap = (List<Document>) reloaded.get("stepsSnapshot");
            assertThat(snap).hasSize(3);
            assertThat(snap.get(0).getString("id")).isNotBlank();
            assertThat(snap.get(1).getString("id")).isNotBlank();
            assertThat(snap.get(2).getString("id")).isNotBlank();
            assertThat(snap.get(0).getString("id"))
                    .isNotEqualTo(snap.get(1).getString("id"))
                    .isNotEqualTo(snap.get(2).getString("id"));
            // currentStepId seeded from currentStepIndex (=1) -> the id of snapshot step at index 1.
            assertThat(reloaded.getString("currentStepId"))
                    .as("currentStepId seeded from currentStepIndex")
                    .isEqualTo(snap.get(1).getString("id"));
        } finally {
            mongoTemplate.getCollection("funnel_executions").deleteMany(new Document("funnelId", marker));
        }
    }

    @Test
    void backfillIsIdempotentAndNeverOverwritesExistingIds() throws Exception {
        String marker = "bf-idem-" + UUID.randomUUID();
        Document funnel = new Document("name", marker)
                .append("status", "draft")
                .append("steps", List.of(step("SEND_MESSAGE", 0), step("DELAY", 1)));
        mongoTemplate.getCollection("funnels").insertOne(funnel);

        try {
            backfill.run(null);
            Document afterFirst = mongoTemplate.getCollection("funnels")
                    .find(new Document("name", marker)).first();
            assertThat(afterFirst).isNotNull();
            @SuppressWarnings("unchecked")
            List<Document> steps1 = (List<Document>) afterFirst.get("steps");
            String id0 = steps1.get(0).getString("id");
            String id1 = steps1.get(1).getString("id");

            // Second run must not change any already-stamped id.
            backfill.run(null);
            Document afterSecond = mongoTemplate.getCollection("funnels")
                    .find(new Document("name", marker)).first();
            assertThat(afterSecond).isNotNull();
            @SuppressWarnings("unchecked")
            List<Document> steps2 = (List<Document>) afterSecond.get("steps");
            assertThat(steps2.get(0).getString("id")).isEqualTo(id0);
            assertThat(steps2.get(1).getString("id")).isEqualTo(id1);
        } finally {
            mongoTemplate.getCollection("funnels").deleteMany(new Document("name", marker));
        }
    }

    @Test
    void backfillToleratesEmptyAndMissingStepArrays() throws Exception {
        String marker = "bf-edge-" + UUID.randomUUID();
        Document zeroSteps = new Document("name", marker).append("status", "draft").append("steps", List.of());
        Document nullSteps = new Document("name", marker).append("status", "draft");
        mongoTemplate.getCollection("funnels").insertOne(zeroSteps);
        mongoTemplate.getCollection("funnels").insertOne(nullSteps);

        try {
            // Must not throw on empty / missing step arrays.
            backfill.run(null);

            long count = mongoTemplate.getCollection("funnels")
                    .countDocuments(new Document("name", marker));
            assertThat(count).isEqualTo(2);
        } finally {
            mongoTemplate.getCollection("funnels").deleteMany(new Document("name", marker));
        }
    }

    @Test
    void backfillToleratesCurrentStepIndexOutOfBounds() throws Exception {
        String marker = "bf-oob-" + UUID.randomUUID();
        Document exec = new Document("funnelId", marker)
                .append("status", "waiting")
                .append("currentStepIndex", 9) // beyond snapshot bounds
                .append("stepsSnapshot", List.of(step("SEND_MESSAGE", 0)));
        mongoTemplate.getCollection("funnel_executions").insertOne(exec);

        try {
            // Out-of-bounds currentStepIndex must not crash the backfill; ids still stamped.
            backfill.run(null);

            Document reloaded = mongoTemplate.getCollection("funnel_executions")
                    .find(new Document("funnelId", marker)).first();
            assertThat(reloaded).isNotNull();
            @SuppressWarnings("unchecked")
            List<Document> snap = (List<Document>) reloaded.get("stepsSnapshot");
            assertThat(snap.get(0).getString("id")).isNotBlank();
            // currentStepId left null when index is out of bounds (no crash).
            assertThat(reloaded.getString("currentStepId")).isNull();
        } finally {
            mongoTemplate.getCollection("funnel_executions").deleteMany(new Document("funnelId", marker));
        }
    }
}
