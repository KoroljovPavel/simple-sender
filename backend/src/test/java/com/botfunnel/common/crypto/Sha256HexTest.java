package com.botfunnel.common.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sha256HexTest {

    @Test
    void hex_knownInput_matchesProducerSideVector() {
        // Hard-coded SHA-256 / UTF-8 / lowercase-hex vector — pinned to a known external value
        // (verified out-of-band: `echo -n test-webhook-secret-deadbeef | shasum -a 256`).
        // If anyone swaps the algorithm, charset, or hex case, this fails — that is the point.
        String input = "test-webhook-secret-deadbeef";
        String expected = "95d0a775bfe72a35d3248540e5fe58bb2162230d557cbffa93b8a8e4f0d4a5f1";

        assertThat(Sha256Hex.hex(input)).isEqualTo(expected);
    }

    @Test
    void hex_utf8Input_matchesKnownVector() {
        // UTF-8 byte handling pinned to a known external vector — `кіт` (3 Cyrillic chars,
        // 6 UTF-8 bytes). A constant-return stub would fail this. Verified out-of-band:
        // `printf '\xd0\xba\xd1\x96\xd1\x82' | shasum -a 256`.
        String input = "кіт";
        String expected = "01c405a4ac44e957c3baf7a6ea489049bdbff5c8d69f4a171639ed454e62f70d";

        assertThat(Sha256Hex.hex(input)).isEqualTo(expected);
    }

    @Test
    void hex_output_isLowercase64chars() {
        String result = Sha256Hex.hex("any-input");

        assertThat(result).matches("^[0-9a-f]{64}$");
    }

    @Test
    void hex_nullInput_throws() {
        // Contract: preserves BotService.sha256Hex behaviour (NPE on null input via
        // String.getBytes). Caller (WebhookSecretVerifier) handles null at its own layer.
        assertThatThrownBy(() -> Sha256Hex.hex(null))
                .isInstanceOf(NullPointerException.class);
    }
}
