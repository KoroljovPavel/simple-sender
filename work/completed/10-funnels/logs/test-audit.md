# Test Audit — Feature 10-funnels (Phase 1, linear funnels)

**Scope:** Cross-feature test-quality audit of Tasks 1–10 (+ Task 15 fixes). Read-only deliverable — no
source/test edits. Assessed against tech-spec "Testing Strategy" and the `test-master` standards
(`test-quality-review.md`, `integration-tests.md`).

**Date:** 2026-06-03
**Auditor:** main agent (Task 13)
**Verdict (preview):** ✅ **Ready for pre-deploy QA (Task 14).** No HIGH gaps. 0 critical / 0 high /
2 medium / 3 low. The two MEDIUM items are coverage improvements, not deploy blockers.

---

## 1. Test inventory (read in full)

### Backend — unit
| File | Focus |
|------|-------|
| `funnel/VariableTemplateRendererTest.java` | 27 tests — substitution, `{{`/`}}`, Double/Boolean/Instant render, HTML/MarkdownV2/None escaping, full charset, brace edge cases |
| `funnel/FunnelStepExecutorTest.java` | 13 tests — per-type executors, trim+continue, caption escape+trim, Delay units, SET_CUSTOM_FIELD (valid/invalid/deleted-skip/audit/null-old), terminal mapping |
| `funnel/FunnelStatusEnumTest.java` | lowercase enum literal contract (Decision 14) + StepType uppercase discriminator |
| `funnel/FunnelStepTest.java` | `copyOf` deep-copy independence (every field, both directions) + null |
| `bot/TelegramSenderTest.java` (sendPhoto block) | 8 sendPhoto tests — success, null-caption omit, 429-retry, 403→blocked, 400-chat-not-found→deleted, 400-other→OTHER, 5xx-exhausted, token-no-leak |
| `subscriber/SubscriberCustomFieldsServiceTest.java` | 11 tests — validateAndNormalize, applyAll (set/unset/mixed/empty), setOne |

### Backend — integration (`AbstractIntegrationTest`: Testcontainers Mongo + JobRunr in-memory + MockWebServer)
| File | Focus |
|------|-------|
| `funnel/FunnelExecutionEngineIT.java` | 16 ITs — happy paths, at-most-once (re-run, crash-before-flip, 2-replica race), F2 cancel-race (engine-write + terminal-write), send-fail matrix, pre-send gates, observability, batch-cap+WARN, recurring-job registration. `@Primary` local MutableClock |
| `funnel/FunnelTriggerServiceIT.java` | 14 ITs — snapshot+pinned bot, deep-copy, exact/empty/null match, no-op, draft-not-matched, re-enter false/true, cancelActiveFor, no-bot/no-sub, **fire() error-isolation (HIGH)**, **reactivation-order (HIGH)** |
| `funnel/FunnelControllerIT.java` | 24 ITs — CRUD, status filter, order-rewrite, delete+cancel, activate/pause, trigger-conflict (pre-check + index race), uniform-404 matrix, bean/per-type validation (422), max-steps |
| `funnel/FunnelIndexesIT.java` | annotation-driven index metadata: `(projectId,status)`, partial-unique trigger (active), `(status,nextRunAt)`, `(projectId)`, re-enter partial-unique `$in[running,waiting]`; empty-trigger uniqueness |
| `bot/TelegramSenderSubscriberHookIT.java` (sendPhoto) | 403→blocked / chat-not-found→deleted subscriber-flip hook |
| `webhook/ProcessTelegramUpdateJobTest.java` | worker dispatch — `/start`→`fire()`, `/stop`→`cancelActiveFor`, payload variants, group/plain/unknown, error-path token-scrub, re-entry guard |
| `jobs/ProjectHardDeleteJobIT.java` | cascade removes funnels+executions; cross-project isolation |

### Frontend — Vitest
| File | Focus |
|------|-------|
| `tests/stores/funnels.spec.ts` | 11 tests — fetch/filter/create/update/fetchOne/activate/pause/delete + re-throw-on-error contract |
| `tests/pages/funnel-editor.spec.ts` | 5 tests — 422-code→inline error (+ litmus: mapped≠generic), 404→redirect, 500→retry banner, pause flow |
| `tests/components/AddStepDialog.spec.ts` | 8 tests — per-type form render, validation blocks emit, valid emits per type |
| `tests/components/EditStepDialog.spec.ts` | (shares `FunnelStepForm`) edit-path render/emit |
| `tests/components/FunnelStepsList.spec.ts` | move bounds/reorder emit, inline-confirm delete, empty-state CTA |
| `tests/components/FunnelTriggerSettings.spec.ts` | deep-link render, bare `/start`, copy+feedback, no-bot hint |
| `tests/components/funnels/CreateFunnelDialog.spec.ts` | create dialog validation + navigate |

