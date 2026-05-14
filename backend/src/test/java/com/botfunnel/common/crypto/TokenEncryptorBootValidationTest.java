package com.botfunnel.common.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Spring application context refuses to start when
 * {@code app.bot.token-encryption-key} is missing, non-hex, or the wrong length —
 * the automated form of the tech-spec criterion
 * "backend fails to boot with a clear error message when the key is missing or wrong length".
 *
 * <p>Uses {@link ApplicationContextRunner} (not {@code @SpringBootTest}) to keep the
 * context minimal — only the {@link TokenEncryptor} bean is instantiated, so the test
 * neither needs Testcontainers nor risks reaching MongoDB/Redis bootstrap.
 *
 * <p>Failure assertions walk the {@link Throwable#getCause()} chain to find our
 * {@link IllegalStateException} and pin its message. The non-hex case wraps a JDK
 * {@code NumberFormatException} as its own cause, so {@code .rootCause()} would point
 * at the JDK exception rather than ours; walking the chain works uniformly.
 */
class TokenEncryptorBootValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TokenEncryptor.class);

    @Test
    void contextFailsWhenKeyIsBlank() {
        contextRunner
                .withPropertyValues("app.bot.token-encryption-key=")
                .run(this::assertKeyValidationFailure);
    }

    @Test
    void contextFailsWhenKeyIsNonHex() {
        contextRunner
                .withPropertyValues("app.bot.token-encryption-key=" + "zz".repeat(32))
                .run(this::assertKeyValidationFailure);
    }

    @Test
    void contextFailsWhenKeyIsWrongLength() {
        contextRunner
                .withPropertyValues("app.bot.token-encryption-key=" + "ab".repeat(31))
                .run(this::assertKeyValidationFailure);
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

    private void assertKeyValidationFailure(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Optional<IllegalStateException> found =
                findInCauseChain(context.getStartupFailure(), IllegalStateException.class);
        assertThat(found)
                .as("expected an IllegalStateException naming BOT_TOKEN_ENCRYPTION_KEY "
                        + "somewhere in the startup-failure cause chain")
                .isPresent();
        assertThat(found.get().getMessage()).contains("BOT_TOKEN_ENCRYPTION_KEY");
    }

    private static <T extends Throwable> Optional<T> findInCauseChain(Throwable head, Class<T> type) {
        Throwable cur = head;
        while (cur != null) {
            if (type.isInstance(cur)) {
                return Optional.of(type.cast(cur));
            }
            if (cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return Optional.empty();
    }
}
