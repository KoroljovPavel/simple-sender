package com.botfunnel.subscriber;

import com.botfunnel.project.CustomFieldType;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Set-one-custom-field cycle extracted from {@code SubscriberCustomFieldsController} so both the PATCH
 * endpoint and the funnel engine's {@code SET_CUSTOM_FIELD} step (Task 6, no HTTP context) can share it
 * without replicating the validate→update sequence (Decision 11).
 *
 * <p>Deliberately request-scope-free: no {@code ProjectService}, no {@code SecurityContextHolder}, no
 * HTTP types. The caller resolves the field {@link CustomFieldType} (controller from
 * {@code Project.customFieldDefinitions}; engine from the project's definitions) and owns anti-IDOR
 * scoping. Audit is NOT written here: the sole-writer path (Decision 11) stays at
 * {@link SubscriberService#recordCustomFieldsSet} and must be called ONCE per PATCH with the aggregated
 * old/new maps — so {@link #setOne} only validates + applies the per-key value and returns the
 * normalized result, leaving the single aggregated record to the caller (see the controller).
 */
@Service
public class SubscriberCustomFieldsService {

    private final CustomFieldValueValidator validator;
    @SuppressWarnings("unused") // sole-writer audit boundary; reserved for the map-taking overload / engine path.
    private final SubscriberService subscriberService;
    private final MongoTemplate mongoTemplate;

    public SubscriberCustomFieldsService(CustomFieldValueValidator validator,
                                         SubscriberService subscriberService,
                                         MongoTemplate mongoTemplate) {
        this.validator = validator;
        this.subscriberService = subscriberService;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Validates and applies ONE custom-field value to {@code customFields.<key>}.
     *
     * <ul>
     *   <li>{@code type == null} (field no longer defined in the project) → skip (no-op), returns
     *       {@code null}. Mirrors the controller mass-assignment defense and the tech-spec
     *       "deleted field → skip" for the engine.</li>
     *   <li>otherwise {@code validator.validate(type, value)} normalizes (or throws 422 on a type
     *       mismatch); a {@code null} normalized value {@code $unset}s the field, a non-null value
     *       {@code $set}s it; the atomic {@code MongoTemplate} update is applied.</li>
     * </ul>
     *
     * Does NOT write the {@code subscriber_custom_field_set} audit event — that stays with
     * {@link SubscriberService#recordCustomFieldsSet}, called ONCE by the caller with the aggregated
     * old/new maps so a single PATCH never fragments into multiple events.
     *
     * @return the normalized value applied (or {@code null} for an unset / a skipped deleted field)
     */
    public Object setOne(String projectId, String subscriberId, CustomFieldType type, String key, Object value) {
        if (type == null) {
            return null; // deleted / unknown field → no-op
        }
        Object normalized = validator.validate(type, value); // 422 on type mismatch
        Update update = new Update();
        if (normalized == null) {
            update.unset("customFields." + key); // null clears the field
        } else {
            update.set("customFields." + key, normalized);
        }
        mongoTemplate.update(Subscriber.class)
                .matching(Query.query(Criteria.where("_id").is(subscriberId).and("projectId").is(projectId)))
                .apply(update)
                .first();
        return normalized;
    }
}
