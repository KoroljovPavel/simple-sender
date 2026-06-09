package com.botfunnel.funnel;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.common.AppException;
import com.botfunnel.funnel.dto.ButtonDto;
import com.botfunnel.funnel.dto.ContentBlockDto;
import com.botfunnel.funnel.dto.CreateFunnelRequest;
import com.botfunnel.funnel.dto.FunnelResponse;
import com.botfunnel.funnel.dto.FunnelStepDto;
import com.botfunnel.funnel.dto.MediaItemDto;
import com.botfunnel.funnel.dto.FunnelSummaryResponse;
import com.botfunnel.funnel.dto.PreviewStepRequest;
import com.botfunnel.funnel.dto.PreviewStepResponse;
import com.botfunnel.funnel.dto.UpdateFunnelRequest;
import com.botfunnel.project.ProjectService;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberService;
import com.botfunnel.subscriber.SubscriberStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    private static final Logger log = LoggerFactory.getLogger(FunnelService.class);

    // Phase 1 supports on_start; Phase 3 (Decision 1) adds four more. A null/blank request triggerType
    // normalises to on_start; any value outside the five-member set is rejected (→ 422).
    static final String TRIGGER_ON_START = "on_start";
    static final String TRIGGER_KEYWORD = "keyword";
    static final String TRIGGER_TAG_ADDED = "tag_added";
    static final String TRIGGER_CUSTOM_FIELD_SET = "custom_field_set";
    static final String TRIGGER_EVENT = "event";
    private static final Set<String> VALID_TRIGGER_TYPES = Set.of(
            TRIGGER_ON_START, TRIGGER_KEYWORD, TRIGGER_TAG_ADDED, TRIGGER_CUSTOM_FIELD_SET, TRIGGER_EVENT);

    // Empty trigger value = bare /start (allowed). Non-empty must match this slug shape — spaces /
    // specials would break the t.me deep-link, so they are rejected as 422 (not silently passed).
    private static final Pattern TRIGGER_VALUE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{0,64}$");
    private static final Pattern TAG_SLUG_PATTERN = Pattern.compile("^[a-z0-9_-]{1,32}$");
    // event_name slug (Decision 4): shared by the EMIT_EVENT step and the `event` trigger value. 1..64,
    // case-preserving (the external API event_name is case-sensitive in the same way).
    private static final Pattern EVENT_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    // keyword list caps (Decision 3): bound the per-funnel keyword scan + each entry's length so a
    // hostile/huge list cannot bloat the document or the runtime contains-match.
    private static final int MAX_KEYWORDS = 50;
    private static final int MAX_KEYWORD_LENGTH = 64;

    private static final String MESSAGE_NOT_FOUND = "Funnel not found";

    static final String CODE_TRIGGER_CONFLICT = "funnel_trigger_conflict";
    static final String CODE_STEP_LIMIT = "funnel_step_limit_reached";
    static final String CODE_INVALID_STEP = "funnel_step_invalid";
    static final String CODE_INVALID_TRIGGER_VALUE = "funnel_invalid_trigger_value";
    // Phase 3 (Decision 1 / 3): unknown triggerType (outside the five-value set) and bad keyword list.
    static final String CODE_INVALID_TRIGGER_TYPE = "funnel_invalid_trigger_type";
    static final String CODE_INVALID_KEYWORDS = "funnel_invalid_keywords";
    static final String CODE_NO_STEPS = "funnel_no_steps";
    static final String CODE_INVALID_STATE = "funnel_invalid_state";
    // Phase 2 (Decision 2 / Decision 10): a graph edge (next / button targetStepId / timeoutTargetStepId)
    // points at a step id that does not exist in the funnel — e.g. the target step was deleted.
    static final String CODE_BROKEN_EDGE = "funnel_broken_edge";
    // Phase 4 / Decision 5: the test-run owner could not be resolved to an ACTIVE subscriber — the author
    // has not (or no longer) linked their own Telegram to the project's bot. All three branches (no bot /
    // ownerChatId null / no-or-inactive subscriber) collapse to this one code; the remediation is the
    // same for the author ("send /start to the bot"). Always 422, never 500 (it is a predictable state).
    static final String CODE_OWNER_NOT_LINKED = "funnel_owner_not_linked";

    // Phase 5 (composition / Task 2): SUBSCRIBE_TO_FUNNEL target validation. Save-time checks (required /
    // not_found / step_not_found) plus an activation-only check (inactive). not_found deliberately covers
    // missing id, malformed ObjectId hex AND a target owned by another project — one code, no
    // cross-tenant existence leak (anti-enumeration, fail-closed by projectId — Decision 6 / §14.4).
    static final String CODE_SUBSCRIBE_TARGET_REQUIRED = "funnel_subscribe_target_required";
    static final String CODE_SUBSCRIBE_TARGET_NOT_FOUND = "funnel_subscribe_target_not_found";
    static final String CODE_SUBSCRIBE_TARGET_STEP_NOT_FOUND = "funnel_subscribe_target_step_not_found";
    // Activation-only (NOT checked at save — a draft target may be referenced while building two linked
    // funnels; Decision 6 chicken-and-egg): every SUBSCRIBE target must be active to activate the parent.
    static final String CODE_SUBSCRIBE_TARGET_INACTIVE = "funnel_subscribe_target_inactive";

    // MENU button limits (Phase 2 — now the MESSAGE composer keyboard, attached to the last non-album
    // block). Telegram allows long keyboards, but the editor caps at 8 (1/row) and labels at 64 chars
    // (also Telegram's practical button-text ceiling).
    private static final int MAX_BUTTONS = 8;
    private static final int MAX_BUTTON_LABEL = 64;
    static final String BUTTON_TYPE_CALLBACK = "callback";
    static final String BUTTON_TYPE_URL = "url";

    // MESSAGE composer block limits (15-message-composer / Decision 1, 5, 7). A composer step holds an
    // ordered List<ContentBlock>; each block is one Telegram message. Block count 1–10; an ALBUM holds
    // 2–10 MediaItems. Over-length text/caption (Telegram's 4096/1024 ceilings) is a WARNING — it does NOT
    // block save (the engine trims+WARNs at send time, Decision 3); these are the warn thresholds only.
    private static final int MAX_BLOCKS = 10;
    private static final int MIN_ALBUM_ITEMS = 2;
    private static final int MAX_ALBUM_ITEMS = 10;
    private static final int TEXT_WARN_LIMIT = 4096;
    private static final int CAPTION_WARN_LIMIT = 1024;

    private final FunnelRepository funnelRepository;
    private final MongoTemplate mongoTemplate;
    private final ProjectService projectService;
    private final BotRepository botRepository;
    private final FunnelExecutionFactory funnelExecutionFactory;
    private final SubscriberService subscriberService;
    private final Clock clock;
    private final int maxSteps;

    public FunnelService(FunnelRepository funnelRepository,
                         MongoTemplate mongoTemplate,
                         ProjectService projectService,
                         BotRepository botRepository,
                         FunnelExecutionFactory funnelExecutionFactory,
                         SubscriberService subscriberService,
                         Clock clock,
                         @Value("${app.funnel.max-steps:50}") int maxSteps) {
        this.funnelRepository = funnelRepository;
        this.mongoTemplate = mongoTemplate;
        this.projectService = projectService;
        this.botRepository = botRepository;
        this.funnelExecutionFactory = funnelExecutionFactory;
        this.subscriberService = subscriberService;
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
        applyTrigger(funnel, request.triggerType(), request.triggerValue(), request.keywords());
        if (request.allowReEnter() != null) {
            funnel.setAllowReEnter(request.allowReEnter());
        }

        List<FunnelStep> steps = toSteps(request.steps());
        validateSteps(steps, projectId);
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
        // Cancel the funnel's in-flight executions BEFORE dropping it, then remove the funnel.
        cancelInFlightExecutions(projectId, funnelId);
        funnelRepository.delete(funnel);
    }

    // Bulk-cancel every in-flight execution of (projectId, funnelId). One atomic updateMulti, shared by
    // delete() and stopAllExecutions() so there is a single bulk-cancel implementation. The engine
    // (Task 6) is the only other writer: a not-yet-claimed row flipped to cancelled here fails the
    // engine's claim predicate; a row the engine is mid-tick on is protected too, because every in-tick
    // engine write is a CAS on stepRunStatus=in_progress — this update flips stepRunStatus→done, so the
    // engine's next write no-ops and the cancel wins (no resurrection, no double-processing). Scoped by
    // BOTH projectId AND funnelId — fail-closed tenant hardening (same shape as
    // FunnelExecutionFactory.cancelExistingForPair); a funnelId-only scope would risk cancelling another
    // project's executions that happen to share a funnelId. Enum statuses are written as lowercase
    // name() literals (Decision 14). Returns the best-effort modifiedCount (Decision 8).
    private long cancelInFlightExecutions(String projectId, String funnelId) {
        return mongoTemplate.updateMulti(
                Query.query(Criteria.where("projectId").is(projectId)
                        .and("funnelId").is(funnelId)
                        .and("status").in(ExecutionStatus.running.name(), ExecutionStatus.waiting.name(),
                                ExecutionStatus.waiting_for_reply.name())),
                new Update()
                        .set("status", ExecutionStatus.cancelled.name())
                        .set("stepRunStatus", StepRunStatus.done.name())
                        .set("updatedAt", Instant.now(clock)),
                FunnelExecution.class).getModifiedCount();
    }

    // Force-stop all in-flight executions of a funnel without deleting the funnel itself (the "stop all"
    // author tool). requireFunnel runs FIRST (anti-IDOR uniform 404 before any side effect). Reuses the
    // shared bulk-cancel helper; returns the best-effort number cancelled (Decision 8 — modifiedCount).
    public long stopAllExecutions(String ownerId, String projectId, String funnelId) {
        requireFunnel(ownerId, projectId, funnelId);
        return cancelInFlightExecutions(projectId, funnelId);
    }

    // Independent copy of a funnel (the "duplicate" author tool, Decision 6). The step graph is copied
    // verbatim via FunnelStep.copyOf — ids and every edge (next / timeoutTargetStepId / Button
    // targetStepId) are preserved (step ids carry no unique index, so reuse across funnels is fine and
    // no re-mint is needed). keywords / allowReEnter / description are copied. The copy is always born
    // draft with the trigger reset to (on_start, "") regardless of the original's status/trigger — this
    // removes the "two funnels claim the same trigger on activate" surprise, and draft never enters the
    // partial-unique trigger index, so a plain save (not saveHandlingTriggerConflict) cannot collide.
    // The name is "<name> (копія)" truncated to 128 chars so the suffix can never breach @Size(max=128).
    public FunnelResponse duplicate(String ownerId, String projectId, String funnelId) {
        Funnel original = requireFunnel(ownerId, projectId, funnelId);
        Instant now = Instant.now(clock);

        Funnel copy = new Funnel();
        copy.setProjectId(projectId);
        copy.setName(duplicateName(original.getName()));
        copy.setDescription(original.getDescription());
        copy.setStatus(FunnelStatus.draft);
        copy.setTriggerType(TRIGGER_ON_START);
        copy.setTriggerValue("");
        copy.setAllowReEnter(original.isAllowReEnter());
        copy.setKeywords(original.getKeywords());
        List<FunnelStep> steps = original.getSteps() == null
                ? new ArrayList<>()
                : original.getSteps().stream().map(FunnelStep::copyOf).collect(Collectors.toCollection(ArrayList::new));
        copy.setSteps(steps);
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        return toResponse(funnelRepository.save(copy));
    }

    private static final String DUPLICATE_SUFFIX = " (копія)";
    private static final int MAX_NAME_LENGTH = 128;

    // "<name> (копія)" truncated to 128 UTF-16 chars (matching @Size, which counts String.length()). The
    // suffix is preserved when possible; if the base is so long that base+suffix would exceed 128, the
    // base is trimmed so the suffix still fits, then the whole thing is capped at 128.
    private static String duplicateName(String original) {
        String base = original == null ? "" : original;
        String candidate = base + DUPLICATE_SUFFIX;
        if (candidate.length() <= MAX_NAME_LENGTH) {
            return candidate;
        }
        int keep = Math.max(0, MAX_NAME_LENGTH - DUPLICATE_SUFFIX.length());
        return (base.substring(0, keep) + DUPLICATE_SUFFIX);
    }

    public FunnelResponse activate(String ownerId, String projectId, String funnelId) {
        Funnel funnel = requireFunnel(ownerId, projectId, funnelId);

        List<FunnelStep> steps = funnel.getSteps();
        if (steps == null || steps.isEmpty()) {
            throw AppException.unprocessableEntity(CODE_NO_STEPS,
                    "Funnel must have at least one step to activate");
        }
        validateSteps(steps, projectId);

        // Activation-only gate (Decision 6): every SUBSCRIBE_TO_FUNNEL target must itself be active before
        // the parent can go live. Not enforced at save (a draft target may be referenced while building two
        // linked funnels). Re-resolves the target fail-closed by projectId, then requires status=active.
        requireSubscribeTargetsActive(steps, projectId);

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

    // "Test for me" (Decision 2): enroll the AUTHOR's own subscriber directly into the funnel, bypassing
    // trigger matching, via insertExecution(depth=0). Order of guards is load-bearing:
    //   1. requireFunnel FIRST (anti-IDOR uniform 404 before any side effect / existence leak).
    //   2. Resolve the owner's ACTIVE subscriber; an unresolved owner → 422 funnel_owner_not_linked
    //      (Decision 5) — never 500, since it is a predictable "account not linked" business state.
    //   3. Pre-validate the funnel (Decision 4): an empty/invalid funnel → the SAME 422 codes as activate,
    //      so the author gets actionable feedback instead of a silent instant-complete.
    //   4. cancelExistingForPair BEFORE insertExecution (Decision 3 restart policy): a repeat test cancels
    //      the previous in-flight run of THIS (funnel, subscriber) pair and starts fresh, so the re-enter
    //      partial-unique index never trips a DuplicateKeyException.
    // A draft (valid, non-empty) funnel is test-runnable — insertExecution does not gate on funnel.status.
    // 2xx means the execution was created (enroll registered); the actual send is async (engine sweep), so
    // a later Telegram-send failure flips the execution to failed without changing this HTTP result.
    public void testRun(String ownerId, String projectId, String funnelId) {
        Funnel funnel = requireFunnel(ownerId, projectId, funnelId);

        Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElse(null);
        Subscriber owner = resolveOwnerSubscriber(projectId, bot).orElseThrow(FunnelService::ownerNotLinked);

        List<FunnelStep> steps = funnel.getSteps();
        if (steps == null || steps.isEmpty()) {
            throw AppException.unprocessableEntity(CODE_NO_STEPS,
                    "Funnel must have at least one step to test-run");
        }
        validateSteps(steps, projectId);

        funnelExecutionFactory.cancelExistingForPair(projectId, funnelId, owner.getId());
        funnelExecutionFactory.insertExecution(projectId, funnel, owner.getId(), bot.getTelegramBotId(), 0);
    }

    // Step preview (Decision 9): render a message step's CURRENT (possibly unsaved) content from the
    // request body — NOT the saved step — so the editor preview is reactive to what the author types now,
    // with escaping computed on the backend byte-for-byte as the runtime (anti markup/XSS drift, A03).
    // requireFunnel runs FIRST (anti-IDOR): a foreign/missing funnel collapses to 404 and that 404
    // PRECEDES the stepId-404 and any other state, so a 404-vs-422 difference never becomes an
    // existence oracle. An unknown stepId in an OWNED funnel → 404. Never 500 on predictable states.
    public PreviewStepResponse previewStep(String ownerId, String projectId, String funnelId,
                                           String stepId, PreviewStepRequest request) {
        Funnel funnel = requireFunnel(ownerId, projectId, funnelId);
        FunnelStep step = findStep(funnel, stepId)
                .orElseThrow(() -> AppException.notFound("Funnel step not found"));

        // Resolve the owner's ACTIVE subscriber for a faithful render; fall back to a sample stub when the
        // bot/owner is not linked so the preview still works (sampleData=true). render() requires a
        // non-null Subscriber, so the stub also prevents an NPE/500.
        Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElse(null);
        Optional<Subscriber> resolved = resolveOwnerSubscriber(projectId, bot);
        boolean sampleData = resolved.isEmpty();
        Subscriber subscriber = resolved.orElseGet(FunnelService::stubSubscriber);

        if (isMessageStep(step.getStepType())) {
            // On-the-fly content (Decision 9 / Decision 8): render EACH request block's text+caption, NOT
            // the saved step. The backend escapes substituted values per the block's parseMode exactly as
            // the runtime engine would (XSS-guard, OWASP A03). Media URLs/items are passed through VERBATIM
            // and NEVER dereferenced (anti-SSRF, Decision 6) — the frontend renders them via :src.
            List<PreviewStepResponse.RenderedBlock> rendered = renderBlocks(request.blocks(), subscriber);
            return new PreviewStepResponse(rendered, sampleData, "message");
        }
        // Non-message step (DELAY/ADD_TAG/REMOVE_TAG/SET_CUSTOM_FIELD/EMIT_EVENT/SUBSCRIBE_TO_FUNNEL):
        // neutral placeholder — an empty rendered-blocks array.
        return new PreviewStepResponse(List.of(), sampleData, "non_message");
    }

    // Render each composer block's text/caption through VariableTemplateRenderer (backend-side parseMode
    // escaping — Decision 8). Media URLs/items pass through verbatim WITHOUT any dereference (anti-SSRF,
    // Decision 6). A null/empty blocks request renders to an empty list. Unknown block types still render
    // (preview is non-validating, Decision 9): the type is echoed back and text/caption rendered if present.
    private static List<PreviewStepResponse.RenderedBlock> renderBlocks(List<ContentBlockDto> blocks,
                                                                        Subscriber subscriber) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<PreviewStepResponse.RenderedBlock> out = new ArrayList<>(blocks.size());
        for (ContentBlockDto b : blocks) {
            if (b == null) {
                continue;
            }
            String parseMode = b.parseMode();
            String renderedText = VariableTemplateRenderer.render(b.text(), parseMode, subscriber);
            String renderedCaption = VariableTemplateRenderer.render(b.caption(), parseMode, subscriber);
            out.add(new PreviewStepResponse.RenderedBlock(
                    b.type(),
                    renderedText,
                    parseMode,
                    b.mediaUrl(),
                    renderedCaption,
                    renderMediaItems(b.items(), parseMode, subscriber)));
        }
        return out;
    }

    private static List<PreviewStepResponse.RenderedMediaItem> renderMediaItems(List<MediaItemDto> items,
                                                                                String parseMode,
                                                                                Subscriber subscriber) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<PreviewStepResponse.RenderedMediaItem> out = new ArrayList<>(items.size());
        for (MediaItemDto m : items) {
            if (m == null) {
                continue;
            }
            String renderedCaption = VariableTemplateRenderer.render(m.caption(), parseMode, subscriber);
            // mediaUrl passes through verbatim — NEVER dereferenced (anti-SSRF, Decision 6).
            out.add(new PreviewStepResponse.RenderedMediaItem(m.mediaUrl(), renderedCaption));
        }
        return out;
    }

    private static boolean isMessageStep(StepType type) {
        return type == StepType.MESSAGE;
    }

    private static Optional<FunnelStep> findStep(Funnel funnel, String stepId) {
        if (stepId == null || funnel.getSteps() == null) {
            return Optional.empty();
        }
        return funnel.getSteps().stream().filter(s -> stepId.equals(s.getId())).findFirst();
    }

    // Shared owner-resolution shim (Decision 11 — test-run + preview share one path): the project's single
    // CONNECTED bot → its ownerChatId → findByChat → keep ONLY an ACTIVE subscriber. Returns empty when
    // any link is missing (no bot / ownerChatId null / no subscriber / not ACTIVE). test-run maps empty to
    // 422; preview falls back to a stub. A non-message step's own preview never reaches the send path, so
    // a missing link is non-fatal there. The audit (Task 7) checks this seam against duplication.
    private Optional<Subscriber> resolveOwnerSubscriber(String projectId, Bot bot) {
        if (bot == null || bot.getOwnerChatId() == null) {
            return Optional.empty();
        }
        return subscriberService.findByChat(projectId, bot.getTelegramBotId(), bot.getOwnerChatId())
                .filter(s -> s.getStatus() == SubscriberStatus.ACTIVE);
    }

    // Sample stub for preview when the owner is not linked (Decision 9). Sample identity values + empty
    // custom fields; NEVER persisted. Matches the runtime user.* placeholders so the author still sees a
    // representative render.
    private static Subscriber stubSubscriber() {
        Subscriber s = new Subscriber();
        s.setFirstName("Іван");
        s.setLastName("Петренко");
        s.setUsername("ivan");
        s.setCustomFields(java.util.Map.of());
        return s;
    }

    private static AppException ownerNotLinked() {
        return AppException.unprocessableEntity(CODE_OWNER_NOT_LINKED,
                "Open your bot in Telegram and send /start, then try the test again");
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

    // Applies + validates the trigger triplet (type, value, keywords) together — they are coupled
    // (Decision 1/3): the value rules and the keywords requirement both depend on the type, so they must
    // be validated as one unit. Sets all three fields on the funnel; throws 422 on any violation.
    private void applyTrigger(Funnel funnel, String rawType, String rawValue, List<String> rawKeywords) {
        String type = normalizeTriggerType(rawType);
        funnel.setTriggerType(type);

        switch (type) {
            case TRIGGER_ON_START -> {
                // on_start keeps the existing slug rule ("" = bare /start); keywords are not allowed.
                funnel.setTriggerValue(normalizeOnStartValue(rawValue));
                funnel.setKeywords(requireNoKeywords(rawKeywords));
            }
            case TRIGGER_KEYWORD -> {
                // keyword ignores triggerValue (the words live in `keywords`); store "" for consistency.
                funnel.setTriggerValue("");
                funnel.setKeywords(requireKeywords(rawKeywords));
            }
            case TRIGGER_TAG_ADDED -> {
                funnel.setTriggerValue(requireTagSlugValue(rawValue));
                funnel.setKeywords(requireNoKeywords(rawKeywords));
            }
            case TRIGGER_CUSTOM_FIELD_SET -> {
                funnel.setTriggerValue(requireFieldKeyValue(rawValue));
                funnel.setKeywords(requireNoKeywords(rawKeywords));
            }
            case TRIGGER_EVENT -> {
                funnel.setTriggerValue(requireEventSlugValue(rawValue));
                funnel.setKeywords(requireNoKeywords(rawKeywords));
            }
            default -> throw invalidTriggerType(type);
        }
    }

    private static String normalizeTriggerType(String triggerType) {
        // A null/blank request value normalises to on_start; anything outside the five-value set is 422.
        if (triggerType == null || triggerType.isBlank()) {
            return TRIGGER_ON_START;
        }
        if (!VALID_TRIGGER_TYPES.contains(triggerType)) {
            throw invalidTriggerType(triggerType);
        }
        return triggerType;
    }

    private static String normalizeOnStartValue(String triggerValue) {
        String value = triggerValue == null ? "" : triggerValue;
        if (!TRIGGER_VALUE_PATTERN.matcher(value).matches()) {
            throw AppException.unprocessableEntity(CODE_INVALID_TRIGGER_VALUE,
                    "Trigger value must match ^[A-Za-z0-9_-]{0,64}$");
        }
        return value;
    }

    private static String requireTagSlugValue(String triggerValue) {
        if (triggerValue == null || !TAG_SLUG_PATTERN.matcher(triggerValue).matches()) {
            throw AppException.unprocessableEntity(CODE_INVALID_TRIGGER_VALUE,
                    "tag_added trigger value must be a tag slug ^[a-z0-9_-]{1,32}$");
        }
        return triggerValue;
    }

    private static String requireFieldKeyValue(String triggerValue) {
        if (triggerValue == null || triggerValue.isBlank()) {
            throw AppException.unprocessableEntity(CODE_INVALID_TRIGGER_VALUE,
                    "custom_field_set trigger value must be a non-blank field key");
        }
        return triggerValue;
    }

    private static String requireEventSlugValue(String triggerValue) {
        if (triggerValue == null || !EVENT_NAME_PATTERN.matcher(triggerValue).matches()) {
            throw AppException.unprocessableEntity(CODE_INVALID_TRIGGER_VALUE,
                    "event trigger value must be an event slug ^[A-Za-z0-9_-]{1,64}$");
        }
        return triggerValue;
    }

    // Normalize + validate the keyword list for a keyword funnel (Decision 3): lowercase (Locale.ROOT),
    // trim, drop blanks, de-dupe preserving order (LinkedHashSet). Required non-empty after normalization,
    // capped in size and per-entry length. Returns the normalized list.
    private static List<String> requireKeywords(List<String> rawKeywords) {
        List<String> normalized = normalizeKeywords(rawKeywords);
        if (normalized.isEmpty()) {
            throw AppException.unprocessableEntity(CODE_INVALID_KEYWORDS,
                    "keyword trigger requires at least one non-blank keyword");
        }
        if (normalized.size() > MAX_KEYWORDS) {
            throw AppException.unprocessableEntity(CODE_INVALID_KEYWORDS,
                    "keyword trigger exceeds the maximum of " + MAX_KEYWORDS + " keywords");
        }
        return normalized;
    }

    // Non-keyword trigger types must NOT carry keywords (tight contract — Decision 3 reject-vs-ignore:
    // reject). A null/empty list is fine (the normal case); any actual keyword content is 422.
    private static List<String> requireNoKeywords(List<String> rawKeywords) {
        if (rawKeywords != null && !normalizeKeywords(rawKeywords).isEmpty()) {
            throw AppException.unprocessableEntity(CODE_INVALID_KEYWORDS,
                    "keywords are only allowed for the keyword trigger type");
        }
        return null;
    }

    private static List<String> normalizeKeywords(List<String> rawKeywords) {
        if (rawKeywords == null) {
            return new ArrayList<>();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : rawKeywords) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > MAX_KEYWORD_LENGTH) {
                throw AppException.unprocessableEntity(CODE_INVALID_KEYWORDS,
                        "keyword exceeds the maximum of " + MAX_KEYWORD_LENGTH + " characters");
            }
            seen.add(value);
        }
        return new ArrayList<>(seen);
    }

    private static AppException invalidTriggerType(String triggerType) {
        return AppException.unprocessableEntity(CODE_INVALID_TRIGGER_TYPE,
                "Unknown trigger type: " + triggerType);
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

            step.setBlocks(toBlocks(dto.blocks()));
            step.setDelayValue(dto.delayValue());
            step.setDelayUnit(dto.delayUnit());
            step.setTagSlug(dto.tagSlug());
            step.setCustomFieldKey(dto.customFieldKey());
            step.setCustomFieldValue(dto.customFieldValue());
            step.setEventName(blankToNull(dto.eventName()));
            step.setTargetFunnelId(blankToNull(dto.targetFunnelId()));
            step.setTargetEntryStepId(blankToNull(dto.targetEntryStepId()));
            step.setEndParentAfter(dto.endParentAfter());
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

    // DTO → domain mapper for the MESSAGE composer blocks (15-message-composer / Decision 1). A null
    // blocks array maps to null (empty composer; validateMessage rejects it as funnel_step_invalid — no
    // NPE here). Every field round-trips verbatim WITHOUT silent drop, including album items + per-element
    // caption. The block `type` String is parsed to BlockType here; an unknown/blank type collapses to a
    // null discriminator that validateMessage rejects with a business code (never a 400/500), keeping the
    // machine-readable-422 contract for hostile/garbage input.
    private static List<ContentBlock> toBlocks(List<ContentBlockDto> dtos) {
        if (dtos == null) {
            return null;
        }
        List<ContentBlock> blocks = new ArrayList<>(dtos.size());
        for (ContentBlockDto b : dtos) {
            blocks.add(new ContentBlock(
                    parseBlockType(b.type()),
                    b.text(),
                    blankToNull(b.parseMode()),
                    b.mediaUrl(),
                    b.caption(),
                    toMediaItems(b.items())));
        }
        return blocks;
    }

    private static List<MediaItem> toMediaItems(List<MediaItemDto> dtos) {
        if (dtos == null) {
            return null;
        }
        List<MediaItem> items = new ArrayList<>(dtos.size());
        for (MediaItemDto m : dtos) {
            items.add(new MediaItem(m.type(), m.mediaUrl(), m.caption()));
        }
        return items;
    }

    // Lenient parse: an unknown/blank block type returns null (NOT a 400/500) so validateMessage can
    // reject it with a machine-readable 422 — consistent with the rest of the per-type 422 contract.
    private static BlockType parseBlockType(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return BlockType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Per-type + limit validation shared by update (reject invalid edits), activate (defense-in-depth
    // re-validation of a possibly directly-seeded funnel) and test-run (Decision 4: pre-validate before
    // enroll so an empty/invalid funnel fails fast with the SAME 422 codes as activate, instead of
    // silently completing). Package-private (raised from private — Decision 4) so testRun can reuse it
    // with NO logic/code change. All failures are 422 with a business code.
    //
    // projectId scopes the SUBSCRIBE_TO_FUNNEL target lookup fail-closed (Task 2): the first validation
    // branch that touches the DB (every other check is in-memory). The lookup stays confined to the
    // SUBSCRIBE_TO_FUNNEL case — no other step type incurs a DB read.
    void validateSteps(List<FunnelStep> steps, String projectId) {
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
                case MESSAGE -> validateMessage(step, stepIds);
                case DELAY -> requireDelay(step.getDelayValue(), step.getDelayUnit());
                case ADD_TAG, REMOVE_TAG -> requireTagSlug(step.getTagSlug());
                case SET_CUSTOM_FIELD -> requireCustomFieldKey(step.getCustomFieldKey());
                case EMIT_EVENT -> requireEventName(step.getEventName());
                // Validate the enroll target: targetFunnelId is required, must resolve to a funnel in THIS
                // project (fail-closed), and targetEntryStepId (if set) must be a real step in that target.
                // Deliberately NOT added to the generic edge-pass below: targetEntryStepId points into a
                // DIFFERENT funnel, so requireExistingTarget (which checks this funnel's stepIds) must not
                // see it, or it would falsely raise funnel_broken_edge (Decision 5). active-status of the
                // target is NOT checked here — only at activation (requireSubscribeTargetsActive, Decision 6).
                case SUBSCRIBE_TO_FUNNEL -> validateSubscribeTarget(step, projectId);
            }
            // Graph-edge pass (every step type): the default outgoing edge and the optional timeout edge
            // must point at an existing step id, or be null (null next = next-in-list; null timeout
            // target = completed). A non-null target that is not in stepIds is a broken edge → 422.
            requireExistingTarget(step.getNext(), stepIds);
            requireExistingTarget(step.getTimeoutTargetStepId(), stepIds);
        }
    }

    // MESSAGE composer validation (15-message-composer / Decision 1, 2, 5, 6, 7). The step holds an
    // ordered List<ContentBlock> sent as N Telegram messages. Hard rules (each → 422 with a machine-readable
    // business code), in order:
    //   - blocks non-empty and 1..MAX_BLOCKS (null/empty → funnel_step_invalid; >10 → funnel_step_invalid)
    //   - per-block, by BlockType: TEXT requires non-empty text + valid parseMode; IMAGE/VIDEO/AUDIO/FILE
    //     require a valid media source (http(s) URL or opaque file_id) + valid parseMode; ALBUM requires
    //     2..10 items each with a valid media source, the type-mixing predicate (Decision 5), and a caption
    //     only on the first element.
    //   - buttons (the inline keyboard) are allowed ONLY on the LAST non-album block; on an album block or
    //     a non-last block → 422. When present, the existing button/timeout/url validation applies.
    // Over-length text (>4096) / caption (>1024) is a WARNING only (logged, NOT a 422) — the engine
    // trims+WARNs at send time (Decision 3). A null `type` (unknown/blank discriminator from toBlocks) is
    // rejected with a business code, never a 400/500.
    private void validateMessage(FunnelStep step, Set<String> stepIds) {
        List<ContentBlock> blocks = step.getBlocks();
        if (blocks == null || blocks.isEmpty()) {
            throw invalidStep("MESSAGE step requires at least one content block");
        }
        if (blocks.size() > MAX_BLOCKS) {
            throw invalidStep("MESSAGE step exceeds the maximum of " + MAX_BLOCKS + " blocks");
        }

        int lastIndex = blocks.size() - 1;
        for (int i = 0; i < blocks.size(); i++) {
            ContentBlock block = blocks.get(i);
            if (block == null || block.type() == null) {
                throw invalidStep("MESSAGE block requires a valid type "
                        + "(TEXT|IMAGE|VIDEO|AUDIO|FILE|ALBUM)");
            }
            validateBlock(block);
        }

        // Buttons (the inline keyboard) attach ONLY to the last NON-album block (Decision 2). A null/empty
        // buttons list = no keyboard (valid). Otherwise the last block must exist and must not be an album.
        List<Button> buttons = step.getButtons();
        if (buttons != null && !buttons.isEmpty()) {
            ContentBlock last = blocks.get(lastIndex);
            if (last.type() == BlockType.ALBUM) {
                throw invalidStep("MESSAGE buttons cannot attach to an album block; "
                        + "move them to a non-album last block");
            }
            validateButtons(step, stepIds);
        }
    }

    // Per-block field validation by BlockType (15-message-composer). Over-length text/caption is a warning
    // (logged, not blocking — Decision 3); everything else here is a hard 422.
    private static void validateBlock(ContentBlock block) {
        switch (block.type()) {
            case TEXT -> {
                if (block.text() == null || block.text().isBlank()) {
                    throw invalidStep("TEXT block requires non-empty text");
                }
                requireParseMode(block.parseMode());
                warnIfOverLength("TEXT", "text", block.text(), TEXT_WARN_LIMIT);
            }
            case IMAGE, VIDEO, AUDIO, FILE -> {
                requireMediaSource(block.type().name(), block.mediaUrl());
                requireParseMode(block.parseMode());
                warnIfOverLength(block.type().name(), "caption", block.caption(), CAPTION_WARN_LIMIT);
            }
            case ALBUM -> validateAlbum(block);
        }
    }

    // ALBUM validation (15-message-composer / Decision 5): 2..10 items; every item has a valid media
    // source; the type-mixing predicate holds (all photo / all video / a photo+video mix — audio/document
    // never mix with another type in one group); a caption is meaningful ONLY on the FIRST element, so a
    // non-first element carrying a caption is rejected (consistent enforcement, not a silent drop).
    private static void validateAlbum(ContentBlock block) {
        requireParseMode(block.parseMode());
        List<MediaItem> items = block.items();
        if (items == null || items.size() < MIN_ALBUM_ITEMS) {
            throw invalidStep("ALBUM block requires at least " + MIN_ALBUM_ITEMS + " items");
        }
        if (items.size() > MAX_ALBUM_ITEMS) {
            throw invalidStep("ALBUM block exceeds the maximum of " + MAX_ALBUM_ITEMS + " items");
        }
        boolean hasVisual = false;   // IMAGE or VIDEO (these may mix with each other)
        boolean hasAudio = false;
        boolean hasFile = false;
        for (int i = 0; i < items.size(); i++) {
            MediaItem item = items.get(i);
            if (item == null) {
                throw invalidStep("ALBUM item must not be null");
            }
            // Each album item must carry a media kind in {IMAGE, VIDEO, AUDIO, FILE} (Decision 5). TEXT,
            // ALBUM and a null/unknown discriminator are never valid here.
            switch (item.type()) {
                case IMAGE, VIDEO -> hasVisual = true;
                case AUDIO -> hasAudio = true;
                case FILE -> hasFile = true;
                case null, default -> throw invalidStep("ALBUM item requires a media kind "
                        + "(IMAGE|VIDEO|AUDIO|FILE)");
            }
            requireMediaSource("ALBUM", item.mediaUrl());
            // Decision 5: only the first element's caption is meaningful — reject a stray caption elsewhere
            // rather than drop it silently (the author would lose text without feedback).
            if (i > 0 && item.caption() != null && !item.caption().isBlank()) {
                throw invalidStep("ALBUM caption is only allowed on the first item");
            }
            warnIfOverLength("ALBUM", "caption", item.caption(), CAPTION_WARN_LIMIT);
        }
        // Decision 5 type-mixing predicate: a Telegram media group is valid only as all photo, all video,
        // or a photo+video mix (collapsed here to "visual"); OR all audio; OR all file (document). AUDIO or
        // FILE must NEVER mix with another kind in the same group. Anything that touches more than one of
        // {visual, audio, file} is an invalid mix.
        int kinds = (hasVisual ? 1 : 0) + (hasAudio ? 1 : 0) + (hasFile ? 1 : 0);
        if (kinds > 1) {
            throw invalidStep("ALBUM mixes incompatible media kinds; allowed groups are "
                    + "photo/video, all audio, or all document");
        }
    }

    // Validate the inline keyboard on the last non-album block (reuses the former MENU button/timeout/url
    // rules verbatim — Decision 2). >=1 callback button so the subscriber can never get stuck; per button —
    // non-empty label <= 64 chars; callback target null (End) or an existing id; url strictly http(s) with
    // a non-empty host; at most 8 buttons. The optional timeout pair is validated too (audit-fix F1): an
    // unvalidated/legacy timeoutUnit would make StepExecutor.durationOf throw BEFORE the keyboard is sent,
    // stranding the execution in stepRunStatus=in_progress.
    private static void validateButtons(FunnelStep step, Set<String> stepIds) {
        List<Button> buttons = step.getButtons();
        requireTimeout(step.getTimeoutValue(), step.getTimeoutUnit());
        if (buttons.size() > MAX_BUTTONS) {
            throw invalidStep("MESSAGE step exceeds the maximum of " + MAX_BUTTONS + " buttons");
        }
        int callbackCount = 0;
        for (Button button : buttons) {
            if (button.label() == null || button.label().isBlank()) {
                throw invalidStep("MESSAGE button requires a non-empty label");
            }
            if (button.label().length() > MAX_BUTTON_LABEL) {
                throw invalidStep("MESSAGE button label exceeds " + MAX_BUTTON_LABEL + " characters");
            }
            if (BUTTON_TYPE_CALLBACK.equals(button.type())) {
                callbackCount++;
                // null targetStepId = End (valid). A non-null target must resolve to an existing step.
                requireExistingTarget(button.targetStepId(), stepIds);
            } else if (BUTTON_TYPE_URL.equals(button.type())) {
                requireUrl(button.url(), "button");
            } else {
                throw invalidStep("MESSAGE button type must be 'callback' or 'url'");
            }
        }
        if (callbackCount == 0) {
            throw invalidStep("MESSAGE step requires at least one callback button");
        }
    }

    // Over-length WARN channel (Decision 3): Telegram caps text at 4096 / media caption at 1024. Over-limit
    // content does NOT block save — the engine trims+WARNs at send time. Log here so an author's over-long
    // content is observable, but never throw. No PII: the value is NOT logged, only its length + field.
    private static void warnIfOverLength(String blockType, String field, String value, int limit) {
        if (value != null && value.length() > limit) {
            log.warn("MESSAGE {} block {} exceeds {} chars (len={}); will be trimmed at send time",
                    blockType, field, limit, value.length());
        }
    }

    // SUBSCRIBE_TO_FUNNEL save-time validation (Task 2): the target funnel must be specified, must resolve
    // (fail-closed by projectId) and, if a targetEntryStepId is given, that id must be a real step in the
    // resolved target. The target's active-status is NOT checked here (Decision 6 — only at activation).
    private void validateSubscribeTarget(FunnelStep step, String projectId) {
        Funnel target = resolveSubscribeTarget(step.getTargetFunnelId(), projectId);
        String entryStepId = step.getTargetEntryStepId();
        if (entryStepId != null) {
            List<FunnelStep> targetSteps = target.getSteps();
            boolean exists = targetSteps != null
                    && targetSteps.stream().anyMatch(s -> entryStepId.equals(s.getId()));
            if (!exists) {
                throw AppException.unprocessableEntity(CODE_SUBSCRIBE_TARGET_STEP_NOT_FOUND,
                        "SUBSCRIBE_TO_FUNNEL targetEntryStepId is not a step of the target funnel");
            }
        }
    }

    // Activation-only gate (Decision 6): every SUBSCRIBE_TO_FUNNEL target must be active before the parent
    // can activate. Re-resolves each target fail-closed by projectId (same lookup as save), then requires
    // status=active. Fails on the FIRST non-active target. Non-SUBSCRIBE steps are skipped (no DB read).
    private void requireSubscribeTargetsActive(List<FunnelStep> steps, String projectId) {
        for (FunnelStep step : steps) {
            if (step.getStepType() != StepType.SUBSCRIBE_TO_FUNNEL) {
                continue;
            }
            Funnel target = resolveSubscribeTarget(step.getTargetFunnelId(), projectId);
            if (target.getStatus() != FunnelStatus.active) {
                throw AppException.unprocessableEntity(CODE_SUBSCRIBE_TARGET_INACTIVE,
                        "SUBSCRIBE_TO_FUNNEL target funnel must be active to activate this funnel");
            }
        }
    }

    // Shared fail-closed resolve for a SUBSCRIBE target, reused by save (validateSubscribeTarget) and
    // activation (requireSubscribeTargetsActive). Returns the target Funnel or throws 422:
    //   - blank/null targetFunnelId            → funnel_subscribe_target_required
    //   - malformed ObjectId / missing / FOREIGN project → funnel_subscribe_target_not_found
    // The projectId scope check (target.getProjectId().equals(projectId)) is the IDOR / cross-tenant guard:
    // FunnelRepository.findById is NOT project-scoped at the DB level, so it is enforced here (mirrors
    // requireFunnel). A malformed id makes findById throw IllegalArgumentException — caught and collapsed
    // into the SAME not_found code so a probe cannot distinguish missing vs malformed vs foreign (no leak).
    // No user-supplied id is logged or echoed in a way that would aid cross-tenant enumeration.
    private Funnel resolveSubscribeTarget(String targetFunnelId, String projectId) {
        if (targetFunnelId == null || targetFunnelId.isBlank()) {
            throw AppException.unprocessableEntity(CODE_SUBSCRIBE_TARGET_REQUIRED,
                    "SUBSCRIBE_TO_FUNNEL step requires a targetFunnelId");
        }
        Optional<Funnel> found;
        try {
            found = funnelRepository.findById(targetFunnelId);
        } catch (IllegalArgumentException e) {
            // Malformed ObjectId hex → same not_found as missing/foreign (anti-enumeration), never 500.
            throw subscribeTargetNotFound();
        }
        return found
                .filter(f -> projectId.equals(f.getProjectId()))
                .orElseThrow(FunnelService::subscribeTargetNotFound);
    }

    private static AppException subscribeTargetNotFound() {
        return AppException.unprocessableEntity(CODE_SUBSCRIBE_TARGET_NOT_FOUND,
                "SUBSCRIBE_TO_FUNNEL target funnel not found in this project");
    }

    // A non-null edge target must point at an existing step id. null = default (next-in-list / End /
    // completed) and is always valid.
    private static void requireExistingTarget(String targetStepId, Set<String> stepIds) {
        if (targetStepId != null && !stepIds.contains(targetStepId)) {
            throw AppException.unprocessableEntity(CODE_BROKEN_EDGE,
                    "Step target points at a non-existent step id: " + targetStepId);
        }
    }

    // Generic strict URL scheme check (Decision 10 / Decision 6, SSRF / scheme-injection defense), shared
    // by the MESSAGE keyboard url-button and the media-source check. Parses via URI and requires the scheme
    // to be EXACTLY http or https (not startsWith) with a non-empty host — rejecting file://, data:,
    // javascript:, tg://, leading-whitespace-obfuscated values, and empty-host URLs. A malformed URI
    // (URISyntaxException → IllegalArgumentException from URI(String)) maps to 422, never 500. fieldLabel is
    // woven into the error message so the author sees which field is wrong (e.g. "IMAGE media url ..." vs
    // "button url ...") instead of a misleading hardcoded "MENU url button".
    private static void requireUrl(String url, String fieldLabel) {
        if (url == null || url.isBlank()) {
            throw invalidStep(fieldLabel + " url requires a value");
        }
        // Leading/trailing whitespace is never valid in a URL; reject before parsing so " http://x"
        // cannot slip a leading-space-obfuscated value past the scheme check.
        if (!url.equals(url.strip())) {
            throw invalidStep(fieldLabel + " url must use the http or https scheme");
        }
        final URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw invalidStep(fieldLabel + " url is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw invalidStep(fieldLabel + " url must use the http or https scheme");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalidStep(fieldLabel + " url must have a non-empty host");
        }
    }

    // A media source (Decision 6) is EITHER an http(s) URL OR an opaque Telegram file_id token. The backend
    // never dereferences it (anti-SSRF). Disambiguation: if the value carries a URI scheme separator (looks
    // like "<scheme>:..."), it is treated as a URL and validated strictly via requireUrl — so file://,
    // data:, javascript: and empty-host URLs are rejected. Otherwise it is accepted as an opaque file_id (a
    // bare token with no scheme). Null/blank is always rejected. The field label (block type) is woven into
    // the error so the message names the right block, not a hardcoded "MENU".
    private static void requireMediaSource(String blockType, String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            throw invalidStep(blockType + " block requires a mediaUrl (http(s) URL or file_id)");
        }
        if (looksLikeUrl(mediaUrl)) {
            requireUrl(mediaUrl, blockType + " media");
        }
        // else: opaque file_id — accepted as-is (Decision 6). No dereference, no scheme.
    }

    // Heuristic for "this value is a URL, not a bare file_id": it has a URI scheme separator. A scheme is
    // ^[A-Za-z][A-Za-z0-9+.-]*: per RFC 3986; a value matching that prefix is treated as a URL and held to
    // the strict http(s) rule (so file://, data:, javascript: are caught). A Telegram file_id (alnum / _ / -
    // only, no ':') has no scheme and is treated as opaque. A leading-whitespace value (" http://...") does
    // NOT match the anchored scheme regex, so it is routed to requireUrl, which rejects it on the strip
    // guard — never silently accepted as a file_id.
    private static final Pattern URI_SCHEME_PREFIX = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");

    private static boolean looksLikeUrl(String value) {
        String stripped = value.strip();
        // A leading-whitespace value is suspicious: route it to requireUrl (which rejects it) rather than
        // accept it as a file_id.
        if (!value.equals(stripped)) {
            return true;
        }
        return URI_SCHEME_PREFIX.matcher(value).matches();
    }

    private static void requireParseMode(String parseMode) {
        if (parseMode != null && !"HTML".equals(parseMode) && !"MarkdownV2".equals(parseMode)) {
            throw invalidStep("parseMode must be HTML or MarkdownV2");
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

    // Optional MESSAGE keyboard timeout pair (audit-fix F1; the park-on-reply timeout on the last-block
    // keyboard). null/null = unlimited wait (valid). Otherwise BOTH must be present, timeoutUnit in
    // {MIN,HOUR,DAY} (same convention as delayUnit; StepExecutor.durationOf only understands these) and
    // timeoutValue >= 1. An invalid pair is a 422 funnel_step_invalid — NOT a 500, and never reaches the
    // engine where an unknown unit would strand the execution.
    private static void requireTimeout(Integer timeoutValue, String timeoutUnit) {
        if (timeoutValue == null && timeoutUnit == null) {
            return; // no timeout configured → unlimited wait
        }
        if (timeoutValue == null || timeoutUnit == null) {
            throw invalidStep("MESSAGE timeout requires both timeoutValue and timeoutUnit");
        }
        if (timeoutValue < 1) {
            throw invalidStep("MESSAGE timeoutValue must be >= 1");
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

    // EMIT_EVENT (Decision 4): eventName is required and must be an event slug ^[A-Za-z0-9_-]{1,64}$ —
    // the same shape as the `event` trigger value, so an emit and its listener share one namespace.
    private static void requireEventName(String eventName) {
        if (eventName == null || !EVENT_NAME_PATTERN.matcher(eventName).matches()) {
            throw invalidStep("EMIT_EVENT step requires an eventName matching ^[A-Za-z0-9_-]{1,64}$");
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
                funnel.getKeywords(),
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
                funnel.getKeywords(),
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
                toBlockDtos(step.getBlocks()),
                step.getDelayValue(),
                step.getDelayUnit(),
                step.getTagSlug(),
                step.getCustomFieldKey(),
                step.getCustomFieldValue(),
                step.getEventName(),
                step.getTargetFunnelId(),
                step.getTargetEntryStepId(),
                step.isEndParentAfter());
    }

    private static List<ButtonDto> toButtonDtos(List<Button> buttons) {
        if (buttons == null) {
            return null;
        }
        return buttons.stream()
                .map(b -> new ButtonDto(b.type(), b.label(), b.targetStepId(), b.url()))
                .toList();
    }

    // domain → DTO mapper for the MESSAGE composer blocks (the round-trip inverse of toBlocks). A null
    // blocks list maps to null; every field (type discriminator name, text, parseMode, mediaUrl, caption,
    // album items + per-element caption) round-trips verbatim WITHOUT silent drop.
    private static List<ContentBlockDto> toBlockDtos(List<ContentBlock> blocks) {
        if (blocks == null) {
            return null;
        }
        return blocks.stream()
                .map(b -> new ContentBlockDto(
                        b.type() == null ? null : b.type().name(),
                        b.text(),
                        b.parseMode(),
                        b.mediaUrl(),
                        b.caption(),
                        toMediaItemDtos(b.items())))
                .toList();
    }

    private static List<MediaItemDto> toMediaItemDtos(List<MediaItem> items) {
        if (items == null) {
            return null;
        }
        return items.stream()
                .map(m -> new MediaItemDto(m.type(), m.mediaUrl(), m.caption()))
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
