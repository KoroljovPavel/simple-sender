---
status: done
depends_on: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
wave: 5
skills: [code-reviewing]
reviewers: []
verify: []
teammate_name:
---

# Task 11: Code Audit

## Required Skills

Before starting, load: /skill:code-reviewing — [SKILL.md](../../../.claude/skills/code-reviewing/SKILL.md)

This task IS a review. Apply the code-reviewing methodology (review dimensions, process, quality standards) across the whole feature rather than to a single diff.

## Description

Full-feature code quality audit for the Funnels — Лінійні воронки (Фаза 1) feature. Tasks 1–10 each delivered and reviewed an isolated unit of work; per-task reviews cannot catch problems that only appear when the components are assembled. This task closes that gap with a single holistic pass over every source file created or modified across the feature.

The audit reads all feature source files (Java backend under `com.botfunnel.funnel`, the modified files in `com.botfunnel.bot` / `com.botfunnel.subscriber` / `com.botfunnel.jobs`, their tests, and the frontend funnels store / pages / components) and evaluates them together for cross-component issues:

- **Duplicate resource initialization** — e.g. the funnel module spinning up its own Telegram client/RestClient, Mongo connection, JobRunr config, or subscriber-lookup path instead of reusing the shared singletons listed in the tech-spec "Shared resources" table (`TelegramSender`, `MongoTemplate`, JobRunr background-job-server, `SubscriberServiceImpl`, `SubscriberCustomFieldsService`).
- **Shared-resource compliance** — the feature must consume the existing `TelegramSender` for all sends (`sendText` + the new `sendPhoto`, Decisions 10/13) and reuse `SubscriberServiceImpl` for subscriber mutations/lookups and `SubscriberCustomFieldsService` for set-custom-field (Decision 11), without duplicating client/connection init.
- **Sole-writer rule** — all subscriber-state mutations and audit writes (`subscriber_events`, `subscriber_custom_field_set`) must go through `SubscriberServiceImpl` as the sole writer (Decision 11); the funnel engine must not replicate that CRM/audit logic. Likewise, a given `FunnelExecution` document is advanced only via the engine's atomic claim — no second writer races it.
- **Architectural consistency** — the atomic-claim idiom (one `findAndModify` CAS that both selects and claims an execution, Decision 4) is used consistently with no parallel/ad-hoc two-step claim; enum statuses are written as `.name()` string literals in `findAndModify` criteria/updates and never as ordinals (Decisions 4, 14); enums are lowercase per Decision 14 (`FunnelStatus`, `ExecutionStatus`, `StepRunStatus`).

The deliverable is an audit report. This is a non-code task: no production code is written here. If the audit surfaces issues, they are enumerated in the report with severity and a suggested fix so a follow-up fix task can act on them; if nothing is found, the report states that explicitly.

## What to do

1. Build the file inventory. Collect every source file created or modified in Tasks 1–10 from two sources: the per-task summaries in [decisions.md](../decisions.md), and the per-task "Files to modify" lists plus the "Shared resources" table in [tech-spec.md](../tech-spec.md). Cross-check that list against what is actually on disk under `backend/src/main/java/com/botfunnel/funnel/`, the modified files in `com/botfunnel/bot/`, `com/botfunnel/subscriber/`, `com/botfunnel/jobs/`, the matching tests under `backend/src/test/java/com/botfunnel/...`, and the frontend `stores/funnels.ts` / `pages/projects/[projectId]/funnels/*` / `components/funnels/*`.
2. Read each file in the inventory in full. Understand how the components fit together (model/repo → CRUD service/controller → trigger service → execution engine → frontend store/pages) before judging any one of them.
3. Review holistically against the cross-component dimensions below. Do not re-litigate per-file nits already handled in per-task reviews; focus on issues that only emerge across components.
   - **Duplicate resource init:** search for any new Telegram client/RestClient, Mongo client/template, JobRunr config, or HTTP client construction inside the funnel package. There should be none — these are the shared singletons from the tech-spec "Shared resources" table, injected/reused.
   - **TelegramSender reuse (Decisions 10, 13):** SEND_MESSAGE steps call `TelegramSender.sendText`, SEND_IMAGE steps call the new `TelegramSender.sendPhoto`; confirm there is no second/duplicate send path and `sendPhoto` reuses the shared retry/backoff/hook/token-decrypt machinery rather than re-implementing it.
   - **SubscriberServiceImpl / SubscriberCustomFieldsService reuse (Decision 11):** subscriber mutations (addTag/removeTag), lookup-by-chat, and set-custom-field go through the shared services; confirm no duplicated CRM/lookup/connection logic and that audit writes stay on the sole-writer path.
   - **Sole-writer (Decision 11):** confirm `SubscriberServiceImpl` remains the sole writer of `subscriber_events` / `subscriber_custom_field_set`; the engine must not write those directly. Confirm a `FunnelExecution` is advanced only through the engine's atomic claim (no second writer).
   - **Atomic-claim idiom (Decision 4):** execution claim is a single `findAndModify` CAS that both selects and claims (not two separate operations), per-step `pending→in_progress→done`; no parallel/ad-hoc claim implementation exists. Mirrors `ProcessTelegramUpdateJob.handleStart`, `ExportSubscribersJob`.
   - **Enum `.name()` literals (Decisions 4, 14):** `FunnelStatus` / `ExecutionStatus` / `StepRunStatus` / `StepType` are written as `.name()` strings in `findAndModify` criteria/updates and queries, never ordinals; enums are lowercase per Decision 14.
