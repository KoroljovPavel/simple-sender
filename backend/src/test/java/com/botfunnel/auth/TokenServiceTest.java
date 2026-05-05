package com.botfunnel.auth;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private final TokenService tokenService = new TokenService();

    @Test
    void generateRawToken_decodesTo32Bytes() {
        String raw = tokenService.generateRawToken();
        byte[] decoded = Base64.getUrlDecoder().decode(raw);
        assertThat(decoded).hasSize(32);
    }

    @Test
    void generateRawToken_returnsDistinctTokens() {
        String a = tokenService.generateRawToken();
        String b = tokenService.generateRawToken();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void generateRawToken_isBase64UrlWithoutPadding() {
        String raw = tokenService.generateRawToken();
        assertThat(raw).doesNotContain("=");
        assertThat(raw).doesNotContain("+");
        assertThat(raw).doesNotContain("/");
    }

    @Test
    void hashToken_isDeterministic() {
        assertThat(tokenService.hashToken("abc")).isEqualTo(tokenService.hashToken("abc"));
    }

    @Test
    void hashToken_returns64HexChars() {
        String hash = tokenService.hashToken("anything");
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]+");
    }

    @Test
    void hashToken_differentInputs_produceDifferentHashes() {
        assertThat(tokenService.hashToken("a")).isNotEqualTo(tokenService.hashToken("b"));
    }
}
