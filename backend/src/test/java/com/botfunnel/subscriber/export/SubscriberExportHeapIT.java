package com.botfunnel.subscriber.export;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberStatus;
import com.botfunnel.subscriber.jobs.ExportSubscribersJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC18 heap-budget probe (Decision 7 / R1): streaming 50k subscribers to GridFS must keep heap
 * bounded. Flake mitigation per Testing Strategy — {@code System.gc()} + sleep around each
 * measurement and the minimum of 3 runs reported.
 */
@Tag("slow")
class SubscriberExportHeapIT extends AbstractIntegrationTest {

    private static final long HEAP_BUDGET_BYTES = 64L * 1024 * 1024;
    private static final int SUBSCRIBER_COUNT = 50_000;
    private static final String PROJECT_ID = "heap-project";

    @Autowired SubscriberExportRepository exportRepository;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired ExportSubscribersJob exportJob;

    @BeforeEach
    void seed() {
        mongoTemplate.remove(new Query(), Subscriber.class);
        mongoTemplate.remove(new Query(), SubscriberExport.class);
        seedSubscribers(SUBSCRIBER_COUNT);
    }

    @Test
    void heapStaysUnder64MB_at50kSubscribers() throws Exception {
        long minDelta = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            String exportId = seedPendingExport();

            System.gc();
            Thread.sleep(200);
            long before = usedHeap();

            exportJob.handle(exportId);

            System.gc();
            Thread.sleep(200);
            long after = usedHeap();

            minDelta = Math.min(minDelta, after - before);
            assertThat(exportRepository.findById(exportId).orElseThrow().getRowCount())
                    .isEqualTo((long) SUBSCRIBER_COUNT);
        }

        assertThat(minDelta)
                .as("streaming 50k rows must keep the heap delta under 64 MB (min of 3 runs)")
                .isLessThan(HEAP_BUDGET_BYTES);
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private String seedPendingExport() {
        SubscriberExport export = new SubscriberExport();
        export.setProjectId(PROJECT_ID);
        export.setOwnerId("heap-owner"); // no User row → owner lookup is null → no email send
        export.setStatus(ExportStatus.PENDING);
        export.setCreatedAt(Instant.now());
        return exportRepository.save(export).getId();
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
            s.setFirstName("Name" + i);
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