4. Write the audit report to `work/10-funnels/logs/code-audit.md`. Structure it as: scope (files audited), method (dimensions checked), findings (one entry per issue with severity / location file:line / description / suggested fix), and a verdict line. If there are no findings, say so explicitly per dimension.
5. Record the outcome in [decisions.md](../decisions.md) (link to the report), per Post-completion.

## Acceptance Criteria

- [ ] File inventory assembled from decisions.md + tech-spec per-task "Files to modify" + "Shared resources" table, and reconciled against files actually present on disk; the full set of Tasks 1–10 source files was read.
- [ ] Audit report produced at `work/10-funnels/logs/code-audit.md` with scope, method, findings, and a verdict.
- [ ] Each of the six cross-component dimensions (duplicate resource init, TelegramSender reuse, SubscriberServiceImpl/SubscriberCustomFieldsService reuse, sole-writer, atomic-claim idiom, `.name()` lowercase enum literals) is explicitly addressed — either with enumerated findings or an explicit "no issues" statement.
- [ ] Every finding includes severity, location (file:line), description, and a concrete suggested fix.
- [ ] If no cross-component issues exist, the report states that clearly per dimension and the verdict reflects it.
- [ ] Outcome recorded in decisions.md with a link to the report.

## Context Files

**Feature specs:**
- [user-spec.md](../user-spec.md)
- [tech-spec.md](../tech-spec.md) — Shared resources table; Decisions 4 (atomic-claim), 7 (sole-writer), 10 (TelegramSender reuse), 11 (`.name()` literals), 14 (shared resources)
- [decisions.md](../decisions.md) — per-task implementation summaries; source of the file inventory

**Project context:**
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md)
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — code + testing conventions to audit against

**Review methodology:**
- [code-reviewing SKILL.md](../../../.claude/skills/code-reviewing/SKILL.md)

**Code under audit (all files created/modified in Tasks 1–10 — from tech-spec "Files to modify"):**

New backend — `backend/src/main/java/com/botfunnel/funnel/` (Tasks 1, 2, 5, 6, 7):
- `Funnel.java`, `FunnelStep.java`, `FunnelExecution.java` (+ enums `FunnelStatus` / `ExecutionStatus` / `StepRunStatus` / `StepType`)
- `FunnelRepository.java`, `FunnelExecutionRepository.java`
- `FunnelService.java`, `FunnelController.java`, `dto/` (request/response records)
- `VariableTemplateRenderer.java`
- `FunnelExecutionEngine.java`, `StepExecutor.java` (executors)
- `FunnelTriggerService.java` (real `@Service` impl; `NoOpFunnelTriggerService.java` deleted)

Modified backend (Tasks 3, 4, 8):
- `backend/src/main/java/com/botfunnel/bot/TelegramSender.java` (new `sendPhoto`)
- `backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsService.java`, `SubscriberCustomFieldsController.java`, `SubscriberService.java`, `SubscriberServiceImpl.java`
- `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java` (cascade `funnels` + `funnel_executions`)
- `backend/src/main/resources/application.properties` (funnel env vars)

Backend tests — `backend/src/test/java/com/botfunnel/funnel/` plus modified tests in `com/botfunnel/bot/`, `com/botfunnel/subscriber/`, `com/botfunnel/jobs/`, `com/botfunnel/webhook/` (read all `*Test.java` / `*IT.java` touching the feature).

Existing shared resources the feature must reuse (read for compliance check, not part of the feature delivery) — per tech-spec "Shared resources" table:
- `TelegramSender` (RestClient bean, `bot/` module) — the single send path
- `SubscriberServiceImpl` (`subscriber/` module) — sole writer of subscriber events; tag/lookup
- `SubscriberCustomFieldsService` (`subscriber/` module) — shared set-custom-field path
- `MongoTemplate` / JobRunr background-job-server — shared singletons, must not be re-instantiated

Frontend (Tasks 9, 10):
- `frontend/stores/funnels.ts`
- `frontend/pages/projects/[projectId]/funnels/index.vue`, `frontend/pages/projects/[projectId]/funnels/[funnelId].vue`
- `frontend/components/funnels/*` (`CreateFunnelDialog.vue`, `FunnelStepsList.vue`, `AddStepDialog.vue`, `EditStepDialog.vue`, `FunnelTriggerSettings.vue`)
- `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json` (`funnels.*` + `errors.funnels.*`)

> The exact filenames above come from the tech-spec; Tasks 1–10 may have split or renamed some. Reconcile this list against decisions.md and what is actually on disk before reading, and include any extra files the tasks added.

## Verification Steps

