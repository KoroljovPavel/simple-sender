package com.botfunnel.subscriber;

import com.botfunnel.project.CustomFieldType;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Validate→apply cycle for subscriber custom fields, extracted from
 * {@code SubscriberCustomFieldsController} so both the PATCH endpoint and the funnel engine's
 * {@code SET_CUSTOM_FIELD} step (Task 6, no HTTP context) can share it without replicating the
 * validate→update sequence (Decision 11).
 *
 * <p>Deliberately request-scope-free: no {@code ProjectService}, no {@code SecurityContextHolder}, no
 * HTTP types. The caller resolves the field {@link CustomFieldType} (controller from
 * {@code Project.customFieldDefinitions}; engine from the project's definitions) and owns anti-IDOR
 * scoping. Audit is NOT written here: the sole-writer path (Decision 11) stays at
 * {@link SubscriberService#recordCustomFieldsSet} and must be called ONCE per PATCH with the aggregated
 * old/new maps.
 *
 * <p>Validation is split from persistence on purpose so a multi-key PATCH stays all-or-nothing: the
 * caller {@link #validateAndNormalize validates+normalizes ALL keys first} (any invalid key throws 422
 * before a single write happens) and only then {@link #applyAll applies one aggregated update}.
 */
@Service
public class SubscriberCustomFieldsService {

    private final CustomFieldValueValidator validator;
    private final MongoTemplate mongoTemplate;

    public SubscriberCustomFieldsService(CustomFieldValueValidator validator,
                                         MongoTemplate mongoTemplate) {
        this.validator = validator;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Pure validation/normalization for ONE custom-field value — NO database access, NO audit.
     *
     * <p>Delegates to {@code validator.validate(type, value)}, which normalizes the value or throws a
     * 422 {@code AppException} on a type mismatch. The returned {@code Object} is the normalized value
     * to persist; a {@code null} return means "unset this field" ({@code $unset}), never "skip" — the
     * caller is responsible for pre-filtering keys whose field is no longer defined (deleted/unknown
     * field → drop before calling this method, as the controller's mass-assignment defense does).
     *
     * @param type  the resolved field type; MUST be non-null (caller pre-filters unknown keys)
     * @param value the raw incoming value
     * @return the normalized value to {@code $set}, or {@code null} to {@code $unset} the field
     * @throws IllegalArgumentException if {@code type} is null (precondition; both callers pre-filter
     *                                  null types, so this is a fail-fast on a programming error)
     * @throws com.botfunnel.common.AppException 422 if the value does not match {@code type}
     */
    public Object validateAndNormalize(CustomFieldType type, Object value) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        return validator.validate(type, value); // 422 on type mismatch
    }

    /**
     * Single-field set for the FUTURE funnel engine path (Task 6, one field per {@code SET_CUSTOM_FIELD}
     * step) — a thin wrapper over {@link #validateAndNormalize} + {@link #applyAll} that performs
     * validate+apply ONLY.
     *
     * <p>This method does NOT write the audit event. The caller (engine) is responsible for the
     * sole-writer audit via {@link SubscriberService#recordCustomFieldsSet} — exactly as the controller
     * does for its aggregated multi-key path. The controller deliberately does NOT route through this
     * method; it uses the two-pass {@link #validateAndNormalize} + {@link #applyAll} directly so a
     * multi-key PATCH stays all-or-nothing with a single aggregated audit event.
     *
     * <p>Behavior:
     * <ul>
     *   <li>{@code type == null} → return immediately (deleted/unknown field → silent no-op skip,
     *       mirroring the controller's mass-assignment pre-filter; no validation, no DB write).</li>
     *   <li>otherwise → {@link #validateAndNormalize}({@code type}, {@code value}) (422 on mismatch)
     *       then {@link #applyAll} of the single {@code key→normalized} entry (non-null {@code $set}s,
     *       {@code null} {@code $unset}s the field).</li>
     * </ul>
     *
     * @param type  the resolved field type, or {@code null} to skip (deleted/unknown field)
     * @param value the raw incoming value
     * @throws com.botfunnel.common.AppException 422 if the value does not match {@code type}
     */
    public void setOne(String projectId, String subscriberId, CustomFieldType type, String key, Object value) {
        if (type == null) {
            return; // deleted/unknown field → no-op skip (no validate, no DB write, no audit)
        }
        Object normalized = validateAndNormalize(type, value); // 422 on type mismatch
        applyAll(projectId, subscriberId, java.util.Collections.singletonMap(key, normalized));
    }

    /**
     * Applies ALL already-validated/normalized values to {@code customFields.<key>} in ONE aggregated
     * {@link MongoTemplate} update (a non-null value {@code $set}s the field, a {@code null} value
     * {@code $unset}s it). Atomic for the whole map; emits NO audit (caller records once).
     *
     * <p>No-op (no DB call) when {@code normalizedByKey} is empty.
     *
     * <p><strong>Precondition (caller-enforced; this method performs NO validation):</strong> every key
     * MUST originate from a validated project field definition — i.e. a slug matching
     * {@code ^[a-z0-9_-]{1,32}$} (no dots / Mongo-operator chars, so no dotted-path or operator
     * injection into {@code customFields.<key>}) — and every value MUST be an output of
     * {@link #validateAndNormalize}. Passing raw/unvalidated keys or values is a programming error.
     *
     * @param normalizedByKey values already passed through {@link #validateAndNormalize} (insertion
     *                        order preserved); {@code null} value → {@code $unset}, else {@code $set}
     */
    public void applyAll(String projectId, String subscriberId, Map<String, Object> normalizedByKey) {
        if (normalizedByKey.isEmpty()) {
            return;
        }
        Update update = new Update();
        for (Map.Entry<String, Object> entry : normalizedByKey.entrySet()) {
            Object normalized = entry.getValue();
            if (normalized == null) {
                update.unset("customFields." + entry.getKey()); // null clears the field
            } else {
                update.set("customFields." + entry.getKey(), normalized);
            }
        }
        mongoTemplate.update(Subscriber.class)
                .matching(Query.query(Criteria.where("_id").is(subscriberId).and("projectId").is(projectId)))
                .apply(update)
                .first();
    }
}
