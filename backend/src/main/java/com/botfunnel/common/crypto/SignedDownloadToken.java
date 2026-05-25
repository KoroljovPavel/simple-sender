package com.botfunnel.common.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Mints and verifies HMAC-SHA256 signed download tokens for subscriber exports (Decision 15).
 *
 * <p>Token shape: {@code <base64url(payload-json)>.<base64url(hmac)>} (no padding). The HMAC is
 * computed over the EXACT base64url-encoded payload segment bytes — never over the decoded JSON —
 * so a JSON-whitespace re-emission cannot forge a token. The payload binds {@code projectId},
 * {@code exportId} and an absolute expiry; the download endpoint matches the parsed {@code projectId}
 * against the URL path itself, so cross-project substitution is rejected by the signature.
 *
 * <p>Constructor shape mirrors {@link TokenEncryptor}: fail-fast on a blank / non-hex / wrong-length
 * key, with the env-var name in the message and the JDK hex-parse detail deliberately scrubbed.
 */
@Component
public class SignedDownloadToken {

    private static final String PROPERTY_NAME = "SUBSCRIBER_EXPORT_TOKEN_KEY";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public SignedDownloadToken(@Value("${app.subscriber.export.token-key}") String hexKey) {
        this.key = new SecretKeySpec(decodeKey(hexKey), HMAC_ALGORITHM);
    }

    /** Produces a signed token binding {@code projectId}, {@code exportId} and {@code expiresAt}. */
    public String mint(String projectId, String exportId, Instant expiresAt) {
        byte[] payloadJson = serialize(new TokenPayload(projectId, exportId, expiresAt.toEpochMilli()));
        String payloadSegment = URL_ENCODER.encodeToString(payloadJson);
        String signatureSegment = URL_ENCODER.encodeToString(hmac(payloadSegment.getBytes(US_ASCII)));
        return payloadSegment + "." + signatureSegment;
    }

    /**
     * Verifies a token. Returns {@link Verified} with the parsed payload on success, or {@link Failed}
     * with a typed reason. Signature is checked (constant-time) before the JSON is parsed, and the
     * expiry only after the signature is trusted — so an attacker-tampered payload can never reach the
     * JSON parser or flip the failure reason to {@code EXPIRED}.
     */
    public VerificationResult verify(String token) {
        if (token == null) {
            return new Failed(FailureReason.INVALID_FORMAT);
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1 || dot != token.lastIndexOf('.')) {
            return new Failed(FailureReason.INVALID_FORMAT);
        }
        String payloadSegment = token.substring(0, dot);
        String signatureSegment = token.substring(dot + 1);

        byte[] providedSignature;
        byte[] payloadJson;
        try {
            providedSignature = URL_DECODER.decode(signatureSegment);
            payloadJson = URL_DECODER.decode(payloadSegment);
        } catch (IllegalArgumentException e) {
            return new Failed(FailureReason.INVALID_FORMAT);
        }

        byte[] expectedSignature = hmac(payloadSegment.getBytes(US_ASCII));
        if (!MessageDigest.isEqual(expectedSignature, providedSignature)) {
            return new Failed(FailureReason.BAD_SIGNATURE);
        }

        TokenPayload payload;
        try {
            payload = MAPPER.readValue(payloadJson, TokenPayload.class);
        } catch (Exception e) {
            return new Failed(FailureReason.INVALID_FORMAT);
        }

        if (payload.expiresAtEpochMilli() < Instant.now().toEpochMilli()) {
            return new Failed(FailureReason.EXPIRED);
        }
        return new Verified(payload.projectId(), payload.exportId(), payload.expiresAtEpochMilli());
    }

    private byte[] hmac(byte[] data) {
        try {
            // Mac is not thread-safe; instantiate per call (cheap) — safe under virtual-thread fan-out.
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    private static byte[] serialize(TokenPayload payload) {
        try {
            return MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize signed-download token payload", e);
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
            // into the boot log (mirror TokenEncryptor). Detail recoverable at DEBUG via the cause.
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

    /** Deterministic JSON payload — component order is the serialized field order. */
    record TokenPayload(String projectId, String exportId, long expiresAtEpochMilli) {}

    /** Typed verification outcome. */
    public sealed interface VerificationResult permits Verified, Failed {}

    public record Verified(String projectId, String exportId, long expiresAtEpochMilli)
            implements VerificationResult {}

    public record Failed(FailureReason reason) implements VerificationResult {}

    public enum FailureReason { INVALID_FORMAT, BAD_SIGNATURE, EXPIRED }
}
