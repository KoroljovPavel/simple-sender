package com.botfunnel.webhook;

import com.botfunnel.AbstractIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class RawUpdateRepositoryTest extends AbstractIntegrationTest {

    @Autowired RawUpdateRepository rawUpdateRepository;
    @Autowired MongoTemplate mongoTemplate;

    @BeforeEach
    void clean() {
        rawUpdateRepository.deleteAll();
    }

    private RawUpdate row(String projectId, Long updateId, RawUpdateStatus status) {
        RawUpdate u = new RawUpdate();
        u.setProjectId(projectId);
        u.setUpdateId(updateId);
        u.setPayload(new Document("update_id", updateId));
        u.setProcessingStatus(status);
        u.setCreatedAt(Instant.now());
        return u;
    }

    @Test
    void duplicateProjectIdUpdateId_throwsDuplicateKey() {
        rawUpdateRepository.save(row("proj-A", 42L, RawUpdateStatus.PENDING));

        assertThatThrownBy(() -> rawUpdateRepository.save(row("proj-A", 42L, RawUpdateStatus.PENDING)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void compoundIndex_isUnique() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(RawUpdate.class).getIndexInfo();
        assertThat(indexes).isNotNull();

        IndexInfo compound = findByName(indexes, "projectId_updateId_unique");
        assertThat(compound.isUnique()).isTrue();
        assertThat(compound.getIndexFields()).extracting("key")
                .containsExactly("projectId", "updateId");
    }

    @Test
    void ttlIndex_hasExpireAfter90Days() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(RawUpdate.class).getIndexInfo();
        assertThat(indexes).isNotNull();

        IndexInfo ttl = findByName(indexes, "ttl_createdAt");
        Optional<Duration> expireAfter = ttl.getExpireAfter();
        assertThat(expireAfter).isPresent();
        assertThat(expireAfter.get()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void ttlIndex_partialFilterIsUppercase() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(RawUpdate.class).getIndexInfo();
        assertThat(indexes).isNotNull();

        IndexInfo ttl = findByName(indexes, "ttl_createdAt");
        String pf = ttl.getPartialFilterExpression();
        assertThat(pf).as("partial filter on ttl_createdAt").isNotBlank();

        Document parsed = Document.parse(pf);
        Document statusClause = parsed.get("processingStatus", Document.class);
        assertThat(statusClause).as("processingStatus clause").isNotNull();
        @SuppressWarnings("unchecked")
        List<String> inList = (List<String>) statusClause.get("$in", List.class);
        assertThat(inList).containsExactlyInAnyOrder("PENDING", "DONE");
    }

    @Test
    void projectIdIndex_existsSeparately() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(RawUpdate.class).getIndexInfo();
        assertThat(indexes).isNotNull();

        // Standalone @Indexed(projectId) is distinct from the compound
        // projectId_updateId_unique index. Match by single-key shape: one indexed field, named projectId.
        Optional<IndexInfo> standalone = indexes.stream()
                .filter(i -> !"projectId_updateId_unique".equals(i.getName()))
                .filter(i -> i.getIndexFields().size() == 1)
                .filter(i -> "projectId".equals(i.getIndexFields().get(0).getKey()))
                .findFirst();
        assertThat(standalone).as("standalone @Indexed projectId index").isPresent();
    }

    @Test
    void statusEnum_roundTrips() {
        RawUpdate saved = rawUpdateRepository.save(row("proj-B", 7L, RawUpdateStatus.PENDING));
        assertThat(saved).isNotNull();

        Optional<RawUpdate> reloaded = rawUpdateRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getProcessingStatus()).isEqualTo(RawUpdateStatus.PENDING);
    }

    private static IndexInfo findByName(List<IndexInfo> indexInfos, String name) {
        return indexInfos.stream()
                .filter(i -> name.equals(i.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("index %s missing".formatted(name)));
    }
}
