# Code Research: 17-funnel-multi-entry (Epic 8, "Multi-entry graph")

Goal of the feature: a funnel holds MANY triggers (`List<Trigger>`), each `(triggerType/event_name, entryStepId)` pointing at an entry step inside the funnel graph. On a trigger firing for a subscriber:
- in-flight execution of THIS funnel exists → REDIRECT `currentStepId` to the trigger's branch, unpark if parked (`waiting_for_reply`), run now;
- else → START a fresh execution at `entryStepId` (mid-graph entry).
ONE execution per funnel per subscriber. Minimal editor = vertical-list trigger panel (no canvas).

All paths below are absolute. Line numbers from the state read on 2026-06-13.

---

## 1. Relevant files (path → role)

### Backend domain
- `/backend/src/main/java/com/botfunnel/funnel/Funnel.java` — `@Document("funnels")`. Holds the SINGLE trigger today: `triggerType` (L72), `triggerValue` (L73), `allowReEnter` (L74), `keywords` (L79), `steps` (L81). Two compound indexes declared on the class (L32-39): `projectId_status`; and the partial-unique trigger index `projectId_triggerType_triggerValue_unique_active` filtered `{status:'active', triggerType:'on_start'}` (L35-38). Class-load static block (L47-58) asserts the partialFilter literals `'active'`/`'on_start'` stay byte-identical to `FunnelStatus.active.name()` and `FunnelService.TRIGGER_ON_START`.
- `/backend/src/main/java/com/botfunnel/funnel/FunnelStep.java` — flat embedded step POJO. `id` (L28), `next` (L29), `buttons`/`timeoutValue`/`timeoutUnit`/`timeoutTargetStepId` (L34-38), `blocks` (L43), `eventName` (L66), `targetFunnelId`/`targetEntryStepId`/`endParentAfter` (L74-76), keyboard fields (L84-88). `FunnelStep.copyOf(source)` deep-copy at L116-154 (the snapshot builder; mutable list fields `buttons`/`blocks`/`keyboardRows` defensively copied L150-152, scalars by reference).
- `/backend/src/main/java/com/botfunnel/funnel/Button.java` — `record Button(String type, String label, String targetStepId, String url)` (L17). `targetStepId` is the intra-funnel MENU edge — the existing "point at a step id" pattern.
- `/backend/src/main/java/com/botfunnel/funnel/StepType.java` — enum: `MESSAGE, DELAY, ADD_TAG, REMOVE_TAG, SET_CUSTOM_FIELD, EMIT_EVENT, SUBSCRIBE_TO_FUNNEL, SET_KEYBOARD, CLEAR_KEYBOARD, UNKNOWN` (L12-43). `UNKNOWN` is a tolerant-read sentinel.
- `/backend/src/main/java/com/botfunnel/funnel/FunnelStatus.java` — `draft/active/paused` (lowercase name()).
- `/backend/src/main/java/com/botfunnel/funnel/ExecutionStatus.java` — includes `running`, `waiting`, `waiting_for_reply`, `completed`, `cancelled`, `failed` (lowercase name()).
- `/backend/src/main/java/com/botfunnel/funnel/StepRunStatus.java` — `pending`, `in_progress`, `done`.

### funnel_executions
- `/backend/src/main/java/com/botfunnel/funnel/FunnelExecution.java` — `@Document("funnel_executions")`. Fields: `funnelId` (L51), `subscriberId` (L52), `telegramBotId` (L53), `status` (L55), `currentStepIndex` (L56), `currentStepId` (L60, graph cursor), `stepRunStatus` (L61), `nextRunAt` (L62), `lastButtonClicked` (L64), `stepsSnapshot` (L67, deep copy at fire), `enrollDepth` (L73). Two compound indexes (L21-28): `status_nextRunAt` (sweep predicate); and the SOLE re-enter guard `funnelId_subscriberId_unique_active` — **unique**, partialFilter `{status:{$in:['running','waiting','waiting_for_reply']}}` (L24-27). Static block L34-43 asserts those status literals.
- `/backend/src/main/java/com/botfunnel/funnel/FunnelExecutionRepository.java` — empty marker interface (engine uses MongoTemplate.findAndModify, not derived queries).

