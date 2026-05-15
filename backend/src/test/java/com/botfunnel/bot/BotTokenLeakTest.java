package com.botfunnel.bot;

import com.botfunnel.bot.dto.BotResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// AC17 / R1 invariant: BotResponse must not expose any encrypted-token component, and Bot must
// not declare a toString that serialises the encrypted-token fields. Reflective so a future rename
// or accidental field addition fails the build, not the manual review.
class BotTokenLeakTest {

    private static final Set<String> FORBIDDEN_RESPONSE_COMPONENTS = Set.of(
            "encryptedTokenCiphertext",
            "encryptedTokenIv",
            "token",
            "webhookSecretHash"
    );

    private static final Set<String> FORBIDDEN_BOT_FIELDS = Set.of(
            "encryptedTokenCiphertext",
            "encryptedTokenIv",
            "webhookSecretHash"
    );

    @Test
    void botResponseRecordHasNoEncryptedTokenComponents() {
        RecordComponent[] components = BotResponse.class.getRecordComponents();
        assertThat(components)
                .as("BotResponse must remain a record so this reflective check is meaningful")
                .isNotNull();

        Set<String> componentNames = new HashSet<>();
        for (RecordComponent c : components) {
            componentNames.add(c.getName());
        }

        for (String forbidden : FORBIDDEN_RESPONSE_COMPONENTS) {
            assertThat(componentNames)
                    .as("BotResponse must NOT expose %s — token-leak invariant (AC17 / D17)", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    @Test
    void botEntityDoesNotDeclareToStringExposingEncryptedTokenFields() {
        // Bot is a plain JavaBean (no Lombok @ToString). If a future change adds a toString that
        // dumps the encrypted-token fields, fail the build here rather than at runtime in a log.
        Method[] declared = Bot.class.getDeclaredMethods();
        Method toString = Arrays.stream(declared)
                .filter(m -> "toString".equals(m.getName()) && m.getParameterCount() == 0)
                .findFirst()
                .orElse(null);

        assertThat(toString)
                .as("Bot must not declare a custom toString that could leak encrypted-token fields. "
                        + "The default Object#toString is acceptable; override with care and exclude "
                        + FORBIDDEN_BOT_FIELDS)
                .isNull();
    }
}
