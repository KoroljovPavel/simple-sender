# Code Audit — Funnels (Лінійні воронки, Фаза 1)

Task 11 · full-feature cross-component review · read-only (no production code changed).
Methodology: `code-reviewing` skill, applied holistically across Tasks 1–10 rather than per-diff.

## Scope — files audited

Inventory reconciled from `decisions.md` (per-task summaries) + `tech-spec.md` ("Files to modify"
+ "Shared resources" table) against what is actually on disk. The on-disk funnel package added a few
files the tech-spec list did not name verbatim (split out `ExecutionStatus`/`FunnelStatus`/
`StepRunStatus`/`StepType` enums into own files; `FunnelTriggerService` interface + separate
`FunnelTriggerServiceImpl`; `dto/FunnelStepDto` + `FunnelSummaryResponse`; frontend `FunnelStepForm.vue`
+ `types/funnel.ts`). All reconciled and included.

**Backend — new (`com/botfunnel/funnel/`):** `Funnel`, `FunnelStep`, `FunnelExecution`, enums
`FunnelStatus`/`ExecutionStatus`/`StepRunStatus`/`StepType`, `FunnelRepository`,
`FunnelExecutionRepository`, `FunnelService`, `FunnelController`, `dto/*` (`CreateFunnelRequest`,
`UpdateFunnelRequest`, `FunnelResponse`, `FunnelSummaryResponse`, `FunnelStepDto`),
`VariableTemplateRenderer`, `FunnelExecutionEngine`, `StepExecutor`, `FunnelTriggerService` (+`Impl`).

**Backend — modified:** `bot/TelegramSender` (new `sendPhoto`); `subscriber/SubscriberCustomFieldsService`
(new) + `SubscriberCustomFieldsController` + `SubscriberService`/`SubscriberServiceImpl` (`findByChat`
lookup); `jobs/ProjectHardDeleteJob` (cascade); `application.properties` (funnel env vars).

**Shared resources read for compliance (not feature deliverables):** `TelegramSender`,
`SubscriberServiceImpl`, `SubscriberCustomFieldsService`, `BotRepository`/`ProjectRepository` usage,
`MongoTemplate`/JobRunr wiring.

**Frontend (Tasks 9–10):** `stores/funnels.ts`, `types/funnel.ts`,
`pages/projects/[projectId]/funnels/index.vue` + `[funnelId].vue`, `components/funnels/*`
(`CreateFunnelDialog`, `FunnelStepsList`, `AddStepDialog`, `EditStepDialog`, `FunnelStepForm`,
`FunnelTriggerSettings`), `i18n/locales/uk.json` + `en.json`.

**Depth note (honesty):** every file bearing on the six cross-component dimensions was read in full
(engine, executor, trigger service, sender, custom-fields service, subscriber service, funnel service,
enums, frontend types/store/form, i18n, cascade job). Model/DTO/repository files and the per-task
test suites were cross-checked structurally and against the per-task review records in `decisions.md`
(all tasks closed with code/security/test reviewers green), not re-litigated line-by-line — per the
task's instruction not to re-run per-file nits already handled in per-task reviews.

## Method — dimensions checked

The six cross-component dimensions mandated by the task (the relevant subset of the 11 `code-reviewing`
dimensions for an assembled feature): (1) duplicate resource initialization, (2) `TelegramSender`
reuse, (3) `SubscriberServiceImpl` / `SubscriberCustomFieldsService` reuse, (4) sole-writer + single
execution advancer, (5) atomic-claim idiom, (6) `.name()` lowercase enum literals. Plus the edge checks
called out in the task: i18n parity and frontend↔backend DTO/enum contract.

## Findings

### F1 — `major` — Funnel engine `SET_CUSTOM_FIELD` writes the field but emits no audit event

**Location:** `backend/src/main/java/com/botfunnel/funnel/StepExecutor.java:131-147`
(`setCustomField`), against the contract in
`backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsService.java:64-94`
(`setOne` Javadoc) and `decisions.md` Task 4 NOTE-for-Task-6.

**Description:** `StepExecutor.setCustomField` calls `customFieldsService.setOne(...)` and nothing else.
`setOne` deliberately does **not** write the `subscriber_custom_field_set` audit event — its Javadoc
states *"This method does NOT write the audit event. The caller (engine) is responsible for the
sole-writer audit via `SubscriberService.recordCustomFieldsSet`"*, and the Task 4 entry in `decisions.md`
records the same hand-off explicitly. `recordCustomFieldsSet` is called by exactly one caller in the
codebase — `SubscriberCustomFieldsController:97` (the HTTP PATCH path). The funnel engine never calls it.

Consequences / why it matters across components:
- **Audit-trail gap:** a funnel-driven custom-field change leaves no `subscriber_custom_field_set` row,
  so the subscriber event history is incomplete for engine-originated mutations (an HTTP PATCH of the
  same field is recorded; a funnel step is not).
