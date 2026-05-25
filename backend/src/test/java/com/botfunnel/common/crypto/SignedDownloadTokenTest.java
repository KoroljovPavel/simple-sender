package com.botfunnel.common.crypto;

import com.botfunnel.common.crypto.SignedDownloadToken.Failed;
import com.botfunnel.common.crypto.SignedDownloadToken.FailureReason;
import com.botfunnel.common.crypto.SignedDownloadToken.VerificationResult;
import com.botfunnel.common.crypto.SignedDownloadToken.Verified;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedDownloadTokenTest {

    private static final String TEST_KEY_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    private static final String OTHER_KEY_HEX =
            "112233445566778899aabbccddeeff00112233445566778899aabbccddeeff00";

    private final SignedDownloadToken token = new SignedDownloadToken(TEST_KEY_HEX);

    @Test
    void mintThenVerify_returnsParsedPayload() {
        Instant expiry = Instant.now().plus(24, ChronoUnit.HOURS);

        VerificationResult result = token.verify(token.mint("projA", "exp123", expiry));

        assertThat(result).isInstanceOf(Verified.class);
        Verified verified = (Verified) result;
        assertThat(verified.projectId()).isEqualTo("projA");
        assertThat(verified.exportId()).isEqualTo("exp123");
        assertThat(verified.expiresAtEpochMilli()).isEqualTo(expiry.toEpochMilli());
    }

    @Test
    void verify_payloadTampered_returnsBadSignature() {
        String[] parts = token.mint("projA", "exp123", future()).split("\\.");
        String tampered = flipFirstBase64Char(parts[0]) + "." + parts[1];

        assertThat(reasonOf(token.verify(tampered))).isEqualTo(FailureReason.BAD_SIGNATURE);
    }

    @Test
    void verify_signatureTampered_returnsBadSignature() {
        String[] parts = token.mint("projA", "exp123", future()).split("\\.");
        String tampered = parts[0] + "." + flipFirstBase64Char(parts[1]);

        assertThat(reasonOf(token.verify(tampered))).isEqualTo(FailureReason.BAD_SIGNATURE);
    }

    @Test
    void verify_expiredToken_returnsExpired() {
        String expired = token.mint("projA", "exp123", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(reasonOf(token.verify(expired))).isEqualTo(FailureReason.EXPIRED);
    }

    @Test
    void verify_crossProjectSubstitution_returnsBadSignature() {
        // Mint for project A, rewrite the payload to project B but keep A's signature.
        String[] parts = token.mint("projA", "exp123", future()).split("\\.");
        String json = new String(Base64.getUrlDecoder().decode(parts[0]), UTF_8)
                .replace("projA", "projB");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(UTF_8));
        String forged = forgedPayload + "." + parts[1];

        assertThat(reasonOf(token.verify(forged))).isEqualTo(FailureReason.BAD_SIGNATURE);
    }

    @Test
    void verify_tamperedAndExpired_returnsBadSignatureNotExpired() {
        // Signature is verified before expiry — a tampered+expired token must report the tamper,
        // never EXPIRED (the documented ordering property; guards against future reordering).
        String[] parts = token.mint("projA", "exp123", Instant.now().minus(1, ChronoUnit.HOURS))
                .split("\\.");
        String tampered = flipFirstBase64Char(parts[0]) + "." + parts[1];

        assertThat(reasonOf(token.verify(tampered))).isEqualTo(FailureReason.BAD_SIGNATURE);
    }

    @Test
    void verify_wrongKey_returnsBadSignature() {
        // A token minted under one key must not verify under another (mirror TokenEncryptor).
        SignedDownloadToken other = new SignedDownloadToken(OTHER_KEY_HEX);
        String minted = token.mint("projA", "exp123", future());

        assertThat(reasonOf(other.verify(minted))).isEqualTo(FailureReason.BAD_SIGNATURE);
    }

    @Test
    void verify_malformedToken_returnsInvalidFormat() {
        assertThat(reasonOf(token.verify("not a token"))).isEqualTo(FailureReason.INVALID_FORMAT);
        assertThat(reasonOf(token.verify("nodothere"))).isEqualTo(FailureReason.INVALID_FORMAT);
        assertThat(reasonOf(token.verify("!!!.@@@"))).isEqualTo(FailureReason.INVALID_FORMAT);
        assertThat(reasonOf(token.verify("a.b.c"))).isEqualTo(FailureReason.INVALID_FORMAT);
        assertThat(reasonOf(token.verify(""))).isEqualTo(FailureReason.INVALID_FORMAT);
    }

    @Test
    void constructor_blankKey_throws() {
        assertThatThrownBy(() -> new SignedDownloadToken(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUBSCRIBER_EXPORT_TOKEN_KEY");
    }

    @Test
    void constructor_nonHexKey_throws() {
        assertThatThrownBy(() -> new SignedDownloadToken("zz".repeat(32)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUBSCRIBER_EXPORT_TOKEN_KEY")
                .hasMessageNotContaining("'z'")
                .hasMessageNotContaining("at index");
    }

    @Test
    void constructor_shortKey_throws() {
        // 60 hex chars = 30 bytes.
        assertThatThrownBy(() -> new SignedDownloadToken("ab".repeat(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUBSCRIBER_EXPORT_TOKEN_KEY")
                .hasMessageContaining("30");
    }

    private static Instant future() {
        return Instant.now().plus(1, ChronoUnit.HOURS);
    }

    private static FailureReason reasonOf(VerificationResult result) {
        assertThat(result).isInstanceOf(Failed.class);
        return ((Failed) result).reason();
    }

    /** Replaces the first character with a different base64url character, keeping the segment decodable. */
    private static String flipFirstBase64Char(String segment) {
        char first = segment.charAt(0);
        char replacement = (first == 'A') ? 'B' : 'A';
        return replacement + segment.substring(1);
    }
}
