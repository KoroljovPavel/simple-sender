package com.botfunnel.webhook;

import com.botfunnel.common.crypto.Sha256Hex;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * Plain JUnit 5 unit test — WebhookSecretVerifier has no injected collaborators yet, so no
 * Spring context (and no {@code @MockitoSpyBean}) is needed. The
 * {@link #verify_usesMessageDigestIsEqual()} test pins the constant-time comparison primitive
 * via {@link MockedStatic} (Mockito 5.x inline mocking; no separate {@code mockito-inline}
 * artifact). A timing-based assertion would be CI-flaky and is intentionally avoided.
 */
class WebhookSecretVerifierTest {

    private final WebhookSecretVerifier verifier = new WebhookSecretVerifier();

    @Test
    void verify_matchingSecret_returnsTrue() {
        String plaintext = "telegram-header-secret-1234567890abcdef";
        String storedHash = Sha256Hex.hex(plaintext);

        assertThat(verifier.verify(plaintext, storedHash)).isTrue();
    }

    @Test
    void verify_nonMatchingSecret_returnsFalse() {
        String storedHash = Sha256Hex.hex("the-real-secret");

        assertThat(verifier.verify("a-different-secret", storedHash)).isFalse();
    }

    @Test
    void verify_emptyHeader_returnsFalse() {
        String storedHash = Sha256Hex.hex("anything");

        assertThat(verifier.verify("", storedHash)).isFalse();
    }

    @Test
    void verify_nullHeader_returnsFalse() {
        String storedHash = Sha256Hex.hex("anything");

        assertThat(verifier.verify(null, storedHash)).isFalse();
    }

    @Test
    void verify_nullStoredHash_returnsFalse() {
        assertThat(verifier.verify("any-header", null)).isFalse();
    }

    @Test
    void verify_malformedHexStoredHash_oddLength_returnsFalse() {
        // 63-char hex string (odd length) — HexFormat.parseHex throws IllegalArgumentException.
        String malformed = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde";

        assertThat(verifier.verify("any-header", malformed)).isFalse();
    }

    @Test
    void verify_malformedHexStoredHash_nonHexChars_returnsFalse() {
        // Contains 'z' / 'g' / 'y' — not valid hex digits.
        String malformed = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz";

        assertThat(verifier.verify("any-header", malformed)).isFalse();
    }

    @Test
    void verify_usesMessageDigestIsEqual() {
        // Pin the constant-time primitive — String.equals would be timing-vulnerable.
        // CALLS_REAL_METHODS keeps actual comparison semantics so the assertion still passes;
        // we only need to PROVE the static call site exists.
        String plaintext = "verify-isequal-call-site";
        String storedHash = Sha256Hex.hex(plaintext);

        try (MockedStatic<MessageDigest> mocked = mockStatic(MessageDigest.class, CALLS_REAL_METHODS)) {
            boolean result = verifier.verify(plaintext, storedHash);

            assertThat(result).isTrue();
            mocked.verify(() -> MessageDigest.isEqual(any(byte[].class), any(byte[].class)));
        }
    }
}
