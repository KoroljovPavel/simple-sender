# Code Research — 13-funnels-tooling (Funnels Phase 4 "Author tooling")

Researched against real code on branch `main`. Phases 1-3 are merged. This document maps the four tools
to concrete files, reusable code, and risks. All paths are repo-relative under
`/Users/pavlokorolov/IdeaProjects/simple-sender`.

Backend package: `backend/src/main/java/com/botfunnel/funnel` (the funnel module). Frontend funnel surface:
`frontend/pages/projects/[projectId]/funnels/*` + `frontend/components/funnels/*` + `frontend/stores/funnels.ts`.

---

## Shared backend foundations (apply to all four tools)

- **Controller base path & auth.** `FunnelController.java` — `@RequestMapping("/api/v1/projects/{projectId}/funnels")`.
  Owner resolved from session via `currentUserId()` (never the body). Existing routes: `POST` (create), `GET`
  (list, optional `?status=`), `GET /{funnelId}`, `PUT|PATCH /{funnelId}` (both full-replace, share `update`),
  `DELETE /{funnelId}`, `POST /{funnelId}/activate`, `POST /{funnelId}/pause`. New endpoints should mirror this:
  service does anti-IDOR via `FunnelService.requireFunnel` (which calls `ProjectService.requireOwned` FIRST, then
  proves the funnel belongs to the path project — cross-project/missing/bad-ObjectId all collapse to a uniform 404).
- **Status enums (lowercase `name()` — Decision 14).** `FunnelStatus` = `{draft, active, paused}`.
  `ExecutionStatus` = `{running, waiting, completed, cancelled, failed, waiting_for_reply}`. `StepRunStatus` =
  `{pending, in_progress, done}`. These names are byte-matched by Mongo partial-index filters and CAS predicates;
  do NOT rename.
- **The in-flight execution status set** is `{running, waiting, waiting_for_reply}` everywhere it appears
  (re-enter guard partial filter, `delete()` cascade, `cancelActiveFor`, `cancelExistingForPair`).
- **Test infra.** ITs extend `backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java` (Testcontainers
  Mongo + in-memory JobRunr via `JobRunrInMemoryConfig`). Controller ITs use MockMvc + `@WithMockAppUser` +
  `.with(csrf())` — see `FunnelControllerIT.java`. Engine/send ITs use a JVM-singleton OkHttp `MockWebServer`
  with `registry.add("app.telegram.base-url", () -> TELEGRAM.url("/").toString())` + `enqueueOk(n)` — see
  `FunnelExecutionEngineIT.java` (lines 69-122). Reuse this exact harness for "Test for me" send assertions.

---

## TOOL 1 — "Test for me" (enroll the author's own Telegram into a funnel)

### Bot.ownerChatId — where set & read
- Entity field: `bot/Bot.java:51` `private Long ownerChatId;` (nullable). Getter `getOwnerChatId()` line 81.
- **Populated** in `webhook/ProcessTelegramUpdateJob.java:287-301` (`handleStart`): only on a PRIVATE `/start`
  against a CONNECTED bot, via an atomic first-writer-wins `findAndModify` — predicate
  `_id == bot.id AND status == CONNECTED AND ownerChatId IS NULL`, update `set ownerChatId = chatId`. A losing
  worker gets `null` back (benign no-op). Group/supergroup/channel `/start` does NOT populate it (line 272).
- **Consequence:** `ownerChatId` is null until the author has sent a private `/start` to their connected bot.
  "Test for me" must handle null → return a 422/409 telling the author to `/start` the bot first.

### Resolving the author's Subscriber from ownerChatId
- `subscriber/SubscriberService.java:109` `Optional<Subscriber> findByChat(String projectId, Long telegramBotId, Long chatId)`
  — the public, non-HTTP boundary lookup (impl `SubscriberServiceImpl.java:316`, delegates to
  `SubscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId`). This is what `FunnelTriggerServiceImpl.fire`
  uses (line 134). Use `findByChat(projectId, bot.getTelegramBotId(), bot.getOwnerChatId())`.