### E2E + i18n gate
| File | Focus |
|------|-------|
| `e2e/funnels.spec.ts` | golden-path build→reorder→trigger/deeplink→activate + 422 inline page-test. **Graceful `test.skip` without live backend** |
| `tests/i18n/required-keys.spec.ts` | hard-required key set present in `uk.json`+`en.json` |
| `scripts/check-locales.mjs` | uk↔en set-equality parity gate (prototype-pollution safe) |

All target test files exist — **no missing-file coverage-gap findings.** (Task 10's `e2e/funnels.spec.ts`
is present.)

---

## 2. Coverage vs tech-spec "Testing Strategy"

| Spec scenario | Test | Status |
|---|---|---|
| **Unit** | | |
| Renderer: substitution / `{{}}` / Double-Instant / HTML+MarkdownV2 charset | `VariableTemplateRendererTest` (27) | ✅ covered |
| Step-executors: SendMessage trim+continue+WARN | `sendMessageOver4096TrimsAndContinues` | ✅ |
| SendImage caption escape+trim 1024 | `sendImageCaptionEscapedAndTrimmedTo1024` | ✅ |
| Delay nextRunAt calc | `delayComputesDurationFromUnit` (MIN/HOUR/DAY) | ✅ |
| Delay `<1хв→422` | — | ⚠️ partial (L1) |
| AddTag/RemoveTag/SetCustomField valid+invalid+deleted-skip | executor tests | ✅ |
| Trigger-matcher exact / empty / no-match | `FunnelTriggerServiceIT` exact/null/non-empty/no-op | ✅ |
| State machine running→waiting→running→completed; cancel; failed | engine IT (delay-resume, cancel, fail) | ✅ |
| Funnel activation validation ≥1 step / fields / conflict | `FunnelControllerIT` activate-* | ✅ |
| Frontend Vitest editor components + i18n parity | component/store/page specs | ✅ |
| **Integration** | | |
| Trigger → execution end-to-end | `stepsRunEndToEnd` | ✅ |
| Consecutive non-Delay in one tick | `consecutiveNonDelayStepsRunInSingleTick` | ✅ |
| Delay → nextRunAt → resume next sweep | `delaySetsNextRunAtAndResumesOnNextSweep` | ✅ |
| Idempotency: re-run claimed step | `reRunningClaimedStepDoesNotDuplicateSend` | ✅ |
| Idempotency: **crash after send / before done-flip** | `crashAfterSendBeforeDoneFlipDoesNotResend` | ✅ |
| Atomic-claim race, 2 replicas | `atomicClaimRaceAcrossTwoReplicasRunsStepOnce` (real Mongo + `parallelInvoke`) | ✅ |
| **fire() error-isolation (HIGH)** | `fireErrorIsolationSwallowsAndDoesNotFailWebhook` (direct + webhook worker, raw_update stays DONE) | ✅ |
| **Reactivation-order (HIGH)** | `reactivationOrderingFirstSendNotSelfCancelled` | ✅ |
| **SendImage failure-matrix** | `sendImageInvalidUrlFailsExecution` (400-other→failed) + `sendImageBlockedFlipsAndCancels` (403→cancelled+flip) | ✅ |
| **Observability no-payload** | `stateTransitionsEmitNamedLogConstantsWithoutPayload` (asserts log constants present + PII absent) | ✅ |
| `/stop` cancelActiveFor | `cancelActiveForCancelsRunningAndWaiting` + worker `/stop` test | ✅ |
| delete → cancel+remove | `deleteReturns204AndCancelsActiveExecutions` | ✅ |
| paused → no new + active drain | only `draftFunnelIsNotMatched` exists | ⚠️ partial (L2) |
| Re-enter false→ignore / true→cancel+restart | `reEnterFalse…` / `reEnterTrue…` | ✅ |
| Send-fail 403→cancelled / 5xx→failed; status gate; bot-pin | `sendMessageTerminalErrorFailsExecution`, `inactiveSubscriberCancelsBeforeSend`, `pinnedBotNotConnectedFailsExecution` | ✅ |
| Snapshot decoupled from edits | `snapshotIsDeepCopyDecoupledFromFunnelEdits` + `FunnelStepTest` | ✅ |
| requireOwned + cross-project uniform 404 | `crossProjectFunnelReturnsUniform404`, malformed-id, foreign-project | ✅ |
| ProjectHardDeleteJob cascade funnels+executions | `cron_cascadeRemovesFunnelsAndExecutions` + isolation | ✅ |
| sub-minute `@Recurring` registered | `subMinuteRecurringJobIsRegistered` (asserts `PT30S`) | ✅ |
| Worker `startCommandFiresFunnelTrigger` | `startPrivateWithPayload_callsSubscriberAndFunnel…` (+variants) | ✅ |
| **E2E** | | |
| Golden-path build/move/delete/trigger/deeplink/activate | `funnels_goldenPath_buildAndActivate` | ⚠️ skip-by-default (M2) |
| 422 activation inline page-test | E2E `funnels_activation422` **and** Vitest `funnel-editor.spec` (always-on) | ✅ |

