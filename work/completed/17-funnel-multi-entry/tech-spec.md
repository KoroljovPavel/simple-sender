---
created: 2026-06-13
status: approved
branch: dev
size: L
---

# Tech Spec: 17-funnel-multi-entry (Epic 8 — Phase 8, Multi-entry graph model)

## Solution

Replace the single flat trigger trio on `Funnel` (`triggerType`/`triggerValue`/`keywords`) with a
`List<Trigger>`, where each `Trigger = (triggerType, triggerValue, keywords, entryStepId)`. The
`on_start` trigger keeps `entryStepId = null` (starts at step 0, the main entry); `event` triggers
each carry an explicit `entryStepId` pointing at an existing step inside the funnel graph (mid-entry).

When a named event fires for a subscriber, a single redirect-or-start dispatcher decides:
- subscriber has an **in-flight** execution of THIS funnel (`status ∈ {running, waiting, waiting_for_reply}`)
  → **REDIRECT**: a new engine method (analogous to `resumeOnCallback`) claims the execution with a
  subscriberId-scoped CAS, sets `currentStepId = trigger.entryStepId`, unparks
  (`status=running`, `stepRunStatus=in_progress`), and runs `preStepGates` + `drive` immediately;
- no in-flight execution (absent / `completed` / `cancelled` / `failed`) → **START** a fresh execution
  at `entryStepId` via the existing `insertExecutionAt` primitive, honouring `allowReEnter`.

Semantics are **one execution per funnel per subscriber** — the redirect mutates the existing row, the
start relies on the `(funnelId, subscriberId)` re-enter unique index. The redirect target resolves
against the execution's `stepsSnapshot` (snapshot isolation, no funnel versioning).

`on_start` cross-funnel uniqueness is preserved at the DB level via **Variant A** (chosen with the user):
a denormalized nullable scalar `onStartTriggerValue` on `Funnel`, kept in sync from the `on_start`
element on every save, with a unique partial index `{projectId, onStartTriggerValue}` filtered
`{status:'active', onStartTriggerValue:{$exists:true}}`. A multikey unique index on the array cannot
express the constraint (partial filters are document-level, so they would wrongly bind `event` triggers
that Phase 3 deliberately allows to be shared across funnels).

The editor stays vertical-list (no canvas): a new "Triggers" panel manages N `event`-triggers, each
bound to an entry step via `SearchableSelect` over the funnel's own steps. No data migration — the
`funnels`/`funnel_executions` collections are wiped manually on first deploy (no production data).

## Architecture

### What we're building/modifying

**Backend — domain & persistence**
- **`Trigger`** (new embedded POJO) — `(String triggerType, String triggerValue, List<String> keywords, String entryStepId)`. Flat POJO, no `_class`. Snapshot-irrelevant (triggers drive START/REDIRECT, they are not copied into `stepsSnapshot`).
- **`Funnel.java`** — replace the flat trio (`triggerType`/`triggerValue`/`keywords`, L72-79) with `List<Trigger> triggers` + a denormalized `String onStartTriggerValue`. Replace the class-level partial-unique trigger index (L35-38) with the `onStartTriggerValue` shape; update the static-block literal assertions (L47-58).
- **`FunnelRepository.java`** — array-aware derived queries (`...TriggersTriggerType...`) for the event/tag/field fan-out and keyword scan; the `on_start` lookup moves to `onStartTriggerValue`.
- **Index reconciliation** — a `BeanPostProcessor` on `MongoDatabaseFactory` (extend or add alongside `FunnelTriggerIndexReconciliation`) that drops the old `projectId_triggerType_triggerValue_unique_active` index before `MongoTemplate` auto-creation, so the new `onStartTriggerValue` shape is laid down without `IndexKeySpecsConflict`.

**Backend — services & engine**
- **`FunnelService.java`** — `applyTrigger` becomes per-element validation over `List<Trigger>` + cross-trigger rules; `entryStepId` resolution against the funnel's step-id set; `onStartTriggerValue` sync; `checkTriggerConflict`/`saveHandlingTriggerConflict` over the array.
- **`FunnelExecutionEngine.java`** — new `redirectExecution(...)` method: subscriberId-scoped claim CAS accepting `running`/`waiting`/`waiting_for_reply`, sets cursor to `entryStepId`, unparks, runs now; preserves the `stillClaimed`/`stepRunStatus=in_progress` discipline.
- **`FunnelEventService.java`** — the single redirect-or-start dispatcher: matched-element re-scan to surface the trigger's `entryStepId`, the redirect-vs-start decision, reusing the three Phase-3 backstops and `insertExecutionAt`.

**Backend — API contract**
- **DTOs** — `TriggerDto` (new); `UpdateFunnelRequest`, `FunnelResponse`, `FunnelSummaryResponse` replace the flat trio with `List<TriggerDto>`. PATCH stays full-replace (no new endpoint).

