package com.botfunnel.webhook.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain JUnit 5 + Jackson — no Spring context. Records map natively in Jackson 2.12+ so no
 * {@code @JsonProperty} aliases should be required for the snake_case fields to bind.
 */
class TelegramUpdateDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialize_fullMessageUpdate_populatesAllNestedFields() throws Exception {
        String json = """
                {
                  "update_id": 100200300,
                  "message": {
                    "message_id": 42,
                    "from": {
                      "id": 9876543210,
                      "is_bot": false,
                      "first_name": "Alice",
                      "last_name": "Liddell",
                      "username": "alice",
                      "language_code": "uk"
                    },
                    "chat": {
                      "id": 123456789,
                      "type": "private",
                      "title": null,
                      "username": "alice"
                    },
                    "date": 1700000000,
                    "text": "/start ref_abc"
                  }
                }
                """;

        TelegramUpdate update = mapper.readValue(json, TelegramUpdate.class);

        assertThat(update.update_id()).isEqualTo(100200300L);
        assertThat(update.message()).isNotNull();
        assertThat(update.message().message_id()).isEqualTo(42L);
        assertThat(update.message().from()).isNotNull();
        assertThat(update.message().from().id()).isEqualTo(9876543210L);
        assertThat(update.message().from().is_bot()).isFalse();
        assertThat(update.message().from().first_name()).isEqualTo("Alice");
        assertThat(update.message().from().last_name()).isEqualTo("Liddell");
        assertThat(update.message().from().username()).isEqualTo("alice");
        assertThat(update.message().from().language_code()).isEqualTo("uk");
        assertThat(update.message().chat()).isNotNull();
        assertThat(update.message().chat().id()).isEqualTo(123456789L);
        assertThat(update.message().chat().type()).isEqualTo("private");
        assertThat(update.message().chat().title()).isNull();
        assertThat(update.message().chat().username()).isEqualTo("alice");
        assertThat(update.message().date()).isEqualTo(1700000000L);
        assertThat(update.message().text()).isEqualTo("/start ref_abc");
    }

    @Test
    void deserialize_unknownTopLevelField_isIgnored() throws Exception {
        String json = """
                {
                  "update_id": 1,
                  "some_future_field": "x",
                  "another_unknown": 42
                }
                """;

        TelegramUpdate update = mapper.readValue(json, TelegramUpdate.class);

        assertThat(update.update_id()).isEqualTo(1L);
    }

    @Test
    void deserialize_unknownNestedField_isIgnored() throws Exception {
        String json = """
                {
                  "update_id": 1,
                  "message": {
                    "message_id": 7,
                    "junk": 1,
                    "chat": {"id": 5, "type": "private", "future_field": "x"}
                  }
                }
                """;

        TelegramUpdate update = mapper.readValue(json, TelegramUpdate.class);

        assertThat(update.message().message_id()).isEqualTo(7L);
        assertThat(update.message().chat().id()).isEqualTo(5L);
        assertThat(update.message().chat().type()).isEqualTo("private");
    }

    @Test
    void deserialize_missingMessage_messageFieldIsNull() throws Exception {
        String json = """
                {"update_id": 1}
                """;

        TelegramUpdate update = mapper.readValue(json, TelegramUpdate.class);

        assertThat(update.update_id()).isEqualTo(1L);
        assertThat(update.message()).isNull();
    }

    @Test
    void deserialize_channelPostNoFrom_messageFromIsNull() throws Exception {
        // Channel posts have no `from`; worker safe-navigates message.from().
        String json = """
                {
                  "update_id": 5,
                  "channel_post": {
                    "message_id": 1,
                    "chat": {"id": -100, "type": "channel", "title": "MyChannel"},
                    "date": 1700000000,
                    "text": "hi"
                  }
                }
                """;

        TelegramUpdate update = mapper.readValue(json, TelegramUpdate.class);

        assertThat(update.channel_post()).isNotNull();
        assertThat(update.channel_post().from()).isNull();
        assertThat(update.channel_post().chat().type()).isEqualTo("channel");
    }

    @Test
    void deserialize_malformedJson_throwsJacksonException() {
        String malformed = "{\"update_id\": 1, \"mes";

        assertThatThrownBy(() -> mapper.readValue(malformed, TelegramUpdate.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void deserialize_emptyObject_allFieldsNull() throws Exception {
        TelegramUpdate update = mapper.readValue("{}", TelegramUpdate.class);

        assertThat(update).isNotNull();
        assertThat(update.update_id()).isNull();
        assertThat(update.message()).isNull();
        assertThat(update.edited_message()).isNull();
        assertThat(update.channel_post()).isNull();
        assertThat(update.edited_channel_post()).isNull();
        assertThat(update.callback_query()).isNull();
        assertThat(update.my_chat_member()).isNull();
        assertThat(update.chat_member()).isNull();
        assertThat(update.inline_query()).isNull();
        assertThat(update.shipping_query()).isNull();
        assertThat(update.pre_checkout_query()).isNull();
        assertThat(update.poll_answer()).isNull();
    }

    @Test
    void deserialize_updateIdAsString_throwsMismatchedInputException() {
        // Type-safety regression: schema must reject a String where Long is expected.
        String json = "{\"update_id\": \"abc\"}";

        assertThatThrownBy(() -> mapper.readValue(json, TelegramUpdate.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void deserialize_callbackQuerySlot_populatedAsJsonNode() throws Exception {
        String json = """
                {
                  "update_id": 9,
                  "callback_query": {"id": "cb-1", "data": "vote_yes"}
                }
                """;

        TelegramUpdate update = mapper.readValue(json, TelegramUpdate.class);

        assertThat(update.callback_query()).isNotNull();
        JsonNode cb = update.callback_query();
        assertThat(cb.get("id").asText()).isEqualTo("cb-1");
        assertThat(cb.get("data").asText()).isEqualTo("vote_yes");
    }

    @Test
    void deserialize_myChatMemberSlot_messageRemainsNull() throws Exception {
        // Slot independence: my_chat_member populated without message implies updateKind
        // resolution path that the worker uses (per AC11).
        String json = """
                {
                  "update_id": 10,
                  "my_chat_member": {"old_chat_member": {"status": "member"}}
                }
                """;

        TelegramUpdate update = mapper.readValue(json, TelegramUpdate.class);

        assertThat(update.my_chat_member()).isNotNull();
        assertThat(update.message()).isNull();
    }
}