- **Intra-engine inconsistency:** the engine's `ADD_TAG`/`REMOVE_TAG` steps go through
  `SubscriberServiceImpl.addTag/removeTag`, which **do** write `subscriber_tag_added`/`removed` audit
  events (`SubscriberServiceImpl.java:252,265`). Only `SET_CUSTOM_FIELD` is silent — an asymmetry, not a
  deliberate decision.

This is not a sole-writer *violation* (the engine does not write `subscriber_events` directly — sole-writer
holds), but it is the documented-contract counterpart: the required sole-writer call was simply omitted.

**Suggested fix (for a follow-up fix task):** after a successful `setOne`, have the engine call
`subscriberService.recordCustomFieldsSet(projectId, subscriberId, oldValues, newValues)` with the
single-key old/new maps. `setOne` currently returns `void` and does not expose the prior value, so the
fix needs either (a) a `setOne` overload/return that yields old+new, or (b) the executor reads the
subscriber's `customFields.<key>` before the set and builds the one-entry maps itself. Mirror the
controller's aggregate-once semantics (`SubscriberCustomFieldsController:85-97`). Keep it on the
sole-writer path — do not write `subscriber_events` from the funnel package.

### F2 — `minor` — Engine advances via unconditional full-document `save()`, racing concurrent cancels

**Location:** `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java:206-241`
(`persistProgress` / `scheduleDelay` / `complete` / `terminate`, all `mongoTemplate.save(exec)`), vs. the
terminal cancellers `FunnelService.delete` (`FunnelService.java:146-152`) and
`FunnelTriggerServiceImpl.cancelActiveFor` / `cancelExistingForPair`
(`FunnelTriggerServiceImpl.java:153-183`).

**Description:** The execution *claim* is a correct single `findAndModify` CAS (Decision 4) and guarantees
at-most-once claiming. But once claimed, the engine commits progress with **full-document
`mongoTemplate.save(exec)`**, which is *not* predicated on the row still being `in_progress`/`running` —
it blindly replaces the document by `_id`. The cancellers match on `status ∈ {running, waiting}`
(any `stepRunStatus`), so they match an already-claimed-and-`in_progress` execution (the claim only flips
`stepRunStatus`, leaving `status = running`). If a cancel lands in the window *after* the claim and
*before* the engine's same-tick `save`, the engine's `save` clobbers the `cancelled` status and
resurrects the execution.

- **`/stop` (`cancelActiveFor`):** self-heals — the webhook worker also flips the subscriber to
  `UNSUBSCRIBED`, so the *next* sweep tick's pre-step status gate
  (`FunnelExecutionEngine.java:135`) cancels it. Low impact (at most a couple extra sends in the racing
  tick).