- **Edge: no subscriber for ownerChatId.** A subscriber row IS upserted on the same `/start` that sets
  `ownerChatId` (`ProcessTelegramUpdateJob.java:304` `subscriberService.upsertFromTelegramUpdate(...)`, runs right
  after the CAS). So in the normal flow a subscriber exists. But it is not guaranteed (e.g. subscriber later
  hard-deleted). Handle empty → 422 "start the bot first" (same remediation as null ownerChatId).
- The subscriber must be `SubscriberStatus.ACTIVE` to actually receive messages — the engine's `preStepGates`
  (`FunnelExecutionEngine.java:210-215`) cancels any execution whose subscriber is not ACTIVE before sending.

### The direct-enroll path — what to call
- **Reuse `FunnelExecutionFactory.insertExecution(projectId, funnel, subscriberId, telegramBotId, enrollDepth)`**
  (`FunnelExecutionFactory.java:75`). This is THE bypass-trigger-matching enroll primitive. It builds a fresh
  `FunnelExecution` from step 0, deep-copies the steps snapshot (`FunnelStep.copyOf`), pins `telegramBotId`, sets
  `status=running`, `stepRunStatus=pending`, `nextRunAt=now`, seeds `currentStepId` to the first step's id, and
  `MongoTemplate.insert`s it (so a unique-index collision surfaces as `DuplicateKeyException`). Pass
  `enrollDepth=0` (human root).
- `cancelExistingForPair(projectId, funnelId, subscriberId)` (line 55) atomically cancels an existing
  running|waiting execution for the pair — call it before insert when you want to force a fresh test run
  (equivalent to `allowReEnter=true` behavior).
- **`FunnelExecutionFactory` is package-private (`class FunnelExecutionFactory`, no `public`).** The "Test for me"
  service lives in the `funnel` package already (or must), so it can inject this bean directly — same as
  `FunnelTriggerServiceImpl` and `FunnelEventService` do.
- Do NOT use `FunnelTriggerServiceImpl.fire(...)` — it re-does trigger matching (looks up the active funnel by
  `(triggerType, triggerValue, status=active)`, `FunnelTriggerServiceImpl.java:143`), which is exactly what
  "Test for me" must bypass. Do NOT use `FunnelEventService.dispatchForSubscriber(...)` either — it also matches
  on trigger + applies the three Phase-3 backstops (depth/volume/fan-out).
- **enrollDepth semantics:** `FunnelExecution.enrollDepth` (entity line 73) is the Redis-independent depth cap.
  A test enroll is a human root → `0`.
- **Re-enter guard interplay:** the unique partial index `funnelId_subscriberId_unique_active`
  (`FunnelExecution.java:24-27`, filter `status IN [running, waiting, waiting_for_reply]`) means a second
  `insertExecution` for the same (funnel, subscriber) while one is in-flight throws `DuplicateKeyException`. The
  tool must decide: (a) catch and treat as "already testing" (mirror `fire()`'s swallow at
  `FunnelTriggerServiceImpl.java:164`), or (b) call `cancelExistingForPair` first to restart. Recommend (b) for a
  test tool, so re-clicking "Test" always re-runs from the top.

### Status gate — can a DRAFT funnel be test-run?
- `insertExecution` itself does NO status check — it takes the `Funnel` object and snapshots its steps regardless
  of `funnel.getStatus()`. So a draft CAN be enrolled at the factory level.
- BUT: enrolling an EMPTY funnel produces an empty snapshot and immediately completes (`FunnelExecutionEngine`
  `drive` → `endOfGraph` → `complete`). And a draft may contain invalid steps (validation runs only on
  `update`/`activate`, see Tool 4). **Recommendation:** before a test enroll, run `FunnelService.validateSteps`
  (currently `private` — needs exposing) and reject empty/invalid funnels with the same 422 codes as activate, so
  the author gets meaningful feedback instead of a silently-completing test.
- The engine's per-execution gates do NOT check funnel status (Decision 11: pause only blocks NEW trigger
  enrollments, in-flight runs drain). So a draft/paused test run will actually send. This is the desired behavior
  for a test tool.

### Send path (for the IT)
- `StepExecutor.java` calls `TelegramSender.sendText(botId, chatId, text, parseMode, null)` for SEND_MESSAGE
  (line 201) and `.sendPhoto(...)` (line 225); MENU also uses `sendText` with `replyMarkup` (line 141). Signatures:
  `bot/TelegramSender.java:128` (5-arg) and `:143` (6-arg with `replyMarkup`).
