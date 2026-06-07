package com.botfunnel.api;

/**
 * POST response for {@code /api/v1/projects/{projectId}/api-key} — carries the freshly minted plaintext
 * key <b>exactly once</b> (Decision 8). This is the ONLY place the full key is ever surfaced; it is never
 * persisted and never returned again (GET only ever yields the {@link ApiKeyMaskResponse} mask).
 *
 * @param apiKey the plaintext key, shown this once only — the caller must store it now
 * @param mask   the {@code prefix•••} mask the GET endpoint will return thereafter
 */
public record ApiKeyGeneratedResponse(String apiKey, String mask) {

    static ApiKeyGeneratedResponse of(String plaintext, String keyPrefix) {
        return new ApiKeyGeneratedResponse(plaintext, ApiKeyMask.mask(keyPrefix));
    }
}
