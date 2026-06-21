# Decisions Log: 10-funnels

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Task 1: Доменна модель + персистенція воронок

**Status:** Done
**Commit:** 8f0bc87 (impl), 9b5437b (review round 1 fixes)
**Agent:** main agent (dispatched impl + review subagents)
**Summary:** Створено пакет-фундамент `com.botfunnel.funnel`: сутності `Funnel`/`FunnelStep`/`FunnelExecution`, lowercase enum-и статусів (Decision 14) + `StepType`, два `MongoRepository` з вузькою поверхнею, annotation-driven `@CompoundIndexes` з partial-unique guard'ами (active-trigger конфлікт, re-enter `$in[running,waiting]`) і static class-load assert'ами байт-match з `.name()`. `FunnelStep.copyOf` дає deep-copy для snapshot (Decision 3).
**Deviations:** `FunnelExecutionRepository` лишено без query-методів (порожній маркер) — каскад у Task 8 йде через `MongoTemplate`, derived-метод був би мертвим кодом (правило "no dead methods").

**Reviews:**

*Round 1:*
- code-reviewer: 1 major + 2 minor → [logs/working/task-1/code-reviewer-1.json]
- security-auditor: OK (2 low notes) → [logs/working/task-1/security-auditor-1.json]
- test-reviewer: 2 minor + 2 low → [logs/working/task-1/test-reviewer-1.json]

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-1/code-reviewer-2.json]
- test-reviewer: OK → [logs/working/task-1/test-reviewer-2.json]

**Verification:**
- `./gradlew test --tests FunnelStatusEnumTest --tests FunnelStepTest` → pass
- `./gradlew test -PrunSlow=true --tests FunnelIndexesIT` → 4 tests pass (index metadata + content asserted)
- `./gradlew test` (full suite) → 789 tests, 0 failures

---

## Task 2: VariableTemplateRenderer (чиста утиліта)

**Status:** Done
**Commit:** be0282f (impl), 10bf269 (review round 1 fixes)
**Agent:** main agent (dispatched impl + review subagents)
**Summary:** Чиста статична утиліта `VariableTemplateRenderer.render(template, parseMode, subscriber)`: підстановка `{user.*}`/`{custom.<key>}`, `{{`/`}}`-escape, human-рендер Double(30.0→30)/Boolean/Instant(ISO-8601), unknown/empty→"". Екранування ТІЛЬКИ підставлених значень за parseMode (None/HTML/MarkdownV2), авторська розмітка недоторкана (Decision 10 / A03). Без I/O, Spring, PII-логів.
**Deviations:** None. Сигнатура приймає `Subscriber` напряму (узгоджено з call-site Task 6), `parseMode` як raw `String` (як `FunnelStep.parseMode`).

**Reviews:**

*Round 1:*
- code-reviewer: OK (3 minor) → [logs/working/task-2/code-reviewer-1.json]
- security-auditor: 1 CRITICAL + 1 major + 2 minor → [logs/working/task-2/security-auditor-1.json]
- test-reviewer: OK (2 minor) → [logs/working/task-2/test-reviewer-1.json]

*Round 2 (after fixes):*
- security-auditor: OK → [logs/working/task-2/security-auditor-2.json]
- code-reviewer: OK → [logs/working/task-2/code-reviewer-2.json]
- test-reviewer: OK → [logs/working/task-2/test-reviewer-2.json]

**Key fix:** CRITICAL — MarkdownV2 не екранував `\` (інʼєкція розмітки через поле підписника); додано self-escape `\`. MAJOR — HTML тепер екранує `"`→`&quot;` (attribute-breakout). Закріплено regression-тестами.

**Verification:**
- `./gradlew test --tests '*VariableTemplateRenderer*'` → 23 tests pass
- `./gradlew test` (full suite) → pass, no regressions

---

## Task 3: TelegramSender.sendPhoto

**Status:** Done
**Commit:** 183a5b7 (impl), fe86f5b (review round 1 fixes)
**Agent:** main agent (dispatched impl + review subagents)
**Summary:** Додано `sendPhoto(botId, chatId, imageUrl, caption, parseMode, ownerId)` у `TelegramSender`. Спільну оркестрацію (CONNECTED-filter → retry/429/5xx → audit → `dispatchSubscriberHook` → rethrow) винесено у приватний `send(endpoint, contentFields, ...)`, який тепер ділять `sendText` і `sendPhoto` — нуль дублювання, failure-matrix (map4xx/toThrowable/terminalReasonFor) байт-ідентична. Токен лишається local-var на час HTTP-attempt; усі логи через `scrubTokens`. Бекенд НЕ фетчить imageUrl (Telegram сам, без SSRF).
**Deviations:** Рекомендований у задачі "новий generic-шлях" замінено на threading `(endpoint, contentFields)` через наявний private-ланцюг (swap параметрів). Чистіше, без дублювання; незмінність `sendText` підтверджена всіма наявними тестами (code-reviewer OK).