**Frontend**
- **`types/funnel.ts`** — new `FunnelTrigger` interface; replace the flat trio on the three funnel types.
- **`stores/funnels.ts`** — thin pass-through, array payload.
- **Triggers panel** (new component) — clones `FunnelStepsList.vue`'s add/delete/select structure, mounts `FunnelTriggerSettings.vue` per trigger + a per-trigger entry-step `SearchableSelect` over the funnel's own steps; client-mirror of the duplicate-event-name guard.
- **`pages/.../funnels/[funnelId].vue`** — trigger array refs, `persist()` array block, per-element `triggerReady()`, debounced watch.

**Operations**
- One-time manual wipe of `funnels`/`funnel_executions` on first deploy; verify clean startup (reconciliation APPLIED, no `IndexKeySpecsConflict`).

### How it works

Event-firing flow (`POST /api/integrations/v1/events` or an `EMIT_EVENT` step):

1. `FunnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", eventName, originDepth)` resolves the bot + subscriber, applies the three backstops (depth cap → auto-enroll rate-limit → fan-out ceiling), and LIST-queries active funnels whose `triggers` array contains a matching `event` trigger.
2. For each matched funnel, **re-scan its `triggers[]`** to find the matched element and read its `entryStepId` (a multikey match returns the funnel, not which element matched — the same re-scan idiom Phase 3 uses for keywords).
3. Redirect-or-start decision per matched funnel (one collaborator, no forked writer):
   - **in-flight execution exists** (`status ∈ {running, waiting, waiting_for_reply}`, read via an `exists` probe extended from the `hasWaitingForReplyExecution` idiom) → `FunnelExecutionEngine.redirectExecution(executionId, subscriberId, entryStepId)`: claim-CAS (predicate scoped by `subscriberId`, accepts the three in-flight statuses + `stepRunStatus=pending`), set `currentStepId=entryStepId`, `status=running`, `stepRunStatus=in_progress`, then run `preStepGates`+`drive` now. The redirect resolves `entryStepId` against the execution's `stepsSnapshot`.
   - **no in-flight** (not enrolled / `completed`/`cancelled`/`failed`) → `insertExecutionAt(projectId, funnel, subscriberId, telegramBotId, depth, entryStepId)`; `allowReEnter` governs cancel-then-insert vs swallowed `DuplicateKeyException`.
4. Edge outcomes:
   - **CAS loss** (the row is mid-tick `in_progress`, a sweep owns it) → redirect is a **best-effort no-op** + greppable WARN; no artificial retry (the next natural event fires later). State is not corrupted.
   - **broken cursor** (`entryStepId` absent from `stepsSnapshot`) → terminal-fail **only that execution** (the Phase-6 tolerant-read precedent), the sweep and other executions continue.
5. The `on_start` path (`FunnelTriggerServiceImpl.fire`) keeps its semantics (always starts at step 0, `entryStepId=null`), but its **lookup must change**: the old `findByProjectIdAndTriggerTypeAndTriggerValueAndStatus` over the removed top-level fields is replaced by a lookup on `onStartTriggerValue` (Decision 6). Both the `EMIT_EVENT`/API-event paths and the on_start path route through the same single collaborator for redirect/start so there is no second writer.

**Loop bound under redirect (US Risk 4 proof).** A redirect **mutates an existing execution** — it does not call `insertExecutionAt`, so it neither creates a `funnel_executions` row nor resets `enrollDepth`; the execution carries its existing `enrollDepth` unchanged, and the depth cap therefore still bounds any *enroll-driven* expansion exactly as before. A redirect-driven cycle (`EMIT_EVENT` → trigger node → redirect → … ) is bounded by the SAME two guards the sweep relies on, because the redirect invokes the same `drive` loop: (a) the per-tick `app.funnel.max-steps-per-tick` budget trips `FUNNEL_STEP_BUDGET_EXCEEDED` → `terminate(failed)` for a budget-blowing loop with no park between steps; (b) the human-press gate — every `EMIT_EVENT`→trigger turn requires the subscriber to press a menu button, and a `MESSAGE` with buttons parks the execution (`waiting_for_reply`) and ends the tick. No artificial redirect counter is added (author-confirmed); the proof is that redirect adds no path around (a)+(b).

### Shared resources

No new heavy shared resources. The feature reuses existing singletons (the JobRunr `@Recurring` sweep,
`MongoTemplate`, the pinned-per-execution `telegramBotId`). The redirect/start decision is centralized in
one collaborator (`FunnelEventService`) to avoid a forked execution writer and the
`FunnelEventService → FunnelTriggerServiceImpl → StepExecutor → FunnelEventService` bean cycle.

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| None new | — | — | — |

## Decisions

### Decision 1: `List<Trigger>` model with explicit `entryStepId`
**Decision:** Replace the flat `triggerType`/`triggerValue`/`keywords` trio with `List<Trigger>`, each carrying its own `entryStepId` (null for `on_start`). Name the field `entryStepId`, mirroring the existing `targetEntryStepId` precedent.
**Rationale:** Multiple entries into one funnel is the core of the feature; an explicit `entryStepId` per trigger makes mid-graph entry safe without funnel versioning (the snapshot already contains every step).
**Alternatives considered:** Keep the flat trio + a separate `eventEntries` map — rejected, splits trigger state across two shapes and complicates validation/DTO.
**Serves:** US "Что делаем" / "Как должно работать" (1-2); AC "Воронка зберігає кілька тригерів".

