package com.botfunnel.subscriber;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.project.CustomFieldDefinition;
import com.botfunnel.project.CustomFieldType;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectService;
import com.botfunnel.subscriber.dto.SetCustomFieldsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

// Subscriber custom-field value PATCH (Epic 05). Split out from Task 8's SubscriberController so the
// two waves don't collide on one file. Mass-assignment defense (AC11): unknown keys are silently
// dropped. The subscriber_custom_field_set event is emitted ONLY via
// SubscriberService.recordCustomFieldsSet — the sole writer per Decision 10 (no EventService here).
@RestController
@RequestMapping("/api/v1/projects/{projectId}/subscribers/{subscriberId}/custom-fields")
public class SubscriberCustomFieldsController {

    private static final Logger log = LoggerFactory.getLogger(SubscriberCustomFieldsController.class);

    private static final String MESSAGE_SUBSCRIBER_NOT_FOUND = "Subscriber not found";

    private final ProjectService projectService;
    private final SubscriberRepository subscriberRepository;
    private final CustomFieldValueValidator validator;
    private final SubscriberService subscriberService;
    private final MongoTemplate mongoTemplate;

    public SubscriberCustomFieldsController(ProjectService projectService,
                                            SubscriberRepository subscriberRepository,
                                            CustomFieldValueValidator validator,
                                            SubscriberService subscriberService,
                                            MongoTemplate mongoTemplate) {
        this.projectService = projectService;
        this.subscriberRepository = subscriberRepository;
        this.validator = validator;
        this.subscriberService = subscriberService;
        this.mongoTemplate = mongoTemplate;
    }

    @PatchMapping
    public ResponseEntity<Map<String, Object>> setCustomFields(@PathVariable String projectId,
                                                               @PathVariable String subscriberId,
                                                               @RequestBody SetCustomFieldsRequest request) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        // projectId-scoped lookup is the cross-project isolation guard: a subscriber that belongs to
        // a different project collapses to the same uniform 404 as a missing one (mirrors
        // SubscriberServiceImpl.unsubscribeManual).
        Subscriber subscriber = subscriberRepository.findById(subscriberId)
                .filter(s -> projectId.equals(s.getProjectId()))
                .orElseThrow(() -> AppException.notFound(MESSAGE_SUBSCRIBER_NOT_FOUND));

        Map<String, CustomFieldType> allowed = allowedTypes(project);
        Map<String, Object> current = subscriber.getCustomFields() == null
                ? Map.of() : subscriber.getCustomFields();
        Map<String, Object> requested = request.values() == null
                ? Map.of() : request.values();

        Map<String, Object> oldValues = new LinkedHashMap<>();
        Map<String, Object> newValues = new LinkedHashMap<>();
        Update update = new Update();
        for (Map.Entry<String, Object> entry : requested.entrySet()) {
            String key = entry.getKey();
            CustomFieldType type = allowed.get(key);
            if (type == null) {
                // Mass-assignment defense (AC11): unknown key → silently dropped, no error.
                log.debug("custom-fields PATCH dropped unknown key '{}' for subscriber {}", key, subscriberId);
                continue;
            }
            Object normalized = validator.validate(type, entry.getValue()); // 422 on type mismatch
            oldValues.put(key, current.get(key));
            newValues.put(key, normalized);
            if (normalized == null) {
                update.unset("customFields." + key); // null clears the field
            } else {
                update.set("customFields." + key, normalized);
            }
        }

        if (!newValues.isEmpty()) {
            mongoTemplate.update(Subscriber.class)
                    .matching(Query.query(Criteria.where("_id").is(subscriberId)))
                    .apply(update)
                    .first();
            // Decision 10 sole-writer path for subscriber_custom_field_set (idempotent: an empty
            // old→new diff writes no event).
            subscriberService.recordCustomFieldsSet(projectId, subscriberId, oldValues, newValues);
        }

        Subscriber reloaded = subscriberRepository.findById(subscriberId)
                .orElseThrow(() -> AppException.notFound(MESSAGE_SUBSCRIBER_NOT_FOUND));
        Map<String, Object> body = reloaded.getCustomFields() == null
                ? Map.of() : reloaded.getCustomFields();
        return ResponseEntity.ok(body);
    }

    private static Map<String, CustomFieldType> allowedTypes(Project project) {
        Map<String, CustomFieldType> map = new LinkedHashMap<>();
        if (project.getCustomFieldDefinitions() != null) {
            for (CustomFieldDefinition def : project.getCustomFieldDefinitions()) {
                map.put(def.name(), def.type());
            }
        }
        return map;
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw AppException.unauthorized("Not authenticated");
        }
        return details.id();
    }
}
