package com.botfunnel.project;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.project.dto.CreateCustomFieldRequest;
import com.botfunnel.project.dto.CustomFieldResponse;
import com.botfunnel.project.dto.UpdateCustomFieldRequest;
import com.botfunnel.subscriber.CustomFieldValueValidator;
import com.botfunnel.subscriber.Subscriber;
import com.mongodb.client.result.UpdateResult;
import jakarta.validation.Valid;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// CustomFieldDefinition schema CRUD on Project.customFieldDefinitions[] (Epic 05). Every handler
// calls projectService.requireOwned(...) FIRST for anti-IDOR / anti-enumeration (uniform 404 on
// foreign owner, soft-deleted, or malformed projectId). Uses MongoTemplate for the atomic
// conditional push (Decision 3) — no repository method can express the array size guard.
@RestController
@RequestMapping("/api/v1/projects/{projectId}/custom-fields")
public class CustomFieldsController {

    private static final int MAX_DEFINITIONS = 20;

    // Decision 3 atomic conditional push guard. The array is "full" once the slot at index
    // (MAX_DEFINITIONS - 1) exists — i.e. there are already MAX_DEFINITIONS elements (indices
    // 0..19). When the push runs with that slot present, $exists:false fails → modifiedCount 0 →
    // 422. NOTE: Decision 3 text names index 20, but index 20 is the 21st slot, which would cap at
    // 21 (off-by-one). Index 19 is what enforces the 20-element cap the acceptance criteria require
    // (parallel21Posts_acceptsOnly20, create_at20Cap_21stPostReturns422). Documented in decisions.md.
    private static final String FULL_SLOT_PATH = "customFieldDefinitions." + (MAX_DEFINITIONS - 1);

    private static final String CODE_LIMIT_REACHED = "custom_field_limit_reached";
    private static final String CODE_NAME_TAKEN = "custom_field_name_taken";

    private static final String MESSAGE_LIMIT_REACHED = "Custom field limit reached (max 20)";
    private static final String MESSAGE_NAME_TAKEN = "Custom field name already in use";
    private static final String MESSAGE_NOT_FOUND = "Custom field not found";

    private static final String EVT_CREATED = "custom_field_definition_created";
    private static final String EVT_UPDATED = "custom_field_definition_updated";
    private static final String EVT_DELETED = "custom_field_definition_deleted";

    private final ProjectService projectService;
    private final MongoTemplate mongoTemplate;
    private final CustomFieldValueValidator validator;
    private final EventService eventService;

    public CustomFieldsController(ProjectService projectService,
                                  MongoTemplate mongoTemplate,
                                  CustomFieldValueValidator validator,
                                  EventService eventService) {
        this.projectService = projectService;
        this.mongoTemplate = mongoTemplate;
        this.validator = validator;
        this.eventService = eventService;
    }