### Decision 2: One execution per funnel per subscriber — redirect-or-start, not N parallel entries
**Decision:** A trigger firing either redirects the subscriber's existing in-flight execution of this funnel or starts a fresh one; never two concurrent executions of the same funnel for one subscriber.
**Rationale:** Matches the author's mental model (the funnel is one flow with several doors) and is cheaper — no index relax for parallel runs, reuses `insertExecutionAt` (start) and the `resumeOnCallback` mechanics (redirect).
**Alternatives considered:** N independent parallel executions — rejected (author intent + index/engine cost).
**Serves:** US "Технические решения"; AC "другого виконання не з'являється".

### Decision 3: Redirect via a new engine method analogous to `resumeOnCallback`
**Decision:** Add `FunnelExecutionEngine.redirectExecution(...)` that claims an in-flight execution with a CAS predicate bound by **both `subscriberId` and `funnelId`** (the anti-IDOR control, mirroring `claimForCallback`), accepting `status ∈ {running, waiting, waiting_for_reply}` (not only `waiting_for_reply` like `claimForCallback`), sets the cursor to `entryStepId`, unparks, and drives now. The `executionId` it operates on originates ONLY from the trusted in-flight `exists`/lookup probe inside the dispatcher (never from request input), and the CAS re-verifies the scope so a mismatched-subscriber redirect is a guaranteed no-op. Preserve the `stillClaimed`/`stepRunStatus=in_progress` CAS discipline so a concurrent sweep tick or cancel cannot double-run.
**Rationale:** `resumeOnCallback` already does exactly this for callbacks (claim → set cursor → run); a redirect is the same mechanic with a wider status predicate and an arbitrary target. Binding the CAS to `subscriberId`+`funnelId` (not trusting the passed id) keeps the Phase-2 anti-IDOR guarantee.
**Alternatives considered:** Reuse `resumeOnCallback` verbatim — rejected, its `claimForCallback` only matches `waiting_for_reply`+`pending`, missing `running`/`waiting`.
**Serves:** US "Как должно работать" (4); AC "виконання продовжується з гілки тригера ... парк знято".

### Decision 4: Redirect CAS-loss is a best-effort no-op + WARN, no artificial retry
**Decision:** When the redirect CAS loses (the row is `stepRunStatus=in_progress`, a sweep tick owns it), the redirect drops as a no-op for that execution with a greppable PII-free WARN. No synthetic retry is introduced.
**Rationale:** The natural re-fire of the event (human button press, repeated API event) retries later; an artificial retry adds complexity and a re-entrancy surface for no behavioural gain. State is never corrupted (CAS guarantees it).
**Alternatives considered:** Schedule a redirect retry — rejected by the author (US Risk 2).
**Serves:** US Risk 2; AC "best-effort no-op ... Стан виконання при цьому не псується".

### Decision 5: Broken cursor after redirect fails only that execution
**Decision:** The redirect resolves `entryStepId` against the execution's `stepsSnapshot`; if it does not resolve, terminal-fail only that execution (greppable broken-cursor marker), never the sweep or other executions.
**Rationale:** Snapshot isolation (Phase 1/2) means a redirect target may be absent from an older snapshot; the Phase-6 tolerant-read precedent already fails a single execution without crashing the tick.
**Alternatives considered:** Re-map against the live funnel — rejected (no versioning; would break snapshot isolation).
**Serves:** US Risk 3; AC "фейлиться лише це виконання ... sweep працюють далі".

### Decision 6: `on_start` uniqueness via denormalized `onStartTriggerValue` scalar + unique partial index (Variant A)
**Decision:** Add a nullable `Funnel.onStartTriggerValue` scalar, synced from the `on_start` trigger on every save, and a unique partial index `{projectId:1, onStartTriggerValue:1}` filtered `{status:'active', onStartTriggerValue:{$exists:true}}`. Keep `checkTriggerConflict`/`saveHandlingTriggerConflict` (pre-check + `DuplicateKeyException`→422) as defense-in-depth.
**Rationale:** A multikey unique partial index over `triggers.*` cannot express the constraint — `partialFilterExpression` filters documents, so a funnel with both an `on_start` and `event` triggers would have ALL its trigger keys (including `event`) bound by the unique index, wrongly colliding `event` values that Phase 3 deliberately shares across funnels. Denormalizing the single `on_start` value to a scalar restores a clean, race-proof DB guarantee and preserves the existing `saveHandlingTriggerConflict` pattern.
**Alternatives considered:** (B) service-only `checkTriggerConflict`, drop the DB index (US Risk 1 fallback) — viable but loses the DB-level race guarantee the project intentionally maintains. User chose A. (C) multikey unique partial on the array — rejected (wrongly binds `event` triggers).
**Serves:** US Risk 1; AC "обмеження «один активний on_start-вхід на (проєкт, тригер)» лишається в силі".

