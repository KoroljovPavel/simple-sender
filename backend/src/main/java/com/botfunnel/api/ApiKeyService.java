package com.botfunnel.api;

import com.botfunnel.common.crypto.Sha256Hex;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Mints, stores, and looks up per-project public-API keys (Decision 8).
 *
 * <p>Security spine — <b>hash-only at rest</b>: the plaintext is generated from a ≥256-bit
 * {@link SecureRandom} source, hex-encoded, and returned to the caller <b>exactly once</b> at
 * (re)generation. Only {@link Sha256Hex#hex(String)} of the plaintext is persisted; the full key is
 * never a stored field. Lookup is by hash — there is no plaintext-comparison path anywhere. A
 * 256-bit random secret is what makes unsalted SHA-256 safe (no brute-forceable low-entropy preimage).
 *
 * <p>Generation is <b>on-demand only</b>: this service is NOT called from {@code ProjectService.create}
 * and ships no backfill runner. Regenerate overwrites the project's existing row, so the old hash is
 * gone and the old plaintext stops resolving.
 */
@Service
public class ApiKeyService {

    /**
     * Random-source size for the plaintext key. MUST be ≥32 bytes (256 bits): a 256-bit secret is what
     * makes the unsalted SHA-256 at rest safe. Deliberately NOT {@code BotService.WEBHOOK_SECRET_BYTES}
     * (16 bytes / 128 bits — too short for an unsalted-SHA-256 secret).
     */
    private static final int API_KEY_BYTES = 32;

    /**
     * Length of the non-sensitive plaintext head kept as {@code keyPrefix} for the UI mask
     * ({@code prefix•••}). Short enough that exposing it leaks no meaningful entropy of the 64-hex-char
     * key.
     */
    private static final int KEY_PREFIX_LENGTH = 8;

    private final ApiKeyRepository apiKeyRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * Generate (or regenerate) the project's API key. Mints a ≥256-bit plaintext, persists only its
     * SHA-256 hash + a short prefix (upsert — one row per project; a prior key is overwritten and thereby
     * invalidated), and returns the plaintext <b>once</b>. The returned plaintext lives only in memory —
     * it is never a stored field.
     */
    public GeneratedKey generate(String projectId) {
        byte[] randomBytes = new byte[API_KEY_BYTES];
        secureRandom.nextBytes(randomBytes);
        String plaintext = HexFormat.of().formatHex(randomBytes);
        String keyHash = Sha256Hex.hex(plaintext);
        String keyPrefix = plaintext.substring(0, KEY_PREFIX_LENGTH);

        // Upsert: overwrite the existing row in place so projectId/keyHash stay unique and the old hash
        // is replaced (the previous plaintext no longer resolves via lookup).
        ApiKey apiKey = apiKeyRepository.findByProjectId(projectId).orElseGet(ApiKey::new);
        apiKey.setProjectId(projectId);
        apiKey.setKeyHash(keyHash);
        apiKey.setKeyPrefix(keyPrefix);
        apiKey.setCreatedAt(Instant.now());
        // A (re)generated key starts unused: clear lastUsedAt so a regenerated key never inherits the
        // previous key's usage timestamp. Task 7's filter stamps it again on each authenticated call.
        apiKey.setLastUsedAt(null);
        apiKeyRepository.save(apiKey);

        return new GeneratedKey(plaintext, keyPrefix);
    }

    /**
     * The project's current key prefix for the UI mask ({@code prefix•••}), or empty when no key exists.
     * Returns only the non-sensitive {@code keyPrefix} — never the hash, never the plaintext (which is
     * not stored). Backs the settings GET endpoint (Task 8).
     */
    public Optional<String> currentKeyPrefix(String projectId) {
        return apiKeyRepository.findByProjectId(projectId).map(ApiKey::getKeyPrefix);
    }

    /**
     * Resolve a presented plaintext key to its {@link ApiKey} by hash. Null/blank input returns empty
     * (never an NPE — {@link Sha256Hex#hex(String)} throws on null, so the boundary guards it). No
     * plaintext comparison occurs.
     */
    public Optional<ApiKey> lookup(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return Optional.empty();
        }
        return apiKeyRepository.findByKeyHash(Sha256Hex.hex(plaintext));
    }

    /**
     * Result of {@link #generate(String)} — carries the plaintext exactly once. Intentionally not a
     * persisted entity: the plaintext exists only as this in-memory return value, surfaced to the user a
     * single time.
     */
    public record GeneratedKey(String plaintext, String keyPrefix) {
    }
}