- IT setup: reuse `FunnelExecutionEngineIT`'s MockWebServer singleton + `enqueueOk(n)`. The engine sweep is
  JobRunr-scheduled (`app.funnel.scheduler-interval=PT30S`); the IT triggers the sweep directly — read how
  `FunnelExecutionEngineIT.stepsRunEndToEnd` advances the engine (it injects the engine and calls `sweep()`).

### Risks / edge cases (Tool 1)
- **Null/absent ownerChatId or subscriber** → explicit remediation message, not a 500.
- **Author subscriber BLOCKED/DELETED** → engine cancels at preStepGates → test "silently" does nothing; surface
  this (check `subscriber.getStatus() == ACTIVE` in the tool and return a clear message).
- **Re-test while a test is running** → DuplicateKeyException; choose restart-or-noop policy explicitly.
- **No new endpoint security pitfalls** if you route through `FunnelService.requireFunnel(ownerId, projectId, funnelId)`
  for ownership before resolving the bot/subscriber.

---

## TOOL 2 — Duplicate funnel

### CRUD structure to mirror
- `FunnelController.create` (line 41) → `FunnelService.create(ownerId, projectId, CreateFunnelRequest)`
  (`FunnelService.java:114`): always born `status=draft`, `triggerType=on_start`, `triggerValue=""`,
  `allowReEnter=false`, empty steps, fresh timestamps. A duplicate endpoint should be
  `POST /{funnelId}/duplicate` → returns 201 + `FunnelResponse`, mirroring create's response shape.

### Funnel entity shape & copy mechanics
- `Funnel.java`: `id, projectId, name, description, status, triggerType, triggerValue, allowReEnter,
  keywords (List<String>), steps (List<FunnelStep>), createdAt, updatedAt`.
- `FunnelStep.java`: per-step `id` (stable, minted server-side as ObjectId hex — `FunnelService.toSteps:417`),
  `next` (default outgoing edge → a step id or null), `buttons` (List<Button>, MENU), `timeoutTargetStepId`,
  plus type-specific scalar fields. `Button` (record) carries `targetStepId` (callback edge).
- **There is already a deep-copy helper: `FunnelStep.copyOf(FunnelStep)` (`FunnelStep.java:82`).** It copies every
  scalar by reference (immutable) and defensively copies the `buttons` list. **It preserves the step `id` and all
  edge targets (`next`, `timeoutTargetStepId`, `Button.targetStepId`) unchanged** (lines 100-107).
- **Edge re-minting decision:** targets are STABLE ids, not array indices (confirmed `FunnelStepDto.java:18-20`
  "reorder in the editor is safe without remapping"). So a duplicate has two valid strategies:
  1. **Keep all ids verbatim (simplest, correct).** Copy each step via `copyOf`; because every edge references an
     id that still exists in the (identically-copied) step set, the graph stays internally consistent. Step ids do
     NOT need to be globally unique across funnels (the only unique index on `funnels` is the trigger one — see
     below; step ids live embedded in the doc, scoped per funnel). Verified: no unique index on `steps.id`.
  2. Remint ids + rewire — only needed if you want fresh ids. More code, more risk; NOT required. Recommend
     strategy 1.
- The simplest faithful copy: `List<FunnelStep> copy = original.getSteps().stream().map(FunnelStep::copyOf).toList()`
  then build a new `Funnel` with copied steps.

### Trigger reset (MANDATORY to avoid the unique-index collision)
- The partial-unique index on `funnels` (`Funnel.java:35-38`):
  `def="{'projectId':1,'triggerType':1,'triggerValue':1}"`, `unique=true`,
  `partialFilter="{ 'status': 'active', 'triggerType': 'on_start' }"`. Only ACTIVE `on_start` funnels participate.
- **Therefore a duplicate MUST be created with `status=draft`** (and ideally trigger reset to the create defaults:
  `on_start`/`""`). A draft never enters the index, so even copying an active funnel's trigger verbatim into a
  draft cannot collide. Reset-to-draft also matches author expectations (you re-activate the copy deliberately).