### Engine + services (the primitives we extend)
- `/backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java` — the `@Recurring("funnel-sweep")` sweep + claim/advance/commit. Detailed below.
- `/backend/src/main/java/com/botfunnel/funnel/StepExecutor.java` — per-StepType dispatch (L108-135), stateless side effects.
- `/backend/src/main/java/com/botfunnel/funnel/FunnelTriggerServiceImpl.java` — `fire`, `cancelActiveFor`, `advanceOnCallback` (chatId-keyed, single-result, `on_start`/callback).
- `/backend/src/main/java/com/botfunnel/funnel/FunnelEventService.java` — `dispatchForSubscriber` (subscriber-keyed fan-out), `enrollSpecificFunnel` (named-target enroll for SUBSCRIBE_TO_FUNNEL).
- `/backend/src/main/java/com/botfunnel/funnel/FunnelExecutionFactory.java` — `insertExecution`, `insertExecutionAt(startStepId)`, `cancelExistingForPair`.
- `/backend/src/main/java/com/botfunnel/funnel/FunnelService.java` — CRUD/lifecycle + `applyTrigger` + `validateSteps` (1504 lines; trigger logic L611-737, step/edge validation L885-931).
- `/backend/src/main/java/com/botfunnel/funnel/FunnelRepository.java` — trigger lookups (single + List siblings).

### Migrations / startup runners
- `/backend/src/main/java/com/botfunnel/funnel/FunnelTriggerIndexReconciliation.java` — `BeanPostProcessor` on `MongoDatabaseFactory` dropping the old broad trigger index before MongoTemplate auto-creates the new shape (L51-116). Idempotent, log-never-throw.
- `/backend/src/main/java/com/botfunnel/funnel/FunnelStepIdBackfill.java` — `ApplicationRunner` stamping step `id`s + seeding `currentStepId` from `currentStepIndex` on legacy data (L34-134). Raw-Document `$set`, idempotent, log-never-throw.

### Entry points (event → dispatcher)
- `/backend/src/main/java/com/botfunnel/api/EventsController.java` — `POST /api/integrations/v1/events` (L79-111); calls `funnelEventService.dispatchForSubscriber(projectId, subscriberId, TRIGGER_EVENT, eventName, 0)` (L105). API-key auth, project pinned from key, never a 5xx.
- `/backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java` — webhook worker. `handleStart` → `funnelTriggerService.fire(projectId, chatId, "on_start", startPayload)` (L311). `handleStop` → `cancelActiveFor` (L323). `handleCallbackQuery` → `advanceOnCallback` (L349). `dispatchKeyword` → `dispatchForSubscriber(..., TRIGGER_KEYWORD, text, 0)` (L247-248), with a `waiting_for_reply` precedence probe `hasWaitingForReplyExecution` (L262-267).

### DTOs / controller
- `/backend/src/main/java/com/botfunnel/funnel/FunnelController.java` — `/api/v1/projects/{projectId}/funnels`. PUT and PATCH share one handler, both **full-replace** (L31-33, L64-76). Steps array authoritative.
- `/backend/src/main/java/com/botfunnel/funnel/dto/UpdateFunnelRequest.java` — `(name, description, triggerType, triggerValue, allowReEnter, keywords, steps)` (L17-28). `@JsonIgnoreProperties(ignoreUnknown=true)` (mass-assignment defense).
- `/backend/src/main/java/com/botfunnel/funnel/dto/FunnelResponse.java` — mirrors single trigger (L12-27).
- DTOs to extend: also `FunnelSummaryResponse`, `FunnelStepDto`, `ButtonDto` under `/backend/src/main/java/com/botfunnel/funnel/dto/`.