### Decision 7: Index reshape via `BeanPostProcessor`, never `ApplicationRunner`
**Decision:** Retire the old trigger index and lay down the `onStartTriggerValue` shape through a `BeanPostProcessor` on `MongoDatabaseFactory` (extend or sibling of `FunnelTriggerIndexReconciliation`), idempotent and log-but-never-throw, with greppable APPLIED/NO-OP markers.
**Rationale:** `auto-index-creation=true` builds indexes during `MongoTemplate` instantiation, before any `ApplicationRunner`; an index whose shape change is a partial-filter edit under a shared name throws `IndexKeySpecsConflict` (error 86) and crashes boot. The drop must happen before `MongoTemplate` initializes.
**Alternatives considered:** `ApplicationRunner` — rejected (runs too late, documented Phase 3 lesson).
**Serves:** AC "Застосунок стартує без помилки конфлікту індексів".

### Decision 8: Wipe, not migrate
**Decision:** No backfill migration; on first deploy, manually drop/clear `funnels` and `funnel_executions`, then verify a clean engine startup.
**Rationale:** No production data (Phase 6 precedent). A wipe also clears in-flight executions whose snapshots predate the schema change.
**Alternatives considered:** Raw-Document `$set` backfill (`FunnelStepIdBackfill` pattern) — unnecessary without prod data.
**Serves:** US "Ограничения" (Decision 4); US "Как должно работать" (6).

### Decision 9: Respect `allowReEnter` in the fresh-start branch
**Decision:** The START branch reuses the existing re-enter discipline — `allowReEnter=false` swallows the `DuplicateKeyException` (no-op), `=true` does atomic cancel-then-insert.
**Rationale:** Least surprise for the author, zero new code (the unique index already enforces it).
**Alternatives considered:** Always restart at `entryStepId` ignoring `allowReEnter` — rejected (surprises the author and contradicts the existing re-enter contract).
**Serves:** US "Технические решения"; AC "лише якщо це дозволяє allowReEnter".

### Decision 10: Mid-entry only for `event`; `on_start` stays the main entry
**Decision:** Only `event` triggers carry a non-null `entryStepId`. `keyword`/`tag_added`/`custom_field_set` remain start-from-beginning via Phase 3; `on_start` keeps `entryStepId=null`.
**Rationale:** Matches the competitor's "Trigger" node (an event listener) and keeps scope tight.
**Serves:** US "Ограничения" (Mid-entry лише для типу event).

### Decision 11: Redirect/start logic lives in one collaborator [TECHNICAL]
**Decision:** Centralize the redirect-vs-start decision and the matched-element re-scan in `FunnelEventService`, reused by both the API-event/`EMIT_EVENT` paths and (where relevant) the on_start path. The new engine `redirectExecution` is the only execution-mutation entry it adds.
**Rationale:** Avoids a forked execution writer and the `FunnelEventService → FunnelTriggerServiceImpl → StepExecutor → FunnelEventService` bean cycle that produced `FunnelExecutionFactory` in Phase 3. Justified purely by code structure; not derived from a user requirement.

### Decision 12: `keywords` moves per-trigger; matched-element re-scan surfaces `entryStepId` [TECHNICAL]
**Decision:** Each trigger carries its own `keywords` (the funnel-level `keywords` field is removed); the keyword code-scan and the event/tag/field match both re-scan the matched funnel's `triggers[]` to pick the right element and its `entryStepId`.
**Rationale:** Direct consequence of the `List<Trigger>` model — a multikey match returns the funnel, not the element. Extends the existing keyword re-scan idiom. Not a user requirement, a structural necessity.

### Decision 13: Frontend Triggers panel clones `FunnelStepsList`; reuses `FunnelTriggerSettings`
**Decision:** Build the Triggers panel by cloning `FunnelStepsList.vue`'s add/delete/select structure, mounting `FunnelTriggerSettings.vue` (already a portable node form, Phase 3 Decision 11) per trigger, plus a per-trigger entry-step `SearchableSelect` over the funnel's own steps (copy the `subscribeEntryOptions` builder; `null`/`__START__` = step 1).
**Rationale:** Maximal reuse of vetted primitives; `FunnelTriggerSettings` was explicitly designed to migrate unchanged as a node form.
**Serves:** US "Как должно работать" (1-2); AC "панель «Тригери» дозволяє додати/видалити event-тригери".

### Decision 14: Bound the trigger array and preserve keyword caps [TECHNICAL]
**Decision:** Cap `List<Trigger>` size with an `@Size` ceiling on the DTO + a service-level check (`funnel_step_invalid`/422), and preserve the existing per-trigger `keywords` size/length caps (`MAX_KEYWORDS=50`, `MAX_KEYWORD_LENGTH=64`) so the per-message keyword scan cannot grow unbounded as keywords move per-trigger (Decision 12). The array cap is pinned at the implementation step, aligned with the existing `max-fanout-per-event`/`max-steps` (50) order of magnitude. Each trigger's `entryStepId` and `triggerValue` use the existing slug regexes.
**Rationale:** The flat-trio model was implicitly bounded (one trigger, capped keyword list); the array reopens a DoS surface (array bloat, N×keyword scan cost, larger PATCH payloads). The caps close it with the existing validation idiom. Not a user requirement — a defensive consequence of the new model.

## Data Models

