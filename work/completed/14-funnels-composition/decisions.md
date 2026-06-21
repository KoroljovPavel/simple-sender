# Decisions Log: 14-funnels-composition

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

## Task 1: Backend data model для SUBSCRIBE_TO_FUNNEL

**Status:** Done
**Commit:** 14efed5
**Agent:** data-model-dev
**Summary:** Added the `SUBSCRIBE_TO_FUNNEL` step type and its three embedded fields (`targetFunnelId`, `targetEntryStepId`, `endParentAfter`) on `FunnelStep` with `copyOf` field-wise copy, mirrored on `FunnelStepDto` with `toSteps`/`toStepDto` mapping (Strings via `blankToNull`, boolean direct). Entry-step field named `targetEntryStepId` per Decision 5 to avoid the `Button.targetStepId` collision and stay out of the generic edge-pass; both exhaustive switches got minimal TODO-marked branches (Task 2 validation / Task 3 enroll) to keep the build green.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: OK, 0 findings → [logs/working/task-1/code-reviewer-round1.json]
- test-reviewer: OK, 0 findings → [logs/working/task-1/test-reviewer-round1.json]

Note: no Task/Agent subagent-spawning tool exists in this environment, so the two reviews were performed inline against the loaded `code-reviewing` / `test-master` methodologies; JSON reports written to the standard paths.

**Verification:**
- `./gradlew test --tests '*StepTypeTest*'` → BUILD SUCCESSFUL (new enum pin passes)
- `./gradlew test --tests 'com.botfunnel.funnel.*'` → BUILD SUCCESSFUL (no regressions)
- `./gradlew compileJava compileTestJava` → green (both exhaustive switches covered)

## Task 2: Save + activation валідація цілі SUBSCRIBE

**Status:** Done
**Commit:** b26d8b3
**Agent:** validation-dev
**Summary:** Added save-time and activation validation for `SUBSCRIBE_TO_FUNNEL` targets in `FunnelService`: a shared fail-closed `resolveSubscribeTarget(targetFunnelId, projectId)` helper loads via `findById` then enforces `projectId.equals(target.getProjectId())` (mirrors `requireFunnel`), with missing/malformed-ObjectId/foreign-project all collapsing to one `funnel_subscribe_target_not_found` (no cross-tenant leak, never 500). `validateSteps` gained a `projectId` param (all three callsites updated) and the `SUBSCRIBE_TO_FUNNEL` branch (required/not_found/step_not_found); `activate` re-resolves each target and requires `status=active` (`funnel_subscribe_target_inactive`) — active is deliberately NOT checked at save (Decision 6).
**Deviations:** None. Task 1 had already updated the test's `FunnelStepDto` callsite to the 20-arg signature, so the spec's "update existing callsites first" step was a no-op.

**Reviews:**

*Round 1:*
- code-reviewer: OK, 2 info findings (skipped, both per task spec) → [logs/working/task-2/code-reviewer-round1.json]
- security-auditor: PASS, fail-closed projectId scoping / IDOR / tenant-leak all verified → [logs/working/task-2/security-auditor-round1.json]
- test-reviewer: 1 minor finding applied (added multi-target activation test) → [logs/working/task-2/test-reviewer-round1.json]

No round 2 needed: the only fix was an added test (no production-code change); the new test is green and asserts real behavior.

Note: no Task/subagent-spawning tool exists in this environment, so all three reviews were performed inline against the loaded `code-reviewing` / `security-auditor` / `test-master` methodologies; JSON reports written to the standard paths.

**Verification:**
- `./gradlew test --tests 'com.botfunnel.funnel.FunnelServiceEmitEventTest'` → BUILD SUCCESSFUL (6 SUBSCRIBE tests green)
- `./gradlew test --tests 'com.botfunnel.funnel.*'` → BUILD SUCCESSFUL (no regressions)
- Smoke: no live backend started (curl not run). The smoke criterion is satisfied by integration tests asserting each 422 code — `subscribe_save_requiresTargetFunnelId` (required), `subscribe_save_targetNotFound_missingOrMalformedOrForeignProject` (not_found ×3 paths), `subscribe_save_targetEntryStepNotInTarget` (step_not_found), `subscribe_activate_blocksOnInactiveTarget` + `subscribe_activate_blocksWhenAnySecondTargetInactive` (inactive). These cover the same paths as the curl smoke steps.

