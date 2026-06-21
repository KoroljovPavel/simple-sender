# Decisions Log: 17-funnel-multi-entry

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

<!-- Entries are added by agents as tasks are completed.

Format is strict — use only these sections, do not add others.
Do not include: file lists, findings tables, JSON reports, step-by-step logs.
Review details — in JSON files via links. QA report — in logs/working/.

## Task N: [title]

**Status:** Done
**Commit:** abc1234
**Agent:** [teammate name or "main agent"]
**Summary:** 1-3 sentences: what was done, key decisions. Not a file list.
**Deviations:** None / Deviated from spec: [reason], did [what].

**Reviews:**

*Round 1:*
- code-reviewer: 2 findings → [logs/working/task-N/code-reviewer-1.json]
- security-auditor: OK → [logs/working/task-N/security-auditor-1.json]

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-N/code-reviewer-2.json]

**Verification:**
- `npm test` → 42 passed
- Manual check → OK

-->

## Task 1: Domain model `List<Trigger>` + denormalized `onStartTriggerValue` + index shape

**Status:** Done
**Commit:** e7f6489
**Agent:** domain-modeler
**Summary:** Added flat embedded POJO `Trigger` (triggerType/triggerValue/keywords/entryStepId, no `_class`, no bean-validation — mirrors `FunnelStep`). Replaced `Funnel`'s flat trigger trio with `List<Trigger> triggers` + denormalized nullable `String onStartTriggerValue`; swapped the partial-unique index `projectId_triggerType_triggerValue_unique_active` for `projectId_onStartTriggerValue_unique_active` filtered `{status:'active', onStartTriggerValue:{$exists:true}}` (Decision 6 / Variant A); kept the `'active'`==`FunnelStatus.active.name()` static-block guard and dropped the now-dead `'on_start'` assertion.
**Deviations:** Expected downstream compile breakage — removing the flat trio breaks `compileJava` in `FunnelService`/`FunnelEventService` (and downstream test files referencing the old getters), all owned by Tasks 2-4. Strictly in scope (two target files + three new test files); downstream files NOT touched. Because Gradle's `compileJava` precedes `compileTestJava`, the new tests cannot run via the full module build until Tasks 2-4 land — they were instead compiled and run in isolation against the module's test runtime classpath (all 6 green).

**Reviews:**

*Round 1:* (all minor, all reviewers approved — quality fixes applied in commit `fix: address review round 1 for task 1`)
- code-reviewer → [logs/working/task-1/code-reviewer-round1.json]
- security-auditor → [logs/working/task-1/security-auditor-round1.json]
- test-reviewer → [logs/working/task-1/test-reviewer-round1.json]
- Fixes: `Trigger` value equality (`equals`/`hashCode` over all four fields); exact index-def assertion (`{'projectId': 1, 'onStartTriggerValue': 1}`, reversed order now fails); `triggersListRoundTrip` uses an `event` trigger for the `entryStepId` case (valid shape per Decision 10) + structural value assertions; reflection-based negative assertion that `Funnel` no longer exposes `getTriggerType`/`getTriggerValue`/`getKeywords`.

**Verification:**
- Isolated compile (Java 21 toolchain: `Funnel`, `Trigger` + transitive funnel pkg via sourcepath; 3 test classes) → clean
- Isolated JUnit run (`TriggerTest`, `FunnelTriggersFieldTest`, `FunnelIndexAssertionsTest`, junit-platform-console-standalone 1.10.5) → 8 tests, 8 passed (added `equalsAndHashCodeOverAllFourFields`, `oldFlatTriggerGettersRemoved`)
- Full module build still blocked on downstream (`FunnelService`/`FunnelEventService` + downstream IT test files referencing the old flat-trio API) — expected; owned by Tasks 2-4

## Task 2: Repository array-aware queries + index reconciliation

**Status:** Done
**Commit:** 7fe979d
**Agent:** repo-indexer
**Summary:** Rewrote `FunnelRepository` trigger lookups to be array-aware over `triggers[]` — `findByProjectIdAndTriggersTriggerTypeAndTriggersTriggerValueAndStatus` (event/tag/field fan-out, multikey element-match), `findByProjectIdAndTriggersTriggerTypeAndStatus` (keyword candidate scan) — and replaced the flat on_start lookup with `findByProjectIdAndOnStartTriggerValueAndStatus` over the denormalized scalar (Decision 6); removed the three flat-trio methods. Re-targeted `FunnelTriggerIndexReconciliation` to drop the OLD `projectId_triggerType_triggerValue_unique_active` index by name (presence-by-name detection, not partial-filter inspection) before auto-creation lays the Task-1 `projectId_onStartTriggerValue_unique_active` shape (Decision 7); idempotent (APPLIED when old index present / NO-OP when absent), never touches the new index, log-but-never-throw; updated `MARKER_APPLIED`/`MARKER_NOOP`. Rewrote both index ITs against the new shape (`setTriggers`/`setOnStartTriggerValue`, separate old/new index helpers + names) and added the three array-aware repository query ITs.
**Deviations:** Deviated from the literal Phase-3 NO-OP detection: idempotency now keys off PRESENCE of the OLD index by name (the new index has a different key AND a different auto-derived name, so partial-filter inspection is dead) — aligned with the task's re-target instruction. Confirmed the new index name from Task 1's annotation is `projectId_onStartTriggerValue_unique_active` (used in markers + IT assertions). `INDEX_NAME` intentionally still points at the OLD index (the drop target). The verify-smoke (`./gradlew test --tests '*FunnelTriggerIndexReconciliationIT' --tests '*FunnelIndexesIT'`) is DEFERRED: the backend module does not compile yet because out-of-scope consumers (`FunnelService`/`FunnelEventService`/`FunnelTriggerServiceImpl` + their test files) still bind the removed flat-trio API (owned by Tasks 4-5). Per the task's build-ordering note this is expected — to be run by Task 11 pre-deploy QA after Task 5.