**Reviews:**

*Round 1:*
- code-reviewer: OK (3 informational) → [logs/working/task-3/code-reviewer-1.json]
- security-auditor: CHANGES_REQUESTED → [logs/working/task-3/security-auditor-1.json] — єдина знахідка ПОЗА скоупом задачі: закоментований Telegram-токен у `application.properties:31` (некомітнута зміна робочого дерева, не у файлах Task 3). Сам код sendPhoto пройшов усі security-перевірки (token redaction, no SSRF, no PII logs). Ескальовано користувачу.
- test-reviewer: OK (3 minor) → [logs/working/task-3/test-reviewer-1.json]

*Round 2 (after fixes):*
- test-reviewer: OK → [logs/working/task-3/test-reviewer-2.json]

**Verification:**
- Smoke (MockWebServer failure-matrix): 403→blocked, 400-chat-not-found→deleted, 400-other→OTHER (no flip), 5xx-exhausted, token-no-leak — усі тест-методи зелені.
- `./gradlew test --tests '*TelegramSender*'` → 37 unit + 9 IT pass
- `./gradlew test` (full suite) → pass, no regressions

---

## Task 4: SubscriberCustomFieldsService + lookup-by-chat

**Status:** Done
**Commit:** d01d4ae (impl), feee80d (review round 1), 45f67c3 (review round 2)
**Agent:** main agent (dispatched impl + review subagents)
**Summary:** Винесено логіку set-custom-field у новий request-scope-free `@Service SubscriberCustomFieldsService` (deps: `CustomFieldValueValidator`, `MongoTemplate`): `validateAndNormalize(type,value)` (pure, 422 на mismatch, null-type guard) + `applyAll(projectId,subscriberId,map)` (один агрегований `$set`/`$unset` Update, projectId-scoped) + `setOne(...)` (single-field engine-wrapper, без audit). Контролер рефакторено на two-pass (валідація ВСІХ ключів → один applyAll → один `recordCustomFieldsSet`) — поведінка PATCH байт-ідентична, аудит агрегований один раз. Додано публічний `findByChat(projectId, telegramBotId, chatId): Optional<Subscriber>` у `SubscriberService` (impl піднято з приватного, делегує до наявного репо-метода).
**Deviations:**
- Початковий impl робив validate+write per-key → partial-write regression (валідний раніший ключ персистився до 422 на пізнішому). Виправлено на two-pass all-or-nothing (round 1).
- `setOne` спершу прибрали на користь `validateAndNormalize`+`applyAll`, але повернули (round 2), бо Task 6/tech-spec явно його викликають.
- **NOTE для Task 6 (wave 2):** реальний контракт — `setOne(projectId, subscriberId, CustomFieldType type, String key, Object value)` (5 арг, з резолвленим type) і `setOne` НЕ пише audit. Prose Task 6 (рядки ~87/90) показує 4-арг виклик і твердить, що setOne емітить `recordCustomFieldsSet` — це треба скоригувати: engine має сам викликати `recordCustomFieldsSet` (sole-writer, Decision 11) після `setOne`.

**Reviews:**

*Round 1:*
- code-reviewer: 1 CRITICAL (partial-write) + 2 major + 2 minor → [logs/working/task-4/code-reviewer-1.json]
- security-auditor: OK (2 minor) → [logs/working/task-4/security-auditor-1.json]
- test-reviewer: 2 major + 3 minor → [logs/working/task-4/test-reviewer-1.json]

*Round 2 (after all-or-nothing refactor):*
- code-reviewer: 1 CRITICAL (setOne dropped → breaks Task 6) + 1 minor → [logs/working/task-4/code-reviewer-2.json]
- security-auditor: OK → [logs/working/task-4/security-auditor-2.json]
- test-reviewer: OK → [logs/working/task-4/test-reviewer-2.json]

*Round 3 (after setOne restored):*
- code-reviewer: OK → [logs/working/task-4/code-reviewer-3.json]

**Verification:**
- `./gradlew test --tests '*SubscriberCustomFieldsService*' --tests '*SubscriberCustomFieldsController*' --tests '*SubscriberServiceImpl*'` → pass (11 service + 6 controller + IT)
- `./gradlew test` (full suite) → pass, no regressions

---

## Task 5: Funnel CRUD service + controller

