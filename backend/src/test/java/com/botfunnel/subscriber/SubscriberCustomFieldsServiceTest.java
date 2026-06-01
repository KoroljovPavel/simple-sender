package com.botfunnel.subscriber;

import com.botfunnel.common.AppException;
import com.botfunnel.project.CustomFieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Pure unit test of the extracted per-key set-custom-field cycle. No Spring: mocked validator,
// SubscriberService (the sole-writer audit boundary), and a deep-stub MongoTemplate so the fluent
// update(...).matching(...).apply(...).first() chain can be stubbed. LENIENT because the shared
// MongoTemplate deep-stub is only exercised by the update scenarios.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriberCustomFieldsServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String SUBSCRIBER_ID = "sub-1";

    @Mock CustomFieldValueValidator validator;
    @Mock SubscriberService subscriberService;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) MongoTemplate mongoTemplate;

    private SubscriberCustomFieldsService newService() {
        return new SubscriberCustomFieldsService(validator, subscriberService, mongoTemplate);
    }

    @Test
    void setOne_validValue_updatesAndReturnsNormalized() {
        when(validator.validate(CustomFieldType.STRING, "  Kyiv  ")).thenReturn("Kyiv");

        Object normalized = newService().setOne(PROJECT_ID, SUBSCRIBER_ID, CustomFieldType.STRING, "city", "  Kyiv  ");

        assertThat(normalized).isEqualTo("Kyiv");
        // Atomic update applied to customFields.city; sole-writer audit NOT called from setOne (the
        // controller aggregates and records once per PATCH).
        ArgumentCaptor<UpdateDefinition> captor = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate.update(Subscriber.class).matching(any(Query.class))).apply(captor.capture());
        assertThat(captor.getValue().getUpdateObject().toString()).contains("customFields.city").contains("Kyiv");
        verify(subscriberService, never()).recordCustomFieldsSet(any(), any(), any(), any());
    }

    @Test
    void setOne_invalidValue_throws422() {
        when(validator.validate(CustomFieldType.NUMBER, "abc"))
                .thenThrow(AppException.unprocessableEntity("custom_field_type_mismatch", "bad"));

        assertThatThrownBy(() ->
                newService().setOne(PROJECT_ID, SUBSCRIBER_ID, CustomFieldType.NUMBER, "age", "abc"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // No write, no audit on a validation failure.
        verifyNoInteractions(subscriberService);
    }

    @Test
    void setOne_deletedField_skips() {
        // type == null → the field is no longer defined in the project → no-op (mirrors the
        // controller mass-assignment defense / tech-spec "deleted field → skip").
        Object result = newService().setOne(PROJECT_ID, SUBSCRIBER_ID, null, "ghost", "anything");

        assertThat(result).isNull();
        verifyNoInteractions(validator);
        verifyNoInteractions(subscriberService);
        verify(mongoTemplate, never()).update(any(Class.class));
    }

    @Test
    void setOne_nullValue_unsetsField() {
        when(validator.validate(CustomFieldType.STRING, null)).thenReturn(null);

        Object normalized = newService().setOne(PROJECT_ID, SUBSCRIBER_ID, CustomFieldType.STRING, "city", null);

        assertThat(normalized).isNull();
        ArgumentCaptor<UpdateDefinition> captor = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate.update(Subscriber.class).matching(any(Query.class))).apply(captor.capture());
        // $unset clears the field rather than storing a null.
        assertThat(captor.getValue().getUpdateObject().toString()).contains("$unset").contains("customFields.city");
        verify(subscriberService, never()).recordCustomFieldsSet(any(), any(), any(), any());
    }
}
