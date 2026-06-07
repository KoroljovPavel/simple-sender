package com.botfunnel.api;

/**
 * Single source of truth for the UI key mask. The mask is built from the non-sensitive
 * {@code keyPrefix} only ({@code prefix•••}); the full plaintext is never available to reconstruct it
 * (hash-only at rest — Decision 8).
 */
final class ApiKeyMask {

    /** Trailing ellipsis appended to the prefix so the mask reads {@code prefix•••}. */
    static final String SUFFIX = "•••";

    private ApiKeyMask() {
        throw new UnsupportedOperationException("utility class");
    }

    static String mask(String keyPrefix) {
        String prefix = keyPrefix == null ? "" : keyPrefix;
        return prefix + SUFFIX;
    }
}