**Status:** Done
**Commit:** 6314ed4 (impl), bb1e813 (review round 1)
**Agent:** main agent
**Summary:** Added `FunnelService` + `FunnelController` under `/api/v1/projects/{projectId}/funnels` (create/list/get/update/delete/activate/pause) plus the `funnel/dto/` records. `requireOwned`-first anti-IDOR with uniform 404 (incl. malformed-id guard), per-type step validation → 422, step-`order` rewrite by array index, cancel-on-delete bulk-update of in-flight executions (lowercase enum `.name()` literals, Decision 14), and active-funnel `deepLink` resolution from the project's CONNECTED bot. Trigger-conflict is defended on BOTH activate and update via service pre-check + partial-unique-index `DuplicateKeyException`→422.
**Deviations:** Per-type step + triggerValue validation is enforced at the service layer (422 with business codes), not as field-level bean validation — the per-type fields are conditional on `stepType` so field annotations cannot express them and would only yield 400. Only `name`/`stepType` use bean validation (400). This matches the TDD anchor (`beanValidation*` expects 400 for missing name, 422 for invalid step/triggerValue).

**Reviews:**

*Round 1:*
- code-reviewer: 1 major (update() active trigger collision → 500) + minors → [logs/working/task-5/code-reviewer-1.json]
- security-auditor: 3 minors (malformed-id anti-enum, CORS PUT, deep-link encoding) → [logs/working/task-5/security-auditor-1.json]
- test-reviewer: OK (3 non-blocking notes) → [logs/working/task-5/test-reviewer-1.json]

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-5/code-reviewer-2.json]
- security-auditor: OK → [logs/working/task-5/security-auditor-2.json]

**Verification:**
- `./gradlew test --tests '*FunnelControllerIT*'` → 25 passed, 0 skipped (all TDD anchors; trigger-conflict covered by both the service pre-check path and a real index-race test)
- `./gradlew test` (full suite) → pass, no regressions

---

## Task 6: FunnelExecutionEngine — sweep + step-runner + executors

**Status:** Done
**Commit:** 92f5816 (impl), 280d452 (review round 1)
**Agent:** main agent
**Summary:** Added `FunnelExecutionEngine` (JobRunr `@Recurring(interval=PT30S)` sweep + step-runner) and `StepExecutor` (per-`StepType` dispatch). At-most-once per step via a single `findAndModify` CAS claim (lowercase enum `.name()` literals, Decision 14); Delay parks via `nextRunAt`→`waiting` (no `Thread.sleep`) and resumes on the next tick; pre-send status-gate + bot-pin check; send-fail maps `TerminalReason` to cancelled/failed without duplicating the sender's subscriber flip; SET_CUSTOM_FIELD resolves the field type from project definitions (deleted → skip, mismatch → failed). Named log constants only, no PII (Decision 16). 14 ITs (Testcontainers + MockWebServer + mutable `@Primary` test Clock declared local to the IT) + 10 executor unit tests.
**Deviations:** Consecutive non-Delay steps run under ONE `in_progress` claim per tick — `stepRunStatus` is NOT reset to `pending` between steps (the task text suggested per-step pending-reset), and returns to `pending` only when the execution yields on a Delay. Rationale: this preserves at-most-once across replicas without per-step re-claim; a crash mid-tick leaves the row `in_progress` (stuck, never re-sent) — the deliberate at-most-once-over-liveness trade-off. The side-effect→persist-advance ordering is the commit point that prevents duplicate sends (proven by the crash + two-replica-race ITs). Doc note: the task's context list references `references/testing.md`, which does not exist in the repo (only `architecture.md`/`patterns.md`/`project.md` etc.) — testing conventions were taken from `patterns.md`.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED, 3 minor + 2 low → [logs/working/task-6/code-reviewer-1.json]
- security-auditor: Task-6 code clean (the lone critical — a live bot token in an UNCOMMITTED `application.properties` comment — is out of scope, not in any commit) → [logs/working/task-6/security-auditor-1.json]
- test-reviewer: PASSED, 4 minor coverage gaps → [logs/working/task-6/test-reviewer-1.json]

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-6/code-reviewer-2.json]
- test-reviewer: OK → [logs/working/task-6/test-reviewer-2.json]

**Verification:**
- `./gradlew test -PrunSlow=true --tests '*FunnelExecutionEngineIT*'` → 14 passed, 0 skipped
- `./gradlew test --tests '*FunnelStepExecutorTest*'` → 10 passed, 0 skipped
- `./gradlew test` (full non-slow suite) → pass, no regressions

---

