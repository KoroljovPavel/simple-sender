package com.botfunnel.webhook;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.common.crypto.Sha256Hex;
import com.botfunnel.common.test.ConcurrencyTestUtils;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// D14 + AC2: P99 latency probe lives on real transport (TestRestTemplate against the
// RANDOM_PORT Tomcat) because MockMvc's in-process dispatch erases transport overhead
// and the <100ms assertion would be a trivial pass. @Tag("slow") so the default CI run
// can opt out of the 100-parallel hammering.
@Tag("slow")
class TelegramWebhookP99IT extends AbstractIntegrationTest {

    private static final String SECRET_PLAIN = "secretToken_AAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String SECRET_HASH = Sha256Hex.hex(SECRET_PLAIN);

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired BotRepository botRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired RawUpdateRepository rawUpdateRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String projectId;

    @BeforeEach
    void setup() {
        rawUpdateRepository.deleteAll();
        botRepository.deleteAll();
        projectRepository.deleteAll();

        Project p = new Project();
        p.setOwnerId("ownerX");
        p.setName("P99 Project " + System.nanoTime());
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectId = projectRepository.save(p).getId();

        Bot b = new Bot();
        b.setProjectId(projectId);
        b.setTelegramBotId(1234567890L);
        b.setStatus(BotStatus.CONNECTED);
        b.setWebhookSecretHash(SECRET_HASH);
        b.setConnectedAt(Instant.now());
        botRepository.save(b);
    }

    @Test
    void p99Latency_under100msAt100ParallelRequests() throws Exception {
        URI url = URI.create("http://localhost:" + port + "/webhooks/telegram/" + projectId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Telegram-Bot-Api-Secret-Token", SECRET_PLAIN);

        List<Long> latencies = ConcurrencyTestUtils.parallelInvoke(100, () -> {
            // Deterministic-UUID + (projectId, updateId) unique index — unique update_id per
            // request keeps every POST on the happy-path branch (no DuplicateKey collision)
            // so the P99 measurement reflects steady-state latency rather than self-heal.
            long uid = System.nanoTime();
            Document body = samplePayload(uid);
            String json = objectMapper.writeValueAsString(body);
            long t0 = System.nanoTime();
            ResponseEntity<Void> resp = restTemplate.postForEntity(
                    url, new HttpEntity<>(json, headers), Void.class);
            long elapsed = System.nanoTime() - t0;
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            return elapsed;
        });

        assertThat(latencies).hasSize(100);
        latencies.sort(Long::compareTo);
        long p99Nanos = latencies.get(98);
        long p99Ms = p99Nanos / 1_000_000L;
        assertThat(p99Ms)
                .as("P99 latency in ms — got %d", p99Ms)
                .isLessThan(100L);
    }

    private Document samplePayload(long updateId) {
        Document chat = new Document("id", 100L).append("type", "private");
        Document message = new Document()
                .append("message_id", 1L)
                .append("chat", chat)
                .append("date", 1700000000L)
                .append("text", "/start");
        return new Document().append("update_id", updateId).append("message", message);
    }
}