    @GetMapping
    public ResponseEntity<List<CustomFieldResponse>> list(@PathVariable String projectId) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        List<CustomFieldResponse> body = definitions(project).stream()
                .map(CustomFieldsController::toResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<CustomFieldResponse> create(@PathVariable String projectId,
                                                      @Valid @RequestBody CreateCustomFieldRequest request) {
        String userId = currentUserId();
        Project project = projectService.requireOwned(userId, projectId, false);

        Object normalizedDefault = validator.validate(request.type(), request.defaultValue());

        // Pre-check the common case so a duplicate name short-circuits with a clean 409 before the
        // conditional push (the race-only path below re-checks after a failed push).
        if (containsName(definitions(project), request.name())) {
            throw AppException.conflict(CODE_NAME_TAKEN, MESSAGE_NAME_TAKEN);
        }

        CustomFieldDefinition def = new CustomFieldDefinition(
                request.name(), request.label(), request.type(), normalizedDefault, Instant.now());

        // Decision 3: atomic conditional push closes the 20-cap race at the Mongo layer.
        UpdateResult result = mongoTemplate.update(Project.class)
                .matching(Query.query(Criteria.where("_id").is(projectId).and(FULL_SLOT_PATH).exists(false)))
                .apply(new Update().push("customFieldDefinitions", def))
                .first();

        if (result.getModifiedCount() == 0L) {
            // Push was a no-op: either the array is full, or a concurrent push added the same name.
            // Re-load to disambiguate (the pre-check above used a possibly-stale snapshot).
            Project reloaded = projectService.requireOwned(userId, projectId, false);
            if (definitions(reloaded).size() >= MAX_DEFINITIONS) {
                throw AppException.unprocessableEntity(CODE_LIMIT_REACHED, MESSAGE_LIMIT_REACHED);
            }
            throw AppException.conflict(CODE_NAME_TAKEN, MESSAGE_NAME_TAKEN);
        }

        eventService.logEvent(userId, EVT_CREATED, null, null,
                Map.of("projectId", projectId, "name", def.name(), "type", def.type().name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(def));
    }

    @PatchMapping("/{name}")
    public ResponseEntity<CustomFieldResponse> update(@PathVariable String projectId,
                                                      @PathVariable String name,
                                                      @Valid @RequestBody UpdateCustomFieldRequest request) {
        String userId = currentUserId();
        Project project = projectService.requireOwned(userId, projectId, false);
        CustomFieldDefinition existing = findDefinition(project, name);

        // PATCH semantics: null field = no change. name + type are immutable (Decision 11); the DTO
        // does not carry them and @JsonIgnoreProperties drops any stray body field, so they cannot
        // be touched here.
        String finalLabel = request.label() != null ? request.label() : existing.label();
        Object finalDefault = existing.defaultValue();

        Update update = new Update().filterArray(Criteria.where("def.name").is(name));
        boolean hasChange = false;
        if (request.label() != null) {
            update.set("customFieldDefinitions.$[def].label", request.label());
            hasChange = true;
        }
        if (request.defaultValue() != null) {
            finalDefault = validator.validate(existing.type(), request.defaultValue());
            update.set("customFieldDefinitions.$[def].defaultValue", finalDefault);
            hasChange = true;
        }

        if (hasChange) {
            mongoTemplate.update(Project.class)
                    .matching(Query.query(Criteria.where("_id").is(projectId)))
                    .apply(update)
                    .first();
            eventService.logEvent(userId, EVT_UPDATED, null, null,
                    Map.of("projectId", projectId, "name", name));
        }

        CustomFieldDefinition updated = new CustomFieldDefinition(
                existing.name(), finalLabel, existing.type(), finalDefault, existing.createdAt());
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String name) {
        String userId = currentUserId();
        projectService.requireOwned(userId, projectId, false);

        UpdateResult pull = mongoTemplate.update(Project.class)
                .matching(Query.query(Criteria.where("_id").is(projectId)))
                .apply(new Update().pull("customFieldDefinitions",
                        Query.query(Criteria.where("name").is(name))))
                .first();
        if (pull.getModifiedCount() == 0L) {
            throw AppException.notFound(MESSAGE_NOT_FOUND);
        }

        // Cascade: drop the value out of every subscriber that carries it (user-spec AC12). The
        // exists(true) filter limits the write to documents that actually hold the field.
        UpdateResult cascade = mongoTemplate.update(Subscriber.class)
                .matching(Query.query(Criteria.where("projectId").is(projectId)
                        .and("customFields." + name).exists(true)))
                .apply(new Update().unset("customFields." + name))
                .all();

        eventService.logEvent(userId, EVT_DELETED, null, null,
                Map.of("projectId", projectId, "name", name,
                        "removedValueCount", cascade.getModifiedCount()));
        return ResponseEntity.noContent().build();
    }

    private static List<CustomFieldDefinition> definitions(Project project) {
        List<CustomFieldDefinition> defs = project.getCustomFieldDefinitions();
        return defs == null ? List.of() : defs;
    }

    private static boolean containsName(List<CustomFieldDefinition> defs, String name) {
        return defs.stream().anyMatch(d -> d.name().equals(name));
    }

    private static CustomFieldDefinition findDefinition(Project project, String name) {
        return definitions(project).stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElseThrow(() -> AppException.notFound(MESSAGE_NOT_FOUND));
    }

    private static CustomFieldResponse toResponse(CustomFieldDefinition def) {
        return new CustomFieldResponse(
                def.name(), def.label(), def.type(), def.defaultValue(), def.createdAt());
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw AppException.unauthorized("Not authenticated");
        }
        return details.id();
    }
}