## Task 7: Реальний FunnelTriggerService (заміна NoOp)

**Status:** Done
**Commit:** de78360 (impl), 9fd6a1c (review round 1 fixes)
**Agent:** main agent
**Summary:** Deleted `NoOpFunnelTriggerService` and implemented the single real `FunnelTriggerServiceImpl`: `fire()` resolves the CONNECTED bot + subscriber (via `SubscriberService.findByChat`), exact-matches the active funnel on `(triggerType, triggerValue)`, inserts a `funnel_execution` with a deep-copy steps snapshot and pinned `telegramBotId`, honours the re-enter guard (unique partial index + `DuplicateKeyException` swallow / atomic cancel-then-insert), and is fully wrapped in `try/catch(Throwable)` so it never throws into the webhook worker (Decision 6). `cancelActiveFor` cancels all running|waiting executions of the subscriber.
**Deviations:** Added a `Clock` constructor dep (mirrors `FunnelExecutionEngine`/`SubscriberServiceImpl`) for execution timestamps — not in the task dep list but the established pattern.

**Reviews:**

*Round 1:*
- code-reviewer: 1 low (wrong cancel log constant) → [logs/working/task-7/code-reviewer-1.json]
- security-auditor: 1 low (defense-in-depth projectId scope) → [logs/working/task-7/security-auditor-1.json]
- test-reviewer: changes_requested — 1 medium (deep-copy test didn't kill shallow mutant) + 2 low → [logs/working/task-7/test-reviewer-1.json]

*Round 2 (after fixes — dedicated LOG_CANCEL_NO_BOT, projectId-scoped cancel queries, snapshot identity guard + nextRunAt window assertion):*
- test-reviewer: OK → [logs/working/task-7/test-reviewer-2.json]

**Verification:**
- `./gradlew test -PrunSlow=true --tests '*FunnelTriggerService*'` → 13 passed, 0 skipped
- `./gradlew test --tests '*ProcessTelegramUpdateJob*'` → pass (interface spy resolves one bean)
- `./gradlew test -PrunSlow=true` (full suite) → 906/907 pass; the only failure, `TelegramWebhookP99IT` (perf p99<100ms), is environmental — fails identically on clean HEAD without these changes.

---

## Task 8: Каскад hard-delete + конфіг/env vars + runbook

**Status:** Done
**Commit:** 4289c9a
**Agent:** main agent
**Summary:** Extended `ProjectHardDeleteJob` to sweep `funnels` + `funnel_executions` by top-level `projectId` (via the existing `removeByProjectId` `.in(deletedIds)` helper) before dropping `projects`, with `funnelsRemoved`/`funnelExecutionsRemoved` log counters on both the zero-deletion and deletion paths. Added `app.funnel.{scheduler-interval,max-steps,sweep-batch-size}` to `application.properties` (keys verified against Task 5/6 consumers) and pinned `org.jobrunr.background-job-server.poll-interval-in-seconds=15` (interval ≥ poll invariant, Decision 2). Documented the three `FUNNEL_*` env vars in `.env.example` + `docs/local-setup.md` and added the manual Telegram runbook `docs/staging-smoke/10-funnels.md`.
**Deviations:** Two env-naming items flagged [PENDING USER APPROVAL] in the spec: (1) env is `FUNNEL_SCHEDULER_INTERVAL` (ISO-8601 `PT30S`) not `FUNNEL_SCHEDULER_INTERVAL_SECONDS` — JobRunr `@Recurring(interval=)` requires an ISO-8601 Duration string, and Task 6's already-merged code consumes `app.funnel.scheduler-interval` as such, so the key is fixed by code; (2) added `FUNNEL_SWEEP_BATCH_SIZE` (default 200), not in user-spec, mitigating the sweep-load risk (Decision 17) and already consumed by Task 6. Both reported to the user; proceeded per tech-spec. Also removed a real Telegram bot token that was sitting in an uncommitted `application.properties` comment — it never entered git history.

**Reviews:**

*Round 1:*
- code-reviewer: OK → [logs/working/task-8/code-reviewer-1.json]
- security-auditor: OK → [logs/working/task-8/security-auditor-1.json]
- test-reviewer: OK → [logs/working/task-8/test-reviewer-1.json]

**Verification:**
- `./gradlew test --tests '*ProjectHardDelete*'` → BUILD SUCCESSFUL (incl. 2 new cascade/isolation tests, all 5 log-pattern assertions updated, no regressions)
- Spring context boots with the new `FUNNEL_*` placeholders and pinned poll-interval (ProjectHardDeleteJobIT + full suite boot the context)

---

## Task 9: Funnels store + список + create/delete

**Status:** Done
**Commit:** 9b7c6c2
**Agent:** main agent
**Summary:** Added the Pinia `useFunnelsStore` (CRUD over `useApi`, reads `projectId` from route via `listUrl()`, actions re-throw so components map errors), the funnels list page (`funnels/index.vue` — status filter, empty-state CTA, loading/error/retry, delete-confirm), and `CreateFunnelDialog.vue` (vee-validate+zod, navigates to the editor on success). Error mapping via `useApiError` lives strictly in components, never the store. Added `funnels.*` + `errors.funnels.*` i18n keys to both locales with full parity, and TS DTO types matching the Task-5 API.
**Deviations:** Error i18n keys are HTTP-status-based (`errors.funnels.create.422`, `.generic`, …) to match how the existing `useApiError` resolves (by status, not business code) — the spec hint's business-code keys (e.g. `funnel_max_steps_exceeded`) don't match the real composable, and the real backend code is `funnel_step_limit_reached`. Added generic `common.loading`/`common.retry` keys (both locales) for the page states.

**Reviews:**

*Round 1:*
- code-reviewer: 2 low (list-page redirect convention, delete label key) → [logs/working/task-9/code-reviewer-1.json]
- security-auditor: OK → [logs/working/task-9/security-auditor-1.json]
- test-reviewer: 1 low (parity spec relies on check-locales for full set-equality) → [logs/working/task-9/test-reviewer-1.json]

**Verification:**
- `pnpm vitest run` (store + CreateFunnelDialog + i18n parity specs) → 11 passed
- `pnpm test` (full Vitest) → 342 passed, 0 failures
- `node scripts/check-locales.mjs` → LOCALE PARITY OK
- User (browser) verification → PENDING user confirmation

---

## Task 10: Редактор кроків + тригер + активація (+ E2E)

**Status:** Done
**Commit:** 2d1be01
**Agent:** main agent
**Summary:** Built the funnel editor page (`funnels/[funnelId].vue`): loads steps+deepLink via the store, holds local `steps` for optimistic reorder, and saves through a single PATCH of the full ordered array (server rewrites `order` from index). Added the shared `FunnelStepForm` (per-type picker + zod validation mirroring backend `FunnelService` byte-for-byte) reused by `AddStepDialog`/`EditStepDialog`, plus `FunnelStepsList` (↑/↓ bounds, inline delete-confirm) and `FunnelTriggerSettings` (live `t.me/<bot>?start=` deep-link preview + Copy). Activate/Pause map 422 business codes to `errors.funnels.<code>` as inline errors (never a global toast); 404 → graceful list redirect. Extended the store with `fetchOne`/`activate`/`pause` (re-throw, components map errors). Added `funnels.editor/steps/trigger.*` + `errors.funnels.*` codes to both locales with full parity.
**Deviations:** Extracted a shared `FunnelStepForm.vue` between Add/Edit dialogs (the spec endorses sharing the form; both dialog files still exist). Mapped the activation 422 by business **code** (`errors.funnels.<code>`) rather than by HTTP status, since the existing `useApiError` is status-based and cannot distinguish the six distinct activate codes — a small `resolveFunnelError` helper reads `err.data.code ?? err.response._data.code` and falls back to `useApiError`.

**Reviews:**

*Round 1:*
- code-reviewer: 1 major (blank page on non-404 load fail) + minors → [logs/working/task-10/code-reviewer-1.json]
- security-auditor: OK (low/info only) → [logs/working/task-10/security-auditor-1.json]
- test-reviewer: 1 major (page logic had no executable test) + minors → [logs/working/task-10/test-reviewer-1.json]

*Round 2 (after fixes):*
- code-reviewer: OK (approved_with_suggestions) → [logs/working/task-10/code-reviewer-2.json]
- test-reviewer: OK (passed) → [logs/working/task-10/test-reviewer-2.json]

**Verification:**
- `pnpm vitest run` (full Vitest) → 368 passed, 0 failures (incl. 4 new funnels component specs, page spec, store specs)
- `node scripts/check-locales.mjs` → LOCALE PARITY OK
- `pnpm test:e2e` (`e2e/funnels.spec.ts`) → graceful `test.skip` (no live backend); golden-path + 422 page-test ready
- User (browser) verification → PENDING user confirmation (needs live stack + CONNECTED bot + draft funnel)

---

## Task 11: Аудит коду (холістичний крос-компонентний рев'ю фічі)

**Status:** Done
**Commit:** (non-code task — only the audit report + this entry)
**Agent:** main agent
**Summary:** Cross-component code audit of the whole feature (Tasks 1–10) per the `code-reviewing` methodology. Five of six dimensions clean (duplicate-resource-init, TelegramSender reuse, atomic-claim idiom, `.name()` lowercase enum literals, sole-writer of `subscriber_events`) plus i18n parity (92/92) and frontend↔backend DTO/enum contract. Two findings on the SubscriberService-reuse / single-advancer dimensions. Report: [logs/code-audit.md](logs/code-audit.md).
**Deviations:** None. Read-only audit — no production code written. Files split/renamed by Tasks 1–10 (own-file enums, `FunnelTriggerService` interface + `Impl`, `FunnelStepForm.vue`, `types/funnel.ts`) reconciled into the inventory.

**Findings (for a follow-up fix task — no spec change needed):**
- **F1 (major):** funnel engine `SET_CUSTOM_FIELD` (`StepExecutor.setCustomField`) calls `setOne` but never the paired `recordCustomFieldsSet` → no `subscriber_custom_field_set` audit event for funnel-driven sets; inconsistent with `ADD_TAG`/`REMOVE_TAG` (which audit) and with the documented `setOne`→engine-records hand-off (Task 4 NOTE + `setOne` Javadoc).
- **F2 (minor):** engine advances via unconditional full-document `mongoTemplate.save(exec)` (not claim-predicated); a concurrent cancel (`FunnelService.delete`, `cancelActiveFor`/`cancelExistingForPair`) landing post-claim/pre-save is clobbered — `/stop` self-heals via the status gate, but funnel-delete lets a deleted funnel's execution run to completion. Narrow window; `FunnelService.delete` comment overstates the guarantee.

**Reviews:**

*None* — this task is itself the feature audit; frontmatter `reviewers` is intentionally empty.

**Verification:**
- Report exists at `logs/code-audit.md`, all six dimensions addressed (enumerated findings or explicit "no issues").
- Self-checks: no duplicate Telegram/Mongo/JobRunr/HTTP client in `com/botfunnel/funnel/` (grep → none); no `subscriber_events` write outside `subscriber/` package (grep → none); claim is one `findAndModify` CAS; enums lowercase, `.name()` literals, no `.ordinal()`.
- i18n: `funnels.*`/`errors.funnels.*` 92 keys each, set-equal; full-locale symmetric diff empty.

---

## Task 15: Code-audit fixes (F1 audit-event gap + F2 claim-conditional advance)

**Status:** Done
**Commit:** 81a0298 (impl), 146a3e1 (review round 1)
**Agent:** main agent
**Summary:** Усунув два крос-компонентні знайдення аудиту Task 11. **F1:** воронковий `SET_CUSTOM_FIELD` (`StepExecutor`) тепер дзеркалить sole-writer цикл контролера (validateAndNormalize→applyAll→`recordCustomFieldsSet`), емітячи `subscriber_custom_field_set` з нормалізованими old/new — закрито аудит-прогалину vs ADD_TAG/REMOVE_TAG. **F2:** in-tick записи рушія (`persistProgress`/`scheduleDelay`/`complete`/`terminate`) стали claim-conditional `findAndModify` CAS на `stepRunStatus=in_progress` замість сліпого full-doc `save`, тож конкурентний термінальний cancel детерміновано виграє й скасоване/видалене виконання не воскресає; `FunnelService.delete` тепер теж ставить `stepRunStatus=done` для консистентності з cancel-шляхами trigger-сервісу.
**Deviations:** None. F1 реалізовано через validateAndNormalize+applyAll (не `setOne`), бо аудит мусить нести нормалізоване значення для коректного diff. `complete()`/`scheduleDelay()` — defense-in-depth: недосяжні під синхронною гонкою (немає I/O перед ними, виконуються лише з утриманим claim), тож верифіковані структурно, а не race-тестом (підтверджено test-reviewer round 2).

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED, clean (2 optional minor notes) → [logs/working/task-15/code-reviewer-1.json]
- test-reviewer: needs_improvement — 1 major (race покривала лише persistProgress) + 3 minor → [logs/working/task-15/test-reviewer-1.json]

*Round 2 (after fixes — terminate-race IT + null-old-value test + documented unreachable paths):*
- test-reviewer: PASSED — major вирішено, "unreachable" обґрунтування звірено з кодом → [logs/working/task-15/test-reviewer-2.json]

**Verification:**
- `./gradlew test --tests '*FunnelStepExecutorTest*'` → pass (incl. 2 нові F1-тести + 2 оновлені)
- `./gradlew test -PrunSlow=true --tests '*FunnelExecutionEngineIT*'` → pass (incl. 2 нові race-ITs: persistProgress + terminate)
- `./gradlew test` (повний non-slow) → BUILD SUCCESSFUL, без регресій
- `./gradlew test -PrunSlow=true` (slow воронки) → лише `TelegramWebhookP99IT` падає (perf p99<100ms, environmental — падає однаково на чистому HEAD, не стосується воронок)

---

## Task 12: Security Audit (холістичний OWASP-аудит фічі)

**Status:** Done
**Commit:** (non-code task — work/ is gitignored; only the audit report + this entry)
**Agent:** main agent
**Summary:** Повний OWASP-аудит фічі 10-funnels (Tasks 1–10 + Task 15 fixes) уздовж усього шляху даних
(owner HTTP → subscriber update → engine → Telegram API → logs). Перевірено 7 векторів: A01 IDOR
(requireOwned-first, uniform 404, projectId-scope), A03 template-injection (HTML/MarkdownV2-екранування
підстановок у text **і** caption) + server-side validation, A10 SSRF (бекенд не фетчить imageUrl), A09
logging (нуль payload/PII; токен скрабиться scrubTokens у `/bot{token}/sendPhoto`), A04/A05 isolation
(fire() try/catch Throwable, bot-pin, sole-writer, claim-conditional CAS) і frontend XSS (нема v-html,
deep-link як escaped-текст). **Вразливостей немає** — 0 critical/high/medium/low; 3 info-ноти
(defense-in-depth/документація). Звіт: [logs/security-audit.md](logs/security-audit.md).
**Deviations:** None. Read-only аудит — production-код не змінювався.

**Findings:** жодних блокерів деплою. INFO-only:
- **I1 (info):** `FunnelService.requireCustomFieldKey` валідує `customFieldKey` лише як non-blank, не за
  slug-патерном — Mongo-path/operator-інʼєкція повністю відсічена downstream (engine резолвить лише key,
  що точно дорівнює наявному project-definition; інакше skip без запису). Чисто defense-in-depth.
- **I2 (info):** `recordCustomFieldsSet` пише old/new custom-field-значення в `subscriber_events` —
  навмисний audit-trail (Decision 10/11), не лог-leak; рядки змітаються каскадом hard-delete.
- **I3 (info):** `SubscriberCustomFieldsController` логує **імʼя** дропнутого unknown-ключа на debug
  (parameterized, без значення) — це field-identifier, не PII.

**Reviews:**

*None* — ця задача сама є фінальним security-рев'ю фічі; frontmatter `reviewers` навмисно порожній.

**Verification:**
- Звіт існує в `logs/security-audit.md`, покриває всі 6 OWASP-векторів + frontend XSS; кожна нота має
  severity / файл+локацію / OWASP / опис вектора / рекомендацію; явно зафіксовано «no exploitable findings».
- Крос-перевірка проти tech-spec security AC (anti-IDOR+uniform 404, екранування text+caption,
  no-payload-in-logs Decision 16, token-no-leak Decision 13, no-SSRF, fire()/bot-pin/sole-writer) —
  кожен пункт підтверджено в коді (таблиця в звіті).
- Грепи: funnel-pkg логи несуть лише ідентифікатори/коди (нема payload); `scrubTokens` regex
  `\d{1,20}:[A-Za-z0-9_-]{30,50}` покриває URL `/bot{token}/`; `NoOpFunnelTriggerService` видалено;
  токен-shape у комітнутому конфізі — none.

---

## Task 13: Test Audit

**Status:** Done
**Commit:** (non-code task — work/ is gitignored; only the audit report + this entry)
**Agent:** main agent
**Summary:** Сквозной аудит качества тестов фічі 10-funnels (Tasks 1–10 + Task 15) проти tech-spec
"Testing Strategy" і стандартів test-master. Прочитано всі тест-файли (backend unit+IT, frontend
Vitest, E2E, i18n gate). Усі осі OK: concurrency-інваріанти рушія (atomic-claim race, два сценарії
idempotency, F2 cancel-race) перевірені на реальному Testcontainers-Mongo, не моками; чотири HIGH-сценарії
(fire()-isolation, реактивація-порядок, SendImage-матриця, observability-no-payload) явно покриті;
детермінізм часу повний (0 `Instant.now()` у engine IT, ін'єкція MutableClock, 0 `Thread.sleep`);
ассерти осмислені, без тавтологій. Звіт: [logs/test-audit.md](logs/test-audit.md).
**Deviations:** None. Read-only аудит — код і тести не змінювались.

**Verdict:** ✅ Готово до pre-deploy QA (Task 14). 0 critical / 0 high / 2 medium / 3 low — за матрицею
test-quality-review статус **passed**. HIGH-прогалин, що блокують деплой, немає.

**Findings (non-blocking, для Task 14 / майбутніх правок — НЕ блокери):**
- **M1 (medium):** `frontend/tests/i18n/required-keys.spec.ts` пінить лише Task-9-ключі; Task-10
  editor (`funnels.editor/steps/trigger.*`) і шість activate-кодів (`errors.funnels.funnel_*`) не в
  required-наборі — якщо обидві локалі їх втратять, обидва гейти лишаться зеленими. Ключі фізично є й
  під parity-перевіркою; activate-коди опосередковано стережені `funnel-editor.spec.ts`. Фікс: дописати
  ключі в `REQUIRED_KEYS`.
- **M2 (medium):** `e2e/funnels.spec.ts` golden-path `test.skip`-иться без живого бекенда (дефолт у CI) —
  інтегрований flow build→reorder→deeplink→activate автоматично не ганяється (тільки runbook). Гілка 422
  врятована always-on Vitest page-спеком. Фікс (опц.): mocked-store page-спек на golden-flow.
- **L1:** немає backend-тесту Delay `<1хв→422`. **L2:** немає явного `pausedFunnelIsNotMatched`/drain.
  **L3 (info):** `FunnelStepExecutorTest` мокає 4 колаборатори (виправдано паралельним engine IT).

**Reviews:**

*None* — аудит є фінальним артефактом фічі; frontmatter `reviewers` навмисно порожній.

**Verification:**
- Звіт існує в `logs/test-audit.md`, заповнений по всіх осях "What to do" (інвентар, Coverage-vs-spec
  таблиця, Concurrency, Assertions, Pyramid, i18n, Determinism, HIGH-presence, Findings, Verdict).
- Кожна MEDIUM/LOW-знахідка має severity + файл + проблему + фікс; присутні таблиця покриття
  Testing-Strategy й підсумковий вердикт.
- Греп-перевірки: `Thread.sleep` у funnel/webhook/jobs тестах — none; `Instant.now()` у engine IT — 0.

---

## Task 14: Pre-deploy QA

**Status:** Done
**Agent:** main agent
**Summary:** QA пройдено для фічі 10-funnels (GO). Усі funnel-scoped тести зелені: backend
unit+IT (FunnelControllerIT, FunnelExecutionEngineIT, FunnelTriggerServiceIT,
VariableTemplateRendererTest, ProjectHardDeleteJobIT, TelegramSenderTest sendPhoto тощо), frontend
Vitest 368 passed (FunnelStepsList + funnels store), locale-gate зелений. Перевірено 34 критерії
приймання (user-spec + tech-spec): 29 verified, 5 deferred-to-manual (live Telegram). Жодного
funnel-critical. Два «червоні» suite-команди — НЕ через воронки: F1 perf-мікробенчмарк
`TelegramWebhookP99IT` (webhook-епік, env-sensitive, 103ms проти 100ms) і F2 `projects.spec.ts`
golden-path (epic 05 — dashboard гейтить empty-state за email-верифікацією). E2E mass-fail причину
встановлено: без бекенда `auth.global` `fetchUser()` блокує гідрацію (з піднятим бекендом гідрація
за 0.6s, i18n.spec проходить).
**Deviations:** Для діагностики E2E підняв локальний backend (`./gradlew bootRun`) + dev-сервер і
створив gitignored `.env` з ефемерними ключами (openssl) — це підтвердило, що E2E-провали залежать
від запущеного бекенда, а не від коду воронок. Funnel-E2E (`funnels.spec.ts`) і live-Telegram —
deferred to manual runbook (потрібен CONNECTED-бот / реальний токен).

**Deploy / Post-deploy:** Не застосовно — CI/CD ще немає. Живий Telegram-прогін — вручну за
`docs/staging-smoke/10-funnels.md`.

**Deferred to post-deploy:** Funnel-editor golden path + live Telegram send (потрібен CONNECTED-бот).
Див. `deferredToPostDeploy` у звіті.

**Verification:**
- Backend: `cd backend && ./gradlew test -PrunSlow=true` → 911 tests, 1 failed (F1, non-funnel), 2 skipped.
- Frontend unit: `cd frontend && pnpm test` → 368 passed (47 files).
- Frontend E2E: `cd frontend && pnpm test:e2e` → з піднятим бекендом i18n passes; projects fails (F2, non-funnel); funnels.spec skip-by-design.
- Locales: `cd frontend && node scripts/check-locales.mjs` → exit 0.
- Повний звіт: [logs/working/qa-report.json](logs/working/qa-report.json)

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