This is an audit, not a code change — there is no automated test suite to run for it and nothing to deploy.

**Deliverable check:**
- The report exists at `work/10-funnels/logs/code-audit.md` and covers all six cross-component dimensions.
- Every dimension has either enumerated findings (with severity, file:line, description, fix) or an explicit "no issues found" note.
- The file inventory in the report matches the union of decisions.md, tech-spec per-task "Files to modify" + "Shared resources" table, and the files actually present on disk.

**Self-check:**
- Confirm no funnel-package file constructs its own Telegram client/RestClient, Mongo client/template, JobRunr config, or HTTP client (all are shared singletons from the "Shared resources" table).
- Confirm subscriber-state/audit writes (`subscriber_events`, `subscriber_custom_field_set`) go only through `SubscriberServiceImpl` / `SubscriberCustomFieldsService`; the engine does not write them directly.
- Confirm the execution claim is a single `findAndModify` CAS (not two separate select+claim operations) and is the sole claim mechanism.
- Confirm enums are persisted/queried via `.name()` strings (grep for ordinal usage or numeric status comparisons as a sanity pass) and are lowercase per Decision 14.

## Details

**Nature of the task:** read-only cross-component review. Do not modify production code. The only artifact written is the audit report (plus the decisions.md entry).

**Files to read:** every file listed in Context Files → "Code under audit", reconciled with decisions.md and the on-disk `com/botfunnel/funnel/` tree. Read the existing `TelegramSender`, `SubscriberServiceImpl`, and `SubscriberCustomFieldsService` as well, since the audit verifies the funnel code reuses them rather than re-implementing their behavior.

**Dependencies:** depends on Tasks 1–10 being complete (their code and decisions.md summaries must exist). Wave 5 — runs after all implementation waves.

**Decision idioms to enforce (from tech-spec):**
- Decision 4 — Atomic claim: execution claim is ONE `findAndModify` CAS (criteria `_id` + `status ∈ {running,waiting}` + `nextRunAt<=now` + `stepRunStatus=pending`; update `stepRunStatus=in_progress`; returnNew) — a single op, not two; per-step `pending→in_progress→done`. Flag any parallel/ad-hoc claim logic.
- Decision 11 — Sole-writer / shared service: set-custom-field and subscriber mutations go through `SubscriberCustomFieldsService` / `SubscriberServiceImpl`; the engine must not replicate validate→update→`recordCustomFieldsSet` or write `subscriber_events` directly.
- Decisions 10 / 13 — TelegramSender reuse: SEND_MESSAGE → `TelegramSender.sendText`, SEND_IMAGE → `TelegramSender.sendPhoto`; no new/duplicate send path; `sendPhoto` reuses shared retry/backoff/hook/token-decrypt machinery.
- Decisions 4 / 14 — Enum literals: persist/query enum `.name()` strings in `findAndModify` criteria/updates (e.g. `ExecutionStatus.running.name()`), never ordinals; enums lowercase.
- Tech-spec "Shared resources" table — reuse `TelegramSender`, `MongoTemplate`, JobRunr background-job-server, `SubscriberServiceImpl`, `SubscriberCustomFieldsService`; do not duplicate client/connection/config init.

**Edge cases / things to look for specifically:**
- A "convenience" duplicate of a `MongoTemplate`/`MongoClient`, JobRunr config, or Telegram client/RestClient introduced in one of the funnel tasks instead of injecting the shared bean.
- The engine writing `subscriber_events` / `subscriber_custom_field_set` or doing tag/custom-field mutation directly instead of delegating to the sole-writer services.
- Execution claim implemented as two ops (find then modify) instead of a single atomic `findAndModify` CAS.
- Status or step-type comparisons done by ordinal or magic number instead of `.name()` strings; or any non-lowercase enum constant diverging from Decision 14 / `architecture.md`.
- Two different ways to advance an execution/step across the engine vs trigger service.
- Frontend (`stores/funnels.ts`, components) assumptions that diverge from the backend enum string / DTO contract (e.g. status strings, `deepLink`, full-`steps` array PATCH contract).
- i18n: `funnels.*` / `errors.funnels.*` keys present and in parity across `uk.json` and `en.json`.

**Output location:** `work/10-funnels/logs/` (create if missing). Report file: `code-audit.md`.

## Reviewers

None. This task is itself the audit/review of the feature, so it is not subject to further reviewers. The frontmatter `reviewers` list is intentionally empty.

## Post-completion

- [ ] Write report to [decisions.md](../decisions.md): summarize the audit outcome and link to `work/10-funnels/logs/code-audit.md`. If multiple passes were made, note each.
- [ ] If the audit found cross-component issues, ensure they are enumerated with severity and suggested fix so a follow-up fix task can act on them; note in decisions.md whether a fix task is needed.
- [ ] If the audit revealed any deviation from the tech-spec idioms (Decisions 4/10/11/13/14 + "Shared resources" table), describe the deviation and reason in decisions.md.
- [ ] Update user-spec/tech-spec only if the audit uncovered a spec inaccuracy that should be corrected.