## Task 3: Engine enroll-шлях для SUBSCRIBE_TO_FUNNEL (ядро фічі)

**Status:** Done
**Commit:** 14c400a
**Agent:** engine-dev
**Summary:** Implemented the runtime enroll path for `SUBSCRIBE_TO_FUNNEL`: a new `FunnelEventService.enrollSpecificFunnel(...)` non-matching enroll (depth-cap → auto-enroll rate-limit → `findById` + explicit fail-closed `projectId.equals(...)` IDOR guard → active-check → entry-step-fallback WARN → re-enter/factory, all error-isolated), a `FunnelExecutionFactory.insertExecutionAt(..., startStepId)` overload (legacy `insertExecution` now delegates with `null`; start-cursor falls back to step 0 when the entry step is null/unresolved), a `StepExecutor` SUBSCRIBE case that enrolls at `parent.enrollDepth+1` with inherited `telegramBotId` then returns `COMPLETE`/`CONTINUE`, and a new `Outcome.COMPLETE` mapped in `FunnelExecutionEngine.drive` to the existing claim-conditional `complete(exec, now)`. No engine/factory/FunnelService injected into `StepExecutor` — the enroll rides the existing `StepExecutor → FunnelEventService` edge (Decision 2, anti-bean-cycle).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: OK (bean-cycle safety, no duplicated backstops, exhaustive switch + explicit `case COMPLETE`, claim-conditional complete all verified) → [logs/working/task-3/code-reviewer-round1.json]
- security-auditor: PASS (runtime fail-closed projectId / cross-funnel IDOR rejected, depth-cap + rate-limit DoS backstops, ids/codes-only logs, at-most-once via re-enter guard) → [logs/working/task-3/security-auditor-round1.json]
- test-reviewer: 1 major finding fixed pre-verification — a JVM-singleton MockWebServer cross-test request-queue bleed that failed the pre-existing `testRunLinkedBotSends`; fixed by draining the new test's recorded request. Coverage complete and deterministic → [logs/working/task-3/test-reviewer-round1.json]

No round 2 needed: the only actionable finding (the MockWebServer queue drain) was fixed and included in the same commit `14c400a` before verification; all reviewers approved with no outstanding code-change findings.

Note: no Task/subagent-spawning tool exists in this environment, so all three reviews were performed inline against the loaded `code-reviewing` / `security-auditor` / `test-master` methodologies; JSON reports written to the standard paths.

**Verification:**
- `./gradlew test --tests 'com.botfunnel.funnel.FunnelStepExecutorTest'` → BUILD SUCCESSFUL (unit: SUBSCRIBE continue/complete + enroll args)
- `./gradlew test --tests 'com.botfunnel.funnel.*' -PrunSlow=true` → BUILD SUCCESSFUL (engine-IT + test-run IT + no regressions)
- Smoke: `./gradlew test --tests '*FunnelExecutionEngineIT*' --tests '*Subscribe*' -PrunSlow=true` → BUILD SUCCESSFUL

---

## Task 4: Frontend — форма кроку «Почати воронку» (SUBSCRIBE_TO_FUNNEL)

**Status:** Done
**Commit:** 2569d71 (impl 84f9aa3 + review-fixes 2569d71)
**Agent:** frontend-dev
**Summary:** Added the SUBSCRIBE_TO_FUNNEL step to the funnel editor form: a status-aware target-funnel picker (lazy store.fetch('all')), an optional entry-step picker (lazy fetchOne(targetFunnelId).steps with a "from the start" sentinel → targetEntryStepId=null), an "end this funnel after starting" checkbox, an inline not-active hint, and a return-pattern hint (recommend endParentAfter=true; Risk 2). Submit emits `{ stepType, targetFunnelId, targetEntryStepId, endParentAfter }`. Extended types/funnel.ts (StepType union + 3 optional fields, entry field named `targetEntryStepId` per Decision 5) and uk/en i18n (type/form labels + the four `errors.funnels.funnel_subscribe_*` codes, byte-matched to Task 2, locale-symmetric via check-locales.mjs).
**Deviations:** None. Used the store-fetch source (with the documented shared-`funnels.value` overwrite side-effect) as the task default; entry field is `targetEntryStepId` not `targetStepId`. Implementation ordering note: the SUBSCRIBE store source + loader are declared above the `selectedType` immediate-watch to avoid a TDZ when an edited SUBSCRIBE step is the initial type.

