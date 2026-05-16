package com.botfunnel.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Single source of truth for SHA-256 hex hashing used by webhook-secret production
 * ({@code BotService.connect}) and verification ({@code WebhookSecretVerifier}).
 * UTF-8 byte encoding, lowercase hex output. Null input propagates as
 * {@link NullPointerException} (matches the previous private {@code BotService.sha256Hex}
 * contract); higher layers handle null-tolerant input at their own boundary.
 */
public final class Sha256Hex {

    private Sha256Hex() {
        throw new UnsupportedOperationException("utility class");
    }

    public static String hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
