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

        // Two-pass, all-or-nothing PATCH semantics: validate EVERY key first (no DB writes), so an
        // invalid key throws 422 before anything is persisted; only then apply ONE aggregated update.
        // Pass 1 — validate + normalize ALL allowed keys; collect old/new maps. NO DB writes here, so
        // a 422 on a later key leaves the subscriber untouched (no partial write).
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
            // 422 here propagates with nothing written yet (Decision 11 shared validate path).
            Object normalized = customFieldsService.validateAndNormalize(type, entry.getValue());
            oldValues.put(key, current.get(key));
            newValues.put(key, normalized);
        }

        if (!newValues.isEmpty()) {
            // Pass 2 — apply ALL writes in ONE aggregated update (set for non-null, unset for null),
            // byte-identical to the original single mongoTemplate.update(...).
            customFieldsService.applyAll(projectId, subscriberId, newValues);
            // Decision 10/11 sole-writer path for subscriber_custom_field_set — called EXACTLY ONCE per
            // PATCH with the full aggregated old/new maps (idempotent: an empty old→new diff writes no
            // event). Per-key recording would fragment one PATCH into multiple events (audit regression).
            // Manual PATCH = human root → originDepth 0 (exempt from the auto-enroll volume limit; a
            // custom_field_set trigger it fires starts a depth-0 root execution). Decision 6.
            subscriberService.recordCustomFieldsSet(projectId, subscriberId, oldValues, newValues, 0);
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