**Reviews:**

*Round 1:*
- code-reviewer: 1 minor (+2 info) → [logs/working/task-4/code-reviewer-round1.json]
- test-reviewer: 1 minor (+1 info) → [logs/working/task-4/test-reviewer-round1.json]

All findings resolved in the same round (commit 2569d71): explicit `subscribeFunnelsLoading` flag for the picker empty-state (CR1); added a test for entry-step reset on target switch (TR1); extended the edit pre-fill test to submit and assert the entry id is re-emitted (TR2). No round 2 needed — no critical/major findings, no outstanding code-change findings. No Task/subagent-spawning tool exists here, so both reviews were performed inline against the loaded `code-reviewing` / `test-master` methodologies; JSON reports at the standard paths.

**Verification:**
- `node ./node_modules/vitest/vitest.mjs run` → 459 passed (51 files; 6 new `FunnelStepForm — SUBSCRIBE_TO_FUNNEL` tests: render+fetch('all'), submit shape, sentinel→null, non-vacuous inactive hint, entry reset, edit pre-fill re-emit). No regressions.
- `node scripts/check-locales.mjs` → uk/en parity OK (catches the new keys; required-keys.spec.ts does not).
- No vue-tsc/typecheck script exists in the frontend package; the missing-submit-case risk (no exhaustiveness guard) is covered by the SUBSCRIBE submit-shape tests.
- USER VERIFY (verify: [user]) — DEFERRED to the team lead / user for a manual localhost check (no live browser available here, dev server not started). Steps: open the funnel editor → "+ Add step" → choose type "Почати воронку"; confirm the target-funnel picker, the entry-step picker, and the "завершити цю воронку після старту" checkbox render; then pick a non-`active` funnel in the target picker and confirm the inline not-active hint (`step-subscribe-inactive-hint`) appears.

---

## Task 5: Code Audit

**Status:** Done
**Agent:** code-auditor
**Summary:** Holistic read-only audit of all 8 backend + 4 frontend feature files for cross-component defects. Verdict CLEAN — no critical/major findings; the cross-component invariants all hold (bean graph acyclic; both Phase-3 backstops reused not duplicated; all StepType switches + engine Outcome exhaustive with explicit SUBSCRIBE_TO_FUNNEL/COMPLETE; projectId fail-closed at save/activate/runtime; at-most-once side-effect-before-claim ordering; ids/codes-only WARNs). 2 minor + 2 info observations, none blocking.
**Deviations:** None.

**Reviews:**

- Audit-task (its output IS the review). Full report → [logs/working/audit/code-auditor.json]

**Verification:**
- Read-only audit; no tests run (Task 1-4 builds already green per their entries; the feature test suite is Task 8 Pre-deploy QA).

---

## Task 6: Security Audit

**Status:** Done
**Agent:** security-auditor
**Summary:** Holistic OWASP Top 10 audit of the full SUBSCRIBE_TO_FUNNEL surface (Wave 1-2 backend + frontend) returned a clean verdict — 0 critical/major/minor, 4 informational confirmations. Multitenant isolation is fail-closed at BOTH boundaries (the explicit `projectId.equals(target.getProjectId())` guard after the non-project-scoped `findById` is present and correct at save/activate in `FunnelService.resolveSubscribeTarget` and at runtime in `FunnelEventService.enrollSpecificFunnel`, with missing/malformed-ObjectId/foreign-project all collapsing to one indistinguishable rejection — no existence oracle); runaway/DoS is bounded by depth-cap + auto-enroll rate-limit applied before the factory in dispatcher order; WARN logs are ids/enum-codes only (no log injection / PII). A06/A02/A07/A10/A05 are N/A (no new packages, secrets, auth surface, user-controlled URLs, or config).
**Deviations:** None. Read-only audit; no source modified. No subagent-spawning tool exists here, so the audit was performed inline against the loaded `security-auditor` methodology.

**Reviews:**

None — audit task, its result is the review.

**Verification:**
- Full JSON report → [logs/working/audit/security-auditor.json]
- Overall verdict: clean — feature ready to deploy, no security fixes required before release.

---

## Task 7: Test Audit

