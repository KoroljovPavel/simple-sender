# Code Research: 14-funnels-composition (SUBSCRIBE_TO_FUNNEL step)

Researched 2026-06-08 against the live Phase 1-4 code in `backend/src/main/java/com/botfunnel/funnel/` and `frontend/`. Every claim cites `file:line`. Phase 4 (13-funnels-tooling) is the closest precedent: it added a NEW direct-enroll caller of the factory (`FunnelService.testRun`), exactly the shape SUBSCRIBE_TO_FUNNEL needs.

All file paths below are relative to repo root `/Users/pavlokorolov/IdeaProjects/simple-sender/`.

---

## 1. Engine / execution creation

### `FunnelExecutionFactory` (`backend/src/main/java/com/botfunnel/funnel/FunnelExecutionFactory.java`)

Package-private `@Component`. Two methods:

- `void insertExecution(String projectId, Funnel funnel, String subscriberId, Long telegramBotId, int enrollDepth)` — `FunnelExecutionFactory.java:75`.
- `void cancelExistingForPair(String projectId, String funnelId, String subscriberId)` — `FunnelExecutionFactory.java:55`.

**`insertExecution` behavior** (`:75-99`):
- Builds a fresh `FunnelExecution`, status `running`, `currentStepIndex=0`, `stepRunStatus=pending`, `nextRunAt=now`, `enrollDepth=<arg>`.
- Snapshot built by `deepCopySteps(funnel.getSteps())` → `FunnelStep.copyOf` per step (`:88`, `:103-111`).
- **Start step is HARD-CODED to step 0:** `execution.setCurrentStepId(snapshot.isEmpty() ? null : snapshot.get(0).getId())` (`:93`). `currentStepIndex` stays 0 (`:84`). There is NO parameter to start at an arbitrary step. **This is the single biggest factory change SUBSCRIBE_TO_FUNNEL needs** (your design wants the child to start at `targetStepId`).
- Uses `mongoTemplate.insert(...)` (`:96`) so a re-enter collision surfaces as `DuplicateKeyException`.
- `enrollDepth` is passed in by the caller, never incremented inside the factory. Callers pass:
  - `FunnelTriggerServiceImpl.fire()` → `0` (`FunnelTriggerServiceImpl.java:157`, `:163`).
  - `FunnelEventService.dispatchForSubscriber` → `originDepth` (`FunnelEventService.java:196`, `:200`).
  - `FunnelService.testRun` → `0` (`FunnelService.java:354`).

**`cancelExistingForPair`** (`:55-68`): `updateMulti` over `(projectId, funnelId, subscriberId)` with `status IN [running, waiting, waiting_for_reply]` → set `cancelled` + `stepRunStatus=done`. Used for `allowReEnter=true` to free the partial-unique index before insert.

**Why the factory exists / cycle note** (`:16-33`): it was extracted in Phase 3 specifically so `FunnelEventService` and `FunnelTriggerServiceImpl` both inject IT instead of each other. The factory has NO edge into the engine/step-executor, which is what keeps the `StepExecutor → FunnelEventService` edge cycle-free. See section 14 for the cycle risk to SUBSCRIBE_TO_FUNNEL.

### `StepExecutor` (`backend/src/main/java/com/botfunnel/funnel/StepExecutor.java`)

- Dispatch is a **`switch` on `step.getStepType()`** returning a `StepResult` — `StepExecutor.java:93-113`. Adding a new `StepType` here without a case is a compile error (exhaustive switch, no default), which is a good guardrail.
- Outcome types — `enum Outcome { CONTINUE, DELAY, CANCEL, FAIL, WAIT_FOR_REPLY }` (`:329`). Factories: `StepResult.cont()`, `.delay(Duration)`, `.cancel(reason)`, `.fail(reason)`, `.waitForReply(Instant)` (`:338-360`). SUBSCRIBE_TO_FUNNEL is fire-and-forget → returns `StepResult.cont()`.
- **`EMIT_EVENT` is the exact precedent** (`:121-125`):
  ```java
  private StepResult emitEvent(FunnelStep step, FunnelExecution execution) {
      funnelEventService.dispatchForSubscriber(execution.getProjectId(), execution.getSubscriberId(),
              FunnelEventService.TRIGGER_EVENT, step.getEventName(), execution.getEnrollDepth() + 1);
      return StepResult.cont();
  }
  ```
  It calls into the event system from inside a running execution at child depth (`parent + 1`), then CONTINUEs. SUBSCRIBE_TO_FUNNEL will be the same shape EXCEPT it enrolls a SPECIFIC funnel (no trigger matching) → it should call the factory directly (or a new collaborator method), not `dispatchForSubscriber`.
