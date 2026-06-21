# Decisions Log: 16-persistent-keyboard

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

## Task 1: Backend step model + validation

**Status:** Done
**Commit:** 5d6cf37 (impl), 8c6ae9d (review round 1 fix)
**Agent:** backend-model
**Summary:** Added `SET_KEYBOARD`/`CLEAR_KEYBOARD` step types — two `StepType` constants (before `UNKNOWN`), five nullable `FunnelStep` fields carried through `copyOf` with a defensive `keyboardRows` list copy (user-spec Risk 1), `KeyboardRow`/`KeyboardButton` records + DTO mirrors, three-point wiring (`FunnelStepDto` + `toSteps` + `toStepDto`), and `validateSetKeyboard`/`validateClearKeyboard` per Decision 6 (text non-blank ≤4096 hard cap, parse mode, 1..10 rows × 1..4 buttons, button text non-blank ≤64, trimmed-duplicate blocking; CLEAR_KEYBOARD strict-rejects the SET_KEYBOARD-only fields). Mappers carry null row/button shapes verbatim so validation returns 422, never NPE. `StepExecutor` got terminal-fail compile-fix cases (Task 3 replaces with real send logic).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 0 blocking/major (3 minor/info, advisory) → [logs/working/task-1/code-reviewer-round1.json](logs/working/task-1/code-reviewer-round1.json)
- security-auditor: approved, 0 blocking/major (2 info, both Task 3 concerns) → [logs/working/task-1/security-auditor-round1.json](logs/working/task-1/security-auditor-round1.json)
- test-reviewer: passed, 0 blocking/major (2 minor + 1 info) → [logs/working/task-1/test-reviewer-round1.json](logs/working/task-1/test-reviewer-round1.json)

Applied test-reviewer's actionable minor: split the merged accept-boundary test and added a button-label round-trip assertion at the 64-char boundary (8c6ae9d). Other minors documented as intentional (one-code/varying-message contract; message-text assertions are against project convention).

**Verification:**
- `cd backend && ./gradlew test --tests '*FunnelStepTest*' --tests '*FunnelServiceKeyboardStepTest*' --tests '*FunnelControllerIT*'` → green
- `cd backend && ./gradlew test` (full default lane) → BUILD SUCCESSFUL, no regressions

## Task 2: Frontend step form + i18n

**Status:** Done
**Commit:** 0af792e (impl), 17654f7 (review round 1 fix), HEAD (review round 2 fix)
**Agent:** frontend-form
**Summary:** Added editor support for `SET_KEYBOARD`/`CLEAR_KEYBOARD` in `FunnelStepForm.vue` — the `StepType` union + `KeyboardButton`/`KeyboardRow` TS mirrors + five nullable `FunnelStep` fields, two per-type `v-if` form blocks (shared mandatory text + parse mode; SET-only rows sub-editor on the composer local-reactive-array idiom with hand-validation mirroring Decision 6 byte-for-byte — non-blank text ≤4096 HARD block, 1..10 rows × 1..4 buttons, button text ≤64, trimmed-duplicate blocking; «Постійна» default-on / «Сховати після натискання» default-off checkboxes, no `resize_keyboard`), the per-button non-blocking amber keyword hint (Decision 8 `containsAnyKeyword` mirror over active keyword funnels in `useFunnelsStore`), and `onSubmit` cases preserving `id`/`next` with CLEAR_KEYBOARD emitting no SET-only fields. Added the exhaustive `FunnelStepsList.summary()` cases for both types and all new uk/en strings (parity gate green). New `FunnelStepForm.keyboard.spec.ts` (19 cases).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 0 blocking/major (1 minor) → [logs/working/task-2/code-reviewer-round1.json](logs/working/task-2/code-reviewer-round1.json)
- security-auditor: clean, 0 findings → [logs/working/task-2/security-auditor-round1.json](logs/working/task-2/security-auditor-round1.json)
- test-reviewer: passed, 0 blocking/major (3 minor) → [logs/working/task-2/test-reviewer-round1.json](logs/working/task-2/test-reviewer-round1.json)

Applied: clarified the 4096-cap comment (verified against backend `requireKeyboardText` — raw length is correct parity, the code-reviewer minor was a false positive), and hardened three tests (count-based no-resize check, a CLEAR blank-text case, a pinned seed-count). 

