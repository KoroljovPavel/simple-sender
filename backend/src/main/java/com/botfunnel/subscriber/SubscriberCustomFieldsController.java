package com.botfunnel.subscriber;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.project.CustomFieldDefinition;
import com.botfunnel.project.CustomFieldType;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectService;
import com.botfunnel.subscriber.dto.SetCustomFieldsRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final SubscriberService subscriberService;
    private final SubscriberCustomFieldsService customFieldsService;

    public SubscriberCustomFieldsController(ProjectService projectService,
                                            SubscriberRepository subscriberRepository,
                                            SubscriberService subscriberService,
                                            SubscriberCustomFieldsService customFieldsService) {
        this.projectService = projectService;
        this.subscriberRepository = subscriberRepository;
        this.subscriberService = subscriberService;
        this.customFieldsService = customFieldsService;
    }

    @PatchMapping
    public ResponseEntity<Map<String, Object>> setCustomFields(@PathVariable String projectId,
                                                               @PathVariable String subscriberId,
                                                               @Valid @RequestBody SetCustomFieldsRequest request) {
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
        for (Map.Entry<String, Object> entry : requested.entrySet()) {
            String key = entry.getKey();
            CustomFieldType type = allowed.get(key);
            if (type == null) {
                // Mass-assignment defense (AC11): unknown key → silently dropped, no error.
                log.debug("custom-fields PATCH dropped unknown key '{}' for subscriber {}", key, subscriberId);
                continue;
            }
            // Delegate validate + atomic per-key update to the shared service (Decision 11). Audit
            // stays aggregated below — recordCustomFieldsSet is NOT called per key.
            Object normalized = customFieldsService.setOne(projectId, subscriberId, type, key, entry.getValue());
            oldValues.put(key, current.get(key));
            newValues.put(key, normalized);
        }

        if (!newValues.isEmpty()) {
            // Decision 10/11 sole-writer path for subscriber_custom_field_set — called EXACTLY ONCE per
            // PATCH with the full aggregated old/new maps (idempotent: an empty old→new diff writes no
            // event). Per-key recording would fragment one PATCH into multiple events (audit regression).
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
