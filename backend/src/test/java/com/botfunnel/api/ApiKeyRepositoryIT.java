package com.botfunnel.api;

import com.botfunnel.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexResolver;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link ApiKeyRepository} against real embedded Mongo: verifies the unique
 * indexes are enforced at the Mongo layer and the derived queries resolve.
 */
class ApiKeyRepositoryIT extends AbstractIntegrationTest {

    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired MongoTemplate mongoTemplate;

    @BeforeEach
    void clean() {
        apiKeyRepository.deleteAll();
        // The @Indexed(unique=true) indexes are auto-created via auto-index-creation=true in prod
        // (same mechanism as Bot/Project). In the test JVM that creation can be lazy, so resolve the
        // annotated indexes explicitly here to guarantee the unique-collision assertions are meaningful
        // regardless of which test touches the collection first.
        IndexResolver.create(mongoTemplate.getConverter().getMappingContext())
                .resolveIndexFor(ApiKey.class)
                .forEach(mongoTemplate.indexOps(ApiKey.class)::ensureIndex);
    }

    private ApiKey newKey(String projectId, String keyHash) {
        ApiKey k = new ApiKey();
        k.setProjectId(projectId);
        k.setKeyHash(keyHash);
        k.setKeyPrefix(keyHash.substring(0, 8));
        k.setCreatedAt(Instant.now());
        return k;
    }

    @Test
    void keyHashUnique_enforced() {
        apiKeyRepository.save(newKey("proj-1", "hash-aaa"));

        // Same keyHash for a different project must collide on the unique index.
        assertThatThrownBy(() -> apiKeyRepository.save(newKey("proj-2", "hash-aaa")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void projectIdUnique_enforced() {
        apiKeyRepository.save(newKey("proj-1", "hash-aaa"));

        // Second key for the same project must collide — one primary key per project.
        assertThatThrownBy(() -> apiKeyRepository.save(newKey("proj-1", "hash-bbb")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void findByKeyHashAndFindByProjectId_resolve() {
        apiKeyRepository.save(newKey("proj-1", "hash-aaa"));

        Optional<ApiKey> byHash = apiKeyRepository.findByKeyHash("hash-aaa");
        assertThat(byHash).isPresent();
        assertThat(byHash.get().getProjectId()).isEqualTo("proj-1");

        Optional<ApiKey> byProject = apiKeyRepository.findByProjectId("proj-1");
        assertThat(byProject).isPresent();
        assertThat(byProject.get().getKeyHash()).isEqualTo("hash-aaa");

        assertThat(apiKeyRepository.findByKeyHash("nope")).isEmpty();
        assertThat(apiKeyRepository.findByProjectId("nope")).isEmpty();
    }
}
