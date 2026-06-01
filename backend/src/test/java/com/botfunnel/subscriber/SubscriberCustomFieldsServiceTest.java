package com.botfunnel.subscriber;

import com.botfunnel.common.AppException;
import com.botfunnel.project.CustomFieldType;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.ExecutableUpdateOperation.TerminatingUpdate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Pure unit test of the extracted validate→apply cycle. No Spring: mocked validator and a deep-stub
// MongoTemplate so the fluent update(...).matching(...).apply(...).first() chain can be stubbed.
// LENIENT because the shared MongoTemplate deep-stub is only exercised by the applyAll scenarios.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriberCustomFieldsServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String SUBSCRIBER_ID = "sub-1";

    @Mock CustomFieldValueValidator validator;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) MongoTemplate mongoTemplate;

    private SubscriberCustomFieldsService newService() {
        return new SubscriberCustomFieldsService(validator, mongoTemplate);
    }

    private TerminatingUpdate<Subscriber> terminalUpdate() {
        return mongoTemplate.update(Subscriber.class).matching(any(Query.class)).apply(any(UpdateDefinition.class));
    }

    @Test
    void validateAndNormalize_validValue_returnsNormalized_noDbNoAudit() {
        when(validator.validate(CustomFieldType.STRING, "  Kyiv  ")).thenReturn("Kyiv");

        Object normalized = newService().validateAndNormalize(CustomFieldType.STRING, "  Kyiv  ");

        assertThat(normalized).isEqualTo("Kyiv");
        // Pure validation: no write is performed by this method.
        verify(mongoTemplate, never()).update(any(Class.class));
    }

    @Test
    void validateAndNormalize_nullType_throwsIllegalArgument() {
        assertThatThrownBy(() -> newService().validateAndNormalize(null, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type must not be null");

        verify(mongoTemplate, never()).update(any(Class.class));
    }

    @Test
    void validateAndNormalize_invalidValue_throws422_noWrite() {
        when(validator.validate(CustomFieldType.NUMBER, "abc"))
                .thenThrow(AppException.unprocessableEntity("custom_field_type_mismatch", "bad"));

        assertThatThrownBy(() -> newService().validateAndNormalize(CustomFieldType.NUMBER, "abc"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        verify(mongoTemplate, never()).update(any(Class.class));
    }

    @Test
    void applyAll_setsNonNullValues_andCommits() {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("city", "Kyiv");
        normalized.put("age", 42.0d);

        newService().applyAll(PROJECT_ID, SUBSCRIBER_ID, normalized);

        ArgumentCaptor<UpdateDefinition> captor = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate.update(Subscriber.class).matching(any(Query.class))).apply(captor.capture());
        Document set = captor.getValue().getUpdateObject().get("$set", Document.class);
        assertThat(set).isNotNull();
        assertThat(set.get("customFields.city")).isEqualTo("Kyiv");
        assertThat(set.get("customFields.age")).isEqualTo(42.0d);
        assertThat(captor.getValue().getUpdateObject().get("$unset", Document.class)).isNull();
        // The terminal DB-commit (.first()) must actually fire — a dropped write would not.
        verify(terminalUpdate()).first();
    }

    @Test
    void applyAll_nullValue_unsetsField_andCommits() {
        Map<String, Object> normalized = new HashMap<>();
        normalized.put("city", null);

        newService().applyAll(PROJECT_ID, SUBSCRIBER_ID, normalized);

        ArgumentCaptor<UpdateDefinition> captor = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate.update(Subscriber.class).matching(any(Query.class))).apply(captor.capture());
        Document unset = captor.getValue().getUpdateObject().get("$unset", Document.class);
        assertThat(unset).isNotNull();
        assertThat(unset).containsKey("customFields.city");
        assertThat(captor.getValue().getUpdateObject().get("$set", Document.class)).isNull();
        verify(terminalUpdate()).first();
    }

    @Test
    void applyAll_mixedNonNullAndNull_setsAndUnsetsInOneUpdate() {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("city", "Kyiv"); // non-null → $set
        normalized.put("age", null);    // null → $unset

        newService().applyAll(PROJECT_ID, SUBSCRIBER_ID, normalized);

        ArgumentCaptor<UpdateDefinition> captor = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate.update(Subscriber.class).matching(any(Query.class))).apply(captor.capture());
        Document update = captor.getValue().getUpdateObject();
        Document set = update.get("$set", Document.class);
        Document unset = update.get("$unset", Document.class);
        assertThat(set).isNotNull();
        assertThat(set.get("customFields.city")).isEqualTo("Kyiv");
        assertThat(unset).isNotNull();
        assertThat(unset).containsKey("customFields.age");
        verify(terminalUpdate()).first();
    }

    @Test
    void applyAll_emptyMap_isNoOp_noDbCall() {
        newService().applyAll(PROJECT_ID, SUBSCRIBER_ID, Map.of());

        verify(mongoTemplate, never()).update(any(Class.class));
    }

    @Test
    void setOne_validValue_validatesAndApplies() {
        when(validator.validate(CustomFieldType.STRING, "  Kyiv  ")).thenReturn("Kyiv");

        newService().setOne(PROJECT_ID, SUBSCRIBER_ID, CustomFieldType.STRING, "city", "  Kyiv  ");

        // validate path was exercised
        verify(validator).validate(CustomFieldType.STRING, "  Kyiv  ");
        // applyAll committed a single-key $set update
        ArgumentCaptor<UpdateDefinition> captor = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate.update(Subscriber.class).matching(any(Query.class))).apply(captor.capture());
        Document set = captor.getValue().getUpdateObject().get("$set", Document.class);
        assertThat(set).isNotNull();
        assertThat(set.get("customFields.city")).isEqualTo("Kyiv");
        assertThat(captor.getValue().getUpdateObject().get("$unset", Document.class)).isNull();
        verify(terminalUpdate()).first();
    }

    @Test
    void setOne_deletedFieldTypeNull_skips() {
        newService().setOne(PROJECT_ID, SUBSCRIBER_ID, null, "gone", "anything");

        // null type → no validate, no DB write, no applyAll
        verify(validator, never()).validate(any(), any());
        verify(mongoTemplate, never()).update(any(Class.class));
    }

    @Test
    void setOne_invalidValue_throws422() {
        when(validator.validate(CustomFieldType.NUMBER, "abc"))
                .thenThrow(AppException.unprocessableEntity("custom_field_type_mismatch", "bad"));

        assertThatThrownBy(() -> newService().setOne(PROJECT_ID, SUBSCRIBER_ID, CustomFieldType.NUMBER, "age", "abc"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 422 before any write
        verify(mongoTemplate, never()).update(any(Class.class));
    }

    @Test
    void setOne_nullValue_unsetsField() {
        when(validator.validate(CustomFieldType.STRING, null)).thenReturn(null);

        newService().setOne(PROJECT_ID, SUBSCRIBER_ID, CustomFieldType.STRING, "city", null);

        ArgumentCaptor<UpdateDefinition> captor = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate.update(Subscriber.class).matching(any(Query.class))).apply(captor.capture());
        Document unset = captor.getValue().getUpdateObject().get("$unset", Document.class);
        assertThat(unset).isNotNull();
        assertThat(unset).containsKey("customFields.city");
        assertThat(captor.getValue().getUpdateObject().get("$set", Document.class)).isNull();
        verify(terminalUpdate()).first();
    }
}