---

## 3. Concurrency invariants (рушій)

**Strong — exercised against real Mongo, not mocks.**

- **Atomic-claim / at-most-once:** `atomicClaimRaceAcrossTwoReplicasRunsStepOnce` runs two concurrent
  `sweep()` calls via `ConcurrencyTestUtils.parallelInvoke(2, …)` (same pattern as
  `SubscriberExportConcurrencyIT`/`BotConnectRaceIT`) against Testcontainers Mongo and asserts exactly
  **one** send (`sentCount()==1`) + `completed`. This is the real `findAndModify` CAS, not a mock.
- **Idempotency — two distinct scenarios, as the spec demands:**
  1. `reRunningClaimedStepDoesNotDuplicateSend` — terminal execution not re-picked.
  2. `crashAfterSendBeforeDoneFlipDoesNotResend` — row forced to `in_progress` (claimed but not
     advanced); next sweep cannot re-claim (predicate requires `pending`) → `sentCount()==0`. This is
     the genuine crash-window scenario, not just a repeat call.
- **F2 claim-conditional advance (Task 15):** `concurrentCancelMidTickWinsOverEngineWrite` and
  `concurrentCancelMidTickWinsOverTerminalWrite` drive a cancel from *inside* the Telegram dispatch
  (engine mid-tick, claim held) and assert the engine's CAS no-ops so the cancel wins (not resurrected /
  not clobbered to `failed`). Real race via MockWebServer dispatcher. `complete()`/`scheduleDelay()`
  CAS paths are documented as unreachable under a synchronous race (no I/O precedes them) and verified
  structurally — reasonable and explicitly justified in-test.
- **At-most-once mechanics:** single `findAndModify` claim verified end-to-end; `.name()` lowercase enum
  literals pinned by `FunnelStatusEnumTest` + `FunnelIndexesIT` (partial-filter `$in[running,waiting]`).

**Status: OK.** No false-confidence (no concurrent invariant covered only by mocks).

---

## 4. Assertion quality

**Uniformly high. No tautologies, no empty/no-assertion tests, no mock-return litmus failures.**

Representative strengths:
- Engine ITs assert real state: `ExecutionStatus`, `stepRunStatus`, `currentStepIndex`, `nextRunAt`
  (exact `BASE.plus(5min)`), and `sentCount()` deltas — not "didn't throw".
- `fireCreatesExecutionWithSnapshotAndPinnedBot` pins `nextRunAt` to a `[before, after]` window (kills a
  null/epoch/far-future mutant) rather than mere non-null.
- `funnel-editor.spec` mapped≠generic litmus proves the `errors.funnels.<code>` branch actually resolves
  a distinct localized string (deleting the mapping fails the test).
- `ProcessTelegramUpdateJobTest.eventWriteBeforeStatusFlip_orderingInvariant` uses a live-status sentinel
  to prove ordering — a genuine behavioral invariant, not a call-count.
- `snapshotIsDeepCopyDecoupledFromFunnelEdits` asserts `isNotSameAs` (kills shallow-copy mutant).