*Round 2 (after fixes):*
- code-reviewer: approved, 0 findings → [logs/working/task-2/code-reviewer-round2.json](logs/working/task-2/code-reviewer-round2.json)
- security-auditor: clean, 0 findings → [logs/working/task-2/security-auditor-round2.json](logs/working/task-2/security-auditor-round2.json)
- test-reviewer: passed, 0 blocking/major (1 minor) → [logs/working/task-2/test-reviewer-round2.json](logs/working/task-2/test-reviewer-round2.json)

Applied the residual minor: broadened the no-resize assertion to count all `step-keyboard*` checkboxes (catches a future resize toggle).

**Verification:**
- `cd frontend && pnpm test tests/components/funnels/FunnelStepForm.keyboard.spec.ts` → 19 passed
- `cd frontend && pnpm test` (full suite) → 506 passed (54 files), no regressions
- `cd frontend && node scripts/check-locales.mjs` (prebuild parity gate) → uk/en parity OK

## Task 4: Backend preview extension

**Status:** Done
**Commit:** 5ac483e (impl), b0837f3 (review round 1 fix)
**Agent:** backend-preview
**Summary:** Extended the step-preview surface for keyboard steps (Decision 7): `PreviewStepRequest` gains `keyboardText`/`keyboardParseMode`/`keyboardRows`, `PreviewStepResponse` gains a `"keyboard"` `kind` value + nullable `keyboardRows` (raw `List<List<String>>` labels). `FunnelService.previewStep` branches on the SAVED step's type — SET_KEYBOARD/CLEAR_KEYBOARD render the request's text through `VariableTemplateRenderer` (escaping per parse mode) as one TEXT block; SET_KEYBOARD echoes button labels VERBATIM (labels are the keyword link, never variable-rendered) but CLAMPED to the validation caps (≤10 rows × ≤4 buttons, label ≤64 — reusing the Task 1 constants), CLEAR_KEYBOARD returns null rows. Anti-IDOR ordering, `@JsonIgnoreProperties` mass-assignment defense, and the no-identity-leak contract are all preserved.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 0 blocking/major (2 minor, by-design) → [logs/working/task-4/code-reviewer-round1.json](logs/working/task-4/code-reviewer-round1.json)
- security-auditor: approved, 0 blocking/major (2 minor, non-exploitable) → [logs/working/task-4/security-auditor-round1.json](logs/working/task-4/security-auditor-round1.json)
- test-reviewer: needs_improvement, 1 major + 3 minor → [logs/working/task-4/test-reviewer-round1.json](logs/working/task-4/test-reviewer-round1.json)

Applied: added the load-bearing major — a keyboard-text parseMode-escaping IT (hostile `<script>` + HTML → escaped, raw body lacks the unescaped tag) that pins the A03/XSS-escape AC for the keyboard render path; plus the three minors (stub-subscriber fallback, blank-text/null-rows path, 64-char clamp boundary). Clarified `clampKeyboardLabels`'s null-vs-empty contract in the doc comment (code-reviewer minor) — behavior intentionally kept (preview faithfully mirrors in-progress form state).

*Round 2 (after fixes):*
- code-reviewer: approved, 0 findings → [logs/working/task-4/code-reviewer-round2.json](logs/working/task-4/code-reviewer-round2.json)
- security-auditor: approved, 0 findings → [logs/working/task-4/security-auditor-round2.json](logs/working/task-4/security-auditor-round2.json)
- test-reviewer: passed, 0 findings → [logs/working/task-4/test-reviewer-round2.json](logs/working/task-4/test-reviewer-round2.json)

**Verification:**
- `cd backend && ./gradlew test --tests '*FunnelControllerIT.preview*'` → green (9 keyboard preview cases + all pre-existing preview cases)
- `cd backend && ./gradlew test` (full default lane) → BUILD SUCCESSFUL, no regressions

## Task 3: StepExecutor execution of both steps