**`Trigger`** (new embedded POJO, `funnel/Trigger.java`):
```
record/POJO Trigger {
  String       triggerType;    // on_start | event | keyword | tag_added | custom_field_set
  String       triggerValue;   // event slug / tag slug / field key; "" for on_start
  List<String> keywords;       // only for keyword triggers
  String       entryStepId;    // null for on_start; existing step id for event mid-entry
}
```

**`Funnel`** (modified):
- Remove: `triggerType`, `triggerValue`, `keywords` (flat trio).
- Add: `List<Trigger> triggers`, `String onStartTriggerValue` (denormalized, nullable).
- Index change: drop `projectId_triggerType_triggerValue_unique_active`; add unique partial `{projectId:1, onStartTriggerValue:1}` filtered `{status:'active', onStartTriggerValue:{$exists:true}}`. Update static-block literal assertions to the new partial-filter literals.

**`FunnelExecution`** — unchanged (the redirect mutates `currentStepId`/`status`/`stepRunStatus` on the existing row; the `(funnelId, subscriberId)` re-enter unique index is unchanged).

**DTO** — `TriggerDto(triggerType, triggerValue, keywords, entryStepId)`; `UpdateFunnelRequest`/`FunnelResponse`/`FunnelSummaryResponse` carry `List<TriggerDto>` instead of the flat trio. `@JsonIgnoreProperties(ignoreUnknown=true)` preserved.

**Frontend (`types/funnel.ts`)** — `FunnelTrigger { triggerType; triggerValue?; keywords?; entryStepId? }`; replace the flat trio on `FunnelResponse`/`FunnelSummaryResponse`/`UpdateFunnelRequest`.

## Dependencies

### New packages
None. The user-spec forbids new backend dependencies and graph libraries (those are Phase 18-canvas).

### Using existing (from project)
- `FunnelExecutionFactory.insertExecutionAt(startStepId)` — the START-at-`entryStepId` primitive (already supports mid-graph entry).
- `FunnelExecutionEngine.resumeOnCallback` / `claimForCallback` / `stillClaimed` — the claim-CAS pattern the new `redirectExecution` extends.
- `FunnelEventService` backstops (depth cap, auto-enroll rate-limit, fan-out ceiling) + `enrollSpecificFunnel`'s fail-closed `projectId` discipline.
- `FunnelTriggerIndexReconciliation` `BeanPostProcessor` pattern — the index reshape mechanism.
- `SearchableSelect.vue`, `FunnelStepsList.vue`, `FunnelTriggerSettings.vue`, the `subscribeEntryOptions` builder — frontend primitives to reuse/clone.

## Testing Strategy

**Feature size:** L

### Unit tests
- `List<Trigger>` build/validation: duplicate `event_name` within a funnel rejected; at most one `on_start`; `entryStepId` must resolve to an existing step id (or be null); `event` trigger requires a non-null `entryStepId`; trigger-array `@Size` cap + per-trigger keyword caps enforced (Decision 14).
- `onStartTriggerValue` sync: derived correctly from the `on_start` element on save; null when no `on_start` value.
- `duplicate(funnel)`: resets triggers to a single `on_start` and clears `onStartTriggerValue` so a clone never inherits a conflicting on_start value (mirrors the Phase-4 duplicate-resets-trigger precedent).
- Redirect-vs-start decision: in-flight present → redirect path chosen; absent/terminal → start path chosen; `allowReEnter` honoured.
- Matched-element re-scan: the correct trigger element (and its `entryStepId`) is selected for a multikey match.
- (Home: `FunnelServiceTriggerTypeTest`, `FunnelEventServiceTest`, domain POJO tests.)

### Integration tests
- **Engine slow-lane** (`FunnelExecutionEngineIT`, `@Tag("slow")`): redirect of an in-flight execution incl. unpark of `waiting_for_reply`; redirect of `running`/`waiting`; **redirect mutates the existing row and inserts NO second `funnel_executions` document** (the core "one execution" invariant, Decision 2); fresh start at `entryStepId`; `allowReEnter` respected on a completed/cancelled run; broken-cursor terminal-fail isolated; **CAS-loss best-effort no-op — assert the row is untouched AND the WARN is emitted** (not just a boolean return); **mismatched-subscriber redirect is a no-op** (anti-IDOR, Decision 3); **cross-funnel isolation** — an event firing while the subscriber is in-flight in a *different* funnel STARTs the trigger's funnel and leaves the other execution untouched; **one event matching TWO funnels** — each funnel is decided independently (redirect for the in-flight one, start for the other) within the fan-out ceiling; **`EMIT_EVENT`→trigger self-loop terminates** at the per-tick budget / human-press gate (US Risk 4 proof as a regression gate, not only the live check).
- **Index** (`FunnelIndexesIT`, `FunnelTriggerIndexReconciliationIT`): the `onStartTriggerValue` partial-unique shape is present at runtime; reconciliation retires the old index without `IndexKeySpecsConflict` on a clean/wiped DB; idempotent re-boot is NO-OP.
- **Validation + DTO** (`FunnelControllerIT`, `FunnelServiceTriggerTypeTest`): duplicate `event_name` → 422 + business code; **intra-funnel "at most one on_start"** vs **cross-funnel duplicate on_start value** → 422 split out as distinct cases; multiple triggers → same entry step allowed and persisted; dangling `entryStepId` rejected; over-cap trigger array rejected; `List<TriggerDto>` round-trips through PATCH/GET.
- **Entry points** (`EventsControllerIT`, `ProcessTelegramUpdateJobTest`): an API event redirects/starts correctly; `handleStart`→`fire` still enrolls via the new `onStartTriggerValue` lookup; `EMIT_EVENT` → trigger-node loop drives end-to-end and respects the depth/budget backstops.
- **Frontend** — `FunnelTriggersPanel.vue` component spec (add/delete trigger, entry-step pick, duplicate-event-name guard); `funnel-editor.spec.ts` page wiring + persist; uk/en i18n parity asserted (no empty keys) in the component spec.