### Frontend
- `/frontend/types/funnel.ts` — TS types. NO `Trigger` type exists; the trigger is the flat trio `triggerType`/`triggerValue`/`keywords` on `FunnelResponse` (L169-184), `FunnelSummaryResponse` (L151-165), `UpdateFunnelRequest` (L194-203). `FunnelTriggerType` union L31. `FunnelStep` L107-148 (`id?` L140, `next?` L140, `targetEntryStepId?` L123 — the existing entry-step-id naming precedent). `Button` L38-43.
- `/frontend/stores/funnels.ts` — Pinia store; `update(funnelId, payload)` PATCHes full funnel (L65-92), `fetchOne` (L96), `syncRow` (L108). Thin pass-through.
- `/frontend/components/funnels/FunnelTriggerSettings.vue` — the single-trigger "portable node form" (Decision 11; header comment L1-11). `defineModel` over `triggerType`/`triggerValue`/`keywords` (L17-19), props `{botUsername, deepLink}` (L16). Per-type editors via `v-if` on triggerType (L160-296). Uses `<SearchableSelect>` for tag/field pickers.
- `/frontend/components/funnels/FunnelStepsList.vue` — vertical list panel. Props `{steps, selectedIndex}` (L10); emits `move/edit/delete/add/select` (L11-17). ↑/↓ reorder, inline-confirm delete, select-to-preview. The structural template a multi-trigger list clones.
- `/frontend/pages/projects/[projectId]/funnels/[funnelId].vue` — editor page. Trigger trio as three refs (L32-37); `persist()` full-replace PATCH with per-type value discipline (L166-192); autosave-on-mutate + 600ms debounced trigger watch (L223-230). `<FunnelTriggerSettings>` mounted below `<FunnelStepsList>` (L435-451).
- `/frontend/components/funnels/FunnelStepForm.vue` — the canonical reusable node form, shared by `AddStepDialog.vue`/`EditStepDialog.vue`. Step-id pickers via `<SearchableSelect>`: MENU target options `menuTargetOptions` (L277-289), SUBSCRIBE entry-step `subscribeEntryOptions` (L528-540) — the closest analog to a trigger→entry-step binding (`{value: stepId, label: "N. TYPE"}`, `__START__`/`null` sentinel for "first step").
- `/frontend/components/funnels/SearchableSelect.vue` — combobox primitive. `SearchableOption = {value, label, hint?}` (L6); single-select `v-model`, `:show-value="false"` for step-id pickers. THE reusable entry-step picker.

---

## 2. Reusable primitives (signatures we extend)

### Execution-creation (FunnelExecutionFactory)
```
void cancelExistingForPair(String projectId, String funnelId, String subscriberId)            // L55
void insertExecution(String projectId, Funnel funnel, String subscriberId,
                     Long telegramBotId, int enrollDepth)                                       // L76 → delegates to insertExecutionAt(..., null)
void insertExecutionAt(String projectId, Funnel funnel, String subscriberId, Long telegramBotId,
                       int enrollDepth, String startStepId)                                     // L88
```
`insertExecutionAt` ALREADY supports mid-graph entry: `resolveStartCursor` (L117-126) seeds `currentStepId = startStepId` iff it resolves in the snapshot, else step-0 fallback. `currentStepIndex` stays 0. Uses `MongoTemplate.insert` so the re-enter unique index surfaces a `DuplicateKeyException` (L109). **This is the primitive the "START fresh at entryStepId" branch reuses verbatim.**

### Engine resume / redirect (FunnelExecutionEngine)
```
public boolean resumeOnCallback(String executionId, String subscriberId, String targetStepId)  // L181
```
Claims a parked `waiting_for_reply` execution (CAS scoped by subscriberId, `claimForCallback` L323-336), sets `status=running`, `currentStepId=targetStepId`, `stepRunStatus=in_progress` atomically, then runs `preStepGates` + `drive` immediately. **This is the EXACT mechanic the "REDIRECT in-flight execution to a trigger branch, unpark, run now" path needs** — but `claimForCallback` only matches `status=waiting_for_reply`. A redirect must also catch a `running`/`waiting` in-flight execution (see change surface §4).

Private claim (sweep): `claim(executionId, now)` L304-315 — predicate `status in [running,waiting,waiting_for_reply] AND nextRunAt<=now AND stepRunStatus=pending`, flips → `in_progress`. The `drive` loop L230-299 resolves the cursor via `currentStep(exec)` (L345-360, source of truth `currentStepId`), executes, advances along `step.next`/default-next (`advanceToNext` L397-416). `persistProgress`/`scheduleDelay`/`parkForReply`/`complete`/`terminate` all CAS on `stillClaimed` (`stepRunStatus=in_progress`, L460-463) so a concurrent cancel deterministically wins.

### Dispatch / fan-out (FunnelEventService)
```
public void dispatchForSubscriber(String projectId, String subscriberId, String triggerType,
                                  String matchKey, int originDepth)                              // L137
public void enrollSpecificFunnel(String projectId, String subscriberId, String targetFunnelId,
                                 String targetEntryStepId, int originDepth, Long telegramBotId)  // L224
```
`dispatchForSubscriber` resolves the bot, the subscriber, `matchingFunnels` (L351-358; List query for event/tag/field, code-scan for keyword), then loops `insertOneFunnel` (L328-345) honouring `allowReEnter` (cancel-then-insert) or swallowing the re-enter `DuplicateKeyException`. Three backstops: depth cap (L142), volume rate-limit (L151), fan-out ceiling (L186-191). `enrollSpecificFunnel` → `insertTargetExecution` (L291-309) starting at `targetEntryStepId` via `insertExecutionAt`. **The redirect-vs-start decision and the per-funnel entry-step enroll both belong here** (or a new sibling method), reusing `insertExecutionAt` + the backstops.