**Reviews:**

*Round 1:* changes applied in commit `fix: address review round 1 for task 2`.
- code-reviewer → [logs/working/task-2/code-reviewer-round1.json] (changes_requested)
- security-auditor → [logs/working/task-2/security-auditor-round1.json] (pass with notes)
- test-reviewer → [logs/working/task-2/test-reviewer-round1.json] (approved with notes)
- Fixes:
  - **MAJOR ($elemMatch):** replaced the derived `findByProjectIdAndTriggersTriggerTypeAndTriggersTriggerValueAndStatus` (flat dotted predicates `{'triggers.triggerType':?,'triggers.triggerValue':?}`, which MongoDB evaluates independently across array elements → cross-element false positive: `[{keyword,purchase},{event,other}]` falsely matched `(event,purchase)`) with an explicit `@Query` using `$elemMatch` (`{ 'projectId': ?0, 'triggers': { '$elemMatch': { 'triggerType': ?1, 'triggerValue': ?2 } }, 'status': ?3 }`). Same name/signature so Task 5 binds unchanged. Javadoc notes the element-scoped semantics + the caller's matched-element re-scan for `entryStepId`.
  - **MEDIUM (marker assertions):** added a `ListAppender`-based log-assertion harness to `FunnelTriggerIndexReconciliationIT` (mirrors `ProcessTelegramUpdateJobTest`) pinning `MARKER_APPLIED` once on the migration (old index present) case and `MARKER_NOOP` once on the idempotent re-boot case (each cross-asserts the other marker did not fire).
  - **LOW:** renamed `migrationDropsOldBroadFilterIndex` → `migrationDropsOldFlatTrioTriggerIndex`; added negative IT `findByTriggersTriggerTypeAndValueExcludesCrossElementMatch` proving `$elemMatch` excludes the cross-element funnel; made the `reconciled` once-per-boot flag `volatile`; asserted `returned isSameAs broken` in `reconciliationNeverThrowsOnMongoFault` (BPP must not drop the bean); added a Javadoc note on `findByProjectIdAndOnStartTriggerValueAndStatus` that callers must not pass `null` (would match all event-only funnels — pass `""` for bare /start).

**Verification:**
- Isolated compile (Java 21, gradle test classpath + `-sourcepath`): `FunnelRepository` + `FunnelTriggerIndexReconciliation` and their source closure → clean (exit 0)
- Isolated compile of rewritten ITs (`FunnelTriggerIndexReconciliationIT`, `FunnelIndexesIT`) against the new model + new repository methods → clean (exit 0)
- Round-1 fixes re-compiled in isolation (same 4 files, JDK 21, `--release 21`, gradle test runtime classpath minus stale `build/classes`) → clean (exit 0)
- DEFERRED: full-module build + index-IT smoke (module won't compile until Tasks 4-5 rewire consumers) → Task 11 pre-deploy QA

## Task 3: DTO contract — `List<TriggerDto>`

**Status:** Done
**Commit:** 184bade
**Agent:** dto-contractor
**Summary:** Added the flat `TriggerDto(triggerType, triggerValue, keywords, entryStepId)` record (mirrors domain `Trigger` 1:1 + the `FunnelStepDto` style — `@JsonIgnoreProperties(ignoreUnknown=true)`, per-type/cross-trigger validation deferred to FunnelService → 422) and replaced the flat `triggerType`/`triggerValue`/`keywords` trio with `List<TriggerDto> triggers` on `UpdateFunnelRequest`, `FunnelResponse`, `FunnelSummaryResponse`. PATCH stays full-replace. Decision 14: `@Valid @Size(max=MAX_TRIGGERS=50)` ceiling on the trigger array + a per-trigger `@Size(max=MAX_KEYWORDS=50)` keyword cap on `TriggerDto.keywords` (so `@Valid` cascades a real constraint and the keyword DoS surface is bounded per element); per-keyword length cap + normalization stay service-side (Task 4).
**Deviations:** Did NOT touch `FunnelService` (task step 5 mentioned a minimal compile-fix there). Per the orchestrator's build-ordering directive, FunnelService is fully reworked by Task 4 and the module is already non-compiling from Task 1 (Funnel lost the flat trigger getters), so adding a speculative stub would be throwaway. Verification was therefore done by ISOLATED compile + JUnit run of the DTO sources + test (full-module `compileTestJava`/`test` is blocked downstream — deferred to Task 11), matching the Task 1/2 precedent. Added one extra boundary test (`sizeCapBoundaryAccepted`) beyond the TDD Anchor list for off-by-one coverage; kept `validCascadesIntoTriggerDto` (the anchor's optional test) because `TriggerDto` does carry a field-level `@Size`.