- Recommend: copy `name` (e.g. append " (copy)" — see i18n note), `description`, `steps`, `keywords`, `allowReEnter`;
  force `status=draft`; reset `triggerType=on_start`, `triggerValue=""`, `keywords=null` OR keep them but force
  draft. Keeping the trigger but forcing draft is safe; resetting it avoids a confusing "two funnels claim the same
  trigger when I activate" surprise. Decision to be made in tech-spec.

### Risks / edge cases (Tool 2)
- **Step count limit** (`app.funnel.max-steps=50`): a copy has the same count, already valid, so no new violation.
- **Name length**: `name` is `@Size(max=128)` on create; appending " (copy)" could exceed 128 for long names —
  truncate or skip the suffix.
- **No tests will break** from id reuse across funnels (verified: no `steps.id` unique index).

---

## TOOL 3 — "Force stop all running executions" (for a funnel)

### Existing cancel paths to mirror (the exact pattern is already written 3×)
- `FunnelService.delete` (`FunnelService.java:180-191`): bulk `mongoTemplate.updateMulti` over
  `funnelId == X AND status IN [running, waiting, waiting_for_reply]`, set `status=cancelled`,
  `stepRunStatus=done`, `updatedAt=now`. **This is exactly the "force stop all" operation, scoped by funnelId** —
  copy it into a new `stopAllExecutions(ownerId, projectId, funnelId)` service method (after `requireFunnel`).
- `FunnelTriggerServiceImpl.cancelActiveFor` (line 177): same update but scoped by `(projectId, subscriberId)` —
  this is the `/stop` command path (single subscriber).
- `FunnelExecutionFactory.cancelExistingForPair` (line 55): scoped by `(projectId, funnelId, subscriberId)`.

### The claim-CAS terminal-cancel correctness (Decision F2 — why the bulk flip is safe vs an in-flight tick)
- Every in-tick engine write is a CAS predicated on `stillClaimed` = `{_id, stepRunStatus: in_progress}`
  (`FunnelExecutionEngine.java:451-454`). The engine's `persistProgress`, `scheduleDelay`, `parkForReply`,
  `complete` all `findAndModify` against `stillClaimed` and bail (`return false`/no-op) when it returns null.
- The cancel update sets `stepRunStatus=done` (NOT just `status=cancelled`). So once the bulk update lands, the
  engine's next CAS no longer matches → its write no-ops → **the cancel deterministically wins, no resurrection,
  no double-send.** This is documented verbatim at `FunnelService.delete` lines 174-189 and engine lines 446-454.
- **CRITICAL: setting `stepRunStatus=done` is mandatory** — a cancel that flipped only `status` would race-lose to
  a mid-tick `persistProgress` that re-sets `status=running`. Mirror the existing update (`status=cancelled` AND
  `stepRunStatus=done` AND `updatedAt`) exactly.

### Which statuses to flip
- In-flight = `{running, waiting, waiting_for_reply}` (all three). `waiting_for_reply` rows parked on a MENU are
  cancelled the same way; a subsequent button-tap callback then loses its `claimForCallback` CAS
  (`FunnelExecutionEngine.java:314`, predicate requires `status=waiting_for_reply`) → benign no-op.
- Do NOT touch `completed`/`failed`/`cancelled` (terminal).

### Endpoint shape
- Suggest `POST /{funnelId}/executions/stop` (or `/stop-all`) → 200 with a count of cancelled executions
  (`updateMulti` returns `UpdateResult.getModifiedCount()`), or 204. Note: `FunnelExecutionRepository.java` declares
  NO query methods by design — bulk ops go through `MongoTemplate` (already the convention). Use `MongoTemplate`
  in the service, do not add a repository method.

### Risks / edge cases (Tool 3)
- **No status gate needed** — stopping executions is valid for active/paused/draft funnels (a paused funnel can
  still have in-flight runs draining).
- **Concurrency with the sweep** is handled by the claim-CAS (above); no extra locking needed.
- Scope the query by `projectId` too (fail-closed tenant hardening) as `cancelActiveFor` does (line 195),
  even though funnelId is already unique — defense in depth.

---

## TOOL 4 — Message preview + pre-save validation