- `StepExecutor` already injects `FunnelEventService` (`:71`, `:78`). It does NOT inject `FunnelExecutionFactory` today — that injection would be added (see cycle analysis §14: it is safe).
- Note ADD_TAG / SET_CUSTOM_FIELD also pass `execution.getEnrollDepth() + 1` into their side-effect calls (`:100`, `:266`), establishing the "funnel-step-originated write counts as a child" convention.

### `FunnelExecutionEngine` (`backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java`)

- The engine drives by `currentStepId` graph cursor (`drive`, `:230-290`); after a CONTINUE it `advanceToNext` along `step.next` or list-next (`:388-407`). SUBSCRIBE_TO_FUNNEL's parent advances normally after CONTINUE — no engine change needed for the parent.
- The drive loop has a `maxStepsPerTick` budget (default 100, `:81`, `:235-242`) → `terminate(failed, "step_budget_exceeded")`. This is the **per-tick runaway backstop** that bounds an A→B→A cycle that runs synchronously without a delay/park. A cycle that crosses executions (child is a separate row picked up by the sweep) is NOT bounded by this — see §14.

---

## 2. `FunnelEventService.dispatchForSubscriber` and the backstops

`backend/src/main/java/com/botfunnel/funnel/FunnelEventService.java`

Signature (`:124`):
```java
public void dispatchForSubscriber(String projectId, String subscriberId, String triggerType,
                                  String matchKey, int originDepth)
```

It matches active funnels by `(triggerType, matchKey)` and fans out, creating one execution per match via the factory. **Three backstops live in the DISPATCHER, NOT the factory:**

1. **Depth cap** (`:129-133`): `if (originDepth > maxEnrollDepth) return;`. `maxEnrollDepth` = `app.funnel.max-enroll-depth` default **10** (`application.properties:64`). Redis-independent.
2. **Volume rate-limit** (`:138-142`, `autoEnrollRateLimitExceeded` `:254-268`): per-subscriber Redis key `bf:rate:auto-enroll:{subscriberId}` (prefix `:84`), INCR + 60s TTL, default cap **20/min** (`app.funnel.auto-enroll-rate-per-min`, `application.properties:61`). **Only counts `originDepth > 0`** (`:138`); depth-0 roots never touch Redis. Fail-open on Redis error (`:262-266`).
3. **Fan-out ceiling** (`:171-182`): `maxFanoutPerEvent` default **50** (`application.properties:58`) — caps how many matched funnels one dispatch starts. Redis-independent.

The dispatcher also resolves the CONNECTED bot (`:146`) and the subscriber via the service boundary (`:155`), and is fully error-isolated (`try/catch(Throwable)`, `:126`/`:183-187`).

### What a DIRECT factory enroll gets vs does NOT get

`insertExecution` itself enforces ONLY the re-enter partial-unique index (via `DuplicateKeyException`). It does NOT consult Redis, NOT check depth cap, NOT check fan-out. So a SUBSCRIBE_TO_FUNNEL that calls the factory directly (mirroring `testRun`) would:
- **Get:** re-enter guard for the target pair (DuplicateKey if `allowReEnter=false` and an in-flight exists).
- **NOT get:** auto-enroll rate-limit, depth cap, fan-out ceiling.

**Design conflict / decision needed:** Your spec says "runaway bounded only by existing runtime backstops." If SUBSCRIBE_TO_FUNNEL bypasses the dispatcher, the depth cap and volume limit do NOT apply to the chain — the ONLY remaining cross-execution backstop is the per-tick step budget (which does NOT bound a cycle that spreads across executions via the sweep, only within a single tick). Recommendation to surface in the spec: pass `enrollDepth = parent.enrollDepth + 1` into the child (mirroring EMIT_EVENT/ADD_TAG convention) AND apply the depth-cap check at the step (e.g. drop/skip when `parent.enrollDepth + 1 > maxEnrollDepth`). The volume rate-limit and fan-out ceiling are dispatcher-specific (fan-out is N-funnels-per-event; SUBSCRIBE_TO_FUNNEL is exactly one target), so the natural reusable backstop is the **depth cap** — which you should explicitly wire, since the factory won't give it for free.

---

## 3. The re-enter guard (partial-unique index)

