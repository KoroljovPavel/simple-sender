package com.botfunnel.common.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Spring application context refuses to start when
 * {@code app.bot.token-encryption-key} is missing, non-hex, or the wrong length —
 * the automated form of the tech-spec criterion
 * "backend fails to boot with a clear error message when the key is missing or wrong length".
 *
 * <p>Uses {@link ApplicationContextRunner} instead of {@code @SpringBootTest} to keep the
 * context minimal: we only instantiate the {@link TokenEncryptor} bean, so the test
 * neither needs Testcontainers nor risks reaching MongoDB/Redis bootstrap.
 */
class TokenEncryptorBootValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TokenEncryptor.class);

    @Test
    void contextFailsWhenKeyIsBlank() {
        contextRunner
                .withPropertyValues("app.bot.token-encryption-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            // Walk the full cause-chain text — the chained NumberFormatException
                            // (non-hex) sits below our IllegalStateException, so .rootCause()
                            // would point at the JDK exception rather than ours.
                            .hasStackTraceContaining("IllegalStateException")
                            .hasStackTraceContaining("BOT_TOKEN_ENCRYPTION_KEY");
                });
    }

    @Test
    void contextFailsWhenKeyIsNonHex() {
        contextRunner
                .withPropertyValues("app.bot.token-encryption-key=" + "zz".repeat(32))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            // Walk the full cause-chain text — the chained NumberFormatException
                            // (non-hex) sits below our IllegalStateException, so .rootCause()
                            // would point at the JDK exception rather than ours.
                            .hasStackTraceContaining("IllegalStateException")
                            .hasStackTraceContaining("BOT_TOKEN_ENCRYPTION_KEY");
                });
    }

    @Test
    void contextFailsWhenKeyIsWrongLength() {
        contextRunner
                .withPropertyValues("app.bot.token-encryption-key=" + "ab".repeat(31))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            // Walk the full cause-chain text — the chained NumberFormatException
                            // (non-hex) sits below our IllegalStateException, so .rootCause()
                            // would point at the JDK exception rather than ours.
                            .hasStackTraceContaining("IllegalStateException")
                            .hasStackTraceContaining("BOT_TOKEN_ENCRYPTION_KEY");
                });
    }

    @Test
    void contextStartsWhenKeyIsValidHexAndThirtyTwoBytes() {
        contextRunner
                .withPropertyValues(
                        "app.bot.token-encryption-key="
                                + "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TokenEncryptor.class);
                });
    }
}