Note (informational, L3): `FunnelStepExecutorTest` mocks 4 collaborators — by the strict test-master
"3+ deps → integration" rule this is a heavy-mock unit. **Justified:** the executor is pure per-type
dispatch (each collaborator is a boundary), assertions check real outcomes/normalized values, and the
*same* paths are re-covered through real Mongo+Telegram in `FunnelExecutionEngineIT`. Not a defect.

**Status: OK.**

---

## 5. Pyramid balance

- **Pure logic → unit:** renderer charset/escaping (27) and step computation are unit. ✅
- **Concurrency / persistence / cadence → IT (Testcontainers, not mocks):** claim race, crash window,
  cascade, index metadata, recurring registration all real-Mongo ITs. ✅
- **UI golden-path → 1 E2E + cheap 422 page-test:** present; 422 additionally covered always-on in
  Vitest. ✅
- **Redundancy check:** executor logic appears at both unit and IT levels, but they catch different
  failures (unit = per-type branch/trim/escape; IT = claim/persist/real-HTTP). Not redundant. The
  renderer is exercised inside the executor unit *and* has its own exhaustive unit — acceptable
  (executor only checks integration trim/escape, renderer unit owns the charset).

**Status: OK.** No inversion, no critical invariant left to mocks.

---

## 6. i18n parity gate

- `check-locales.mjs` — real symmetric set-equality between `uk.json`/`en.json`, `process.exit(1)` on
  any diff, `__proto__`/`constructor`/`prototype` filtered. **Genuinely fails on desync.** ✅
- `required-keys.spec.ts` — asserts a hard-required set exists in *both* locales.
- All Task-10 keys physically exist and are parity-checked: `funnels.editor/steps/trigger.*` +
  `errors.funnels.{funnel_no_steps,funnel_step_invalid,funnel_step_limit_reached,funnel_trigger_conflict,
  funnel_invalid_trigger_value,funnel_invalid_state}` present in `uk.json` (and, by the parity gate, in
  `en.json`).