**Status:** Done
**Commit:** d4fb7f6 (impl), 7684c79 (review round 1 fix)
**Agent:** executor
**Summary:** Replaced the Task 1 terminal-fail compile-fix cases with real send logic: two `StepExecutor.execute` cases delegating to private handlers — SET_KEYBOARD builds a `ReplyKeyboardMarkup` Map (`{"keyboard":[[{"text":…}]], "is_persistent":…, "resize_keyboard":true, "one_time_keyboard":…}`, buttons in object form, `resize_keyboard` hardcoded true, `is_persistent`/`one_time_keyboard` falling back to form defaults true/false when the snapshot field is null), CLEAR_KEYBOARD uses a constant `{"remove_keyboard":true}` markup; both render mandatory `keyboardText` via the shared `renderTrimmed` (4096 cap, codes-only WARN), send one message via the existing 6-arg `sender.sendText` with null ownerId, and return `cont()` (fire-and-forget — never parks). A shared `sendKeyboardText` helper carries the byte-for-byte error mapping of every send step; SET_KEYBOARD has a defensive guard (null/empty `keyboardRows` or a row with empty buttons → terminal `fail("empty_keyboard_rows")`, no send) mirroring `empty_message_blocks`.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 0 blocking/major (1 minor + 2 info) → [logs/working/task-3/code-reviewer-round1.json](logs/working/task-3/code-reviewer-round1.json)
- security-auditor: clean, 0 findings → [logs/working/task-3/security-auditor-round1.json](logs/working/task-3/security-auditor-round1.json)
- test-reviewer: 0 blocking, 1 major + 1 minor (untested `isEmptyRow` defensive branch; no keyboard-path 4096-trim/PII test) → [logs/working/task-3/test-reviewer-round1.json](logs/working/task-3/test-reviewer-round1.json)

Applied: added `setKeyboard_rowWithNoButtons_failsTerminally` (covers the empty-buttons-row guard branch) and `setKeyboard_trimsTextOver4096AndWarns_pii` (pins the 4096-trim + codes-only-WARN AC on the keyboard path).

*Round 2 (after fixes):*
- code-reviewer: approved, 0 findings → [logs/working/task-3/code-reviewer-round2.json](logs/working/task-3/code-reviewer-round2.json)
- security-auditor: clean, 0 findings → [logs/working/task-3/security-auditor-round2.json](logs/working/task-3/security-auditor-round2.json)
- test-reviewer: passed, 0 findings → [logs/working/task-3/test-reviewer-round2.json](logs/working/task-3/test-reviewer-round2.json)

**Verification:**
- `cd backend && ./gradlew test --tests '*FunnelStepExecutorTest*'` → BUILD SUCCESSFUL (36 tests, incl. 11 new keyboard cases)
- `cd backend && ./gradlew test` (full default lane) → BUILD SUCCESSFUL, no regressions (note: concurrent parallel-agent gradle runs intermittently collide on the shared `build/test-results` XML files — environmental I/O race, not a test failure; verified green on isolated runs)

## Task 7: Manual smoke checklist (smoke.md)

**Status:** Done
**Commit:** N/A (smoke.md lives under gitignored `work/` — not committed)
**Agent:** smoke-writer
**Summary:** Authored `work/16-persistent-keyboard/smoke.md` — the Ukrainian manual verification checklist the author runs with a real connected bot. Covers the full user-spec «Пользователь проверяет» chain (menu funnel with SET_KEYBOARD → persistent keyboard visible + surviving `completed` → button tap starts the keyword funnel → bonus scenario end-to-end with both branches restoring the main menu → CLEAR_KEYBOARD removes the keyboard), plus editor spot-checks and a Troubleshooting section flagging the consciously-accepted keyword-mechanism properties (contains-match, menu precedence, fan-out, dead button, re-enter guard, no keyboard stack) as NOT bugs. Format adapted from the 09-subscribers precedent (Передумови / numbered Кроки / results checklist / Troubleshooting / Sign-off), stripped of staging-only machinery; all UI labels verified verbatim against `frontend/i18n/locales/uk.json`.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 0 blocking/major (2 minor + 1 info) → [logs/working/task-7/code-reviewer-round1.json](logs/working/task-7/code-reviewer-round1.json)

Applied both actionable minors: defined `{APP_URL}` for the plain-localhost health-check case (self-containment), and added a menu-precedence caveat to the bonus-funnel setup so a restoring SET_KEYBOARD is not parked behind an inline menu in the same branch.

**Verification:**
- Self-review per task: full «Пользователь проверяет» chain present and in order; terminology matches the implemented editor (uk.json); fully Ukrainian; bot token referenced only as `<token>` placeholder (no secrets).

