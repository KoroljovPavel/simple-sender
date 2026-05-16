package com.botfunnel.common.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sha256HexTest {

    @Test
    void hex_knownInput_matchesProducerSide() throws Exception {
        // Parity vector with BotService.connect hashing: re-implement the exact same primitive
        // chain (MessageDigest.getInstance("SHA-256") + UTF-8 bytes + HexFormat.of()) so the
        // producer-side persisted hash and the verifier's recomputed hash compare equal.
        String input = "test-webhook-secret-deadbeef";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String expected = HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));

        assertThat(Sha256Hex.hex(input)).isEqualTo(expected);
    }

    @Test
    void hex_utf8Input_isDeterministic() {
        String input = "кіт";

        String first = Sha256Hex.hex(input);
        String second = Sha256Hex.hex(input);

        assertThat(first).isEqualTo(second);
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