### Trigger fire (single-valued, on_start) — FunnelTriggerServiceImpl
```
public void fire(String projectId, Long chatId, String triggerType, String payload)             // L121
public void cancelActiveFor(String projectId, Long chatId)                                       // L177
public void advanceOnCallback(String projectId, Long chatId, String callbackData, String callbackQueryId)  // L211
```
`fire` does the single-result lookup `findByProjectIdAndTriggerTypeAndTriggerValueAndStatus` (L143-145) then `insertExecution(..., 0)`.

### Repository
```
Optional<Funnel> findByProjectIdAndTriggerTypeAndTriggerValueAndStatus(...)                      // L27 (on_start single)
List<Funnel>     findAllByProjectIdAndTriggerTypeAndTriggerValueAndStatus(...)                    // L33 (fan-out)
List<Funnel>     findByProjectIdAndTriggerTypeAndStatus(...)                                      // L39 (keyword scan)
```
With `List<Trigger>` these derived queries can no longer match on a top-level `triggerType`/`triggerValue` field — they must query into the array (`triggers.triggerType`/`triggers.triggerValue`). See §4.

### Service trigger application + step validation (FunnelService)
- `applyTrigger(funnel, rawType, rawValue, rawKeywords)` (L611-640) — validates the trigger triplet by type, with regexes `TRIGGER_VALUE_PATTERN` (L74), `TAG_SLUG_PATTERN` (L75), `EVENT_NAME_PATTERN` (L78). With `List<Trigger>` this becomes per-element + cross-trigger uniqueness (at most one `on_start`, no duplicate within funnel).
- `validateSteps(steps, projectId)` (L885-931) — per-type + edge validation; `requireExistingTarget(next/timeoutTargetStepId, stepIds)` (L928-929). A trigger's `entryStepId` must be validated the SAME way (must resolve to an existing step id in THIS funnel), but NOT through the generic edge pass (mirror how `targetEntryStepId` is kept out of it, L906-912).
- `checkTriggerConflict(funnel)` (L337-344) + `saveHandlingTriggerConflict` (L350-357) — the on_start trigger-conflict defense-in-depth (pre-check + DuplicateKeyException → 422 `funnel_trigger_conflict`).

---

## 3. How the trigger is currently single-valued (read/write trace)

WRITE: `FunnelService.applyTrigger` (L611) sets `funnel.triggerType` + `funnel.triggerValue` + `funnel.keywords` from `UpdateFunnelRequest`. `create` seeds `(on_start, "")` (L177-178). `duplicate` resets to `(on_start, "")` (L282-283).

READ (matching at fire time):
- `on_start`: `FunnelTriggerServiceImpl.fire` → `findByProjectIdAndTriggerTypeAndTriggerValueAndStatus` (single) (L143).
- `event`/`tag_added`/`custom_field_set`: `FunnelEventService.matchingFunnels` → `findAllByProjectIdAndTriggerTypeAndTriggerValueAndStatus` (L356).
- `keyword`: `matchingKeywordFunnels` → `findByProjectIdAndTriggerTypeAndStatus` then code `containsAnyKeyword` over `funnel.keywords` (L360-371).

All four read the single top-level `triggerType`/`triggerValue`/`keywords`. There is no notion of an entry step today — every start is at step 0 (`insertExecution` passes `startStepId=null`).

---

## 4. Exact change surface for `List<Trigger>` + redirect-execution

### A new embedded `Trigger` record/POJO
Likely shape (mirroring existing naming): `Trigger(String triggerType, String triggerValue, List<String> keywords, String entryStepId)`. Reuse `targetEntryStepId`-style naming → call it `entryStepId`. Flat POJO, no `_class` (Decision 12 convention). Must be snapshot-irrelevant (triggers are not copied into `stepsSnapshot`; they drive START/REDIRECT only).

