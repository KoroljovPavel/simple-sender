package com.botfunnel.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BotStatusJsonTest {

    // Deliberately a bare ObjectMapper, not the Spring-configured bean — this test asserts the
    // enum's own @JsonValue/@JsonCreator contract independent of any global Jackson configuration.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void jacksonSerializesEnumAsLowercase() throws Exception {
        assertThat(objectMapper.writeValueAsString(BotStatus.CONNECTED)).isEqualTo("\"connected\"");
        assertThat(objectMapper.writeValueAsString(BotStatus.DISCONNECTED)).isEqualTo("\"disconnected\"");
    }

    @Test
    void jacksonDeserializesLowercaseToEnum() throws Exception {
        assertThat(objectMapper.readValue("\"connected\"", BotStatus.class)).isEqualTo(BotStatus.CONNECTED);
        assertThat(objectMapper.readValue("\"disconnected\"", BotStatus.class)).isEqualTo(BotStatus.DISCONNECTED);
    }
}