**Finding M1 (medium):** `required-keys.spec.ts` pins only the **Task-9** subset
(`funnels.title/createButton/status/filter/emptyState/deleteConfirm/form`, `errors.funnels.create|list|delete`).
The file's own comment says *"Section is extensible (Task 10 adds editor keys)"* — **but Task 10 never
extended the list.** Consequence: if a future change dropped `funnels.editor.*` / the six activate
`errors.funnels.<code>` keys from **both** locales, `check-locales` (symmetry) **and** `required-keys`
(doesn't list them) would both stay green. Mitigation today: the activate codes are indirectly guarded
by `funnel-editor.spec.ts` (asserts resolved text ≠ raw key, mapped≠generic). Editor *display* labels
(`funnels.editor/steps/trigger.*`) have only weak indirect coverage (component specs assert on
`data-test` structure, not label text).

---

## 7. Time determinism

**Excellent.**

- `FunnelExecutionEngineIT`: **0** `Instant.now()`; a `@Primary` `MutableClock` (declared *local* to the
  IT so it can't leak into other contexts) drives all timing; `CLOCK.advance(...)` moves the Delay
  window; `sweep()` is invoked **directly** (per `ProjectHardDeleteJobIT` pattern), never via real
  JobRunr cadence.
- **No `Thread.sleep`** anywhere in funnel/webhook/jobs tests (grep clean) — no real-wait flakiness.
- `FunnelTriggerServiceIT` uses real `Instant.now()` only to seed timestamps and as a *bounded*
  `[before, after]` window around `fire()` for `nextRunAt` — wide, not sleep-based, not flaky.
- Sub-minute cadence verified by asserting the registered `RecurringJob` schedule == `PT30S`, not by
  waiting for it to fire.

**Status: OK.**

---

## 8. HIGH-scenario presence check (tech-spec)

| HIGH scenario | Present? |
|---|---|
| `fire()` error-isolation (swallow, raw_update not FAILED, no webhook-retry) | ✅ `fireErrorIsolationSwallowsAndDoesNotFailWebhook` |
| Reactivation order (upsert→active before fire(), first send not self-cancelled) | ✅ `reactivationOrderingFirstSendNotSelfCancelled` |
| SendImage failure-matrix (400-other→failed vs 403/chat-not-found→cancelled+flip) | ✅ `sendImageInvalidUrlFailsExecution` + `sendImageBlockedFlipsAndCancels` |
| Observability — log constant present, payload (PII) absent | ✅ `stateTransitionsEmitNamedLogConstantsWithoutPayload` |

All four HIGH scenarios explicitly covered with real persistence/HTTP.

---

## 9. Findings (severity · file · problem · fix)

### MEDIUM

**M1 — i18n required-keys gate omits Task-10 editor + activate-code keys**
`frontend/tests/i18n/required-keys.spec.ts`
*Problem:* `REQUIRED_KEYS` stops at Task-9 keys; `funnels.editor/steps/trigger.*` and the six
`errors.funnels.<businessCode>` keys are not pinned. Both gates would stay green if both locales lost
them. The TODO-comment intent was never fulfilled.
*Fix:* Append to `REQUIRED_KEYS`: representative editor/steps/trigger keys (e.g. `funnels.editor.title`,
`funnels.steps.title`, `funnels.trigger.title`) and all six activate codes
(`errors.funnels.funnel_no_steps`, `…funnel_step_invalid`, `…funnel_step_limit_reached`,
`…funnel_trigger_conflict`, `…funnel_invalid_trigger_value`, `…funnel_invalid_state`). Cheap, deterministic.

**M2 — E2E golden-path is skip-by-default (not exercised in CI)**
`frontend/e2e/funnels.spec.ts`
*Problem:* `funnels_goldenPath_buildAndActivate` `test.skip`s whenever the backend isn't live or the
`E2E_FUNNELS_*` bootstrap env is absent (the CI default). The integrated build→reorder→deeplink→activate
flow therefore runs only manually (runbook). The 422 branch is salvaged by the always-on Vitest page
spec, but reorder/deeplink/activate integration is not.
*Fix:* Accept as a documented Phase-1 limitation (consistent with tech-spec AVP: no CI yet, Telegram not
automatable) **or** add a Vitest page-spec asserting the editor's add-step→PATCH→reorder→activate state
flow against the mocked store (closes most of the gap without a live stack). Recommend at least logging
the skip in CI output so it's not mistaken for "passed".

### LOW

**L1 — No backend test for Delay `<1 min → 422`**
`funnel/FunnelStepExecutorTest.java` / `funnel/FunnelControllerIT.java`
*Problem:* tech-spec unit bullet lists "Delay `<1хв→422`". `delayComputesDurationFromUnit` covers
MIN/HOUR/DAY computation; the frontend `AddStepDialog` blocks `value < 1`. No backend test asserts the
server-side min-duration rejection (`funnel_step_invalid`) for a sub-minute delay.
*Fix:* Add a `FunnelControllerIT` PUT case with a `DELAY` step that resolves to `<1 min` → expect 422
`funnel_step_invalid` (defense-in-depth beyond the client check).

**L2 — `paused` funnel trigger/drain not explicitly tested**
`funnel/FunnelTriggerServiceIT.java`
*Problem:* `draftFunnelIsNotMatched` exists, but there's no `pausedFunnelIsNotMatched` and no explicit
"paused funnel's in-flight executions still drain" assertion. Behavior is implied (trigger query filters
`status=active`; engine sweeps by execution status independent of funnel status) but unpinned.
*Fix:* Add `pausedFunnelIsNotMatched` (mirror of the draft test) and one engine IT asserting an existing
`running` execution completes even after its funnel is paused.

**L3 — `FunnelStepExecutorTest` mocks 4 collaborators (informational)**
*Problem/assessment:* Technically trips the test-master "3+ deps → integration" guideline, but justified
(pure dispatch boundary, meaningful outcome assertions, paths re-covered by the real-Mongo engine IT).
*Fix:* None required. Noted for completeness.

---

## 10. Verdict

✅ **Ready for pre-deploy QA (Task 14).**

Tally: **0 critical · 0 high · 2 medium · 3 low.** Per the `test-quality-review` decision matrix
(`high ≥ 1 AND medium ≥ 3` or `medium ≥ 5` → needs_improvement) this is **passed** — the suite is
balanced, concurrency/idempotency/HIGH invariants are exercised against real Mongo/HTTP with meaningful
assertions, and time is fully deterministic. No HIGH coverage gap blocks deployment.

**Recommended before/with Task 14 (non-blocking):**
- M1 — extend `required-keys.spec.ts` with Task-10 editor + activate-code keys (cheap, closes a real
  regression hole).
- M2 — add a mocked-store editor page-spec for the golden-path flow, since the live E2E skips in CI.

These are improvements; none change the deploy decision.