### Funnel.java
- Replace `triggerType`/`triggerValue`/`keywords` (L72-79) with `private List<Trigger> triggers;` (or keep the scalars for backward-compat during migration — see Decision 4 below).
- The class-level partial-unique trigger index (L35-38) references top-level `triggerType`/`triggerValue` — with an array those keys no longer exist at top level. **The index must change** to a multikey partial-unique on `triggers.triggerType`/`triggers.triggerValue` filtered `{status:'active', 'triggers.triggerType':'on_start'}`, OR the on_start uniqueness must move to a service-only check. NOTE: a multikey unique partial index has MongoDB caveats (a unique index over an array enforces uniqueness across documents per array element — needs careful validation). The static-block literal assertions (L47-58) must be updated to whatever new shape is chosen.

### Indexes & migration (Decision 4: migration-vs-wipe)
- `FunnelTriggerIndexReconciliation` (BeanPostProcessor pattern) is the established mechanism to drop/recreate the trigger index shape before MongoTemplate auto-creation. A new reconciliation (or an extension) is needed to retire `projectId_triggerType_triggerValue_unique_active` and lay down the `triggers.*` shape — it must run as a `BeanPostProcessor` on `MongoDatabaseFactory`, NOT an `ApplicationRunner` (the IndexKeySpecsConflict-before-MongoTemplate reason, documented in that file L24-44 and `deployment.md` L129).
- A data backfill from the flat trio → `triggers:[{triggerType, triggerValue, keywords, entryStepId:null}]` would follow the `FunnelStepIdBackfill` raw-Document `$set` idempotent pattern (L58-104). **OR**, per precedent: Phase 6 chose a one-time manual `funnels`/`funnel_executions` wipe instead of a backfill because there was no production data (`deployment.md` L131). If the multi-entry schema again lands with no prod data, the same wipe-not-migrate route is open (the tech-spec must pick one — this is "Decision 4 migration-vs-wipe").

### Repository (FunnelRepository)
- `findByProjectIdAndTriggerTypeAndTriggerValueAndStatus` (single, L27) and `findAllBy...` (List, L33) currently match top-level fields. With the array, derived queries become `findByProjectIdAndTriggersTriggerTypeAndTriggersTriggerValueAndStatus` (matches a document with ANY array element having that type+value) — Spring Data nested-property syntax. The keyword scan `findByProjectIdAndTriggerTypeAndStatus` (L39) similarly → `...TriggersTriggerType...`. CAUTION: a multikey match returns the funnel but NOT which element matched — the caller must re-scan the matched funnel's `triggers` to pick the right `entryStepId` (the same code-side re-scan `matchingKeywordFunnels` already does for keywords, L360-371). This re-scan is the natural seam to surface the matched trigger's `entryStepId`.

### Matching + redirect logic (FunnelEventService / FunnelTriggerServiceImpl)
The core new behaviour. For a fired trigger that matches a funnel, the decision is:
1. Does the subscriber have an in-flight execution of THIS funnel (`status in [running, waiting, waiting_for_reply]`)? Detected today via the unique partial index / `mongoTemplate.exists` (the `hasWaitingForReplyExecution` probe L262-267 is the exact idiom, but it currently filters only `waiting_for_reply` — a redirect needs all three in-flight statuses).
2. If yes → REDIRECT: a NEW engine method analogous to `resumeOnCallback` that claims the in-flight execution (CAS scoped by subscriberId, must accept `status in [running,waiting,waiting_for_reply]`, not just `waiting_for_reply` like `claimForCallback` L323-336), sets `currentStepId = trigger.entryStepId`, `status=running`, `stepRunStatus=in_progress`, then runs `preStepGates`+`drive` now. The `stillClaimed`/`stepRunStatus=in_progress` CAS discipline (L460-482) must be preserved so a concurrent sweep tick / cancel can't double-run.
   - Race landmine: if the execution is currently `in_progress` (a sweep tick owns it), the redirect CAS (predicate `stepRunStatus=pending`) loses — the redirect must define behaviour for that (retry / drop / re-schedule). The existing claim only ever matches `pending`.
3. If no in-flight (not in funnel, or completed/cancelled) → START fresh at `entryStepId` via `insertExecutionAt(..., trigger.entryStepId)` (the primitive already exists, L88). The re-enter unique index naturally gives "one execution per funnel per subscriber".