**Status:** Done
**Agent:** test-auditor
**Summary:** Full-feature test-quality audit — verdict CLEAN. Every user-spec AC and tech-spec Testing-Strategy item is covered by behaviour-asserting tests (enrollDepth=parent+1, inherited telegramBotId, currentStepId, parent status, both backstops with distinct greppable WARNs, re-enter no-op/restart, fail-closed cross-project, return-to-menu, self-target, test-run delivery); engine-IT is deterministic (local @Primary MutableClock, per-test MockWebServer baseline+drain, Redis-key cleanup, Redis-counter rate-limit with no wall-clock wait). 0 critical / 0 major; 2 minor (re-enter no-op logged at INFO vs AC's "greppable-WARN" wording — intent met; SUBSCRIBE non_message preview pinned only generically via DELAY) + 2 info, all non-blocking. Full suite re-run green. No blockers for Task 8.
**Deviations:** None — audit task, read-only, no code changed. Save/activation validation correctly lives in FunnelServiceEmitEventTest (an integration test) because validateSteps now does a DB lookup; report under gitignored work/ (no commit).

**Reviews:**

- Audit-task (its output IS the review) → [logs/working/audit/test-auditor.json]

**Verification:**
- `./gradlew test --tests '*StepTypeTest*' --tests '*FunnelStepExecutorTest*' --tests '*FunnelServiceEmitEventTest*'` → BUILD SUCCESSFUL
- `./gradlew test --tests '*FunnelExecutionEngineIT*' --tests '*FunnelTestRunSendIT*' -PrunSlow=true` → BUILD SUCCESSFUL (28s)
- `vitest run tests/pages/funnel-editor.spec.ts` → 45 passed (incl. 6 SUBSCRIBE_TO_FUNNEL cases)

---

## Ad-hoc: Audit fixes

**Status:** Done
**Commit:** f1c6512
**Agent:** audit-fixer
**Summary:** Addressed the 2 minor test-audit findings: changed the re-enter no-op log (LOG_ENROLL_REENTER_IGNORED in FunnelEventService.insertTargetExecution) from INFO to WARN to match the greppable-WARN spec (tech-spec line 170) and tightened the corresponding FunnelExecutionEngineIT assertion from infoAndWarn() to warn() (removing the now-unused helper); added a focused FunnelControllerIT test pinning SUBSCRIBE_TO_FUNNEL → non_message preview placeholder, where previously only DELAY covered the path generically.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: OK (0 findings) → [logs/working/audit/audit-fixer-code-reviewer.json]
- test-reviewer: OK (0 findings) → [logs/working/audit/audit-fixer-test-reviewer.json]

**Verification:**
- `./gradlew test --tests '*FunnelExecutionEngineIT*' --tests '*FunnelControllerIT*' --tests '*FunnelServiceEmitEventTest*' -PrunSlow=true` → BUILD SUCCESSFUL (34s)

---

## Task 8: Pre-deploy QA

**Status:** Done
**Agent:** qa-runner
**Summary:** QA verdict GO (conditional on the user-run live-Telegram scenario). Funnel module 248/248 backend tests green incl. slow ITs; frontend 459/459 vitest green + locale parity OK. 22 acceptance criteria checked: 20 PASS, 0 FAIL, 1 DEFERRED-TO-USER (live E2E A→B→menu A), 1 not-verifiable-headless folded into that deferral. All three Audit-Wave verdicts CLEAN; the 2 test-audit minors were already fixed in f1c6512.
**Deviations:** None. The broader `./gradlew test -PrunSlow=true` had 1 unrelated flaky failure — `TelegramWebhookP99IT` P99 latency 101ms vs <100ms threshold, in the webhook module, no feature code touched — recorded as non-blocking, not a feature regression.

**Deferred to user:** 1 criterion (E2E-1: live Telegram A→B→menu A with inline-button webhooks) requires a tunnel + live bot — cannot run headless. Repro steps in qa-report.json `deferredToUser`.

**Reviews:**

- QA task (its output IS the review).

**Verification:**
- `./gradlew test --tests 'com.botfunnel.funnel.*' -PrunSlow=true` → BUILD SUCCESSFUL (248 tests, 0 failures)
- `./gradlew test -PrunSlow=true` → 1135 tests, 1 unrelated flaky failure (webhook P99 SLA), funnel module fully green
- `node ./node_modules/vitest/vitest.mjs run` → 459 passed (51 files; 6 SUBSCRIBE_TO_FUNNEL cases)
- `node scripts/check-locales.mjs` → exit 0 (uk/en parity OK)
- Full report: [logs/working/qa-report.json]