### E2E tests
None. The existing Playwright golden path covers only locale switching; the trigger panel is covered by
frontend component tests (`tests/pages/funnel-editor.spec.ts`, `tests/components/`), and the cross-cutting
engine behaviour by backend ITs. The live Telegram loop is a user-verified post-deploy check (no agent
can reproduce webhook↔engine↔Telegram without a live bot). This matches every prior funnel phase.

## Agent Verification Plan

**Source:** user-spec "Как проверить" section.

### Verification approach
Automated lanes run during implementation and QA: the backend slow-lane engine ITs (redirect/start/
allowReEnter/broken-cursor/CAS-loss), the index ITs (reshape without conflict), and the frontend editor
component/page tests (trigger add/delete, entry-step pick, duplicate-event-name guard). Beyond tests, the
agent verifies the API path with a real `curl` against `POST /api/integrations/v1/events` and a bash
scenario that drives `/start` → `waiting_for_reply` → event → asserts the same `funnel_executions` row's
cursor moved and the park was cleared. Per-task smoke checks are in each task's Verify-smoke / Verify-user.
The live Telegram "menu button → EMIT_EVENT → trigger node → redirect" loop and the manual wipe + clean
startup are described in the Post-deploy verification task and run on the first available environment.

### Tools required
`curl`, `bash` (API event + in-flight redirect scenarios), `pnpm` (frontend lint/typecheck/test),
Telegram MCP (the live end-to-end loop, post-deploy).

## Risks

| Risk | Mitigation |
|------|-----------|
| **Multikey unique partial index can't express on_start uniqueness** (US Risk 1) | Variant A: denormalized `onStartTriggerValue` scalar + unique partial index on it; multikey-over-array rejected because partial filters are document-level and would wrongly bind `event` triggers. `FunnelIndexesIT` proves the shape. |
| **Redirect CAS race vs sweep claim** (US Risk 2) | `redirectExecution` reuses the subscriberId-scoped claim CAS extended to `running`/`waiting`/`waiting_for_reply`; CAS loss (row mid-tick `in_progress`) → best-effort no-op + greppable WARN, no artificial retry, state intact. |
| **Broken cursor after redirect** (US Risk 3) | Redirect resolves `entryStepId` against `stepsSnapshot`; not found → terminal-fail only that execution (Phase-6 tolerant-read precedent), sweep + other executions continue. |
| **`EMIT_EVENT` ↔ trigger-node self-loop** (US Risk 4) | Proven in Architecture → "Loop bound under redirect": redirect mutates an existing execution (no new row, `enrollDepth` unchanged) and invokes the same `drive` loop, so the per-tick `max-steps-per-tick` budget (`FUNNEL_STEP_BUDGET_EXCEEDED`) + the human-press/menu-park gate still bound any cycle. No artificial redirect counter (author-confirmed). A self-loop termination IT is added. |
| **Index reshape crashes boot** | Reshape via `BeanPostProcessor` on `MongoDatabaseFactory`, never `ApplicationRunner`; idempotent, log-but-never-throw, APPLIED/NO-OP markers. |
| **fire()/dispatch error-isolation regression** | The new redirect/start path keeps the `try/catch(Throwable)` + swallow-and-WARN discipline of `fire`/`dispatchForSubscriber` so a redirect fault never fails the webhook job or 5xx the events API. New WARN markers log ids/codes only — never `event_name`/`triggerValue`/PII (anti log-injection, Phase-5 precedent). |
| **Unbounded `List<Trigger>` / keyword growth** (DoS, A04) | `@Size` cap on the trigger array + preserved per-trigger keyword caps (Decision 14); the public `/events` path stays behind the existing rate-limit + fan-out ceiling. |

## User-Spec Deviations

- **US Risk 1 (on_start uniqueness mechanism):** user-spec's stated fallback was "keep on_start uniqueness at the service-check level (drop the DB index)". The tech-spec instead adds a denormalized `onStartTriggerValue` scalar + a unique partial index on it (Variant A), preserving the race-proof DB-level guarantee. Reason: the multikey-over-array option cannot express the constraint, and the project intentionally maintains DB-level index guarantees. **→ APPROVED by user during tech-spec planning (chose Variant A).**

## Acceptance Criteria

Технические критерии приёмки (дополняют пользовательские из user-spec):