This redirect/start dispatcher is best placed alongside `dispatchForSubscriber`/`fire`, reusing the bot/subscriber resolution, the three backstops, and `enrollSpecificFunnel`'s fail-closed `projectId` discipline. The `on_start` path (`FunnelTriggerServiceImpl.fire`) and the fan-out path (`FunnelEventService`) BOTH need the new redirect-or-start decision — keep the logic in ONE collaborator to avoid a forked writer (the same anti-bean-cycle reasoning that produced `FunnelExecutionFactory`, see its javadoc L22-32).

### Service validation (FunnelService)
- `applyTrigger` → per-element validation over `List<Trigger>` + cross-trigger rules (at most one `on_start`; reject duplicate (type,value) within the funnel; each `entryStepId` must be null or resolve to an existing step id — validated against the step-id set built in `validateSteps` L893-898). Keep `entryStepId` OUT of the generic edge pass (mirror `targetEntryStepId`, L906-912, to avoid a false `funnel_broken_edge`).
- `checkTriggerConflict`/`saveHandlingTriggerConflict` (L337-357) — the on_start cross-funnel uniqueness now spans the array; the pre-check must look up active funnels whose `triggers` array contains an `on_start` with the same value.

### DTOs + controller
- `UpdateFunnelRequest` (L17-28), `FunnelResponse` (L12-27), `FunnelSummaryResponse`: replace the flat trigger trio with `List<TriggerDto>`. PATCH stays full-replace (FunnelController L31-33 — no new endpoint needed).

### Frontend
- `frontend/types/funnel.ts`: add `FunnelTrigger` interface (none exists), e.g. `{ triggerType; triggerValue?; keywords?; entryStepId? }`; replace the flat trio on `FunnelResponse`/`FunnelSummaryResponse`/`UpdateFunnelRequest`.
- New multi-trigger vertical panel: clone `FunnelStepsList.vue`'s add/reorder/delete/select structure; mount N `FunnelTriggerSettings.vue` instances (already a portable node form, Decision 11); add a per-trigger entry-step `<SearchableSelect>` over the funnel's OWN steps (copy `subscribeEntryOptions` builder, `FunnelStepForm.vue` L528-540, with `null`/`__START__` = step 1).
- `[funnelId].vue`: replace the three trigger refs (L32-37) with an array; rework `persist()`'s trigger block (L166-192), `triggerReady()` (L97-111), and the debounced trigger `watch` (L223-230) to operate per-element.

---

## 5. Risks / landmines

| Risk | Detail / file |
|------|---------------|
| **Multikey unique partial index semantics** | Moving the on_start uniqueness onto `triggers.triggerType`/`triggers.triggerValue` (Funnel.java L35-38) creates a multikey unique index. Mongo enforces uniqueness per array element across documents — a funnel with two on_start triggers with empty value would self-collide; the constraint semantics need explicit design + a `FunnelIndexesIT`. Consider service-only uniqueness for on_start if the multikey index is unsafe. |
| **Redirect CAS race** | The new redirect must claim an in-flight execution that may be `running`/`waiting`/`waiting_for_reply` AND may be mid-tick (`stepRunStatus=in_progress`). `claimForCallback` (L323) only matches `waiting_for_reply`+`pending`. A redirect arriving while the sweep owns the row (in_progress) loses the CAS — define the outcome. Reuse the `stillClaimed`/`stepRunStatus=in_progress` discipline (L460-482) so no double-drive. |
| **One-execution-per-funnel guarantee** | The unique partial index `funnelId_subscriberId_unique_active` (FunnelExecution L24-27) is what enforces "one execution per funnel per subscriber". The redirect path must NOT insert a second execution — it must mutate the existing one. The START path relies on the index to reject a duplicate (`DuplicateKeyException` swallowed). |
| **Snapshot isolation vs redirect target** | A redirect sets `currentStepId = trigger.entryStepId`, but the entry step must exist in the EXECUTION's `stepsSnapshot` (deep-copied at fire, Funnel L116/Execution L67), not the live funnel — the funnel may have been edited after the execution started. A trigger pointing at a step added after this execution's snapshot will not resolve → `drive` treats a non-resolving cursor as a broken cursor and FAILS the execution (`LOG_BROKEN_CURSOR`, Engine L252-254). Redirect must validate the target against the snapshot (or accept graceful broken-cursor fail). |
| **Index relax must precede MongoTemplate** | Any trigger-index reshape MUST go through a `BeanPostProcessor` on `MongoDatabaseFactory` (the `FunnelTriggerIndexReconciliation` pattern, L24-44), never an `ApplicationRunner` — else `IndexKeySpecsConflict` (Mongo error 86) crashes boot. |
| **Migration-vs-wipe (Decision 4)** | Either a `FunnelStepIdBackfill`-style raw-Document idempotent backfill (flat trio → `triggers[]`), or a one-time manual collection wipe (Phase 6 precedent, `deployment.md` L131) if no prod data. The tech-spec must choose; a wipe also clears in-flight `funnel_executions` whose snapshots predate any schema change. |
| **`keywords` placement** | Today `keywords` is a funnel-level list scanned by `matchingKeywordFunnels` (L360-371). Under `List<Trigger>` each keyword trigger carries its own `keywords` — the code-scan must move to per-trigger and surface that trigger's `entryStepId`. |
| **Matched-element ambiguity** | A multikey repository match returns the funnel, not which `triggers[]` element matched. The caller must re-scan to find the matching trigger and its `entryStepId` (extend the existing keyword re-scan idiom). |
| **fire() / dispatch error-isolation** | Both `fire` (L170-173) and `dispatchForSubscriber` (L196-200) swallow all Throwables (never poison the webhook). The new redirect/start logic must preserve this — a redirect fault must not fail the webhook job or 5xx the events API. |

