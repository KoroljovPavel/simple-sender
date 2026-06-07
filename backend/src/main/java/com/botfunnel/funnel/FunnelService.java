package com.botfunnel.funnel;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.common.AppException;
import com.botfunnel.funnel.dto.ButtonDto;
import com.botfunnel.funnel.dto.CreateFunnelRequest;
import com.botfunnel.funnel.dto.FunnelResponse;
import com.botfunnel.funnel.dto.FunnelStepDto;
import com.botfunnel.funnel.dto.FunnelSummaryResponse;
import com.botfunnel.funnel.dto.UpdateFunnelRequest;
import com.botfunnel.project.ProjectService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CRUD + lifecycle for linear funnels (Phase 1). HTTP facade over the Task 1 domain model. Every
 * public method calls {@link ProjectService#requireOwned} FIRST (anti-IDOR / anti-enumeration uniform
 * 404, patterns.md), then enforces that the funnel belongs to the path project — a foreign /
 * soft-deleted / missing project or a cross-project funnel id all collapse to the same 404.
 *
 * <p>Trigger-conflict defense-in-depth (Decision 8): a service pre-check rejects a second active funnel
 * with the same {@code (triggerType, triggerValue)} BEFORE the write, and the partial-unique index on
 * {@code funnels} closes the residual race — its {@link DuplicateKeyException} on activate is mapped to
 * the SAME 422 {@code funnel_trigger_conflict} (never a 500).
 */
@Service
public class FunnelService {

    // Phase 1 supports exactly one trigger kind. A null/blank request triggerType normalises to this.
    static final String TRIGGER_ON_START = "on_start";

    // Empty trigger value = bare /start (allowed). Non-empty must match this slug shape — spaces /
    // specials would break the t.me deep-link, so they are rejected as 422 (not silently passed).
    private static final Pattern TRIGGER_VALUE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{0,64}$");
    private static final Pattern TAG_SLUG_PATTERN = Pattern.compile("^[a-z0-9_-]{1,32}$");

    private static final String MESSAGE_NOT_FOUND = "Funnel not found";

    static final String CODE_TRIGGER_CONFLICT = "funnel_trigger_conflict";
    static final String CODE_STEP_LIMIT = "funnel_step_limit_reached";
    static final String CODE_INVALID_STEP = "funnel_step_invalid";
    static final String CODE_INVALID_TRIGGER_VALUE = "funnel_invalid_trigger_value";
    static final String CODE_NO_STEPS = "funnel_no_steps";
    static final String CODE_INVALID_STATE = "funnel_invalid_state";
    // Phase 2 (Decision 2 / Decision 10): a graph edge (next / button targetStepId / timeoutTargetStepId)
    // points at a step id that does not exist in the funnel — e.g. the target step was deleted.
    static final String CODE_BROKEN_EDGE = "funnel_broken_edge";

    // MENU button limits (Phase 2). Telegram allows long keyboards, but the editor caps at 8 (1/row) and
    // labels at 64 chars (also Telegram's practical button-text ceiling).
    private static final int MAX_BUTTONS = 8;
    private static final int MAX_BUTTON_LABEL = 64;
    static final String BUTTON_TYPE_CALLBACK = "callback";
    static final String BUTTON_TYPE_URL = "url";

    private final FunnelRepository funnelRepository;
    private final MongoTemplate mongoTemplate;
    private final ProjectService projectService;
    private final BotRepository botRepository;
    private final Clock clock;
    private final int maxSteps;

    public FunnelService(FunnelRepository funnelRepository,
                         MongoTemplate mongoTemplate,
                         ProjectService projectService,
                         BotRepository botRepository,
                         Clock clock,
                         @Value("${app.funnel.max-steps:50}") int maxSteps) {
        this.funnelRepository = funnelRepository;
        this.mongoTemplate = mongoTemplate;
        this.projectService = projectService;
        this.botRepository = botRepository;
        this.clock = clock;
        this.maxSteps = maxSteps;
    }

