package com.botfunnel.subscriber.export;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.subscriber.SegmentFilter;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC18 latency probe (Decision 7): the indexed-only estimate count must stay under a 200ms P95 even
 * at 100k subscribers, so the {@code POST /export} cap check never blocks the request.
 */
@Tag("slow")
class SubscriberExportEstimateLatencyIT extends AbstractIntegrationTest {

    private static final int SUBSCRIBER_COUNT = 100_000;
    private static final String PROJECT_ID = "estimate-project";
    private static final long P95_BUDGET_MS = 200L;

    @Autowired MongoTemplate mongoTemplate;
    @Autowired SubscriberExportController controller;

    @BeforeEach
    void seed() {
        mongoTemplate.remove(new Query(), Subscriber.class);
        seedSubscribers(SUBSCRIBER_COUNT);
    }

    @Test
    void estimateP95Under200ms_at100k() {
        // Indexed-only filter — status=ACTIVE hits the (projectId, status) compound index.
        SegmentFilter filter = new SegmentFilter(null, SubscriberStatus.ACTIVE, null, null,
                null, null, SegmentFilter.SortKey.CREATED_DESC, null, 0);

        // Warm up the JVM + query plan cache before timing.
        for (int i = 0; i < 5; i++) {
            controller.estimatedCount(PROJECT_ID, filter);
        }

        long[] timings = new long[50];
        for (int i = 0; i < timings.length; i++) {
            long start = System.nanoTime();
            long count = controller.estimatedCount(PROJECT_ID, filter);
            timings[i] = (System.nanoTime() - start) / 1_000_000;
            assertThat(count).isEqualTo((long) SUBSCRIBER_COUNT);
        }

        Arrays.sort(timings);
        long p95 = timings[(int) Math.ceil(0.95 * timings.length) - 1];
        assertThat(p95)
                .as("estimated-count P95 over 50 runs must stay under %d ms (was %d ms)", P95_BUDGET_MS, p95)
                .isLessThan(P95_BUDGET_MS);
    }

    private void seedSubscribers(int count) {
        List<Subscriber> batch = new ArrayList<>(5000);
        long seq = 1L;
        for (int i = 0; i < count; i++) {
            Subscriber s = new Subscriber();
            s.setProjectId(PROJECT_ID);
            s.setTelegramUserId(seq++);
            s.setTelegramChatId(s.getTelegramUserId());
            s.setTelegramBotId(9000L);
            s.setStatus(SubscriberStatus.ACTIVE);
            s.setSubscribedAt(Instant.now());
            s.setLastSeenAt(Instant.now());
            batch.add(s);
            if (batch.size() == 5000) {
                mongoTemplate.insert(batch, Subscriber.class);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            mongoTemplate.insert(batch, Subscriber.class);
        }
    }
}