## Task 6: Engine slow-lane wire-format ITs

**Status:** Done
**Commit:** 959907c (impl 34284ab + review fix 959907c)
**Agent:** engine-it
**Summary:** Added the feature's slow-lane integration coverage to `FunnelExecutionEngineIT` — the first engine ITs that assert the actual `/sendMessage` request body: a SET_KEYBOARD sweep pins the exact `reply_markup` (object-form `keyboard` rows, `is_persistent`, hardcoded `resize_keyboard:true`, `one_time_keyboard`) and `completed`-not-parked status, a CLEAR_KEYBOARD sweep pins `remove_keyboard:true`, and a keyword-dispatch test proves a button label starts the matching keyword funnel via `FunnelEventService.dispatchForSubscriber` (depth 0, case-insensitive contains-match). Body-assert idioms borrowed verbatim from `TelegramSenderIT` (JsonNode walk) and `FunnelTestRunSendIT` (takeRequest/readUtf8). Added a `drainRecordedRequests()` helper to clear the JVM-singleton MockWebServer's cumulative request-log backlog before each body-asserting sweep so `takeRequest()` pops the test's own request, not a stale one.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 0 blocking/major (1 minor, 2 info) → [logs/working/task-6/code-reviewer-round1.json](logs/working/task-6/code-reviewer-round1.json)
- test-reviewer: passed, 0 blocking/major (2 info) → [logs/working/task-6/test-reviewer-round1.json](logs/working/task-6/test-reviewer-round1.json)