### Backend validation: `FunnelService.validateSteps` (already exists — `FunnelService.java:452-488`)
Per step-type checks (all throw 422 with a business code):
- `SEND_MESSAGE`: `requireText` (non-blank, ≤4096) + `requireParseMode` (null|HTML|MarkdownV2).
- `SEND_IMAGE`: `requireImageUrl` (non-blank, http(s)) + `requireParseMode`.
- `DELAY`: `requireDelay` (value≥1, unit MIN|HOUR|DAY).
- `ADD_TAG`/`REMOVE_TAG`: `requireTagSlug` (`^[a-z0-9_-]{1,32}$`).
- `SET_CUSTOM_FIELD`: `requireCustomFieldKey` (non-blank). (Field-value type checks live in
  `subscriber/.../CustomFieldValueValidator` — note `custom_field_type_mismatch` mentioned in the prompt is NOT a
  funnel error code; see codes below.)
- `MENU`: `validateMenu` (≥1 callback button, ≤8 buttons, label non-blank ≤64, callback target null or existing
  id, url strictly http(s) with non-empty host, optional timeout both-or-neither + value≥1 + unit MIN|HOUR|DAY).
- `EMIT_EVENT`: `requireEventName` (`^[A-Za-z0-9_-]{1,64}$`).
- Graph-edge pass for EVERY step: `next` and `timeoutTargetStepId` must be null or an existing step id
  (`requireExistingTarget` → `funnel_broken_edge`).
- Step-count cap: `> app.funnel.max-steps (50)` → `funnel_step_limit_reached`.

