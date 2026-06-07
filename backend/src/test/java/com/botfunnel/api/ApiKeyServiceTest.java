package com.botfunnel.api;

import com.botfunnel.common.crypto.Sha256Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link ApiKeyService}. Uses an in-memory fake {@link ApiKeyRepository} (Mongo-free)
 * so the security invariants — ≥256-bit entropy, hash-only at rest, plaintext-once, lookup-by-hash —
 * are pinned without touching the database (the Mongo index enforcement lives in {@link ApiKeyRepositoryIT}).
 */
class ApiKeyServiceTest {

    private FakeApiKeyRepository repository;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        repository = new FakeApiKeyRepository();
        service = new ApiKeyService(repository);
    }

    @Test
    void generate_producesAtLeast256BitPlaintext() {
        ApiKeyService.GeneratedKey key = service.generate("proj-1");

        // The plaintext is hex of the random source; decoding it back to bytes recovers the entropy.
        // Hard floor: ≥32 bytes (256 bits). Catches accidental reuse of a 16-byte constant.
        byte[] decoded = HexFormat.of().parseHex(key.plaintext());
        assertThat(decoded.length).isGreaterThanOrEqualTo(32);
    }

    @Test
    void generate_storesHashNotPlaintext() {
        ApiKeyService.GeneratedKey key = service.generate("proj-1");

        ApiKey stored = repository.findByProjectId("proj-1").orElseThrow();
        assertThat(stored.getKeyHash()).isEqualTo(Sha256Hex.hex(key.plaintext()));
        // The persisted record must hold the hash, never the raw key, in any field.
        assertThat(stored.getKeyHash()).isNotEqualTo(key.plaintext());
        assertThat(stored.getKeyPrefix()).isNotEqualTo(key.plaintext());
        assertThat(stored.getProjectId()).isNotEqualTo(key.plaintext());
    }

    @Test
    void generate_returnsPlaintextOnce() {
        ApiKeyService.GeneratedKey key = service.generate("proj-1");

        assertThat(key.plaintext()).isNotBlank();
        // A subsequent read of the stored record exposes only hash + prefix — never the plaintext.
        ApiKey stored = repository.findByProjectId("proj-1").orElseThrow();
        assertThat(stored.getKeyHash()).isNotEqualTo(key.plaintext());
        assertThat(stored.getKeyPrefix()).isNotEqualTo(key.plaintext());
    }

    @Test
    void lookupByHash_roundTrips() {
        ApiKeyService.GeneratedKey key = service.generate("proj-1");

        Optional<ApiKey> resolved = service.lookup(key.plaintext());
        assertThat(resolved).isPresent();
        assertThat(resolved.get().getProjectId()).isEqualTo("proj-1");

        // A wrong plaintext resolves to empty (no plaintext compare; hash mismatch).
        assertThat(service.lookup("deadbeef-not-the-key")).isEmpty();
    }

    @Test
    void keyPrefix_maskShape() {
        ApiKeyService.GeneratedKey key = service.generate("proj-1");

        // keyPrefix is a short head of the plaintext, not the full key.
        assertThat(key.keyPrefix()).isNotBlank();
        assertThat(key.keyPrefix().length()).isLessThan(key.plaintext().length());
        assertThat(key.plaintext()).startsWith(key.keyPrefix());

        ApiKey stored = repository.findByProjectId("proj-1").orElseThrow();
        assertThat(stored.getKeyPrefix()).isEqualTo(key.keyPrefix());
    }

    @Test
    void regenerate_overwritesAndInvalidatesOld() {
        ApiKeyService.GeneratedKey first = service.generate("proj-1");
        ApiKeyService.GeneratedKey second = service.generate("proj-1");

        assertThat(second.plaintext()).isNotEqualTo(first.plaintext());
        // Old plaintext no longer resolves; new one does.
        assertThat(service.lookup(first.plaintext())).isEmpty();
        assertThat(service.lookup(second.plaintext())).isPresent();
        // Still exactly one key per project.
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void lookup_nullOrBlankKey_returnsEmpty() {
        service.generate("proj-1");

        // Guard at the boundary: never pass null/blank into Sha256Hex.hex (which NPEs on null).
        assertThatCode(() -> {
            assertThat(service.lookup(null)).isEmpty();
            assertThat(service.lookup("")).isEmpty();
            assertThat(service.lookup("   ")).isEmpty();
        }).doesNotThrowAnyException();
    }

    /**
     * In-memory {@link ApiKeyRepository} fake — only the methods exercised by {@link ApiKeyService} are
     * implemented; the rest throw {@link UnsupportedOperationException}. Keyed by id; assigns ids on save
     * to mirror Mongo behavior so the upsert path round-trips.
     */
    private static final class FakeApiKeyRepository implements ApiKeyRepository {
        private final ConcurrentHashMap<String, ApiKey> byId = new ConcurrentHashMap<>();
        private final AtomicInteger idSeq = new AtomicInteger();

        @Override
        public Optional<ApiKey> findByKeyHash(String keyHash) {
            return byId.values().stream().filter(k -> keyHash.equals(k.getKeyHash())).findFirst();
        }

        @Override
        public Optional<ApiKey> findByProjectId(String projectId) {
            return byId.values().stream().filter(k -> projectId.equals(k.getProjectId())).findFirst();
        }

        @Override
        public <S extends ApiKey> S save(S entity) {
            if (entity.getId() == null) {
                entity.setId("id-" + idSeq.incrementAndGet());
            }
            byId.put(entity.getId(), entity);
            return entity;
        }

        @Override
        public long count() {
            return byId.size();
        }

        // --- unused MongoRepository surface ---
        @Override public <S extends ApiKey> java.util.List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public Optional<ApiKey> findById(String s) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(String s) { throw new UnsupportedOperationException(); }
        @Override public java.util.List<ApiKey> findAll() { throw new UnsupportedOperationException(); }
        @Override public java.util.List<ApiKey> findAllById(Iterable<String> strings) { throw new UnsupportedOperationException(); }
        @Override public void deleteById(String s) { throw new UnsupportedOperationException(); }
        @Override public void delete(ApiKey entity) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends String> strings) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends ApiKey> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public java.util.List<ApiKey> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<ApiKey> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends ApiKey> S insert(S entity) { throw new UnsupportedOperationException(); }
        @Override public <S extends ApiKey> java.util.List<S> insert(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public <S extends ApiKey> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ApiKey> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ApiKey> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends ApiKey> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends ApiKey> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ApiKey> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ApiKey, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }
}