`backend/src/main/java/com/botfunnel/funnel/FunnelExecution.java:24-27`:
```java
@CompoundIndex(name = "funnelId_subscriberId_unique_active",
    def = "{'funnelId': 1, 'subscriberId': 1}",
    unique = true,
    partialFilter = "{ 'status': { $in: ['running', 'waiting', 'waiting_for_reply'] } }")
```
- Statuses are lowercase `name()` literals; a static block asserts they don't drift (`:34-43`).
- `allowReEnter` switching (the canonical pattern, `FunnelEventService.insertOneFunnel` `:192-209`, also `FunnelTriggerServiceImpl.fire` `:155-169`):
  - `allowReEnter=true` → `cancelExistingForPair(...)` THEN `insertExecution(...)`.
  - `allowReEnter=false` → `insertExecution(...)` in a `try`; swallow `DuplicateKeyException` as a benign no-op.

**Implication for SUBSCRIBE_TO_FUNNEL:** the re-enter guard is keyed on the TARGET funnelId + subscriberId. If the same subscriber is already running the target funnel and target `allowReEnter=false`, the SUBSCRIBE_TO_FUNNEL enroll becomes a silent no-op (DuplicateKey swallowed). For the "return to caller's MENU" pattern (B targets A's MENU), A is still in-flight (parked `waiting_for_reply`), so a SUBSCRIBE_TO_FUNNEL targeting A would collide with A's own in-flight execution → no-op unless A is `allowReEnter=true` or the cancel-then-insert path is used. This is a subtle but important semantics point — see §14.

---

## 4. `StepType` enum and what must change to add a value

`backend/src/main/java/com/botfunnel/funnel/StepType.java:8-20` — current values:
`SEND_MESSAGE, SEND_IMAGE, DELAY, ADD_TAG, REMOVE_TAG, SET_CUSTOM_FIELD, MENU, EMIT_EVENT`.

Adding `SUBSCRIBE_TO_FUNNEL` touches (all enumerations are exhaustive switches → compiler forces these):
1. `StepType.java` — add the enum constant.
2. `StepExecutor.execute` switch — `StepExecutor.java:93` (new case; compile error otherwise).
3. `FunnelService.validateSteps` switch — `FunnelService.java:648` (new case; compile error otherwise).
4. `FunnelService.previewStep` / `isMessageStep` — `FunnelService.java:386-388` (decide it's non-message; falls through to placeholder by default, but `isMessageStep` should NOT include it).
5. New per-type fields on `FunnelStep` POJO + `copyOf` (§6).
6. New fields on `FunnelStepDto` (§5/§6) + `FunnelService.toSteps` (`:587-612`) + `toStepDto` (`:860-879`).
7. Frontend `types/funnel.ts` `StepType` union (`frontend/types/funnel.ts:10-18`) + `FunnelStep` interface fields.
8. Frontend `FunnelStepForm.vue` `STEP_TYPES` array (`:30-39`) + a new `<template v-else-if>` block + submit case (`:427`).
9. i18n labels `funnels.steps.type.SUBSCRIBE_TO_FUNNEL` in `frontend/i18n/locales/{uk,en}.json` (the picker renders `t(\`funnels.steps.type.${ty}\`)`, form `:504`).

There is a test `StepTypeTest.java` that likely pins the enum members — check it when adding the value.

---

## 5. `FunnelStepDto` and the API surface

`backend/src/main/java/com/botfunnel/funnel/dto/FunnelStepDto.java:22-42` — flat record; only `@NotNull StepType stepType` is bean-validated (everything else is per-type, validated in `FunnelService` → 422). New fields needed: `targetFunnelId` (String), `targetStepId` (String). Add them to the record, to `FunnelService.toSteps` (`:587-612`), and to `toStepDto` (`:860-879`).

**Naming collision warning:** `targetStepId` already exists as a concept on `Button.targetStepId` (intra-funnel). On a step DTO it would be a new flat field. Pick a clear name; the spec uses `targetStepId` for the SUBSCRIBE step's entry-step pointer into the TARGET funnel. Consider `targetEntryStepId` to avoid confusion with MENU button targets, OR document clearly. (Decision to surface in the spec.)

Endpoints (`FunnelController.java`):
- List funnels: `GET /api/v1/projects/{projectId}/funnels?status=` → `FunnelSummaryResponse[]` (`:51-56`). Summaries OMIT steps.
- Single funnel: `GET /api/v1/projects/{projectId}/funnels/{funnelId}` → `FunnelResponse` WITH full `steps` (`:58-62`). **This is the endpoint the editor's target-step picker uses to fetch the TARGET funnel's steps.**
- Frontend store already has `fetch(status)` (`stores/funnels.ts:35`) and `fetchOne(id)` (`:96`) — both reusable for the funnel picker and the target-step picker. No new API needed; the picker can call `fetchOne(targetFunnelId)` and read `.steps`.

---

## 6. `FunnelStep` model, graph edges, copyOf, and validation of targets

`backend/src/main/java/com/botfunnel/funnel/FunnelStep.java` — flat per-type fields (Decision 12), no `_class`. Graph: `id` (`:23`), `next` (`:24`), MENU `Button.targetStepId` + `timeoutTargetStepId` (`:31`). `copyOf` (`:82-109`) reference-copies immutable scalars and defensively copies the `buttons` list. **Add `targetFunnelId` + `targetStepId` as immutable String fields and copy them by reference in `copyOf`** (same as `eventName`, `:99`).

### How intra-funnel targets are validated (`FunnelService.validateSteps` `:633-668`)
- Builds `stepIds` set from the funnel's OWN steps (`:641-646`).
- `requireExistingTarget(step.getNext(), stepIds)` and `requireExistingTarget(step.getTimeoutTargetStepId(), stepIds)` for EVERY step (`:666-667`); MENU button targets the same (`:696`). A non-null target NOT in `stepIds` → 422 `funnel_broken_edge` (`requireExistingTarget` `:710-715`).

**Critical conflict:** SUBSCRIBE_TO_FUNNEL's `targetStepId` points into ANOTHER funnel, so it must NOT be checked against THIS funnel's `stepIds`. If you reuse the field name `targetStepId` and the generic edge pass picks it up, it would falsely raise `funnel_broken_edge`. The cross-funnel target validation (target funnel exists, is active, same project; targetStepId exists in the TARGET funnel) requires a SEPARATE validation path with a DB lookup of the target funnel — `validateSteps` is currently pure/in-memory and static for most checks. You'll need to make the SUBSCRIBE case instance-level (it already is — `validateSteps` is an instance method with access to `funnelRepository`, `projectService`). See §8 for where new codes slot.

### Step id uniqueness (§7 answer)
- Ids are minted in `FunnelService.toSteps` via `new ObjectId().toHexString()` (`:595`); client-supplied ids are preserved, duplicates WITHIN a funnel rejected (`:592-594`).
- **Ids are unique only WITHIN a funnel, NOT globally.** Phase 4 `duplicate()` copies ids verbatim (`FunnelService.java:228-255`, esp. `:248-250`, and the comment at `:228-233`: "step ids carry no unique index, so reuse across funnels is fine"). So a duplicated funnel has the SAME step ids as its original.
- **Therefore `(targetFunnelId, targetStepId)` IS a sound cross-funnel reference** (the funnelId disambiguates), but a bare `targetStepId` is NOT globally unique. Always carry BOTH. This also means: if the author duplicates the target funnel, the copy's entry step has the same id — a SUBSCRIBE step pointing at the ORIGINAL still resolves correctly because it pins `targetFunnelId`.

---

## 7. (answered inline in §6) Step-id minting & cross-funnel soundness

See §6 last bullet. Summary: ids = ObjectId hex, unique per-funnel only, duplicated verbatim across funnels; `(targetFunnelId, targetStepId)` is the sound reference.

---

## 8. `FunnelService.validateSteps` error codes and frontend surfacing

Business codes (constants `FunnelService.java:79-95`):
`funnel_trigger_conflict`, `funnel_step_limit_reached` (`CODE_STEP_LIMIT`), `funnel_step_invalid` (`CODE_INVALID_STEP`), `funnel_invalid_trigger_value`, `funnel_invalid_trigger_type`, `funnel_invalid_keywords`, `funnel_no_steps`, `funnel_invalid_state`, `funnel_broken_edge` (`CODE_BROKEN_EDGE`), `funnel_owner_not_linked`. All raised via `AppException.unprocessableEntity(code, msg)` → 422.

`custom_field_type_mismatch` is NOT in FunnelService — it comes from the subscriber custom-fields validator at EXECUTION time (`StepExecutor.setCustomField` maps it to `StepResult.fail`, `:268-271`).

### Frontend surfacing
`frontend/pages/projects/[projectId]/funnels/[funnelId].vue:122-126`:
```js
function resolveFunnelError(err, contextKey) {
  const code = errorCode(err)
  if (code && te(`errors.funnels.${code}`)) return t(`errors.funnels.${code}`)
  return resolveError(err, contextKey)
}
```
Codes are mapped under `errors.funnels.{code}` in i18n. Current keys: `frontend/i18n/locales/en.json:455-464` (and uk.json mirror). An UNMAPPED code falls back to a generic message — so a new code works without an i18n entry but shows generic text until added.

**New codes to slot in** (add constants in `FunnelService`, raise in the SUBSCRIBE validation branch, add i18n keys):
- `funnel_subscribe_target_required` (no targetFunnelId).
- `funnel_subscribe_target_not_found` (target funnel missing / wrong project).
- `funnel_subscribe_target_inactive` (target not active — if you enforce active-at-save).
- `funnel_subscribe_target_step_not_found` (targetStepId not in target funnel).
- Possibly `funnel_subscribe_self` if you forbid targeting the same funnel at save (you do NOT — cycles allowed — so probably skip).

---

## 9. Engine behavior for a missing/invalid target at EXECUTION time

Precedents:
- **Silent skip + CONTINUE:** unknown custom-field key — `StepExecutor.setCustomField` `:239-244` logs `FUNNEL_STEP_CUSTOM_FIELD_SKIPPED_DELETED_DEFINITION` and returns `StepResult.cont()`.
- **fail(reason):** bot disconnected / type mismatch / Telegram OTHER terminal reason (`:209`, `:270`, `:306`).
- **cancel(reason):** subscriber blocked / chat not found (`:304`).
- **Engine-level fail:** broken cursor (`FunnelExecutionEngine.java:252-253` → `terminate(failed, "broken_cursor")`); step budget exceeded (`:240`).
- **Engine-level cancel:** subscriber inactive at pre-gate (`:213`); pinned bot no longer CONNECTED → fail (`:219`).

**Recommendation for SUBSCRIBE_TO_FUNNEL at execution:** target funnel deleted/inactive between save and run → mirror the custom-field "silently skip" precedent (`StepResult.cont()` + a greppable id-only WARN like `FUNNEL_SUBSCRIBE_SKIP_TARGET_MISSING`), so the parent keeps running rather than failing. Decide and document. The snapshot does NOT carry the target funnel's steps (it's resolved live at execution from `FunnelRepository`), so a deleted target must be handled gracefully.

---

## 10. Frontend editor

### `FunnelStepForm.vue` (`frontend/components/funnels/FunnelStepForm.vue`)
- `STEP_TYPES` array drives the picker (`:30-39`); each rendered via `t(\`funnels.steps.type.${ty}\`)` (`:504`).
- Per-type UI is a chain of `<template v-if / v-else-if="selectedType === '...'">` (`:509-894`). Add a SUBSCRIBE_TO_FUNNEL block.
- Submit narrows the model in the `onSubmit` switch (`:424-491`) — add a `case 'SUBSCRIBE_TO_FUNNEL'`.
- `siblingSteps` prop (`:21-23`) threads the funnel's OTHER steps for MENU target pickers. **This does NOT help for the cross-funnel step picker** — you need the TARGET funnel's steps, which the form must FETCH (lazy, like `ensureDefinitionsLoaded`/`ensureTagsLoaded`, `:181-212`).

### How MENU target picker works (the pattern to follow)
- `menuTargetOptions` (`:127-139`) maps siblingSteps → `{value: step.id, label: "N. <type>"}` + an End sentinel.
- Rendered with `<SearchableSelect :options="..." :show-value="false">` (`:707-716`).

### `SearchableSelect.vue` (`frontend/components/funnels/SearchableSelect.vue`)
- Option type `{ value, label, hint? }` (`:6`); props include `options`, `loading`, `invalid`, `show-value` (default true via `withDefaults`, `:27-29`), placeholder/loading/empty/no-matches text. v-model emits the option `value` string. Reusable as-is for BOTH the funnel picker and the target-step picker.

### What SUBSCRIBE_TO_FUNNEL needs in the form
1. **Funnel picker:** fetch active funnels via `useFunnelsStore().fetch('active')` or `useApi(\`/api/v1/projects/${projectId}/funnels?status=active\`)` → map to `SearchableSelect` options `{value: funnel.id, label: funnel.name}`. Exclude the current funnel? No — cycles allowed; but the current funnel won't be `active` while editing a draft, and a self-target is fine. Lazy-load on type-select (mirror `ensureTagsLoaded` `:201-212`).
2. **Target-step picker:** when a target funnel is chosen, fetch its detail via `funnelsStore.fetchOne(targetFunnelId)` → `.steps`, map to options (id + "N. type" label) plus a "start step (default)" sentinel mapping to `targetStepId=null`. Reacts to the funnel selection (a `watch`).
3. Submit emits `{ stepType: 'SUBSCRIBE_TO_FUNNEL', targetFunnelId, targetStepId }`.

### `types/funnel.ts` (`frontend/types/funnel.ts`)
- `StepType` union (`:10-18`) → add `'SUBSCRIBE_TO_FUNNEL'`.
- `FunnelStep` interface (`:49-72`) → add `targetFunnelId?: string | null; targetStepId?: string | null`.
- `CURRENT_DATE_TOKEN` mirror pattern (`:43`) is the precedent for any frontend-mirrored backend literal — SUBSCRIBE_TO_FUNNEL needs no literal mirror unless you add a "default start step" sentinel (frontend-only, like `MENU_END_TARGET` in the form `:59`).

---

## 11. (answered in §10) types/funnel.ts mirror

See §10 last block.

---

## 12. Tooling: test-run and preview

- **Test-run** (`POST /{funnelId}/test-run`, `FunnelController.java:113-118` → `FunnelService.testRun` `:340-355`): runs the engine normally via `insertExecution(depth=0)`. A SUBSCRIBE_TO_FUNNEL step "just works" under test-run — when the engine reaches it, it enrolls the target (a separate execution the sweep picks up). The author's own subscriber gets enrolled in the target too. **Caveat:** the child enroll is fire-and-forget and async (next sweep), so the test-run HTTP 200 only confirms the PARENT enroll; the child send is observable only later. Validation note: `testRun` calls `validateSteps` (`:351`) — so an invalid SUBSCRIBE config (bad target) is caught at test-run time too, same as activate.
- **Preview** (`POST /{funnelId}/steps/{stepId}/preview`, `FunnelService.previewStep` `:363-384`): SUBSCRIBE_TO_FUNNEL has NO message body. `isMessageStep` (`:386-388`) currently returns true only for SEND_MESSAGE/SEND_IMAGE/MENU; SUBSCRIBE falls into the `non_message` placeholder branch (`:382-383`) automatically. **No special handling needed** — just make sure `isMessageStep` does NOT include it (it won't). Frontend `FunnelMessagePreview` shows nothing for non-message steps (preview kind `non_message`).

---

## 13. Engine integration-test pattern

`backend/src/test/java/com/botfunnel/funnel/FunnelExecutionEngineIT.java`:
- Extends `AbstractIntegrationTest` (Testcontainers Mongo), in-memory JobRunr, `MockWebServer` for Telegram (`:54-79`).
- **Deterministic clock:** a `@Primary` `MutableClock CLOCK` (`:66-67`, `TestClockConfig` `@Import` `:59`); `CLOCK.advance(Duration)` to elapse delays (`:172-176`).
- Drives the engine by calling `engine.sweep()` directly (`:135` etc.), asserting `reload(execId).getStatus()` and `sentCount()` (MockWebServer request count, cumulative-aware `:97`).
- Seed helpers: `seedActiveSubscriber()`, `seedExecution(subId, nextRunAt, FunnelStep...)`, step builders `sendMessage(text)` (`:939`), `delay(value, unit)` (`:953`), graph builders `sendMessageStep(id, text, next)` (`:963`) + `seedGraphExecution(...)` (`:998-1013`, which sets `currentStepId = snapshot.get(0).getId()` like the factory).
- Race tests: `ConcurrencyTestUtils.parallelInvoke(2, () -> { engine.sweep(); })` (`:217-220`) for the two-replica CAS race; `concurrent cancel mid-tick` test (`:~224`).

**Pattern for a SUBSCRIBE_TO_FUNNEL engine IT:** seed a parent execution with a SUBSCRIBE step targeting an active funnel B; `engine.sweep()`; assert (a) parent advanced/completed, (b) a NEW execution row for funnel B + the subscriber exists with `currentStepId` = B's entry/targetStep, (c) `enrollDepth` = parent+1, (d) re-enter no-op when B already in-flight and `allowReEnter=false`, (e) graceful skip when B deleted/inactive. A second sweep then runs B and asserts its send via MockWebServer.

Other relevant tests: `FunnelStepExecutorTest.java` (unit, per-step `StepResult`), `FunnelServiceTriggerTypeTest.java` / `FunnelServiceEmitEventTest.java` (validation), `FunnelControllerIT.java`, `FunnelTestRunSendIT.java` (Phase 4 direct-enroll send harness — the closest IT to copy).

---

## 14. Risks / surprises (where the planned design meets reality)

1. **Factory only starts at step 0.** `insertExecution` hard-codes `currentStepId = snapshot.get(0)` (`FunnelExecutionFactory.java:93`). Starting the child at `targetStepId` requires a new factory parameter or a new factory method (e.g. `insertExecutionAt(... String startStepId)`). All existing callers pass no start-step. Mint the cursor from `targetStepId` (or fall back to step 0 when null). Validate `targetStepId` exists in the snapshot before insert, else fall back to step 0 or skip.

2. **Snapshot semantics: target resolved LIVE, parent snapshot is frozen.** The parent execution's `stepsSnapshot` is a deep copy frozen at parent enroll (Decision 3, `FunnelExecution.java:67`, `:112-116`). But the SUBSCRIBE step only stores `targetFunnelId`/`targetStepId` — the TARGET funnel's steps are fetched live at execution and deep-copied into the CHILD's snapshot at enroll time. So editing the target funnel after the parent was saved is fine (child gets the latest); deleting/deactivating the target between save and execution must be handled (§9 — graceful skip).

3. **`telegramBotId` pinning.** Every execution pins `telegramBotId` at creation (`FunnelExecution.java:53`, set in `insertExecution` `:82`). The child enroll must resolve the project's CONNECTED bot and pin its `telegramBotId` (same as the dispatcher `FunnelEventService.java:146-151`). The parent execution already HAS a `telegramBotId` — you can reuse `execution.getTelegramBotId()` directly (single-bot-per-project model), avoiding a bot lookup. Confirm there's exactly one CONNECTED bot per project (the codebase assumes it everywhere: `findByProjectIdAndStatus(..., CONNECTED)`).

4. **Project-scoping / tenant isolation on the target lookup.** The target funnel MUST be looked up scoped to `execution.getProjectId()` (fail-closed). `FunnelRepository.findById` is NOT project-scoped — you must verify `targetFunnel.getProjectId().equals(execution.getProjectId())` after load (mirror `FunnelService.requireFunnel` `:429-442` and `cancelExistingForPair`'s projectId scoping `:55-67`). Same at SAVE-time validation (the author can only target funnels in the same project — and `validateSteps` runs with the funnel's own projectId via the service).

5. **No new ExecutionStatus / no new status `$in` site.** Confirmed: SUBSCRIBE_TO_FUNNEL is fire-and-forget CONTINUE — the parent never parks, the child is a normal `running` execution. You do NOT need a new status, NOT touch the `waiting_for_reply` discipline, NOT add a `$in` literal. The `FunnelExecution` static-block assertion (`:34-43`) and every `status IN [running, waiting, waiting_for_reply]` site stay untouched. (This matches your explicit "NO new wait-state / NO park / NO resume" design.)

6. **Bean-cycle risk — the big one.** Phase 3 broke the cycle `FunnelEventService → FunnelTriggerServiceImpl → FunnelExecutionEngine → StepExecutor → FunnelEventService` by extracting `FunnelExecutionFactory` (no edge into engine/step-executor) — see `FunnelExecutionFactory.java:23-33` and `FunnelEventService.java:49-52`.
   - If SUBSCRIBE_TO_FUNNEL has `StepExecutor` inject `FunnelExecutionFactory` and call it directly: **SAFE.** The factory has zero edges into the engine/step-executor, so `StepExecutor → FunnelExecutionFactory` adds no back-edge and closes no cycle. This is exactly the design the factory was built to enable.
   - If instead you route through `FunnelEventService` (e.g. a new `enrollSpecificFunnel(...)` method on it): also safe, since `StepExecutor → FunnelEventService` already exists (`StepExecutor.java:71`) and `FunnelEventService → FunnelExecutionFactory` already exists. But `FunnelEventService` is trigger-matching-oriented; a direct factory call from `StepExecutor` is cleaner and matches the EMIT_EVENT-vs-testRun split. **Recommendation: inject the factory into `StepExecutor` and call it directly.** Do NOT inject `FunnelExecutionEngine` or `FunnelService` into `StepExecutor` (either would create a cycle).

7. **Re-enter collision on "return to caller's MENU" (B → A's MENU).** Because the re-enter index is `(funnelId, subscriberId)` filtered in-flight, a SUBSCRIBE targeting funnel A while the subscriber's A execution is still in-flight (parked on the MENU) will hit `DuplicateKeyException`. If A is `allowReEnter=false` → silent no-op (the "return" never happens). If `allowReEnter=true` → cancel-then-insert restarts A from `targetStepId` (A's MENU) as a FRESH execution. So the "return to caller's menu" pattern only works predictably when the caller funnel is `allowReEnter=true` (or you use the cancel-then-insert path). **This is a real semantic constraint to document** — it's not a bug, but the author must understand it.

8. **Cross-execution cycle bounding.** The per-tick step budget (`FunnelExecutionEngine.java:235-242`) bounds a synchronous loop within one tick, but a SUBSCRIBE chain A→B→A spreads across SEPARATE executions picked up by successive sweeps — NOT bounded by the step budget. The dispatcher's depth cap (`maxEnrollDepth=10`) is the intended cross-execution bound, but the factory does NOT apply it. **You must explicitly carry+check `enrollDepth` for SUBSCRIBE_TO_FUNNEL** (set child = `parent.enrollDepth + 1`; drop/skip when it would exceed `maxEnrollDepth`), otherwise an A↔B cycle runs unbounded (each enroll succeeds via the factory, sweep keeps creating fresh executions until the subscriber/bot gate trips — which it won't in a healthy system). This is the most important runaway-safety item.

9. **Validation needs a DB lookup (breaks the pure-static validateSteps pattern).** Every other `validateSteps` check is in-memory. The SUBSCRIBE target checks (exists / same project / active / targetStepId in target) require `funnelRepository` access — `validateSteps` is already an instance method (`FunnelService.java:633`) with the repository injected, so it CAN do the lookup, but it will be the first validation rule that touches the DB. Keep it self-excluded from the generic `requireExistingTarget` edge pass (the SUBSCRIBE step's `targetStepId` is cross-funnel; do NOT feed it to `requireExistingTarget(step.getNext()/timeoutTargetStepId)` which only checks `next`/`timeoutTargetStepId`, so it's already excluded — just don't add SUBSCRIBE's targetStepId to that pass).

10. **"active target at save" vs "active target at execution".** If you validate target-is-active at SAVE, an author can't reference a draft target (chicken-and-egg when building two funnels). Phase 4 test-run intentionally allows running DRAFT funnels. Consider validating only existence + same-project + targetStepId-resolves at save, and active-state at EXECUTION (graceful skip if not runnable). Decision to surface in the spec. Your spec text says target "must be active" — clarify whether that's enforced at save (blocks two-funnel authoring) or at execution.

---

## Quick reference: files to change

**Backend (funnel module):**
- `StepType.java` — add constant.
- `FunnelStep.java` — add `targetFunnelId`/`targetStepId` fields + copy in `copyOf`.
- `dto/FunnelStepDto.java` — add fields; `FunnelService.toSteps`/`toStepDto` map them.
- `StepExecutor.java` — new switch case; inject `FunnelExecutionFactory`; enroll child at depth+1 with start-step.
- `FunnelExecutionFactory.java` — new start-step param/overload (start at `targetStepId`); apply depth-cap if you put it here.
- `FunnelService.java` — `validateSteps` SUBSCRIBE case (DB lookup, new 422 codes); keep out of the generic edge pass; `isMessageStep` unchanged.
- `application.properties` — reuse `app.funnel.max-enroll-depth`; no new prop strictly required.

**Frontend:**
- `types/funnel.ts` — `StepType` union + `FunnelStep` fields.
- `components/funnels/FunnelStepForm.vue` — `STEP_TYPES`, new template block (funnel picker + target-step picker via lazy `fetchOne`), submit case.
- `i18n/locales/{uk,en}.json` — `funnels.steps.type.SUBSCRIBE_TO_FUNNEL`, form labels, `errors.funnels.funnel_subscribe_*`.

**Tests:**
- Engine IT in `FunnelExecutionEngineIT.java` style (clock + MockWebServer + sweep), or a new `FunnelSubscribeStepIT.java`.
- `FunnelStepExecutorTest.java` unit for the new `StepResult.cont()` + enroll side-effect.
- `FunnelService` validation unit (new codes), `StepTypeTest.java` enum pin.