- **Funnel delete (`FunnelService.delete`):** does **not** self-heal — the subscriber stays `ACTIVE` and
  the bot stays `CONNECTED`, so the resurrected execution keeps running to completion against its
  self-contained `stepsSnapshot` even though the funnel document has been deleted. The reassuring comment
  at `FunnelService.java:142-145` ("a running|waiting row flipped to cancelled here will fail the engine's
  claim predicate — no double-processing") is true only for executions **not yet claimed in the current
  tick**; it overstates the guarantee for one already mid-tick.

This is the same *class* of trade-off the engine documents for crash-mid-tick (at-most-once over
liveness), but the concurrent-cancel-clobber and the deleted-funnel-keeps-sending paths are not covered
by that note. The window is narrow (post-claim → final save of one tick, ~ms) and the operations are
infrequent, hence `minor`.

**Suggested fix (for a follow-up fix task):** make the engine's in-tick writes conditional on still
holding the claim — replace the full-document `save(exec)` calls with a `findAndModify`/`updateFirst`
predicated on `_id` + `stepRunStatus = in_progress` (and, for the terminal/complete writes, `status ∈
{running, waiting}`), so a concurrent cancel deterministically wins and the engine's write becomes a
no-op it can detect. Alternatively, re-read+verify status before `complete`. Then soften the
`FunnelService.delete` comment to match the real guarantee.

### Dimension-by-dimension verdict

1. **Duplicate resource initialization — no issues.** No funnel-package file constructs a Telegram
   client / `RestClient`, Mongo client / `MongoTemplate`, JobRunr config, or `HttpClient`
   (grep for `new RestClient` / `MongoClients` / `new MongoTemplate` / `HttpClient.newBuilder` /
   `@EnableJobRunr` in `com/botfunnel/funnel/` → none). `FunnelExecutionEngine`, `FunnelService`,
   `FunnelTriggerServiceImpl`, `StepExecutor` all constructor-inject the shared singletons
   (`MongoTemplate`, `TelegramSender`, `SubscriberService`, `SubscriberCustomFieldsService`,
   `BotRepository`, `ProjectRepository`) per the "Shared resources" table.

2. **`TelegramSender` reuse (Decisions 10/13) — no issues.** `sendText` and the new `sendPhoto` both
   funnel into a single private `send(botId, chatId, endpoint, contentFields, ownerId)`
   (`TelegramSender.java:158-195`) — the CONNECTED filter, 5xx backoff, 429 loop, 30s deadline, audit
   emission, Decision-4 subscriber hook, and per-attempt token-decrypt are byte-identical across both
   endpoints. No second/duplicate send path. `StepExecutor` routes `SEND_MESSAGE → sendText`,
   `SEND_IMAGE → sendPhoto` (`StepExecutor.java:71-72,95,119`). Backend never dereferences `imageUrl`
   (no SSRF) — Telegram fetches it.

3. **`SubscriberServiceImpl` / `SubscriberCustomFieldsService` reuse (Decision 11) — one finding (F1).**
   Tag mutations and subscriber lookup go through the shared services (`StepExecutor.java:75,79`;
   `FunnelTriggerServiceImpl.java:95,144` uses `subscriberService.findByChat`, never the repository).
   `SET_CUSTOM_FIELD` reuses `SubscriberCustomFieldsService.setOne` correctly but omits the paired
   sole-writer audit call — see **F1**. No duplicated CRM / lookup / connection logic.

4. **Sole-writer + single execution advancer (Decision 11 / Decision 4) — F1 (audit gap) + F2
   (advance race).** `SubscriberServiceImpl` remains the *sole writer* of `subscriber_events` /
   `subscriber_custom_field_set`: a repo-wide grep shows no `SubscriberEvent` / `subscriber_events` write
   anywhere in `com/botfunnel/funnel/` (the only references outside the `subscriber/` package are
   `ProjectHardDeleteJob`, which *deletes* the collection during cascade — not a CRM write). The engine
   does not replicate the validate→update→record cycle. Caveat: the funnel-driven custom-field audit
   event is *missing* (F1), and the engine's execution-advance writes are not claim-conditional (F2).

5. **Atomic-claim idiom (Decision 4) — no issues.** The claim is exactly one `findAndModify` CAS
   (`FunnelExecutionEngine.java:193-203`): criteria `_id` + `status ∈ {running,waiting}` +
   `nextRunAt ≤ now` + `stepRunStatus = pending`; update `stepRunStatus = in_progress`; `returnNew(true)`.
   No two-step select-then-claim, no parallel/ad-hoc claim path anywhere. Per-step
   `pending → in_progress → done`; consecutive non-Delay steps run under one claim per tick by design
   (documented). Mirrors the `ProcessTelegramUpdateJob.handleStart` idiom.

6. **`.name()` lowercase enum literals (Decisions 4/14) — no issues.** `FunnelStatus`
   (`draft/active/paused`), `ExecutionStatus` (`running/waiting/completed/...`), `StepRunStatus`
   (`pending/in_progress/done`) are all lowercase. Every Mongo criterion/update writes `.name()` string
   literals — `FunnelExecutionEngine` (claim/sweep), `FunnelTriggerServiceImpl`
   (cancelActiveFor/cancelExistingForPair), `FunnelService.delete`. No `.ordinal()` and no numeric-status
   comparison exists in `com/botfunnel/funnel/` (the `getStatus() ==` sites are in-memory enum compares,
   not persistence). `FunnelExecution.status`/`stepRunStatus` are typed enum fields, which Spring Data
   persists as their `.name()` — byte-consistent with the literals (Task 1 added class-load asserts for
   this). `StepType` is uppercase by design (a step kind, not a status; outside Decision 14's scope) and
   matches the frontend literally.

**Edge checks:**
- **i18n parity — no issues.** `funnels.*` + `errors.funnels.*` = 92 keys in each locale, set-equal
  (no keys only-in-uk / only-in-en); full-file parity also holds (489 leaf keys each, symmetric diff
  empty).
- **Frontend↔backend contract — no issues.** `types/funnel.ts` mirrors the backend byte-for-byte:
  `FunnelStatus = 'draft' | 'active' | 'paused'`, `StepType` = the six `SEND_MESSAGE`…`SET_CUSTOM_FIELD`
  literals, `FunnelResponse.deepLink: string | null`, and the full-`steps`-array PATCH contract. The
  store reads `projectId` from the route and re-throws so components map errors.

## Verdict

Two cross-component findings, **both non-blocking for the audit itself** (this task writes no code):
one **major** (F1 — missing `subscriber_custom_field_set` audit on funnel-driven custom-field sets,
inconsistent with the tag steps and the documented `setOne`→`recordCustomFieldsSet` hand-off) and one
**minor** (F2 — engine's non-claim-conditional `save()` can be clobbered by a concurrent cancel; the
funnel-delete path then keeps a deleted funnel's execution running). The remaining five dimensions plus
both edge checks are clean. The feature's shared-resource discipline, atomic-claim idiom, and enum
literal conventions are consistently applied across all ten tasks.

**Recommendation:** open a small follow-up fix task covering F1 (required — audit completeness) and F2
(should-fix — narrow race + overstated comment). Neither requires spec changes; both are implementation
gaps against the existing tech-spec idioms.