    public FunnelResponse create(String ownerId, String projectId, CreateFunnelRequest request) {
        projectService.requireOwned(ownerId, projectId, false);
        Instant now = Instant.now(clock);
        Funnel funnel = new Funnel();
        funnel.setProjectId(projectId);
        funnel.setName(request.name());
        funnel.setDescription(blankToNull(request.description()));
        funnel.setStatus(FunnelStatus.draft);
        funnel.setTriggerType(TRIGGER_ON_START);
        funnel.setTriggerValue("");
        funnel.setAllowReEnter(false);
        funnel.setSteps(new ArrayList<>());
        funnel.setCreatedAt(now);
        funnel.setUpdatedAt(now);
        return toResponse(funnelRepository.save(funnel));
    }

    public List<FunnelSummaryResponse> list(String ownerId, String projectId, FunnelStatus status) {
        projectService.requireOwned(ownerId, projectId, false);
        List<Funnel> funnels = status == null
                ? funnelRepository.findByProjectId(projectId)
                : funnelRepository.findByProjectIdAndStatus(projectId, status);
        return funnels.stream().map(FunnelService::toSummary).toList();
    }

    public FunnelResponse get(String ownerId, String projectId, String funnelId) {
        return toResponse(requireFunnel(ownerId, projectId, funnelId));
    }

    public FunnelResponse update(String ownerId, String projectId, String funnelId,
                                 UpdateFunnelRequest request) {
        Funnel funnel = requireFunnel(ownerId, projectId, funnelId);

        if (request.name() != null && !request.name().isBlank()) {
            funnel.setName(request.name());
        }
        if (request.description() != null) {
            funnel.setDescription(blankToNull(request.description()));
        }
        funnel.setTriggerType(normalizeTriggerType(request.triggerType()));
        funnel.setTriggerValue(normalizeTriggerValue(request.triggerValue()));
        if (request.allowReEnter() != null) {
            funnel.setAllowReEnter(request.allowReEnter());
        }

        List<FunnelStep> steps = toSteps(request.steps());
        validateSteps(steps);
        funnel.setSteps(steps);
        funnel.setUpdatedAt(Instant.now(clock));
        // Editing an ACTIVE funnel's trigger can collide with another active funnel (Decision 3 allows
        // editing while active). Only an active row participates in the partial-unique index, so the
        // conflict guard runs only for active funnels — same defense-in-depth as activate (pre-check +
        // DuplicateKeyException → 422), so a colliding edit never surfaces as a 500.
        if (funnel.getStatus() == FunnelStatus.active) {
            checkTriggerConflict(funnel);
        }
        return toResponse(saveHandlingTriggerConflict(funnel));
    }