- [ ] PATCH/GET funnel round-trips `List<TriggerDto>`; invalid trigger payloads return 422 + business code (duplicate `event_name`, dangling `entryStepId`).
- [ ] The `onStartTriggerValue` unique partial index is created on a clean/wiped DB; the app starts with no `IndexKeySpecsConflict`; reconciliation logs APPLIED (first boot) / NO-OP (re-boot).
- [ ] `redirectExecution` mutates the existing `funnel_executions` row (no second execution); CAS-loss path is a no-op with a greppable WARN and intact state.
- [ ] Broken-cursor redirect terminal-fails only the affected execution; the sweep and other executions keep running.
- [ ] All backend slow-lane engine ITs + index ITs are green; frontend lint/typecheck/test + i18n (uk/en) parity green.
- [ ] No regressions in existing funnel tests.

## Implementation Tasks

### Wave 1 (независимые)

#### Task 1: Domain model `List<Trigger>` + denormalized `onStartTriggerValue` + index shape
- **Description:** Introduce the `Trigger` embedded POJO and replace `Funnel`'s flat `triggerType`/`triggerValue`/`keywords` with `List<Trigger> triggers` plus a denormalized nullable `onStartTriggerValue`. Swap the class-level partial-unique trigger index for the `{projectId, onStartTriggerValue}` shape (Decision 6) and update the static-block literal assertions. Result: the funnel domain compiles with the multi-trigger model and the new index annotation.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/Trigger.java`, `backend/src/main/java/com/botfunnel/funnel/Funnel.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/FunnelStep.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelStatus.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`

### Wave 2 (зависит от Task 1)

#### Task 2: Repository array-aware queries + index reconciliation
- **Description:** Convert the trigger lookups to array-aware derived queries (event/tag/field fan-out, keyword scan) and move the on_start lookup to `onStartTriggerValue`. Extend/add a `BeanPostProcessor` on `MongoDatabaseFactory` that retires the old trigger index before `MongoTemplate` auto-creation so the new shape lays down without `IndexKeySpecsConflict` (Decision 7). Result: queries match into the array and the app boots clean on a wiped DB.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*FunnelTriggerIndexReconciliationIT' --tests '*FunnelIndexesIT'` → green, reconciliation APPLIED
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelRepository.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerIndexReconciliation.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/Funnel.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelExecution.java`

#### Task 3: DTO contract — `List<TriggerDto>`
- **Description:** Add `TriggerDto` and replace the flat trigger trio with `List<TriggerDto>` on `UpdateFunnelRequest`, `FunnelResponse`, and `FunnelSummaryResponse`, keeping `@JsonIgnoreProperties(ignoreUnknown=true)`. PATCH stays full-replace. Result: the API contract carries the multi-trigger array.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/dto/UpdateFunnelRequest.java`, `backend/src/main/java/com/botfunnel/funnel/dto/FunnelResponse.java`, `backend/src/main/java/com/botfunnel/funnel/dto/FunnelSummaryResponse.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/Trigger.java`, `backend/src/main/java/com/botfunnel/funnel/dto/FunnelStepDto.java`

### Wave 3 (зависит от Task 1-3)