Applied the substantive test-reviewer note: seeded the NON-default keyboard booleans (`is_persistent=false`, `one_time_keyboard=true`) so a builder that ignored the step fields and emitted null-fallback defaults would fail loudly. Kept the task-mandated `isNotEqualTo(waiting_for_reply)` assertion (code-reviewer flagged it as tautological after `isEqualTo(completed)`, but the task's Acceptance Criteria + Edge Cases explicitly require it as the documented no-park contract).

**Verification:**
- `cd backend && ./gradlew test -PrunSlow=true --tests '*FunnelExecutionEngineIT*'` → BUILD SUCCESSFUL (60 tests, incl. 3 new keyboard wire-format cases; no regressions). Requires Docker (Testcontainers Mongo+Redis) — verified green twice (post-impl + post-fix).

## Task 5: Frontend preview panel

**Status:** Done
**Commit:** cab46b1 (impl), 67b8d91 (review round 1 fix)
**Agent:** frontend-preview
**Summary:** Extended `FunnelMessagePreview.vue` to preview the two keyboard step types: a renderable-step gate (`isRenderableStep` = MESSAGE | SET_KEYBOARD | CLEAR_KEYBOARD) replaces the MESSAGE-only gate, keyboard steps send a `{stepType, keyboardText, keyboardParseMode, keyboardRows}` payload (MESSAGE still sends `blocks`), the watch tuple gained the three keyboard fields, and a dedicated `kind === 'keyboard'` template branch renders the backend-rendered text (single TEXT block, `{{ }}` only — never v-html) plus, for SET_KEYBOARD, a bottom-keyboard mock built from the RESPONSE `keyboardRows` (server-clamped, one source of truth) styled distinctly from the inline-button chips. Mirrored the Task-4 DTO contract in `types/funnel.ts` (`kind` union gains `'keyboard'`, response `keyboardRows: string[][] | null`, request gains the keyboard fields), added `previewKeyboardCaption`/`previewKeyboardAria` in both locales, and 7 new spec cases.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 0 blocking/major (3 minor) → [logs/working/task-5/code-reviewer-round1.json](logs/working/task-5/code-reviewer-round1.json)
- security-auditor: approved, 0 findings → [logs/working/task-5/security-auditor-round1.json](logs/working/task-5/security-auditor-round1.json)
- test-reviewer: passed, 0 blocking/major (3 minor) → [logs/working/task-5/test-reviewer-round1.json](logs/working/task-5/test-reviewer-round1.json)

Applied: the empty-`renderedBlocks` keyboard desync test, `toHaveBeenLastCalledWith` payload assertions on the debounce test, and the stale top-of-file `funnel.ts` header-comment fix. Two component minors (dev-warn on a dropped empty row; empty-text-box cosmetic) left as-is — both reviewer-marked optional/by-design (the empty-row filter is defensive over a backend-guaranteed shape; the empty TEXT box intentionally matches the MESSAGE branch).

*Round 2 (after fixes):*
- code-reviewer: approved, 0 findings → [logs/working/task-5/code-reviewer-round2.json](logs/working/task-5/code-reviewer-round2.json)
- security-auditor: approved, 0 findings → [logs/working/task-5/security-auditor-round2.json](logs/working/task-5/security-auditor-round2.json)
- test-reviewer: passed, 0 blocking/major (1 minor, redundant with the full-payload test) → [logs/working/task-5/test-reviewer-round2.json](logs/working/task-5/test-reviewer-round2.json)

**Verification:**
- `cd frontend && node_modules/.bin/vitest run tests/components/funnels/FunnelMessagePreview.spec.ts` → 29 passed (incl. the no-v-html source guard)
- `cd frontend && node_modules/.bin/vitest run` (full suite) → 513 passed (54 files), no regressions
- `cd frontend && node scripts/check-locales.mjs` (prebuild parity gate) → uk/en parity OK
- Note: the harness shell had no node/pnpm on PATH; tests were run via the absolute v24 node through the vitest shim (the `pnpm test` / `pnpm build` task commands are equivalent). User check pending (Verify-user).

## Task 8: Code Audit

**Status:** Done
**Commit:** N/A (audit report lives under gitignored `work/` — not committed)
**Agent:** auditor-code
**Summary:** Holistic full-feature code-quality audit (read-only, the auditor IS the review). Verdict **CLEAN** — verified all four cross-component axes byte-for-byte: validation mirror parity (backend Decision 6 ↔ FunnelStepForm.vue), `copyOf` completeness (all 5 fields, defensive row copy, null-safe), wire-builder consistency (`StepExecutor` vs Decision 5 JSON — object-form buttons, hardcoded `resize_keyboard:true`, `remove_keyboard:true`, error trio identical to `message()`), and DTO↔TS mirrors (both `toSteps`/`toStepDto` legs, `kind` union, positional ctor order). Architectural patterns match Phases 1-6 (flat records, `*Touched` idiom, no v-html, locale parity); Not-modified invariants confirmed via dev↔main diff (TelegramSender/webhook/engine/converters untouched); Task 1's temporary executor cases properly replaced by Task 3. Report → [logs/working/audit/code-audit.json](logs/working/audit/code-audit.json).
**Deviations:** None.

**Reviews:**

*Round 1:*
- (Audit Wave — no reviewers; the auditor is the review.) 0 blocking, 0 major, 3 info (all by-design) → [logs/working/audit/code-audit.json](logs/working/audit/code-audit.json)

**Verification:**
- Completeness greps (`SET_KEYBOARD`/`CLEAR_KEYBOARD`, `resize_keyboard`, `v-html`, i18n key parity) → all expected touchpoints present, nothing leaked.
- `git diff --stat main...dev` → 23 files, scope matches tech-spec exactly; no out-of-scope changes.

## Task 10: Test Audit

**Status:** Done
**Commit:** N/A (audit artifact under gitignored `work/` — not committed)
**Agent:** auditor-tests
**Summary:** Full-feature test-quality audit (read-only, the auditor IS the review) — verdict **passed**. All 13 user-spec acceptance criteria map to concrete tests (12 Covered, 1 Partial-by-design: i18n parity via the Task 11 prebuild gate, not a Vitest spec); load-bearing assertions are behavioral (engine slow-lane ITs parse the `/sendMessage` JsonNode body for the exact `reply_markup` shape with non-default booleans seeded; executor captures the markup Map; controller IT round-trips every keyboard field through PUT+GET; `copyOf` pins new-list-instance + mutation isolation; the no-v-html source guard still scans the real component source). All four cited "not rewritten" tests (two re-enter-guard, keyword dispatch, menu precedence) verified real, enabled, asserting the cited semantics. Pyramid balanced (unit-heavy, slow-lane ITs only for wire/dispatch invariants, no E2E by design). Findings: 0 blocker, 0 major, 2 minor, 4 info — no code/test changes required for sign-off. Report → [logs/working/audit/test-auditor.json](logs/working/audit/test-auditor.json).
**Deviations:** None.

**Reviews:**

*Round 1:*
- (Audit Wave — no reviewers; the auditor is the review.) 0 blocking, 0 major, 2 minor + 4 info → [logs/working/audit/test-auditor.json](logs/working/audit/test-auditor.json)

**Verification:**
- Read-only audit; suites not re-run (Task 11 QA runs all lanes). No `@Disabled` in any cited test file; all four cited existing tests confirmed present under their exact names.

## Task 9: Security Audit

**Status:** Done
**Commit:** N/A (audit report lives under gitignored `work/` — not committed)
**Agent:** auditor-security
**Summary:** Full-feature OWASP Top 10 audit of the read-only final state of all tasks-1–7 source files (backend step model/records/DTOs, validation, StepExecutor cases + markup builders, preview branch; frontend types/form/preview + locales). Verdict **PASS**, 0 critical / 0 major / 0 minor / 8 info — zero findings; the five tech-spec focus areas are all held invariants, confirmed explicitly with file:line evidence. One benign normalization note (editor emits trimmed labels; backend stores verbatim, trim-based dup check on both sides) flagged for the code audit, not a security gap. Report → [logs/working/audit/security-auditor.json](logs/working/audit/security-auditor.json).
**Deviations:** None.

**Reviews:**

*Audit Wave (security-auditor IS the review — no separate reviewers):*
- security-auditor: PASS, 0 critical/major/minor (8 positive confirmations) → [logs/working/audit/security-auditor.json](logs/working/audit/security-auditor.json)

Focus-area outcomes (all CONFIRMED, no defect): (1) preview XSS — backend renders keyboard text via `VariableTemplateRenderer` escaped per parse mode, labels echoed verbatim but clamped (≤10×4, ≤64); frontend keyboard branch is `{{ }}`-only, zero v-html (A03). (2) Validation completeness — caps 10×4, text ≤4096 HARD, label ≤64, trimmed-duplicate block, CLEAR_KEYBOARD strict field rejection, null shapes → 422 not NPE (A03/A04). (3) Log hygiene — keyboard cases + error mapping log ids/codes/lengths only, never text/labels/payloads (A09). (4) Preview IDOR — `requireOwned` first, projectId-scoped funnel then step lookup, saved-type gate, fail-closed uniform 404 (A01). (5) Wire-body injection — `reply_markup` as LinkedHashMap/List, buttons object-form `{"text":…}`, Jackson-serialized verbatim, no concatenation, no sibling-key injection (A03). Beyond the five: mass-assignment (no identity fields in new DTOs, `@JsonIgnoreProperties`), variable-substitution injection (single-pass non-recursive renderer), SSRF (A10 N/A — labels carry no URL), and `copyOf` snapshot isolation all confirmed clean.

**Verification:**
- Read-only audit — no code modified, no tests run. Report JSON written to `logs/working/audit/security-auditor.json`; each of the five focus areas has an explicit positive confirmation with severity/location/OWASP category.

## Task 11: Pre-deploy QA

**Status:** Done
**Commit:** N/A (QA report lives under gitignored `work/` — not committed)
**Agent:** qa
**Summary:** Acceptance testing of the full feature before `main`. All automatable lanes pass — backend default lane (1157 tests, 0 failures), backend slow lane / Testcontainers (1239 tests, 0 failures, incl. the three keyboard engine ITs asserting exact `reply_markup` wire bodies + `completed`-not-parked), and uk/en locale parity (657 keys each side, zero diff). All 13 user-spec ACs and all 7 tech-spec ACs are satisfied with concrete test evidence; the three audit waves (code/security/test) are clean/passed with zero blocking or major findings. Overall verdict: **pass-with-deferred**. Report → [logs/working/qa/pre-deploy-qa-report.json](logs/working/qa/pre-deploy-qa-report.json).
**Deviations:** Two environment-driven substitutions (not feature defects): (1) `pnpm test` / `pnpm build` could not be executed — every node-binary invocation (incl. `node --version`) was denied by the harness permission layer this session; the prebuild locale-parity gate's exact algorithm (`check-locales.mjs`) was replicated faithfully in Python (zero diff), and frontend green is corroborated by both spec files present (19 + 29 cases), the Task 10 test-audit 'passed' verdict, and decisions.md Task 2/5 reporting the suite green at authoring with no frontend source changed since. (2) AVP local API run (step 4) DEFERRED — funnel endpoints need an auth session and a live bootRun sends to the real Telegram API (no MockWebServer outside the IT harness, no bot token in scope), so the wire body is not observable locally; the sanctioned engine-IT fallback (identical payload through the full sweep cycle) applies.

**Deferred to user (smoke.md):** the four real-Telegram-client manual checks — persistent keyboard visible + survives execution end, live tap starts the keyword funnel, bonus scenario end-to-end (both branches), CLEAR_KEYBOARD removes the keyboard. `work/16-persistent-keyboard/smoke.md` exists and covers all four (Steps 2–5 + result checklist).

**Reviews:**

*Final Wave (QA is its own verification — no reviewers):*
- Audit-wave inputs reviewed before verdict: code-audit (clean), security-auditor (PASS), test-auditor (passed) — all 0 blocking/major.

**Verification:**
- `cd backend && ./gradlew test` → 1157 passed, 0 failed, 2 skipped (BUILD SUCCESSFUL)
- `cd backend && ./gradlew test -PrunSlow=true` → 1239 passed, 0 failed, 2 skipped; FunnelExecutionEngineIT 60/0/0 incl. all 3 keyboard cases (BUILD SUCCESSFUL, Docker up)
- Frontend suite → green (not executed this session — see Deviations; corroborated by test-audit + spec presence)
- Locale parity (`check-locales.mjs` algorithm) → uk 657 / en 657, zero diff (PASS); 23 keyboard keys both sides
- No source modified by QA.

---

## Post-delivery UX change: keyboard behaviour radio (replaces two checkboxes)

**Date:** 2026-06-13
**Status:** Done
**Commit:** (pending — frontend-only, on `dev`)
**Agent:** lead (user-requested)
**Trigger:** User noticed the SET_KEYBOARD form showed two simultaneous checkboxes — «Постійна» (`is_persistent`) and «Сховати після натискання» (`one_time_keyboard`) — and asked whether that was correct / whether a radio fits better.

**Decision:** Replace the two independent checkboxes with a single 3-way **radio group** «Поведінка клавіатури»:
- **Постійна** — `is_persistent=true,  one_time=false` (default)
- **Звичайна** — `is_persistent=false, one_time=false`
- **Одноразова** — `is_persistent=false, one_time=true`

Each option carries an inline hint line (the hint the user originally asked for is now built into the choice). The radio collapses the 4 boolean combinations into the 3 meaningful, mutually-exclusive behaviours and **removes the contradictory `is_persistent=true + one_time=true`** combo the two checkboxes allowed.

**Rationale:** The two Telegram fields are semantically conflicting when both true (clients favour `is_persistent`, so `one_time` is silently ignored) — a foot-gun in the old UI. The three behaviours are mutually exclusive, which is exactly what a radio models.

**Contract unchanged:** Backend / DTO / preview / engine ITs / wire format are untouched — the form still emits the same two boolean fields. Mapping lives entirely in `FunnelStepForm.vue`: mode → booleans on submit, booleans → mode on edit-load. **Persistent wins** on a contradictory stored `true+true` seed (matches Telegram client behaviour).

**Deviation from approved spec:** Supersedes user-spec AC «Форма має два чекбокси: …» (user-spec.md line 68). The wire-format AC (line 71) and `resize_keyboard` hardcoding (line 153) are unaffected.

**Files changed:**
- `frontend/components/funnels/FunnelStepForm.vue` — `keyboardPersistent`/`keyboardOneTime` refs → single `keyboardMode` ref + radio fieldset; submit/edit mapping.
- `frontend/i18n/locales/{uk,en}.json` — dropped `keyboardPersistent`/`keyboardOneTime`; added `keyboardModeLabel` + `keyboardMode_{persistent,normal,oneTime}` (+ `_hint` each).
- `frontend/tests/components/funnels/FunnelStepForm.keyboard.spec.ts` — checkbox assertions → radio; +4 cases (Normal-mode emit, One-time-mode emit, both-false→Normal seed, contradictory true+true→Persistent seed).

**Verification:**
- `vitest run tests/components/funnels/FunnelStepForm.keyboard.spec.ts` → 23/23 passed.
- `vitest run` (full suite) → 517/517 passed (was 513, +4 new).
- `check-locales.mjs` → exit 0 (uk/en parity held).
