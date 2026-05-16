package com.botfunnel.webhook;

import com.botfunnel.common.crypto.Sha256Hex;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Constant-time verifier for the {@code X-Telegram-Bot-Api-Secret-Token} header. Re-hashes
 * the incoming plaintext and compares it against the persisted hash using
 * {@link MessageDigest#isEqual(byte[], byte[])} — {@link String#equals(Object)} would be
 * timing-vulnerable and is forbidden here. Defensive null / empty / malformed-hex inputs
 * return {@code false} so the controller can map a uniform 401; neither the header value
 * nor the stored hash is ever logged.
 */
@Component
public class WebhookSecretVerifier {

    public boolean verify(String headerSecret, String storedHashHex) {
        if (headerSecret == null || headerSecret.isEmpty()) {
            return false;
        }
        if (storedHashHex == null) {
            return false;
        }

        byte[] candidateBytes;
        byte[] storedBytes;
        try {
            candidateBytes = HexFormat.of().parseHex(Sha256Hex.hex(headerSecret));
            storedBytes = HexFormat.of().parseHex(storedHashHex);
        } catch (IllegalArgumentException e) {
            // Protect against corrupt persisted state (storedHashHex malformed).
            return false;
        }

        return MessageDigest.isEqual(candidateBytes, storedBytes);
    }
}