**Reviews:**

*Not yet run* — review cycle managed by the orchestrator (reviewers: code-reviewer, security-auditor, test-reviewer → logs/working/task-3/).

**Verification:**
- Isolated compile (Java 21, gradle test runtime classpath): `dto/*.java` + `FunnelStatus`/`StepType`/`BlockType` → clean (exit 0)
- Isolated compile of `TriggerDtoTest` against the above → clean (benign apiguardian warnings only)
- Isolated JUnit run (`junit-platform-launcher` 1.12.2, Hibernate Validator 8.0.2): `TriggerDtoTest` → 6 tests, 6 passed (round-trip, ignore-unknown mass-assignment, response/summary round-trip, @Size cap violation + boundary, @Valid cascade into element)
- DEFERRED: full-module `./gradlew compileTestJava test --tests '*TriggerDtoTest'` (module won't compile until Tasks 4-5 rewire FunnelService/FunnelEventService/FunnelTriggerServiceImpl to the array API) → Task 11 pre-deploy QA

## Task 4: Trigger validation + `onStartTriggerValue` sync + conflict pre-check + duplicate reset

**Status:** Done
**Commit:** 9a1441c
**Agent:** service-validator
**Summary:** Reworked `FunnelService` from the flat trigger trio to per-element validation over `List<Trigger>`: new `applyTriggers` runs one pass building a validated/normalized array with cross-trigger rules (≤1 on_start; reject duplicate `event_name` case-preserving; each entryStepId null for non-event / non-null+resolving-to-a-step for event per Decision 10; array-size cap 50 + per-trigger keyword caps via the reused value/keyword helpers), keeping entryStepId OUT of `validateSteps`' generic edge-pass. Steps are now built/validated BEFORE the trigger pass so event entryStepIds resolve against the new step-id set. Added a single-writer `syncOnStartTriggerValue` (on_start element → scalar; ""/blank/absent → null, Variant A) called from create/update/activate/duplicate; rewrote `checkTriggerConflict` to key on the denormalized scalar via `findByProjectIdAndOnStartTriggerValueAndStatus` (skips the lookup when scalar is null, never passes null), kept `saveHandlingTriggerConflict`'s DuplicateKeyException→422 as defense-in-depth; `duplicate` resets the clone to a single bare on_start + null scalar (Phase-4 precedent); `create` is born with a single bare on_start. Mapped entity↔DTO for the `List<TriggerDto>` (`toResponse`/`toSummary` request-apply via new `toTriggerDtos`, deepLink reads the on_start element). Business codes: `funnel_multiple_on_start`, `funnel_duplicate_event_name`, `funnel_invalid_entry_step`, `funnel_trigger_limit_reached` (each 422), reusing existing value/keyword/type codes.
**Deviations:** (1) New business-code names chosen (none prescribed by user-spec, which only mandates "422 + бізнес-код"): `funnel_multiple_on_start` / `funnel_duplicate_event_name` / `funnel_invalid_entry_step` / `funnel_trigger_limit_reached`; trigger-array cap = 50 (same order as `max-steps`/fan-out per Decision 14). (2) Beyond the named files, migrated three FunnelService-focused test files broken by Task 3's `UpdateFunnelRequest` ctor change to the new array API: `FunnelServiceKeywordTest`, `FunnelServiceEmitEventTest`, `FunnelServiceKeyboardStepTest` (keyword now per-trigger). These are NOT Task 5's files (Task 5 = FunnelEventService/FunnelExecutionEngine/FunnelTriggerServiceImpl + their tests) and they directly exercise FunnelService, so leaving them broken would block all test compilation even after Task 5. (3) verify-smoke DEFERRED per the build-ordering note: the module does not compile until Task 5 rewires its three files (compileTestJava errors are confined to `FunnelEventService.java` + `FunnelTriggerServiceImpl.java` only) — Task 5 teammate / Task 11 runs `./gradlew test --tests '*FunnelServiceTriggerTypeTest' --tests '*FunnelControllerIT'`. Did NOT touch Task 2's repository/reconciliation files or Task 5's files.

**Reviews:**

*Round 1* — code-reviewer (changes_requested), test-reviewer (CHANGES_REQUESTED), security-auditor (PASS_WITH_OBSERVATIONS, low notes). Reports:
- [code-reviewer-round1.json](logs/working/task-4/code-reviewer-round1.json)
- [test-reviewer-round1.json](logs/working/task-4/test-reviewer-round1.json)
- [security-auditor-round1.json](logs/working/task-4/security-auditor-round1.json)

**Round 1 fixes (commit: see below):**
- MAJOR/HIGH (code-reviewer + test-reviewer, convergent — false-green ITs): the legacy FLAT top-level trigger fields (`"triggerValue": ...`) in `FunnelControllerIT` were silently dropped by `UpdateFunnelRequest`'s `@JsonIgnoreProperties(ignoreUnknown=true)`, so `triggers` normalized to a bare on_start and bad-value tests passed with 200 instead of the claimed 422. Migrated ALL ~23 remaining flat `"triggerValue", ""` request bodies (lines 128/160/202 + the `Map.of("name","Draft","triggerValue","",...)` block 1338→1762) to the `triggers: [onStartTriggerMap("")]` array form (helpers already existed). Rewrote `invalidTriggerValueWithSpaceReturns422` to send the bad value INSIDE `onStartTriggerMap("has space")` so it actually exercises the `funnel_invalid_trigger_value` 422 path.
- MEDIUM (test-reviewer F-2): added HTTP-level intra-funnel case `patch_multipleOnStartReturns422` (PATCH `triggers=[onStartTriggerMap(""), onStartTriggerMap("x")]` → 422 `funnel_multiple_on_start`), complementing the existing cross-funnel duplicate-value cases — both distinct 422s now covered in `FunnelControllerIT` per tech-spec.
- MINOR: `applyTriggers` null/empty path now reuses `bareOnStartTrigger()` directly (no `TriggerDto` round-trip); tightened `saveHandlingTriggerConflict` comment (ONLY-unique-index claim scoped to the `funnels` collection); added boundary IT/service test `triggers_arraySizeCapBoundaryAccepted` (exactly 50 triggers accepted, Decision 14).
- SECURITY LOW: `requireNoEntryStep` message no longer echoes the `triggerType` string (id/code-only, matching the other validators; `type` param dropped); added a clarifying comment on `checkTriggerConflict`'s skip-on-null documenting its dependence on `syncOnStartTriggerValue` mapping bare `""`→null.
- Isolation compile re-verified: `FunnelService.java` + both changed test files compile clean (exit 0) against the Gradle testRuntimeClasspath; full-module compile still blocked only by Task 5's `FunnelEventService.java` + `FunnelTriggerServiceImpl.java` (expected).

**Verification:**
- Isolated compile (Java 21, gradle testRuntimeClasspath + `-sourcepath`): `FunnelService.java` → clean (exit 0), `-Xlint:all` no unused-symbol warnings
- Isolated compile of all touched test files (`FunnelServiceTriggerTypeTest`, `FunnelControllerIT`, `FunnelServiceKeywordTest`, `FunnelServiceEmitEventTest`, `FunnelServiceKeyboardStepTest`) against the compiled main → clean (exit 0)
- Full-module `compileJava`/`compileTestJava` error set confined to Task 5's `FunnelEventService.java` + `FunnelTriggerServiceImpl.java` (expected, build-ordering note)
- DEFERRED: `./gradlew test --tests '*FunnelServiceTriggerTypeTest' --tests '*FunnelControllerIT'` (module compiles only after Task 5) → Task 5 teammate / Task 11

## Task 5: Redirect-or-start dispatcher + engine `redirectExecution` + on_start lookup

**Status:** Done
**Commit:** 64ee8bd
**Agent:** engine-redirector
**Summary:** Added `FunnelExecutionEngine.redirectExecution` + `claimForRedirect` (CAS bound by subscriberId AND funnelId, status ∈ {running, waiting, waiting_for_reply}, cursor→entryStepId resolved against the execution's own stepsSnapshot, unpark + preStepGates + drive; CAS-loss → greppable WARN no-op, broken cursor → terminal-fail only that execution). Centralised the redirect-vs-start decision in `FunnelEventService.dispatchForSubscriber`: matched-element re-scan of `triggers[]` for `entryStepId`, a fresh in-flight `findOne` probe (projectId+funnelId+subscriberId, in-flight statuses) whose executionId is the ONLY redirect input, per-funnel error isolation; switched `matchingFunnels` to the Task-2 array-aware `$elemMatch`/keyword queries; injected `@Lazy FunnelExecutionEngine` to break the real bean cycle `FunnelEventService → FunnelExecutionEngine → StepExecutor → FunnelEventService`. The backend module compiles again (Tasks 1-4 had left it broken). Also fixed the test-compile fallout from Task 1's removed flat-trigger setters in 4 sibling test files (seed helpers rebuilt to `List<Trigger>` + `onStartTriggerValue`).
**Deviations:** Deviated from spec on the on_start lookup: the task said use `findByProjectIdAndOnStartTriggerValueAndStatus`, but that denormalized scalar is null for a bare `/start` ("" → null, Variant A) and its repo method forbids a null arg, so a bare-start funnel could never resolve. `FunnelTriggerServiceImpl.fire` instead uses the array-aware `$elemMatch` query over `triggers[]` on `(on_start, payload)` (exact old semantics for both bare "" and a deep-link payload; `.findFirst()` keeps the single-result contract). Documented inline. Enroll stays step-0/depth-0.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 3 minor → [logs/working/task-5/code-reviewer-1.json] (applied: gated `matchedEntryStepId` on `TRIGGER_EVENT` to make Decision 10 explicit)
- security-auditor: approved, 2 minor (confirmatory) → [logs/working/task-5/security-auditor-1.json]
- test-reviewer: needs_improvement, 2 major + 2 minor → [logs/working/task-5/test-reviewer-1.json]

*Round 2 (after fixes):*
- test-reviewer: passed → [logs/working/task-5/test-reviewer-2.json]
- Fixes: `redirect_ofRunningAndWaiting_movesCursor` now proves the cursor move by send-count (s1→tail = 2 sends if not moved vs entry→End = 1); split the allowReEnter coverage into three dispatcher ITs (fresh-at-entry, terminal-row-does-not-block, allowReEnter=true cancel-then-insert); added a unit test locking the entry-step `DuplicateKeyException` swallow branch.

*Round 1 re-application (consolidated, post-64ee8bd):* picked up the round-1 reports' remaining items that were not yet in the committed tree → [logs/working/task-5/code-reviewer-1.json], [logs/working/task-5/code-reviewer-round2.json], [logs/working/task-5/security-auditor-round1.json], [logs/working/task-5/test-reviewer-round1.json].
- **TR-3 (test-reviewer minor):** added `eventMatchedFunnel_inMemoryTriggerDrift_warnsAndFallsBackToStepZero` unit test — a funnel returned by the `event` `$elemMatch` query but whose in-memory `triggers[]` carries only a `tag_added` element exercises the `LOG_DISPATCH_NO_MATCHED_TRIGGER_ELEMENT` drift WARN and the step-0 fallback (`insertExecution` once, `insertExecutionAt` never). Locks the defensive `.equals` re-scan against a silent type-check regression.
- **TR-1/TR-2 provability (test-reviewer):** confirmed already PROVABLE in 64ee8bd — `redirect_ofRunningAndWaiting_movesCursor` asserts `currentStepId==null` + send-delta==1 (the `.set(currentStepId)` CAS is the lone gate); `eventEntryStep_insertAtEntryDuplicate_isSwallowedPerFunnel` unit test truly drives the entry-step swallow (probe null, `insertExecutionAt` throws `DuplicateKeyException`). No further change needed.
- **code-reviewer minors (applied):** (1) explicit null/blank `entryStepId` guard at the top of `redirectExecution` → fast WARN no-op (`LOG_REDIRECT_CLAIM_LOST`) instead of drifting into the broken-cursor terminal-fail with misleading telemetry; (2) demoted the dispatch-side `LOG_DISPATCH_REDIRECT` to `debug` (it fires before the CAS, so cannot assert success) — the engine's `LOG_REDIRECT` info is now the single authoritative won-redirect record; (3) strengthened `event_noInFlight_allowReEnterTrue_cancelThenInsertAtEntry` to seed a PRE-EXISTING terminal (cancelled) row, asserting the cancel-then-insert branch leaves the predecessor untouched and inserts a distinct fresh entry row; (4) added a `.findFirst()`-safety comment on `FunnelTriggerServiceImpl.fire` (on_start uniqueness partial index guarantees ≤1 result).
- **security low (applied):** added a guard comment on `LOG_DISPATCH_NO_MATCHED_ELEMENT` forbidding future addition of `matchKey`/`triggerValue`/`event_name` (PII) to that WARN.

**Verification:**
- *Original (64ee8bd):* EngineIT 67 / EventServiceIT 13 / EventServiceTest 17, all passed.
- *Re-application re-run* (`./gradlew test -PrunSlow=true --tests '*FunnelExecutionEngineIT' --tests '*FunnelEventServiceIT' --tests '*FunnelEventServiceTest'`, `--rerun-tasks`):
  - `*FunnelExecutionEngineIT` → 67 passed, 0 failed, 0 errors
  - `*FunnelEventServiceIT` → 13 passed, 0 failed, 0 errors
  - `*FunnelEventServiceTest` → 18 passed, 0 failed, 0 errors (+1 vs round-2: the new TR-3 drift test)
  - Total 98 passed, 0 failed, 0 errors.
- Deferred Task 2/4 smokes: `*FunnelTriggerIndexReconciliationIT` (5), `*FunnelIndexesIT` (8), `*FunnelServiceTriggerTypeTest` (13), `*FunnelControllerIT` (104), `*FunnelTriggerServiceIT` (25) → all passed, 0 failed
- Full module `compileJava` + `compileTestJava` → BUILD SUCCESSFUL (module compiles again)

## Task 6: Frontend types, store, and Triggers panel

**Status:** Done
**Commit:** a192a3b
**Agent:** frontend-panel
**Summary:** Added the `FunnelTrigger` interface (mirrors Task 3 `TriggerDto` 1:1) and replaced the flat triggerType/triggerValue/keywords trio with `triggers: FunnelTrigger[]` on `FunnelResponse`/`FunnelSummaryResponse`/`UpdateFunnelRequest`; the store now projects the whole array into the list row (thin pass-through, no trigger-form logic). New `FunnelTriggersPanel.vue` clones `FunnelStepsList`'s add/delete(two-click)/select structure: on_start is a read-only main-entry slot, the author adds/removes `event` triggers, each mounting `FunnelTriggerSettings` (type-locked — the panel owns the type) plus a per-trigger entry-step `SearchableSelect` whose options are built from the funnel's OWN steps (prop, not async). The whole array is emitted via `update:triggers` on every mutation (full-replace, mirrors PATCH). Advisory duplicate-event-name guard (trim-normalized, empty-name-skipping); uk/en i18n under `funnels.triggersPanel.*`.
**Deviations:** Deviated from spec on the "from start" sentinel for event triggers: Task 4's validation REQUIRES a non-null `entryStepId` resolving to a step for `event` (`triggers_eventRequiresEntryStepId` → 422). So the panel's `__START__` sentinel maps to the FIRST step's id (not null) on emit, and a stored entryStepId equal to the first step's id displays as the sentinel. New event rows default `entryStepId` to the first step's id; if the funnel has no saved step yet it falls back to null (the server 422 + advisory is the backstop). Added a `lock-type` prop to `FunnelTriggerSettings` (disables the type select) so the panel-owned type can't be changed from a row (code-reviewer minor). The editor page (`pages/.../[funnelId].vue`) still uses the old flat trigger model — wiring the panel + autosave is Task 7's scope; it compiles at runtime (no typecheck gate) and its 46 tests pass unchanged.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions, 4 minor → [logs/working/task-6/code-reviewer-1.json] (applied: `lock-type` pin on the on_start + event slots; the editor-page mismatch is tracked as Task 7 scope)
- security-auditor: approved, 0 findings → [logs/working/task-6/security-auditor-1.json] (advisory guard correctly NOT a security control; no v-html/unsafe sinks)
- test-reviewer: needs_improvement, 2 major + 2 minor → [logs/working/task-6/test-reviewer-1.json]

*Round 2 (after fixes):*
- test-reviewer: passed → [logs/working/task-6/test-reviewer-2.json]
- Fixes: added a test proving the sentinel→step-1-id EMIT direction (`__START__` click → `entryStepId === 's1'`, not null); asserted `addEvent` defaults `entryStepId` to the first step's id; positive locale-agnostic sentinel-label display assertion; added a duplicate-guard trim-normalization + empty-name-skip test.

**Verification:**
- `pnpm test tests/components/funnels/FunnelTriggersPanel.spec.ts` → 9 passed
- `pnpm test tests/i18n/required-keys.spec.ts` → 2 passed (new `funnels.triggersPanel.*` keys, uk/en parity)
- `node scripts/check-locales.mjs` → exit 0 (uk/en parity, no missing/empty keys)
- Full frontend suite `pnpm test` → 55 files, 527 passed (regression check across store/editor-page/step-form)

## Task 7: Editor page wiring for the trigger array

**Status:** Done
**Commit:** c14edfd
**Agent:** editor-wiring
**Summary:** Reworked `pages/.../[funnelId].vue` from the three flat trigger refs (triggerType/triggerValue/keywords) to a single reactive `triggers: FunnelTrigger[]`, filled from `FunnelResponse.triggers` (fallback to a lone `on_start` element when the backend returns empty). `persist()` now sends the array via a per-element `sanitizeTrigger()` (keyword owns `keywords`/null triggerValue; other types own `triggerValue`/`[]` keywords; entryStepId carried as-is). `triggerReady()` became per-element and the debounced autosave gate fires only when `triggers.every(triggerReady)` — so switching/adding one trigger whose required value is still empty never fires a premature 422. The deep watch moved onto the array, and `<FunnelTriggersPanel v-model:triggers :steps :bot-username :deep-link>` replaced the page-level `<FunnelTriggerSettings>`.
**Deviations:** Deviated by adding two safeguards the spec did not name but the array model requires: (1) `scheduleTriggerPersist()` now ALWAYS cancels the pending debounce timer before re-checking the gate — a later edit that makes the array incomplete (e.g. an empty event trigger just added) must cancel an already-scheduled PATCH, else it would flush a not-ready array and 422; (2) an `applyingResponse` re-entrancy guard suppresses exactly one watch cycle when `applyResponse()` re-assigns `triggers` from the server — without it `persist→applyResponse→watch→persist` loops forever (the server-echoed array always has a fresh identity). Both are correctness fixes surfaced by the new array-deep-watch and confirmed by reviewers.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 0 critical/major, 2 minor optional → [logs/working/task-7/code-reviewer-1.json] (re-entrancy guard verified robust — can never permanently swallow a real edit; payload + per-element gate correct)
- security-auditor: approved, 0 findings → [logs/working/task-7/security-auditor-1.json] (no v-html/new DOM sink; anchored bounded regexes are ReDoS-safe; client gate is UX-only, backend stays validator; no IDOR change)
- test-reviewer: passed, 2 minor → [logs/working/task-7/test-reviewer-1.json] (REAL panel mounted, not stubbed — selectors load-bearing; all five TDD anchors covered, assertions non-vacuous)
- Fixes applied: strengthened the "PATCHes once every trigger is ready" test to seed a saved step and assert the event's `entryStepId === 'step1'` (was a weak `'entryStepId' in ev` presence check). Other minors skipped (optional defense-in-depth / redundant coverage).

*Round 1 (re-review JSONs):* [logs/working/task-7/test-reviewer-round1.json], [logs/working/task-7/code-reviewer-round1.json], [logs/working/task-7/security-auditor-round1.json]
- test-reviewer-round1 flagged two partial TDD-anchor gaps (F1 type-switch / per-element gate, F2 PATCH-storm cancellation), non-blocking. Added two regression tests to `tests/pages/funnel-editor.spec.ts`: (1) "mutating one trigger while its value stays empty fires no premature PATCH (per-element gate)" — seeds an incomplete event trigger, mutates it toward a still-not-ready value, asserts no PATCH (the main-regression-case TDD anchor); (2) "cancels a previously-armed PATCH when a later edit makes the array incomplete" — arms the debounce with a ready array, then adds an empty trigger before it fires, asserts the armed PATCH is cancelled (covers the `clearTimeout`-before-gate-recheck safeguard, the real bug the implementation fixed). Both use the real mounted `FunnelTriggersPanel`.

**Verification:**
- `pnpm test tests/pages/funnel-editor.spec.ts` → 49 passed (47 prior + 2 new regression tests; stable)
- Full frontend suite `pnpm test` → 55 files, 528 passed (no regressions)
- `node scripts/check-locales.mjs` → exit 0 (no new i18n keys; uk/en parity intact)
- User check (open editor, add event trigger, pick entry step, reload) → deferred to final user-review phase per task scope

## Task 10: Test Audit

**Status:** Done
**Agent:** test-auditor
**Summary:** Read-only full-feature test-quality audit. Verdict **pass-with-findings**: every load-bearing invariant of the multi-entry redirect is covered by a concrete, non-vacuous test in the correct pyramid layer — redirect/start branches, the one-execution invariant (row-count assertion), CAS-loss (untouched-state + greppable WARN, not a bare boolean), broken-cursor isolation (fail + sibling survives), anti-IDOR, cross-funnel/two-funnel independence, EMIT_EVENT self-loop budget-trip, index reshape (APPLIED/NO-OP markers + on_start partial-unique shape + null-never-binds), all trigger-validation 422 business codes (intra- vs cross-funnel on_start as distinct cases), onStartTriggerValue sync + duplicate-reset, and the rewired on_start fire lookup. Engine/concurrency/index sit in @Tag("slow") ITs (-PrunSlow=true), validation in service+controller IT, panel in component/page specs (no E2E). Findings are MINOR only (no critical/major): the headline gap is that the user-spec AC "без порожніх ключів" is not actually asserted — both the i18n parity script and required-keys.spec.ts check key presence, not non-empty values (actual values verified non-empty). Plus a pyramid-label note (FunnelServiceTriggerTypeTest is a service IT, not a unit test) and an optional menu-park self-loop hardening note.
**Deviations:** None.

**Reviews:** N/A — this task IS the review (Audit Wave, no downstream reviewers).

**Verification:**
- Report written → [logs/working/audit/test-auditor.json](logs/working/audit/test-auditor.json) (valid JSON, verdict + full coverage matrix + findings w/ severity + file/test anchors + pyramid + slow-lane assessment)
- Empty-value i18n gap confirmed by reading check-locales.mjs (collectKeySet records `acc[path]=true` for any leaf incl. "") and required-keys.spec.ts (toHaveProperty satisfied by ""); funnels.triggersPanel.* values manually verified non-empty (17/17 uk + en)
- @Tag("slow") placement verified by grep across the funnel test package (all engine/index ITs slow-tagged)

## Task 8: Code Audit

**Status:** Done
**Agent:** code-auditor
**Summary:** Holistic cross-component quality audit of the whole feature (Tasks 1-7, read final-state files, not diffs). Feature is architecturally sound and consistent with Phases 1-7: single execution writer confirmed (API-event/EMIT_EVENT and on_start both route through one collaborator; redirectExecution is the only new engine mutation; no forked writer), the new FunnelEventService -> FunnelExecutionEngine -> StepExecutor -> FunnelEventService bean cycle is correctly broken by @Lazy, error-isolation/snapshot-isolation/anti-IDOR-CAS (subscriberId+funnelId)/one-execution + loop-bound invariants all hold, no flat-trio dead code remains. Result: status issues_found with 0 blocker / 0 major / 3 minor / 2 nit. Findings JSON: [logs/working/audit/code-auditor.json](logs/working/audit/code-auditor.json).
**Deviations:** None (read-only audit; no source modified). One pre-existing IMPLEMENTATION deviation re-confirmed as already-documented (not silent): FunnelTriggerServiceImpl.fire's on_start lookup uses the array-aware $elemMatch query instead of the spec's onStartTriggerValue lookup, correctly because the scalar is null for bare /start (Variant A) — recorded in Task 5's entry and inline; flagged as a minor for tech-spec-text alignment only.

## Task 9: Security Audit

**Status:** Done
**Agent:** security-auditor
**Summary:** Full-feature OWASP Top 10 audit (Tasks 1-7 final-state, read-only). Verdict CLEAN — 0 findings. All declared controls hold end-to-end: anti-IDOR redirect CAS bound to subscriberId+funnelId with executionId sourced only from the trusted in-flight probe (never request input, A01/Decision 3); fail-closed projectId on every redirect/start path; ids/codes-only WARN/info/error logs with event_name/triggerValue/PII never logged (A09/A03/Decision 4); @Size(50) trigger-array + per-trigger keyword caps + rate-limit + fan-out ceiling (A04/Decision 14); @JsonIgnoreProperties mass-assignment defense on all request DTOs (A08); public /events input validated before fan-out; EMIT_EVENT->trigger self-loop bounded by depth cap + per-tick budget + human-press gate; try/catch(Throwable) error-isolation so a redirect fault never 5xx's /events nor fails the webhook job; parameterized Mongo queries; no frontend v-html/eval sinks. Report: [logs/working/audit/security-auditor.json](logs/working/audit/security-auditor.json).
**Deviations:** None (read-only audit; no source modified). Read two files beyond the task's explicit list — EventsController.java + EventIngressRequest.java (the public attack surface, required for the end-to-end anti-IDOR/input-validation trace) and StepExecutor.java (EMIT_EVENT depth-propagation for the self-loop bound).

## Audit Fixes (post-audit follow-up)

**Status:** Done
**Agent:** audit-fixer
**Summary:** Applied two audit findings.
- **Finding 1 (test-audit, Task 10 headline gap — real AC gap):** `frontend/tests/i18n/required-keys.spec.ts` now asserts each required `funnels.triggersPanel.*` key has a non-empty TRIMMED value (`value.trim().length > 0`) in BOTH uk.json and en.json — closes the "без порожніх ключів" AC that the presence-only `toHaveProperty` / `collectKeySet` (records `acc[path]=true` for `""`) left unguarded. Added a value-preserving `loadValues()` helper (collectKeySet drops leaf values) + two new test cases; existing presence assertions left intact. Result: 4 tests pass (2 presence + 2 non-empty value).
- **Finding 2 (code-audit, doc-only):** `backend/src/main/java/com/botfunnel/funnel/FunnelService.java` class-level Javadoc updated — replaced the stale flat `(triggerType, triggerValue)` conflict-pair description with the current model: `List<Trigger>` + denormalized `onStartTriggerValue`, on_start uniqueness via the partial-unique index on `onStartTriggerValue`. Comment-only; no logic changed.

**Deviations:** None. `work/` is gitignored, so the decisions.md change is not committed (no force-add); only the two source files are committed.

## Task 11: Pre-deploy QA

**Status:** Done
**Agent:** qa-runner
**Summary:** QA gate **PASS**. Backend full slow lane (`./gradlew test -PrunSlow=true`, Testcontainers Mongo+Redis): 1284 tests, all 17 feature test classes 100% green (EngineIT 67, EventServiceIT 13, EventServiceTest 18, IndexReconciliationIT 5, IndexesIT 8, ServiceTriggerTypeTest 13, ControllerIT 104, TriggerServiceIT 25, EventsControllerIT 15, ProcessTelegramUpdateJobTest 48, + domain/DTO/keyword tests); no `IndexKeySpecsConflict` in the run. Frontend `pnpm test` 532 passed (55 files); `check-locales.mjs` exit 0 (uk/en parity, non-empty values asserted). 18 acceptance criteria (12 user-spec + 6 tech-spec) all PASS with concrete test evidence.
**Deviations:** None (no source modified — acceptance gate).

**Verification:**
- Full report: [logs/working/task-11/qa-report.json](logs/working/task-11/qa-report.json)
- The only lane failure is `TelegramWebhookP99IT.p99Latency` (104ms vs <100ms, 4ms over) — the documented-flaky `@Tag("slow")` cold-JVM latency SLA probe (build.gradle L48-51), unrelated to this feature, pre-existing, NON-BLOCKING.

**Deferred to post-deploy:** 4 criteria require a live environment (AVP-1 curl API-event, AVP-2 in-flight-redirect bash, LIVE-1 Telegram loop, LIVE-2 manual wipe + clean start) — no bootable live stack (no standalone Mongo/Redis/app/seed data/X-API-Key, no live bot). Their behavior is already proven at the IT layer against Testcontainers Mongo. See `deferredToPostDeploy` in qa-report.json → Task 12.
