package com.botfunnel.api;

/**
 * GET response for {@code /api/v1/projects/{projectId}/api-key} — the current key's mask only, NEVER
 * the plaintext (Decision 8). The mask is derived from the stored {@code keyPrefix} ({@code prefix•••}),
 * not reconstructed from the full key (which is not persisted).
 *
 * @param present whether a key currently exists for the project
 * @param mask    {@code prefix•••} when present, {@code null} when absent
 */
public record ApiKeyMaskResponse(boolean present, String mask) {

    static ApiKeyMaskResponse absent() {
        return new ApiKeyMaskResponse(false, null);
    }

    static ApiKeyMaskResponse of(String keyPrefix) {
        return new ApiKeyMaskResponse(true, ApiKeyMask.mask(keyPrefix));
    }
}