#### Task 4: Trigger validation + `onStartTriggerValue` sync + conflict pre-check + duplicate reset
- **Description:** Rework `applyTrigger` into per-element validation over `List<Trigger>` with cross-trigger rules (at most one `on_start`; reject duplicate `event_name` within the funnel; each `entryStepId` null or resolving to an existing step id; `event` requires a non-null `entryStepId`; trigger-array size cap + per-trigger keyword caps per Decision 14), keeping `entryStepId` out of the generic edge pass. Sync `onStartTriggerValue` from the `on_start` element on save, make `checkTriggerConflict`/`saveHandlingTriggerConflict` operate over the array against `onStartTriggerValue`, and update `duplicate` to reset triggers to a single `on_start` + clear `onStartTriggerValue` (Phase-4 precedent). Result: invalid trigger sets return 422 + business code; valid ones (incl. clones) persist without index conflict.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*FunnelServiceTriggerTypeTest' --tests '*FunnelControllerIT'` → green
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/Funnel.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelRepository.java`, `backend/src/main/java/com/botfunnel/funnel/dto/UpdateFunnelRequest.java`

#### Task 5: Redirect-or-start dispatcher + engine `redirectExecution` + on_start lookup
- **Description:** Add `FunnelExecutionEngine.redirectExecution` (claim CAS bound by `subscriberId`+`funnelId` accepting `running`/`waiting`/`waiting_for_reply`, set cursor to `entryStepId` resolved against `stepsSnapshot`, unpark, drive now; CAS-loss best-effort no-op + ids-only WARN per Decision 4; broken-cursor terminal-fail per Decision 5). In `FunnelEventService`, add the matched-element re-scan and the redirect-vs-start decision (in-flight → redirect; else `insertExecutionAt(entryStepId)` honouring `allowReEnter`), reusing the three backstops and preserving error-isolation. Move `FunnelTriggerServiceImpl.fire`'s on_start lookup to `onStartTriggerValue` (its enroll semantics stay step-0). Wire the API-event/`EMIT_EVENT` paths through the dispatcher. Result: a fired event redirects an in-flight run or starts fresh at the entry step, one execution per funnel per subscriber; `/start` still enrolls.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test -PrunSlow=true --tests '*FunnelExecutionEngineIT'` → redirect/start/allowReEnter/broken-cursor/no-second-execution cases green
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelEventService.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerServiceImpl.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionFactory.java`, `backend/src/main/java/com/botfunnel/funnel/StepExecutor.java`, `backend/src/main/java/com/botfunnel/api/EventsController.java`

### Wave 4 (фронтенд, зависит от Task 3 контракта)

#### Task 6: Frontend types, store, and Triggers panel
- **Description:** Add a `FunnelTrigger` interface to `types/funnel.ts`, replace the flat trio on `FunnelResponse`/`FunnelSummaryResponse`/`UpdateFunnelRequest`, and keep the `funnels` store a thin array pass-through. Build a vertical-list Triggers panel cloning `FunnelStepsList.vue`'s add/delete/select structure, mounting `FunnelTriggerSettings.vue` per trigger plus a per-trigger entry-step `SearchableSelect` over the funnel's own steps (copy the `subscribeEntryOptions` builder; `null`/`__START__` = step 1), with a client-mirror duplicate-event-name guard (advisory; the 422 is the backstop). Result: the trigger array is modelled end-to-end and the author can add/remove `event` triggers and pick each one's entry step; uk/en labels без порожніх ключів.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** open the funnel editor on localhost → Triggers panel renders, add/remove trigger works, entry-step picker lists the funnel's steps, duplicate event name is flagged
- **Files to modify:** `frontend/types/funnel.ts`, `frontend/stores/funnels.ts`, `frontend/components/funnels/FunnelTriggersPanel.vue`
- **Files to read:** `frontend/components/funnels/FunnelStepsList.vue`, `frontend/components/funnels/FunnelTriggerSettings.vue`, `frontend/components/funnels/FunnelStepForm.vue`, `frontend/components/funnels/SearchableSelect.vue`

#### Task 7: Editor page wiring for the trigger array
- **Description:** Rework `[funnelId].vue` to hold the triggers as an array (replacing the three flat refs), rework the `persist()` trigger block, the per-element `triggerReady()` gate, and the debounced trigger watch to operate per element, and mount `FunnelTriggersPanel`. Result: trigger edits autosave per element and switching a trigger's type alone never fires a premature 422.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm lint && pnpm typecheck && pnpm test tests/pages/funnel-editor.spec.ts` → green
- **Verify-user:** open the funnel editor → add an event trigger, pick an entry step, reload → trigger persisted
- **Files to modify:** `frontend/pages/projects/[projectId]/funnels/[funnelId].vue`
- **Files to read:** `frontend/components/funnels/FunnelTriggersPanel.vue`, `frontend/stores/funnels.ts`, `frontend/types/funnel.ts`

### Audit Wave

#### Task 8: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified in this feature (from decisions.md + tech-spec "Files to modify"). Review holistically for cross-component issues: duplicate execution-writer paths, bean-cycle compliance (redirect/start in one collaborator), error-isolation preserved on every new path, snapshot-isolation respected, architectural consistency with Phases 1-7. Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 9: Security Audit
- **Description:** Full-feature security audit. Read all source files created/modified in this feature. Analyze for OWASP Top 10 across all components: API-event input validation, fail-closed `projectId` discipline on redirect/start, anti-IDOR on the subscriberId-scoped redirect CAS, no PII in WARN logs, mass-assignment defense on the trigger DTOs. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 10: Test Audit
- **Description:** Full-feature test quality audit. Read all test files created in this feature. Verify coverage of the redirect/start branches, CAS-loss and broken-cursor isolation, index reshape, trigger validation 422 codes, and the frontend panel; check meaningful assertions and test-pyramid balance (unit vs slow-lane IT vs component). Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 11: Pre-deploy QA
- **Description:** Acceptance testing: run the full backend lane incl. `-PrunSlow=true` engine ITs and the index ITs, run frontend lint/typecheck/test + i18n parity, and verify every acceptance criterion from user-spec and tech-spec. Run the agent `curl`/bash API-event + in-flight-redirect scenarios from the Agent Verification Plan.
- **Skill:** pre-deploy-qa
- **Reviewers:** none

#### Task 12: Post-deploy verification (first available environment)
- **Description:** Live-environment verification on the first available environment (deployment via CI/CD is TBD — no standalone Deploy task; there is no pipeline/environment yet). Operational steps (guarded — confirm target is the intended non-prod environment and the "no production data" precondition holds before any destructive command): manually wipe `funnels`/`funnel_executions`, then confirm a clean engine startup (reconciliation APPLIED, no `IndexKeySpecsConflict`, sweep up without snapshot-deserialization errors). Then run the live loop: build a funnel "menu button → EMIT_EVENT SOME_TRIGGER" + an `event` trigger node `SOME_TRIGGER` → message; do `/start`, press the button, confirm the bot drives onto the trigger branch (the same in-flight execution redirected, park cleared) — tool: Telegram MCP. API-event entry: `POST /api/integrations/v1/events` with `X-API-Key` drops a subscriber mid-funnel — tool: curl/bash.
  Tools: Telegram MCP, curl, bash.
- **Skill:** post-deploy-qa
- **Reviewers:** none
