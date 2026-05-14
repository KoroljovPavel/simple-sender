package com.botfunnel.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class TokenEncryptor {

    private static final String PROPERTY_NAME = "BOT_TOKEN_ENCRYPTION_KEY";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenEncryptor(@Value("${app.bot.token-encryption-key}") String hexKey) {
        this.key = new SecretKeySpec(decodeKey(hexKey), "AES");
    }

    public EncryptedValue encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(UTF_8));
            return new EncryptedValue(iv, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    public String decrypt(byte[] iv, byte[] ciphertext) {
        if (iv == null || iv.length != IV_BYTES) {
            throw new IllegalArgumentException(
                    "AES-GCM IV must be exactly " + IV_BYTES + " bytes; got "
                            + (iv == null ? "null" : iv.length));
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }

    private static byte[] decodeKey(String hexKey) {
        if (hexKey == null || hexKey.isBlank()) {
            throw new IllegalStateException(failMessage("blank value"));
        }
        byte[] decoded;
        try {
            decoded = HexFormat.of().parseHex(hexKey);
        } catch (IllegalArgumentException e) {
            // Deliberately drop the JDK message — `HexFormat.parseHex` echoes the offending
            // character and its index, which would leak a fragment of the misconfigured key
            // into the boot log. The character-position detail is recoverable at DEBUG by
            // attaching `e` as the cause.
            throw new IllegalStateException(
                    failMessage("not valid hex; expected 0-9 / a-f / A-F only"), e);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException(failMessage(
                    "got " + decoded.length + " bytes after hex-decoding "
                            + hexKey.length() + " characters"));
        }
        return decoded;
    }

    private static String failMessage(String reason) {
        return PROPERTY_NAME + " must be " + KEY_BYTES + " bytes / "
                + (KEY_BYTES * 2) + " hex characters; " + reason;
    }
}