    public void delete(String ownerId, String projectId, String funnelId) {
        Funnel funnel = requireFunnel(ownerId, projectId, funnelId);
        // Cancel the funnel's in-flight executions BEFORE dropping it. One atomic bulk-update. The
        // engine (Task 6) is the only other writer: a not-yet-claimed row flipped to cancelled here
        // fails the engine's claim predicate; a row the engine is mid-tick on is protected too, because
        // every in-tick engine write is a CAS on stepRunStatus=in_progress — this update flips
        // stepRunStatus to done, so the engine's next write no-ops and the cancel wins (no resurrection,
        // no double-processing). Enum statuses written as lowercase name() literals (Decision 14).
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("funnelId").is(funnelId)
                        .and("status").in(ExecutionStatus.running.name(), ExecutionStatus.waiting.name(),
                                ExecutionStatus.waiting_for_reply.name())),
                new Update()
                        .set("status", ExecutionStatus.cancelled.name())
                        // Also flip stepRunStatus→done so the engine's claim-conditional in-tick writes
                        // (CAS on stepRunStatus=in_progress) no-op for a row it is mid-processing — keeps
                        // this canceller consistent with FunnelTriggerService's cancel paths.
                        .set("stepRunStatus", StepRunStatus.done.name())
                        .set("updatedAt", Instant.now(clock)),
                FunnelExecution.class);
        funnelRepository.delete(funnel);
    }

    public FunnelResponse activate(String ownerId, String projectId, String funnelId) {
        Funnel funnel = requireFunnel(ownerId, projectId, funnelId);

        List<FunnelStep> steps = funnel.getSteps();
        if (steps == null || steps.isEmpty()) {
            throw AppException.unprocessableEntity(CODE_NO_STEPS,
                    "Funnel must have at least one step to activate");
        }
        validateSteps(steps);

        // Service pre-check (first line of the Decision 8 defense): another ACTIVE funnel already owns
        // this trigger → 422. The partial-unique index is the second line for the parallel-activate race.
        checkTriggerConflict(funnel);

        funnel.setStatus(FunnelStatus.active);
        funnel.setUpdatedAt(Instant.now(clock));
        return toResponse(saveHandlingTriggerConflict(funnel));
    }

    // Service-side pre-check: reject if a DIFFERENT active funnel already owns this funnel's
    // (triggerType, triggerValue). Excludes self so re-activating / editing the same funnel is fine.
    private void checkTriggerConflict(Funnel funnel) {
        Optional<Funnel> conflict = funnelRepository.findByProjectIdAndTriggerTypeAndTriggerValueAndStatus(
                funnel.getProjectId(), funnel.getTriggerType(), funnel.getTriggerValue(), FunnelStatus.active);
        if (conflict.isPresent() && !conflict.get().getId().equals(funnel.getId())) {
            throw AppException.unprocessableEntity(CODE_TRIGGER_CONFLICT,
                    "Another active funnel already uses this trigger");
        }
    }

    // Second line of the Decision 8 defense: the partial-unique (projectId, triggerType, triggerValue)
    // filtered active index closes the race the pre-check can lose. The ONLY unique index on `funnels`
    // is the trigger one, so a DuplicateKeyException here can only mean a trigger collision → map to the
    // SAME 422 as the pre-check, never a 500.
    private Funnel saveHandlingTriggerConflict(Funnel funnel) {
        try {
            return funnelRepository.save(funnel);
        } catch (DuplicateKeyException ex) {
            throw AppException.unprocessableEntity(CODE_TRIGGER_CONFLICT,
                    "Another active funnel already uses this trigger");
        }
    }

    public FunnelResponse pause(String ownerId, String projectId, String funnelId) {
        Funnel funnel = requireFunnel(ownerId, projectId, funnelId);
        if (funnel.getStatus() != FunnelStatus.active) {
            throw AppException.unprocessableEntity(CODE_INVALID_STATE,
                    "Only an active funnel can be paused");
        }
        funnel.setStatus(FunnelStatus.paused);
        funnel.setUpdatedAt(Instant.now(clock));
        return toResponse(funnelRepository.save(funnel));
    }

    // requireOwned FIRST, then prove the funnel belongs to THIS project: a cross-project / missing
    // funnelId collapses to the same uniform 404 (anti-IDOR, no existence leak).
    private Funnel requireFunnel(String ownerId, String projectId, String funnelId) {
        projectService.requireOwned(ownerId, projectId, false);
        Optional<Funnel> found;
        try {
            found = funnelRepository.findById(funnelId);
        } catch (IllegalArgumentException e) {
            // Malformed ObjectId hex collapses into the same anti-enumeration 404 (mirrors
            // ProjectService.requireOwned) — a probe learns nothing about funnel existence.
            throw AppException.notFound(MESSAGE_NOT_FOUND);
        }
        return found
                .filter(f -> projectId.equals(f.getProjectId()))
                .orElseThrow(() -> AppException.notFound(MESSAGE_NOT_FOUND));
    }

    private String normalizeTriggerType(String triggerType) {
        // Phase 1 supports only on_start; a null/blank request value normalises to it.
        return (triggerType == null || triggerType.isBlank()) ? TRIGGER_ON_START : triggerType;
    }

    private String normalizeTriggerValue(String triggerValue) {
        String value = triggerValue == null ? "" : triggerValue;
        if (!TRIGGER_VALUE_PATTERN.matcher(value).matches()) {
            throw AppException.unprocessableEntity(CODE_INVALID_TRIGGER_VALUE,
                    "Trigger value must match ^[A-Za-z0-9_-]{0,64}$");
        }
        return value;
    }

    private List<FunnelStep> toSteps(List<FunnelStepDto> dtos) {
        List<FunnelStep> steps = new ArrayList<>();
        if (dtos == null) {
            return steps;
        }
        // Graph model (Decision 2): preserve any client-supplied stable id, mint one for new steps,
        // and reject duplicate ids within the funnel (a client sending two steps with the same id
        // would corrupt the graph — targets resolve ambiguously). Minted ids use ObjectId hex, matching
        // the Task 1 backfill convention.
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < dtos.size(); i++) {
            FunnelStepDto dto = dtos.get(i);
            FunnelStep step = new FunnelStep();
            step.setStepType(dto.stepType());
            step.setOrder(i); // position in the array IS the order (server-authoritative)

            String incomingId = blankToNull(dto.id());
            if (incomingId != null && !seenIds.add(incomingId)) {
                throw invalidStep("Duplicate step id within funnel: " + incomingId);
            }
            step.setId(incomingId != null ? incomingId : new org.bson.types.ObjectId().toHexString());
            step.setNext(blankToNull(dto.next()));
            step.setButtons(toButtons(dto.buttons()));
            step.setTimeoutValue(dto.timeoutValue());
            step.setTimeoutUnit(blankToNull(dto.timeoutUnit()));
            step.setTimeoutTargetStepId(blankToNull(dto.timeoutTargetStepId()));

            step.setText(dto.text());
            step.setParseMode(blankToNull(dto.parseMode()));
            step.setImageUrl(dto.imageUrl());
            step.setCaption(dto.caption());
            step.setDelayValue(dto.delayValue());
            step.setDelayUnit(dto.delayUnit());
            step.setTagSlug(dto.tagSlug());
            step.setCustomFieldKey(dto.customFieldKey());
            step.setCustomFieldValue(dto.customFieldValue());
            steps.add(step);
        }
        return steps;
    }

    private static List<Button> toButtons(List<ButtonDto> dtos) {
        if (dtos == null) {
            return null;
        }
        List<Button> buttons = new ArrayList<>(dtos.size());
        for (ButtonDto b : dtos) {
            buttons.add(new Button(b.type(), b.label(), blankToNull(b.targetStepId()), blankToNull(b.url())));
        }
        return buttons;
    }

    // Per-type + limit validation shared by update (reject invalid edits) and activate (defense-in-depth
    // re-validation of a possibly directly-seeded funnel). All failures are 422 with a business code.
    private void validateSteps(List<FunnelStep> steps) {
        if (steps.size() > maxSteps) {
            throw AppException.unprocessableEntity(CODE_STEP_LIMIT,
                    "Funnel exceeds the maximum of " + maxSteps + " steps");
        }
        // Build the set of existing step ids ONCE, before the edge pass (Decision 2: targets are stable
        // ids). A null id can occur only for a directly-seeded legacy step that bypassed toSteps; the
        // backfill (Task 1) stamps ids, and toSteps mints them, so on the normal path every step has one.
        Set<String> stepIds = new HashSet<>();
        for (FunnelStep step : steps) {
            if (step.getId() != null) {
                stepIds.add(step.getId());
            }
        }
        for (FunnelStep step : steps) {
            switch (step.getStepType()) {
                case SEND_MESSAGE -> {
                    requireText(step.getText());
                    requireParseMode(step.getParseMode());
                }
                case SEND_IMAGE -> {
                    requireImageUrl(step.getImageUrl());
                    requireParseMode(step.getParseMode());
                }
                case DELAY -> requireDelay(step.getDelayValue(), step.getDelayUnit());
                case ADD_TAG, REMOVE_TAG -> requireTagSlug(step.getTagSlug());
                case SET_CUSTOM_FIELD -> requireCustomFieldKey(step.getCustomFieldKey());
                case MENU -> validateMenu(step, stepIds);
            }
            // Graph-edge pass (every step type): the default outgoing edge and the optional timeout edge
            // must point at an existing step id, or be null (null next = next-in-list; null timeout
            // target = completed). A non-null target that is not in stepIds is a broken edge → 422.
            requireExistingTarget(step.getNext(), stepIds);
            requireExistingTarget(step.getTimeoutTargetStepId(), stepIds);
        }
    }

    // MENU validation (Decision 10): >=1 callback button so the subscriber can never get stuck; per
    // button — non-empty label <= 64 chars; callback target null (End) or an existing id; url strictly
    // http(s) with a non-empty host; at most 8 buttons. The optional timeout pair is validated too
    // (audit-fix F1): an unvalidated/legacy timeoutUnit would make StepExecutor.menuDeadline ->
    // durationOf throw BEFORE the menu is sent, stranding the execution in stepRunStatus=in_progress.
    private static void validateMenu(FunnelStep step, Set<String> stepIds) {
        List<Button> buttons = step.getButtons();
        if (buttons == null || buttons.isEmpty()) {
            throw invalidStep("MENU step requires at least one button");
        }
        requireTimeout(step.getTimeoutValue(), step.getTimeoutUnit());
        if (buttons.size() > MAX_BUTTONS) {
            throw invalidStep("MENU step exceeds the maximum of " + MAX_BUTTONS + " buttons");
        }
        int callbackCount = 0;
        for (Button button : buttons) {
            if (button.label() == null || button.label().isBlank()) {
                throw invalidStep("MENU button requires a non-empty label");
            }
            if (button.label().length() > MAX_BUTTON_LABEL) {
                throw invalidStep("MENU button label exceeds " + MAX_BUTTON_LABEL + " characters");
            }
            if (BUTTON_TYPE_CALLBACK.equals(button.type())) {
                callbackCount++;
                // null targetStepId = End (valid). A non-null target must resolve to an existing step.
                requireExistingTarget(button.targetStepId(), stepIds);
            } else if (BUTTON_TYPE_URL.equals(button.type())) {
                requireHttpUrl(button.url());
            } else {
                throw invalidStep("MENU button type must be 'callback' or 'url'");
            }
        }
        if (callbackCount == 0) {
            throw invalidStep("MENU step requires at least one callback button");
        }
    }

    // A non-null edge target must point at an existing step id. null = default (next-in-list / End /
    // completed) and is always valid.
    private static void requireExistingTarget(String targetStepId, Set<String> stepIds) {
        if (targetStepId != null && !stepIds.contains(targetStepId)) {
            throw AppException.unprocessableEntity(CODE_BROKEN_EDGE,
                    "Step target points at a non-existent step id: " + targetStepId);
        }
    }

    // Strict URL-button scheme check (Decision 10, SSRF / scheme-injection defense): parse via URI and
    // require the scheme to be EXACTLY http or https (not startsWith) with a non-empty host. This rejects
    // javascript:/data:/tg://, leading-whitespace-obfuscated values, and empty-host URLs. A malformed URI
    // (URISyntaxException → IllegalArgumentException from URI(String) path) is mapped to 422, never 500.
    private static void requireHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw invalidStep("MENU url button requires a url");
        }
        // Leading/trailing whitespace is never valid in a URL; reject before parsing so " http://x"
        // cannot slip a leading-space-obfuscated value past the scheme check.
        if (!url.equals(url.strip())) {
            throw invalidStep("MENU url button must use the http or https scheme");
        }
        final URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw invalidStep("MENU url button is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw invalidStep("MENU url button must use the http or https scheme");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalidStep("MENU url button must have a non-empty host");
        }
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw invalidStep("SEND_MESSAGE step requires non-empty text");
        }
        if (text.length() > 4096) {
            throw invalidStep("SEND_MESSAGE text exceeds 4096 characters");
        }
    }

    private static void requireParseMode(String parseMode) {
        if (parseMode != null && !"HTML".equals(parseMode) && !"MarkdownV2".equals(parseMode)) {
            throw invalidStep("parseMode must be HTML or MarkdownV2");
        }
    }

    private static void requireImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw invalidStep("SEND_IMAGE step requires an imageUrl");
        }
        String lower = imageUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw invalidStep("imageUrl must use the http or https scheme");
        }
    }

    private static void requireDelay(Integer delayValue, String delayUnit) {
        if (delayValue == null || delayValue < 1) {
            throw invalidStep("DELAY step requires delayValue >= 1");
        }
        if (!"MIN".equals(delayUnit) && !"HOUR".equals(delayUnit) && !"DAY".equals(delayUnit)) {
            throw invalidStep("delayUnit must be MIN, HOUR or DAY");
        }
        // MIN/HOUR/DAY with delayValue >= 1 is always >= 1 minute, satisfying Decision 9's floor.
    }

    // Optional MENU timeout pair (audit-fix F1). null/null = unlimited wait (valid). Otherwise BOTH must
    // be present, timeoutUnit in {MIN,HOUR,DAY} (same convention as delayUnit; StepExecutor.durationOf
    // only understands these) and timeoutValue >= 1. An invalid pair is a 422 funnel_step_invalid — NOT a
    // 500, and never reaches the engine where an unknown unit would strand the execution.
    private static void requireTimeout(Integer timeoutValue, String timeoutUnit) {
        if (timeoutValue == null && timeoutUnit == null) {
            return; // no timeout configured → unlimited wait
        }
        if (timeoutValue == null || timeoutUnit == null) {
            throw invalidStep("MENU timeout requires both timeoutValue and timeoutUnit");
        }
        if (timeoutValue < 1) {
            throw invalidStep("MENU timeoutValue must be >= 1");
        }
        if (!"MIN".equals(timeoutUnit) && !"HOUR".equals(timeoutUnit) && !"DAY".equals(timeoutUnit)) {
            throw invalidStep("timeoutUnit must be MIN, HOUR or DAY");
        }
    }

    private static void requireTagSlug(String tagSlug) {
        if (tagSlug == null || !TAG_SLUG_PATTERN.matcher(tagSlug).matches()) {
            throw invalidStep("tagSlug must match ^[a-z0-9_-]{1,32}$");
        }
    }

    private static void requireCustomFieldKey(String key) {
        if (key == null || key.isBlank()) {
            throw invalidStep("SET_CUSTOM_FIELD step requires a customFieldKey");
        }
    }

    private static AppException invalidStep(String message) {
        return AppException.unprocessableEntity(CODE_INVALID_STEP, message);
    }

    private FunnelResponse toResponse(Funnel funnel) {
        List<FunnelStepDto> steps = funnel.getSteps() == null
                ? List.of()
                : funnel.getSteps().stream().map(FunnelService::toStepDto).toList();
        return new FunnelResponse(
                funnel.getId(),
                funnel.getProjectId(),
                funnel.getName(),
                funnel.getDescription(),
                funnel.getStatus(),
                funnel.getTriggerType(),
                funnel.getTriggerValue(),
                funnel.isAllowReEnter(),
                steps,
                resolveDeepLink(funnel),
                funnel.getCreatedAt(),
                funnel.getUpdatedAt());
    }

    private static FunnelSummaryResponse toSummary(Funnel funnel) {
        int stepCount = funnel.getSteps() == null ? 0 : funnel.getSteps().size();
        return new FunnelSummaryResponse(
                funnel.getId(),
                funnel.getProjectId(),
                funnel.getName(),
                funnel.getDescription(),
                funnel.getStatus(),
                funnel.getTriggerType(),
                funnel.getTriggerValue(),
                funnel.isAllowReEnter(),
                stepCount,
                funnel.getCreatedAt(),
                funnel.getUpdatedAt());
    }

    private static FunnelStepDto toStepDto(FunnelStep step) {
        return new FunnelStepDto(
                step.getStepType(),
                step.getId(),
                step.getNext(),
                toButtonDtos(step.getButtons()),
                step.getTimeoutValue(),
                step.getTimeoutUnit(),
                step.getTimeoutTargetStepId(),
                step.getText(),
                step.getParseMode(),
                step.getImageUrl(),
                step.getCaption(),
                step.getDelayValue(),
                step.getDelayUnit(),
                step.getTagSlug(),
                step.getCustomFieldKey(),
                step.getCustomFieldValue());
    }

    private static List<ButtonDto> toButtonDtos(List<Button> buttons) {
        if (buttons == null) {
            return null;
        }
        return buttons.stream()
                .map(b -> new ButtonDto(b.type(), b.label(), b.targetStepId(), b.url()))
                .toList();
    }

    // deepLink is shown for an active funnel so the editor can display + copy it after a page reload
    // (not only in the activate response). Resolves the project's single CONNECTED bot (Phase 1) for
    // its username; null when the funnel is not active or no bot is connected yet.
    private String resolveDeepLink(Funnel funnel) {
        if (funnel.getStatus() != FunnelStatus.active) {
            return null;
        }
        Optional<Bot> bot = botRepository.findByProjectIdAndStatus(funnel.getProjectId(), BotStatus.CONNECTED);
        if (bot.isEmpty() || bot.get().getTelegramUsername() == null) {
            return null;
        }
        return "t.me/" + bot.get().getTelegramUsername() + "?start=" + funnel.getTriggerValue();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