---

## 6. Test surface (naming/location conventions)

Base class: `/backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java` — singleton Testcontainers (Mongo + Mailpit), `@SpringBootTest(RANDOM_PORT)`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, JobRunr in-memory.

### Backend funnel tests (`/backend/src/test/java/com/botfunnel/funnel/`)
- `FunnelExecutionEngineIT.java` (1938 lines) — **the "slow-lane engine ITs"**, `@Tag("slow")` (L62), ~60 `@Test` methods. The claim/advance/park/resume/redirect behaviour goes here. (roadmap notes "57/57 slow-lane engine ITs".)
- `FunnelTriggerServiceIT.java` (728) — `fire`/`cancelActiveFor`/`advanceOnCallback` integration.
- `FunnelEventServiceIT.java` (241) + `FunnelEventServiceTest.java` (369) — fan-out dispatch + backstops (IT and pure unit).
- `FunnelIndexesIT.java` (207) — runtime proof of the index shapes (the place to assert the new `triggers.*` partial-unique shape).
- `FunnelTriggerIndexReconciliationIT.java` (208) — the BeanPostProcessor drop/recreate migration proof.
- `FunnelStepIdBackfillTest.java` (210) — the backfill pattern to mirror for any triggers backfill.
- `FunnelControllerIT.java` (2292) — CRUD/lifecycle + DTO contract; where `UpdateFunnelRequest`/`FunnelResponse` array shape + trigger validation (422 codes) is exercised.
- `FunnelServiceTriggerTypeTest.java` (121) — pure trigger-type validation unit tests (the home for cross-trigger uniqueness + entryStepId validation).
- `FunnelExecutionTest.java` (18) + `FunnelStepTest.java` — domain POJO / `copyOf` unit tests.

### Entry-point tests
- `/backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java` (996) — `handleStart`/`fire`, `dispatchKeyword`, `handleCallbackQuery`, the `waiting_for_reply` precedence probe.
- `/backend/src/test/java/com/botfunnel/api/EventsControllerIT.java` (363) — `POST /events` → dispatch.

### Frontend tests
- `/frontend/tests/components/FunnelTriggerSettings.spec.ts` (148), `/frontend/tests/components/FunnelStepsList.spec.ts` (56).
- `/frontend/tests/stores/funnels.spec.ts` (276), `/frontend/tests/pages/funnel-editor.spec.ts` (1047 — the page wiring + persist).
- `/frontend/e2e/funnels.spec.ts` (186 — Playwright E2E).
Convention: component specs under `tests/components/`, page specs under `tests/pages/`, store specs under `tests/stores/`, E2E under `e2e/`.

---

## 7. Config knobs (already present, `application.properties`)
- `app.funnel.scheduler-interval` (PT30S), `app.funnel.max-steps` (50), `app.funnel.sweep-batch-size` (200), `app.funnel.max-steps-per-tick` (100), `app.funnel.max-fanout-per-event` (50), `app.funnel.auto-enroll-rate-per-min` (20), `app.funnel.max-enroll-depth` (10). The fan-out ceiling + depth cap + rate-limit backstops the redirect/start dispatcher must keep honouring (FunnelEventService L114-116).
