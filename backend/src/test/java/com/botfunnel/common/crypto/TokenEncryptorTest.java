package com.botfunnel.common.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenEncryptorTest {

    private static final String TEST_KEY_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    private static final String OTHER_KEY_HEX =
            "112233445566778899aabbccddeeff00112233445566778899aabbccddeeff00";

    @Test
    void roundTripAscii() {
        TokenEncryptor encryptor = new TokenEncryptor(TEST_KEY_HEX);
        String plaintext = "1234567890:AAFakeAsciiBotTokenForUnitTest";

        EncryptedValue encrypted = encryptor.encrypt(plaintext);
        String decrypted = encryptor.decrypt(encrypted.iv(), encrypted.ciphertext());

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void roundTripUnicode() {
        TokenEncryptor encryptor = new TokenEncryptor(TEST_KEY_HEX);
        String plaintext = "привіт-bot";

        EncryptedValue encrypted = encryptor.encrypt(plaintext);
        String decrypted = encryptor.decrypt(encrypted.iv(), encrypted.ciphertext());

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void freshIvPerCall() {
        TokenEncryptor encryptor = new TokenEncryptor(TEST_KEY_HEX);
        String plaintext = "same-plaintext";

        EncryptedValue first = encryptor.encrypt(plaintext);
        EncryptedValue second = encryptor.encrypt(plaintext);

        assertThat(first.iv()).hasSize(12);
        assertThat(second.iv()).hasSize(12);
        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void tamperedCiphertextRejected() {
        TokenEncryptor encryptor = new TokenEncryptor(TEST_KEY_HEX);
        EncryptedValue encrypted = encryptor.encrypt("victim-plaintext");

        byte[] tampered = encrypted.ciphertext().clone();
        tampered[0] = (byte) (tampered[0] ^ 0x01);

        assertThatThrownBy(() -> encryptor.decrypt(encrypted.iv(), tampered))
                .rootCause()
                .isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void tamperedIvRejected() {
        TokenEncryptor encryptor = new TokenEncryptor(TEST_KEY_HEX);
        EncryptedValue encrypted = encryptor.encrypt("victim-plaintext");

        byte[] otherIv = new byte[12];
        new SecureRandom().nextBytes(otherIv);
        // Make sure it really differs from the original.
        if (Arrays.equals(otherIv, encrypted.iv())) {
            otherIv[0] = (byte) (otherIv[0] ^ 0x01);
        }

        assertThatThrownBy(() -> encryptor.decrypt(otherIv, encrypted.ciphertext()))
                .rootCause()
                .isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void wrongKeyRejected() {
        TokenEncryptor encryptor = new TokenEncryptor(TEST_KEY_HEX);
        TokenEncryptor other = new TokenEncryptor(OTHER_KEY_HEX);
        EncryptedValue encrypted = encryptor.encrypt("secret-token");

        assertThatThrownBy(() -> other.decrypt(encrypted.iv(), encrypted.ciphertext()))
                .rootCause()
                .isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void missingKeyFailsFast() {
        assertThatThrownBy(() -> new TokenEncryptor(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOT_TOKEN_ENCRYPTION_KEY");
    }

    @Test
    void shortKeyFailsFast() {
        String shortHex = "ab".repeat(31); // 62 hex chars = 31 bytes
        assertThatThrownBy(() -> new TokenEncryptor(shortHex))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOT_TOKEN_ENCRYPTION_KEY");
    }

    @Test
    void nonHexKeyFailsFast() {
        String nonHex = "zz".repeat(32); // 64 chars, none of which are hex digits
        assertThatThrownBy(() -> new TokenEncryptor(nonHex))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOT_TOKEN_ENCRYPTION_KEY");
    }

    @Test
    void longKeyFailsFast() {
        String longHex = "ab".repeat(33); // 66 hex chars = 33 bytes
        assertThatThrownBy(() -> new TokenEncryptor(longHex))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOT_TOKEN_ENCRYPTION_KEY");
    }

    @Test
    void decryptRejectsNonTwelveByteIv() {
        TokenEncryptor encryptor = new TokenEncryptor(TEST_KEY_HEX);
        byte[] badIv = new byte[16];
        byte[] anyCiphertext = new byte[16];

        assertThatThrownBy(() -> encryptor.decrypt(badIv, anyCiphertext))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