**Where called:** `update` (line 159, every save) and `activate` (line 203, defense-in-depth). NOT called on
`create` (born empty) or `pause`. **Error codes (constants in `FunnelService.java:73-84`):**
`funnel_trigger_conflict`, `funnel_step_limit_reached`, `funnel_step_invalid`, `funnel_invalid_trigger_value`,
`funnel_invalid_trigger_type`, `funnel_invalid_keywords`, `funnel_no_steps`, `funnel_invalid_state`,
`funnel_broken_edge`. (The prompt's `custom_field_type_mismatch` is a subscriber-module code, not here.)

> **`validateSteps` is `private void`.** If Tool 1 (test-run pre-check) or a dedicated "validate" endpoint needs it,
> it must be promoted to package-private/public or wrapped. Trigger validation (`applyTrigger`) is also private.

### Frontend: client-side validation mirror ALREADY EXISTS
- `frontend/components/funnels/FunnelStepForm.vue` (~880 lines) — header comment line 13: "All client validation
  MIRRORS backend `FunnelService.validateSteps` byte-for-…". Uses `vee-validate` + `@vee-validate/zod`. Constants
  mirror the backend (`MENU_MAX_BUTTONS=8`, 4096 text cap, parse modes `['', 'HTML', 'MarkdownV2']`, tag/event slug
  regexes). Validation messages live under i18n `funnels.steps.validation.*` (full list dumped below).
- **There is NO message preview panel anywhere yet.** Searched `components/funnels/*` and the funnel pages: the only
  "preview" hits are the deep-link preview in `FunnelTriggerSettings.vue` (lines 7/54/159) and the editor's bot
  deep-link. `ux-guidelines.md:62` describes a "Step preview panel on the right showing how message looks in
  Telegram" — **this is NOT built. Tool 4's preview is greenfield.**

### `VariableTemplateRenderer` — what the preview must mirror (`funnel/VariableTemplateRenderer.java`)
- Pure static `render(template, parseMode, Subscriber)`. Substitutes `{user.first_name|last_name|username}` and
  `{custom.<key>}`. Rules (from the class Javadoc + impl):
  - Unknown placeholder or null/empty value → empty string (placeholder disappears, never errors).
  - `{{` → literal `{`, `}}` → literal `}` (escapes take precedence). Unclosed `{` left verbatim. Single pass
    (substituted value is not re-parsed).
  - Non-string custom values: integral `Double` drops `.0`; `Boolean`→`true`/`false`; `Instant`→ISO-8601 UTC.
  - **Escaping (injection defence): ONLY substituted values are escaped, author markup is left untouched.**
    None (null parseMode) → no escape; HTML → escape `& < > "`; MarkdownV2 → escape the full set
    ``_*[]()~`>#+-=|{}.!`` plus backslash.
- **Preview-rendering options:**
  1. **Backend preview endpoint** (recommended for fidelity): `POST /{funnelId}/preview` or a step-level preview
     that runs `VariableTemplateRenderer.render` against the AUTHOR's subscriber (resolved via `ownerChatId` like
     Tool 1) and returns the rendered string. Pro: byte-identical to what Telegram receives. Con: needs ownerChatId.
  2. Frontend re-implements the substitution+escaping in TS. Con: drift risk (the renderer has subtle rules:
     `{{`/`}}` escaping, single-pass, Double `.0` trimming, full MarkdownV2 special set). A backend endpoint avoids
     re-implementing escaping (an OWASP A03 concern flagged in the renderer Javadoc).
- Note `VariableTemplateRenderer.render` requires a non-null `Subscriber` (`requireNonNull` line 66). A preview with
  no resolvable author subscriber must supply a stub subscriber or fall back to showing raw placeholders.

### Risks / edge cases (Tool 4)
- **Validation is already double-implemented (BE + FE).** Tool 4's "pre-save validation" is largely DONE — confirm
  whether the ask is (a) surfacing it better, (b) a standalone validate endpoint, or (c) just the missing preview.
- **Preview parse_mode escaping must match the renderer** — re-implementing in TS risks XSS/markup-injection drift;
  prefer the backend endpoint.
- **MENU has no `text` requirement on the backend** (`validateMenu` does not call `requireText`; comment at
  `FunnelStepForm.vue:268` "MENU text is the message body; the backend does NOT require it"). The preview must not
  assume MENU text is present.

---

## Frontend integration surface (all tools)

### Pages
- `frontend/pages/projects/[projectId]/funnels/index.vue` (215 lines) — list page. Each row (lines 149-177) is a
  `<li>` with an open-button (navigates to editor) + a red delete button (opens a confirm `Dialog`, lines 181-213).
  **Per-funnel "Duplicate" and "Stop all" actions go on this row** (next to delete) and/or in the editor header.
  Create trigger at line 90. Delete confirm uses shadcn `Dialog` (NOT AlertDialog — see below) with
  `deleteTarget`/`deleteError`/`deleting` refs and `funnels.deleteConfirm.*` i18n.
- `frontend/pages/projects/[projectId]/funnels/[funnelId].vue` (321 lines) — editor. Header (lines 240-275) holds
  the Activate/Pause buttons (`data-test="funnel-activate"`/`funnel-pause`) + saving indicator. **Duplicate / Test
  for me / Stop all / Preview toggle belong in this header action group (lines 254-274).** Steps rendered via
  `FunnelStepsList`; trigger via `FunnelTriggerSettings`; add/edit via `AddStepDialog`/`EditStepDialog` (which wrap
  `FunnelStepForm`). Auto-save via `persist()` (PATCH full funnel) on every step/trigger change. Error mapping:
  `resolveFunnelError(err, ctx)` maps a 422 body `code` → `errors.funnels.${code}` (lines 78-86).

### components/funnels inventory
- `FunnelStepForm.vue` — the big step editor + client validation mirror (vee-validate/zod). **Preview panel would
  attach here or in the editor page.**
- `FunnelTriggerSettings.vue` — trigger type/value/keywords + deep-link preview.
- `SearchableSelect.vue` — reusable searchable dropdown (tag/field/step-target pickers).
- `FunnelStepsList.vue`, `AddStepDialog.vue`, `EditStepDialog.vue`, `CreateFunnelDialog.vue`.

### Store & API conventions (`frontend/stores/funnels.ts`)
- Pinia store over `useApi()` (`frontend/composables/useApi.ts`). `listUrl()` reads `projectId` from the route.
  Actions catch-flip-`error`-and-RETHROW; the component maps errors via `useApiError` in its own setup (the store
  must NEVER call `useApiError` — it crashes in a Pinia action, see store comment lines 14-18).
- Existing actions: `fetch`, `fetchOne`, `create`, `update` (PATCH), `activate`, `pause`, `delete`. **Add
  `duplicate`, `stopAllExecutions`, `testRun`, and (if backend) `preview`** following the same shape. `syncRow`
  (line 105) updates the list row from a fresh `FunnelResponse`.
- `useApi` auto-attaches the XSRF token for non-safe methods (POST/DELETE) — new POST endpoints get CSRF for free.
- Types live in `frontend/types/funnel.ts` (referenced throughout) — add request/response types there.

### Confirm-dialog availability
- shadcn components present: `frontend/components/ui/{dialog, select, combobox, input, badge, card, checkbox,
  sheet, sonner, table, tabs, tooltip}`. **There is NO `alert-dialog`.** Reuse the `Dialog`-based confirm pattern
  from `index.vue` (delete) for "Stop all executions" (a destructive confirm). `sonner` (toast) is available for
  success feedback (e.g. "Test sent", "Duplicated", "N executions stopped").

### i18n (`frontend/i18n/locales/{uk,en}.json`)
- `funnels.*` top keys: `title, createButton, filter, status, emptyState, form, createDialog, deleteConfirm,
  editor, steps, trigger`.
- `funnels.editor` = `{stepsTitle, saving, activate, pause}` — **add keys** for `duplicate`, `testRun`/`testForMe`,
  `stopAll`, `preview`, plus their confirm/result strings.
- `funnels.steps.validation.*` already covers all step field messages (full set: textRequired, textMax,
  imageUrlRequired/Scheme, delayMin, tagPattern, customFieldKeyRequired, customFieldValueNumber/Date/StringMax,
  menuButtonLabelRequired/Max, menuButtonUrlRequired/Scheme, menuNeedsCallback, menuTimeoutValueMin,
  menuTimeoutUnitRequired, eventNamePattern).
- `errors.funnels.*` maps business codes → messages: `create.{422,generic}`, `list.generic`, `update.generic`,
  `delete.generic`, `activate.generic`, `pause.generic`, plus per-code: `funnel_no_steps`, `funnel_step_invalid`,
  `funnel_step_limit_reached`, `funnel_trigger_conflict`, `funnel_invalid_trigger_value`, `funnel_invalid_state`,
  `funnel_broken_edge`. **Missing codes that may surface from new endpoints:** `funnel_invalid_trigger_type`,
  `funnel_invalid_keywords` (exist in BE, not yet in `errors.funnels`), plus any NEW codes Tool 1/3 introduce
  (e.g. an "owner not linked / start the bot first" code). Add UK + EN.

---

## Already-built (do NOT rebuild)

- **Bulk execution cancel by funnelId** — exact pattern lives in `FunnelService.delete` (just needs extracting into
  a public `stopAllExecutions` + an endpoint). Tool 3 is mostly extraction.
- **Direct-enroll primitive** — `FunnelExecutionFactory.insertExecution` + `cancelExistingForPair`. Tool 1 wires
  these to the ownerChatId-resolved subscriber.
- **Deep-copy of steps** — `FunnelStep.copyOf`. Tool 2 maps over `getSteps()` with it; edge ids are copy-safe.
- **Full step validation** — `FunnelService.validateSteps` (BE) + `FunnelStepForm.vue` zod schema (FE mirror).
  Tool 4's "pre-save validation" is essentially done; only the **message preview panel is greenfield**.
- **`ownerChatId` population** — done in `ProcessTelegramUpdateJob` (Epic 04b). Tool 1 only READS it.

## Backend constraints that affect these tools

- **Trigger partial-unique index** (`funnels`, active+on_start only) → duplicates MUST be `status=draft`.
- **Re-enter partial-unique index** (`funnel_executions`, in-flight statuses) → "Test for me" must handle
  `DuplicateKeyException` (restart-or-noop).
- **Claim-CAS / `stepRunStatus=done` terminal-cancel rule** → "Stop all" MUST set both `status=cancelled` AND
  `stepRunStatus=done`.
- **`FunnelExecutionFactory` is package-private** → new enroll logic must live in the `funnel` package (or expose
  the factory).
- **`validateSteps`/`applyTrigger` are private** → expose if a standalone validate/preview/test endpoint needs them.
- **Lowercase enum `name()` literals** are load-bearing for indexes/CAS — never rename `FunnelStatus`/
  `ExecutionStatus`/`StepRunStatus` constants.
- **Config:** `app.funnel.max-steps=50`, `scheduler-interval=PT30S`, `max-steps-per-tick=100`,
  `max-fanout-per-event=50`, `auto-enroll-rate-per-min=20`, `max-enroll-depth=10`, `app.telegram.base-url`
  (`backend/src/main/resources/application.properties:45-81`).
