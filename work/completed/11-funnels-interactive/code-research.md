# Code Research: 11-funnels-interactive (Funnels Phase 2 — Interactive + keyboard)

Scope: what Phase 2 must touch/extend on top of the completed Phase-1 linear engine
(`work/completed/10-funnels`). Phase 2 adds: inline keyboards (`reply_markup`) in TelegramSender,
`callback_query` handling in the webhook worker, a "Wait for Reply" step, "Branch by Button", a
composite "Menu" step, and branch-analytics hooks.

All paths absolute. The Phase-2 spec artifacts (`user-spec.md`, `tech-spec.md`, `decisions.md`) are
still empty templates — this research is derived from the Phase-1 source.

---

## 1. Funnel engine internals — where branching + `waiting_for_reply` plug in

File: `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java`

### Claim / advance / park model (today, linear only)

- `sweep()` (line 86) — `@Recurring(id="funnel-sweep", interval=PT30S)`. Queries `funnel_executions`
  where `status IN [running, waiting] AND nextRunAt <= now`, oldest-first, capped at `batchSize`
  (lines 90-95). Calls `runExecution(id)` per row inside a per-row try/catch (lines 101-109) — one
  bad execution never aborts the tick.
- `claim(executionId, now)` (line 199) — the **at-most-once CAS**. Single `findAndModify`:
  predicate `_id == id AND status IN [running,waiting] AND nextRunAt<=now AND stepRunStatus==pending`;
  update flips `stepRunStatus pending→in_progress`, `returnNew(true)`. `null` = lost the race.
- `runExecution(executionId)` (line 114):
  - **Resume-from-delay** (lines 128-131): a claimed `waiting` execution means its Delay elapsed; it
    bumps `currentStepIndex+1` and flips `waiting→running` in memory. **This is the exact seam
    a `waiting_for_reply` resume must NOT reuse** — a reply-resume is driven by an external
    callback, not by `nextRunAt`, and the branch target index is data-dependent, not `+1`.
  - Pre-step gates: subscriber must be `ACTIVE` (lines 134-138 → `terminate(cancelled)`); pinned
    `telegramBotId` must resolve to a `CONNECTED` bot (lines 141-146 → `terminate(failed)`).
  - Main loop (lines 153-189): `while status==running && guard-->0`; `guard = size+1` (line 152).
    Dispatches each step via `stepExecutor.execute(...)` and switches on `result.outcome()`:
    `CONTINUE` → `currentStepIndex+1` then `persistProgress` (lines 161-175); `DELAY` →
    `scheduleDelay` + return (lines 176-179); `CANCEL`/`FAIL` → `terminate` + return.
- `stillClaimed(executionId)` (line 216) — **conditional-write pattern**. Every in-tick write
  (`persistProgress`, `scheduleDelay`, `complete`, `terminate`) is a `findAndModify` predicated on
  `stepRunStatus==in_progress`. If a concurrent terminal cancel flipped `stepRunStatus→done`, the
  CAS no-ops (`returnNew==null`), the engine detects it and stops — cancel deterministically wins,
  no resurrection.
- `scheduleDelay(exec, nextRunAt)` (line 242) — the **park primitive**: sets `status=waiting`,
  `stepRunStatus=pending`, leaves `currentStepIndex` on the Delay step, sets `nextRunAt`. This is
  the template for a new "park on reply" write, EXCEPT a reply-park needs `nextRunAt` set far in the
  future (or null) so the sweep never auto-resumes it, and a new status to keep it out of the
  delay-resume path.

### EXACTLY where Phase 2 plugs in

1. **New `waiting_for_reply` status (recommended) so a reply-park is distinct from a delay-park.**
   The sweep query (line 90) intentionally must NOT include `waiting_for_reply` (otherwise the sweep
   would auto-resume a reply-park as if a delay elapsed — wrong). The resume path (lines 128-131)
   keys on `status==waiting` (= delay), so a separate status keeps the `+1` auto-advance from firing
   on a reply-park.
2. **A new `StepExecutor.Outcome` (e.g. `WAIT_FOR_REPLY`)** returned by the Wait/Menu step, handled
   in the `runExecution` switch (lines 160-188) with a new `parkOnReply(exec)` write modeled on
   `scheduleDelay` but writing `status=waiting_for_reply`, `stepRunStatus=pending`,
   `currentStepIndex` left ON the wait step (so the resume knows which step's buttons to match).
3. **Branching = a non-linear `currentStepIndex` jump.** Today the ONLY index mutations are `+1`
   (CONTINUE line 162, delay-resume line 129). A branch must set `currentStepIndex` to a target
   index (the branch's destination). The cleanest seam is a NEW engine entry point invoked by the
   callback path (NOT the sweep): `resumeOnCallback(executionId, matchedTargetIndex)` that re-claims
   the reply-parked row (`claim`-style CAS but predicate `status==waiting_for_reply`), sets
   `currentStepIndex = matchedTargetIndex`, flips `status→running`, then runs the same step loop. The
   guard `size+1` (line 152) is index-monotonic for linear flows; a backward branch jump would break
   the guard invariant — Phase 2 must redefine the loop ceiling (e.g. a max-iterations cap) if
   backward jumps are allowed, otherwise restrict branch targets to forward-only.

The `stillClaimed`/CAS discipline (line 216) MUST be preserved for any new write — a callback-driven
resume races the sweep, `/stop` cancel (`cancelActiveFor`), and `delete`.

---

## 2. Enums — `ExecutionStatus` / `StepRunStatus` / `StepType` + index-literal constraints

- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/ExecutionStatus.java`
  — `running, waiting, completed, cancelled, failed`. **LOWERCASE on purpose (Decision 14):** Spring
  Data persists via `name()`, and the values are written as `.name()` literals in the sweep predicate
  and in the partial-index `partialFilter` (`status: { $in: ['running','waiting'] }`).
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/StepRunStatus.java`
  — `pending, in_progress, done` (lowercase, drives the CAS).
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/StepType.java`
  — `SEND_MESSAGE, SEND_IMAGE, DELAY, ADD_TAG, REMOVE_TAG, SET_CUSTOM_FIELD`. UPPERCASE — a step-kind
  discriminator, **no partial-index literal dependency**, so adding values here is low-risk.

### What adding `waiting_for_reply` requires (audit checklist)

The literal `running`/`waiting` appears as byte-match index/query literals in these spots — a new
status that participates in the re-enter guard or sweep must be added to ALL of them, and the static
assertion extended:

- `FunnelExecution.java` (`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/FunnelExecution.java`):
  - `@CompoundIndex` `funnelId_subscriberId_unique_active` `partialFilter = "{ 'status': { $in: ['running', 'waiting'] } }"` (lines 24-27) — the **sole re-enter guard**. A `waiting_for_reply`
    execution is still an in-flight execution; it MUST be in this `$in` list or a `/start` re-enter
    could create a second concurrent execution for the same `(funnelId, subscriberId)`.
  - **Static class-load assertion** (lines 34-41) asserts `"running".equals(running.name())` and
    `"waiting".equals(waiting.name())`. Extend it to assert the new literal too.
- `FunnelExecutionEngine.java`: sweep `in(...)` (line 91), `claim` `in(...)` (line 201). The new
  status should be EXCLUDED from the sweep/claim `in(...)` (a reply-park is not time-driven).
- `FunnelTriggerServiceImpl.java`: `cancelActiveFor` `in(...)` (line 156), `cancelExistingForPair`
  `in(...)` (line 177) — a `/stop` and a re-enter restart must cancel a reply-parked execution too →
  add `waiting_for_reply` here.
- `FunnelService.java`: `delete()` `in(...)` (line 150) — funnel delete must cancel reply-parked
  executions → add here.

`StepType` additions (`WAIT_FOR_REPLY`, `BRANCH`, `MENU` — exact shape TBD in tech-spec): add the
enum constants, the `StepExecutor.execute` switch arms (line 83), and the frontend mirror
`/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/types/funnel.ts` `StepType` union (line 10).
No index literals depend on StepType.

---

## 3. TelegramSender — adding `reply_markup` + `answerCallbackQuery`

File: `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/bot/TelegramSender.java`

Confirmed: **NO `reply_markup`, `inline_keyboard`, or `answerCallbackQuery` support anywhere in the
backend** (grep over `src/main` returns only the opaque `callback_query` slot in
`TelegramUpdate`/`ProcessTelegramUpdateJob`).

Current signatures:
- `sendText(String botId, Long chatId, String text, String parseMode, String ownerId)` (line 128) —
  builds `contentFields` map (`text`, optional `parse_mode`), delegates to `send(...)` with endpoint
  `/bot{token}/sendMessage`.
- `sendPhoto(String botId, Long chatId, String imageUrl, String caption, String parseMode, String ownerId)`
  (line 139) — `photo`, optional `caption`, optional `parse_mode`, endpoint `/bot{token}/sendPhoto`.
- Both funnel call-sites are in `StepExecutor` (lines 108, 132) passing `null` for `ownerId`.

### Where reply_markup goes

The shared orchestration `send(botId, chatId, endpoint, contentFields, ownerId)` (line 158) is
endpoint-agnostic — it only varies by endpoint path + `contentFields`. To add an inline keyboard:
add `reply_markup` (a `{"inline_keyboard": [[{text, callback_data}, ...]]}` object) into the
`contentFields` map in `sendText`/`sendPhoto` (or new overloads), **only when non-null** (mirror the
`parse_mode` `if (parseMode != null)` idiom, lines 133, 148). Everything downstream
(`sendWithRateLimitRetry` → `sendWith5xxRetry` → `sendOnce`, lines 225-310) serializes the body map
to JSON via RestClient and needs no change — `reply_markup` rides along as another body field.
`sendOnce` copies `contentFields` into a fresh map per attempt (line 297), so the keyboard is
retry-safe.

`callback_data` byte limit is 64 bytes (Telegram); enforce at validation time (Section 6) or when
building the markup. Button text must respect `parse_mode` only for the *message* text, NOT the
button label — Telegram does not parse button labels, so no escaping needed for labels.

### `answerCallbackQuery` (new method)

A new `answerCallbackQuery(botId, callbackQueryId, optionalText)` calling
`/bot{token}/answerCallbackQuery` must follow the SAME patterns the existing methods establish:
- AES-GCM per-call token decrypt inside `sendOnce` (lines 274-286).
- CONNECTED-bot filter (lines 173-175) → `AppException.notFound(MESSAGE_BOT_NOT_FOUND)`.
- 5xx backoff (1/2/4s, 3 retries) + 429 `retry_after` loop + 30s overall deadline (lines 225-270).
- Token-scrubbed logging via `TelegramApiClient.scrubTokens(...)` — NEVER log raw token.
- Audit events `telegram_message_sent` / `telegram_send_failed` (lines 50-51, 179-187) — decide
  whether an answerCallbackQuery emits an audit event (it is a non-content ack; likely a lighter or
  no audit, TBD). The existing `dispatchSubscriberHook` (line 200, 403→blocked / chat-not-found) is
  send-specific; answerCallbackQuery should probably NOT flip subscriber CRM state.
- Telegram requirement: every `callback_query` MUST be answered with `answerCallbackQuery` or the
  user's button shows a spinner indefinitely. This call should be best-effort / non-fatal to the
  funnel advance (answer the query, then advance; a failed answer must not block the branch).

Body DTOs live under `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/bot/dto/`
(`TelegramSendParameters`, `TelegramSendResult`, `SentMessage`). `SentMessage(chatId, messageId,
sentAt)` (record) is returned by sends; a Menu step that sends a keyboard may want the `messageId`
back to later edit/remove the keyboard (optional Phase-2 scope).

---

## 4. Webhook ingestion — modeling `callback_query`

File: `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`

Today `callback_query` is an opaque `JsonNode` slot on `TelegramUpdate`
(`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/webhook/dto/TelegramUpdate.java`
line 23). In `dispatch(...)` (line 146) a non-message update falls into `resolveUpdateKind(update)`
(line 292), which detects `callback_query` (line 297) and routes to `logEventOther(..., "callback_query")`
(line 152) — i.e. it is currently logged and dropped.

### What Phase 2 must do

1. **New typed DTO** for the callback (replace the `JsonNode` slot or add a typed slot). A Telegram
   `callback_query` carries: `id` (the `callbackQueryId` for answerCallbackQuery), `from` (User),
   `message` (the original Message, → `chat.id`), `data` (the `callback_data` string). Model a
   `CallbackQuery(String id, User from, Message message, String data)` record (mirror the existing
   `Message`/`Chat`/`User` records under
   `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/webhook/dto/`,
   all `@JsonIgnoreProperties(ignoreUnknown=true)` snake_case records).
2. **Dispatch branch**: in `dispatch(...)`, before the `message == null → logEventOther` fallback
   (line 148), add a `if (update.callback_query() != null)` arm that:
   - Resolves the CONNECTED bot (`botRepository.findByProjectIdAndStatus(projectId, CONNECTED)`,
     mirror line 183/208) — bot-pin check; if absent, `logEventOther("unknown")`.
   - Extracts `chatId` from `callback_query.message.chat.id` and `data`.
   - Calls a NEW `funnelTriggerService.advanceOnCallback(projectId, chatId, data, callbackQueryId)`
     (Section 5). This MUST be error-isolated the same way `fire()`/`cancelActiveFor()` are (the
     trigger service swallows internally; the worker never lets a funnel fault flip the raw_update to
     FAILED → retry storm).
   - Emits an event (a new `telegram_callback_query` event or the branch-analytics event — Section 9).
3. **Ordering / idempotency invariants to preserve** (Decisions 5/9/12, lines 30-35, 88-92):
   - **Re-entry guard** (line 88): `if processingStatus == DONE return` — already in place; the
     callback path inherits it.
   - **Idempotency**: `raw_updates` unique `(projectId, updateId)` index dedupes Telegram retries at
     ingestion (controller `DuplicateKeyException` self-heal,
     `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java`
     lines 167-182). A double-click on a button = two DIFFERENT `update_id`s → two raw_updates → the
     callback path runs twice; **button idempotency must be enforced in the engine resume** (the
     reply-park is already consumed on first resume; the second resume finds the execution no longer
     `waiting_for_reply` and no-ops via the claim CAS). See Section 9.
   - **Event-before-status-flip ordering** (lines 31-33, 102-105): every `eventService.logEvent(...)`
     in `dispatch` must finish BEFORE `rawUpdate.setProcessingStatus(DONE)` (line 104). The new
     callback event must be logged inside `dispatch`, before the flip — same as `handleStart`.
   - **Error isolation** (lines 109-111, `handleFailure`): catch Throwable → write FAILED + rethrow
     for JobRunr retry. `advanceOnCallback` must be internally try/catch so a funnel fault does NOT
     reach this path.

DI already present in the constructor (lines 58-76): `funnelTriggerService`, `subscriberService`,
`botRepository`, `eventService`, `mongoTemplate`, `objectMapper`.

---

## 5. FunnelTriggerService / FunnelTriggerServiceImpl — `advanceOnCallback`

Files:
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/FunnelTriggerService.java`
  (interface: `fire(projectId, chatId, triggerType, payload)`, `cancelActiveFor(projectId, chatId)`)
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/FunnelTriggerServiceImpl.java`

### Existing shape to mirror

- `fire()` (line 82): all wrapped in `try/catch(Throwable)` that swallows + logs `LOG_FIRE_ERROR`
  (lines 129-132) — **NEVER throws outward** (Decision 6). Resolves CONNECTED bot → subscriber via
  `subscriberService.findByChat(projectId, telegramBotId, chatId)` (line 95, the non-HTTP lookup,
  `SubscriberService.findByChat` line 88) → matches the active funnel by `(triggerType, triggerValue)`
  → re-enter guard → `insertExecution` (line 188, builds the snapshot, sets status/index).
- `cancelActiveFor()` (line 136): resolves bot+subscriber, `updateMulti` flips every running|waiting
  execution → cancelled + stepRunStatus=done (lines 153-161).
- Named log constants (lines 51-61) — codes/ids only, no PII.

### New `advanceOnCallback(projectId, chatId, callbackData, callbackQueryId)`

Add to the interface + impl. It must, all inside a `try/catch(Throwable)` swallow:
1. Resolve CONNECTED bot (mirror line 85) → `telegramBotId`.
2. Resolve subscriber via `subscriberService.findByChat(projectId, telegramBotId, chatId)` (line 95).
3. **Find the waiting execution.** Query `funnel_executions` where
   `projectId == p AND subscriberId == sub.id AND status == waiting_for_reply`. The re-enter unique
   index guarantees at most one in-flight execution per `(funnelId, subscriberId)`, but a subscriber
   can be in multiple funnels → there may be >1 reply-parked execution. Disambiguation needs the
   button's origin — likely match the execution whose CURRENT wait step's buttons contain a button
   with `callback_data == callbackData`. The `callbackData` should encode the execution/step (e.g.
   `executionId:stepIndex:buttonKey` within 64 bytes) so the resume targets the exact parked
   execution and validates the button still belongs to the current wait step (callback-on-wrong-step
   defense, Section 9).
4. **Match the button → branch target index** by reading `exec.getStepsSnapshot().get(currentStepIndex)`
   and finding the button matching `callbackData`; its configured branch target becomes the new index.
   A non-matching `callbackData` (stale button from an earlier step) → no-op (do not advance).
5. **Resume the engine** via a new `FunnelExecutionEngine.resumeOnCallback(executionId, targetIndex)`
   (Section 1) — a `claim`-style CAS predicated on `status==waiting_for_reply && stepRunStatus==pending`,
   then set index + `status→running` and run the step loop. The claim CAS makes a double-click /
   concurrent resume idempotent (second loses the CAS).
6. Best-effort `telegramSender.answerCallbackQuery(...)` (Section 3) so the button spinner clears —
   on its own try/catch so a failed ack never blocks the advance.

Note: `FunnelExecutionEngine` is currently NOT injected into `FunnelTriggerServiceImpl`. Either inject
it, or put `resumeOnCallback` orchestration on the engine and have the trigger service locate the
execution then delegate. Both are in the same `funnel` package — no module-boundary issue. Subscriber
lookups MUST stay via `SubscriberService` (not the repo) per the established boundary (line 92 comment).

---

## 6. FunnelService validateSteps + DTOs

Files:
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/FunnelService.java`
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/dto/FunnelStepDto.java`
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/dto/UpdateFunnelRequest.java`
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/dto/CreateFunnelRequest.java`

### Validation today

`validateSteps(List<FunnelStep>)` (FunnelService line 273): first checks `steps.size() > maxSteps`
(`@Value("${app.funnel.max-steps:50}")`, line 72 — the **step-limit constant**, `CODE_STEP_LIMIT`),
then a per-type `switch` (lines 278-292) calling `requireText`/`requireImageUrl`/`requireParseMode`/
`requireDelay`/`requireTagSlug`/`requireCustomFieldKey`. All failures → 422 with a business code
(`CODE_INVALID_STEP` etc., lines 53-58). Called from `update()` (line 127) and `activate()` (line 170).

`toSteps(dtos)` (line 247) maps each DTO to a `FunnelStep`, setting `order = i` (array index =
server-authoritative order, lines 256-266). DTO carries only the flat per-type fields; only
`@NotNull stepType` is bean-validated (FunnelStepDto lines 16, comment 8-13).

### New Phase-2 validation needed (in `validateSteps` switch + new helpers)

- For a Menu/Wait/Branch step: **buttons present** (≥1, ≤ N — Telegram caps inline keyboards; a
  sane max like 8-12 buttons), each button has non-empty label + a `callback_data` ≤ 64 bytes and
  unique within the step.
- **Branch targets valid**: each button's target index must be in-range (`0 <= target < steps.size()`)
  and (if forward-only is chosen to keep the engine guard, Section 1) `target > thisStepIndex`.
- **No orphan branches**: every button maps to a reachable target; optionally a default/fallthrough.
  Because order is rewritten from the array index, branch targets should be stored as a stable
  reference resolved against the final array (the editor must keep targets consistent across reorder —
  Section 7 hazard).
- A reply/menu step likely must NOT be the implicit last step without buttons, etc. (TBD).

### New DTO fields

`FunnelStepDto` (record, line 15) gains nullable fields for the keyboard/branch (e.g.
`List<ButtonDto> buttons`, a wait-timeout, branch config). A nested `ButtonDto(label, callbackData,
targetIndex)` record. `FunnelStep` POJO
(`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/FunnelStep.java`)
gains the matching fields AND `copyOf` (line 57) must deep-copy them — **note the deep-copy contract
(lines 46-56): a `List<Button>` is mutable, so `copyOf` MUST defensively copy it** (the current
contract relies on all fields being immutable scalars; a buttons list breaks that and needs explicit
copying to preserve snapshot isolation, Decision 3). `FunnelStepDto.toStepDto`/`toSteps` mapping
(FunnelService lines 247-269, 381-393) must round-trip the new fields. `@JsonIgnoreProperties` on the
DTOs (mass-assignment defense) stays.

---

## 7. Frontend funnel editor

Files under `/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/`:
- `components/funnels/FunnelStepForm.vue` — shared per-type step form (used by both dialogs).
- `components/funnels/AddStepDialog.vue` / `components/funnels/EditStepDialog.vue` — thin
  `Dialog` wrappers around `FunnelStepForm` (Add emits append, Edit emits replace-at-index).
- `components/funnels/FunnelStepsList.vue` — vertical ordered list (move ↑/↓, edit, two-click delete).
- `components/funnels/SearchableSelect.vue` — reusable combobox (used for tag + custom-field pickers).
- `components/funnels/FunnelTriggerSettings.vue`, `CreateFunnelDialog.vue`.
- `pages/projects/[projectId]/funnels/[funnelId].vue` — the editor page (state, persist, activate).
- `stores/funnels.ts` — Pinia CRUD store.
- `types/funnel.ts` — TS mirror of backend DTOs.
- i18n: `i18n/locales/en.json` + `i18n/locales/uk.json` (funnel keys at lines 418 and 655).

### How step types are added/edited today

`FunnelStepForm.vue`: a `selectedType` ref (line 42) drives a `<select>` (line 319) over `STEP_TYPES`
(lines 24-31). The validation `schema` is a `computed` over `selectedType` (`schemaFor(type)`, line
135) — only the active type's fields are validated, the rest fall back to `z.any()` (so a stale value
never blocks submit). Per-type template blocks are `v-if="selectedType === ..."` (lines 330-516).
`onSubmit` (line 281) narrows the flat model to ONLY the active type's persisted fields. The form is
re-keyed per dialog open (AddStepDialog line 18-25) so it starts clean. The page
(`[funnelId].vue`) holds `steps` locally and PATCHes the FULL array on every add/edit/delete/move
(`persist()` line 96; handlers lines 119-138). Position = order; client never sends `order`.

### Where buttons/branch editor + Menu/Wait UI plug in

- `types/funnel.ts`: add the new `StepType` members to the union (line 10) and new optional fields to
  the `FunnelStep` interface (line 30) — `buttons?: Button[]`, branch/wait fields, plus a `Button`
  interface. Keep the `CURRENT_DATE_TOKEN` mirroring discipline (line 24) for any new shared literal.
- `FunnelStepForm.vue`: add `MENU`/`WAIT_FOR_REPLY`/`BRANCH` to `STEP_TYPES` (line 24), a new
  `schemaFor` arm (line 135) validating buttons (non-empty label, callback_data ≤64, unique; branch
  target in-range) mirroring backend `validateSteps`, a new `v-if` template block with a dynamic
  list-of-buttons editor (add/remove button rows). The branch-target picker can reuse
  `SearchableSelect.vue` (options = the other steps by index/label). `onSubmit` (line 281) gains a
  new `case` building the menu/wait step.
- `FunnelStepsList.vue`: `summary(step)` switch (line 19) needs arms for the new types so the list
  renders a readable summary (e.g. button count). Branch targets shift on reorder — the list/move
  handlers (`[funnelId].vue` `onMove` line 131) currently just splice the array; with branch targets
  stored as indices, **a reorder must remap every branch target index** (hazard, Section 9) — prefer
  storing targets as stable ids resolved at PATCH time, or remap on move.
- Locale keys for the new step types/labels in both `en.json` and `uk.json` under the `funnels.steps.*`
  trees (e.g. `funnels.steps.type.MENU`, button editor labels, validation messages).

`SearchableSelect.vue` reuse: the props contract (`options: {value,label,hint?}[]`, `loading`,
`invalid`, `test-prefix`, placeholder/empty/loading/no-matches text) is already used by the tag and
custom-field pickers (FunnelStepForm lines 422-453) — a branch-target picker uses the same shape.

---

## 8. Tests & patterns Phase 2 must mirror

### Backend engine ITs
File: `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/test/java/com/botfunnel/funnel/FunnelExecutionEngineIT.java`
- Full-context IT: Testcontainers Mongo + in-memory JobRunr + `MockWebServer` Telegram + a LOCAL
  `@Primary` `MutableClock` (line 66, `TestClockConfig` line 58, the class at line 536). `sweep()` is
  invoked directly; time is moved via `CLOCK.advance(...)` (e.g. delay-resume test lines 153-173).
- The deterministic-clock bean: production `Clock` is `ClockConfig.systemClock()`
  (`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/common/ClockConfig.java`);
  the IT overrides it `@Primary` locally so it never leaks into other contexts.
- Patterns Phase 2 reply/branch tests must mirror:
  - `delaySetsNextRunAtAndResumesOnNextSweep` (line 153) → the template for a `parkOnReply` +
    `resumeOnCallback` test (park, assert `status==waiting_for_reply` & sweep does NOT resume it,
    then invoke `resumeOnCallback`, assert it advances to the branch target).
  - `reRunningClaimedStepDoesNotDuplicateSend` (line 178), `crashAfterSendBeforeDoneFlipDoesNotResend`
    (line 191), `atomicClaimRaceAcrossTwoReplicasRunsStepOnce` (line 209, uses
    `ConcurrencyTestUtils.parallelInvoke`), `concurrentCancelMidTickWinsOverEngineWrite` (line 225,
    cancel fired from inside the MockWebServer dispatcher) and `...OverTerminalWrite` (line 261) →
    the templates for: double-click button idempotency, callback-vs-cancel race, callback-on-completed.
  - Helpers `seedExecution(subId, nextRunAt, steps...)` (line 484), `seedActiveSubscriber` (line 504),
    `seedConnectedBot` (line 520), `enqueueOk(n)` (line 451), `reload(id)` (line 447).
- `FunnelStepExecutorTest.java` (unit, per-step dispatch) and `FunnelStepTest.java` (copyOf/deep-copy)
  — add WAIT/MENU/BRANCH unit cases; `FunnelStepTest` must verify the new buttons list is
  deep-copied.
- `FunnelControllerIT.java` / `FunnelService` tests — add validateSteps cases (buttons/branch target
  422s, max-buttons).
- `FunnelIndexesIT.java`, `FunnelStatusEnumTest.java` — if a new `waiting_for_reply` status touches
  the partial index, extend these to assert the index literal / enum drift guard.

### Webhook ITs
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java`
  — establishes the dispatch-matrix patterns: `reentryGuard_doneStatus_noOp` (line 113),
  `eventWriteBeforeStatusFlip_orderingInvariant` (line 202), `startPrivateWithPayload_callsSubscriberAndFunnel_writesEvent`
  (line 234), `stopPrivate_callsMarkUnsubscribedAndCancel_writesEvent` (line 281). A new
  `callbackQuery_callsAdvanceOnCallback_writesEvent` test mirrors these (stub
  `funnelTriggerService.advanceOnCallback`, assert call + event-before-flip).
- `TelegramWebhookControllerIT.java` — ingestion/idempotency (`(projectId, updateId)` unique).

### Frontend tests
- vitest editor/component: `tests/pages/funnel-editor.spec.ts`,
  `tests/components/funnels/CreateFunnelDialog.spec.ts`, `tests/components/FunnelStepsList.spec.ts`,
  `tests/components/SearchableSelect.spec.ts`, `tests/stores/funnels.spec.ts`. New buttons/branch
  form cases mirror the existing per-type form tests (select a type, fill fields, assert emitted step
  shape + validation messages).
- playwright e2e: `e2e/funnels.spec.ts` — `funnels_goldenPath_buildAndActivate` builds a 3-step
  funnel via `data-test` selectors and waits on the PATCH responses (lines 77-118). A Menu/branch
  e2e adds steps with buttons + asserts the editor renders/persists them. Note the e2e is skipped
  unless a live backend + bootstrap env vars are present (lines 6-25).

---

## 9. Risks / integration hazards

- **Snapshot isolation vs branch edits (Decision 3):** executions run on `stepsSnapshot` (deep copy at
  `fire()`, `FunnelTriggerServiceImpl.insertExecution` line 199 / `deepCopySteps` line 209). A
  reply-parked execution holds an OLD snapshot; editing the funnel afterward does NOT change the
  parked execution's buttons/targets — branch resume must match against the SNAPSHOT, not the live
  funnel. The new `Button`/branch fields MUST be deep-copied in `FunnelStep.copyOf` (currently
  reference-copies because all fields are immutable scalars — a buttons LIST breaks that contract,
  FunnelStep.java lines 46-56).
- **Callback for a completed/cancelled execution:** the `resumeOnCallback` claim CAS predicated on
  `status==waiting_for_reply` no-ops if the execution already advanced/cancelled/completed — same
  guarantee as the sweep claim (FunnelExecutionEngine line 199) and the `concurrentCancelMidTick...`
  ITs. A stale callback must NOT resurrect a terminal execution.
- **Callback on the wrong step:** a user clicks a button from an EARLIER message after the funnel
  moved on. Defense: encode the step index in `callback_data` and validate it matches the
  execution's current wait `currentStepIndex` before advancing; otherwise answer the query and no-op.
- **Double-click on a button (idempotency):** each click is a distinct Telegram `update_id` → two
  raw_updates pass the `(projectId, updateId)` dedupe. The engine resume claim CAS makes the SECOND
  resume lose (status no longer `waiting_for_reply`). Still answer both callback queries.
- **Bot-pin check:** the callback path must resolve the project's CONNECTED bot (mirror
  `ProcessTelegramUpdateJob` line 183 / engine line 141) before answering/advancing; a disconnected
  bot → log + skip, do not advance.
- **Analytics-hook seam:** events today go through `EventService.logEvent(userId, type, ...)` (e.g.
  `ProcessTelegramUpdateJob` `logEventCommandStart` line 259; `TelegramSender` audit events). Phase 2
  branch analytics should emit a NEW event type (e.g. `funnel_branch_taken` with
  `funnelId/stepIndex/buttonKey`, NO PII per Decision 16) at the resume site, distinct from the raw
  `telegram_callback_query` ingestion event. Per-execution analytics aggregation belongs to the
  Analytics epic (roadmap note) — Phase 2 only emits the hook events.
- **Telegram answerCallbackQuery requirement:** unanswered `callback_query` leaves the button
  spinner spinning. Answer best-effort, isolated from the advance.
- **parse_mode escaping of button text:** message text is escaped per `parseMode` by
  `VariableTemplateRenderer.render` (`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/VariableTemplateRenderer.java`,
  escape at line 168). **Button labels are NOT parsed by Telegram** — do not escape labels, but DO
  validate `callback_data` length (≤64 bytes) and that labels are non-empty.
- **Re-enter guard coverage:** a `waiting_for_reply` execution must be inside the
  `funnelId_subscriberId_unique_active` partial filter (`FunnelExecution.java` line 26) and every
  cancel path's `status IN (...)` (Section 2) or a `/start` re-enter could spawn a duplicate
  concurrent execution, or `/stop`/delete could leave a parked execution dangling forever.
- **Engine loop guard vs backward branches:** `guard = size+1` (FunnelExecutionEngine line 152)
  assumes strictly-increasing `currentStepIndex`. A backward branch jump violates this. Either
  restrict targets to forward-only (validated in `validateSteps`, Section 6) or replace the guard
  with a max-iterations cap.
- **Branch-target index stability on reorder:** the editor PATCHes the full array and the server
  rewrites `order` from index (`FunnelService.toSteps` line 256); a move/insert/delete shifts indices,
  so any branch target stored as a raw index becomes stale. Store stable button→step references and
  remap on reorder (frontend `onMove`/`onDeleteStep`, `[funnelId].vue` lines 127-138), or resolve
  targets server-side.

---

## 10. Graph-model conversion (supersedes index-based assumptions in sections 1-9)

> The finalized `user-spec.md` (status: approved) **rejects** the index-based model that sections 1-9
> assumed. There is **ONE composite `MENU` step** (message + inline keyboard + park-on-reply +
> branch-by-button) — NO separate `WAIT_FOR_REPLY`/`BRANCH` steps. Navigation moves from
> `currentStepIndex` (int) → `currentStepId` (String). Loops + fan-in are ALLOWED. The cycle guard
> `guard = size+1` is REPLACED by a per-tick step limit. Where sections 1-9 contradict this, the
> contradictions are flagged below — those sections are kept for history, not deleted.
>
> Verified against current source on 2026-06-06.

### 10.1 Every `currentStepIndex` read/write across the backend

`grep "currentStepIndex"` over `backend/src` + `frontend/` returns hits in **exactly two backend
files** (engine + model) and **zero** in trigger service, DTOs, responses, or frontend. No DTO or
`FunnelResponse` exposes the index — it is engine-internal. Full inventory:

**`FunnelExecution.java`** (the persisted field):
- L54 `private int currentStepIndex;` → **becomes** `private String currentStepId;` (a stable step
  id; null/legacy handled by backfill, see 10.2). Keep `currentStepIndex` as a deprecated additive
  field if the drain path needs it, OR drop it once backfill guarantees every snapshot has ids — but
  the spec wants in-flight Phase-1 runs to drain, so the safe path is: **add `currentStepId`
  additively, keep `currentStepIndex`**, and have the engine prefer `currentStepId` when present,
  else fall back to the index (linear drain).
- L82-83 getter/setter `getCurrentStepIndex()` / `setCurrentStepIndex(int)` → add
  `getCurrentStepId()` / `setCurrentStepId(String)` alongside.

**`FunnelExecutionEngine.java`** — every index touch:
- L122-123 `LOG_CLAIM_WON` logs `stepIndex={exec.getCurrentStepIndex()}` → log `stepId=` instead
  (or both during migration).
- **L128-131 — delay-resume `+1`** (`if status==waiting → setCurrentStepIndex(idx+1); status=running`).
  This is the seam section 1 (L29-30) flagged. Under the graph model: a `waiting` (delay) resume must
  advance to the Delay step's `next` target id, NOT `idx+1`. A **`waiting_for_reply` resume does NOT
  pass through here at all** — it is a separate engine entry point (`resumeOnCallback`, 10.4) that
  sets `currentStepId = matchedButtonTarget` directly. **Contradiction w/ section 1 L60-67:** section
  1 proposed `resumeOnCallback(executionId, matchedTargetIndex)` with an *index* — under the graph
  model the param is a **target step id**, not an index.
- **L148-158 — main loop** (`snapshot.get(exec.getCurrentStepIndex())`). `snapshot` IS a
  `List<FunnelStep>` (confirmed `exec.getStepsSnapshot()` L148). Today a step is fetched **by array
  position** (`snapshot.get(idx)`). Under graph model: resolve **by id** — a new helper
  `stepById(List<FunnelStep> snapshot, String id)` (linear scan or a `Map<String,FunnelStep>` built
  once per tick). **No id→step lookup exists anywhere today** — it is a NEW helper.
- **L150-153 — `guard = size + 1` + `while (... && guard-- > 0)`**. This is the cycle guard the
  spec REPLACES. New: `@Value("${app.funnel.max-steps-per-tick:...}") int maxStepsPerTick;` field on
  the engine, `guard = maxStepsPerTick`. When it trips with `status==running` (a loop with no
  wait/Delay/MENU-park between steps), the execution must be `terminate(failed)` with a **greppable
  log marker** (add e.g. `LOG_STEP_BUDGET_EXCEEDED = "FUNNEL_STEP_BUDGET_EXCEEDED"` next to the
  existing markers L51-61). Today L190-193 treats a tripped guard as a benign end-of-steps complete —
  that must change to a `failed` terminate when the cursor is NOT at end-of-graph.
- **L160-162 — `CONTINUE → setCurrentStepIndex(idx+1)`**. The ONLY forward advance. Under graph:
  `exec.setCurrentStepId(step.getNext())` (or the default = next step in the snapshot list when
  `next` is unset). A `MENU` step does NOT take this arm — it returns a new park outcome.
- L173-174 `LOG_STEP_ADVANCED` logs `newStepIndex=` → `newStepId=`.
- **L227 `persistProgress`** `.set("currentStepIndex", exec.getCurrentStepIndex())` → also/instead
  `.set("currentStepId", exec.getCurrentStepId())`.
- **L246 `scheduleDelay`** (the park primitive) `.set("currentStepIndex", ...)` → `.set("currentStepId", ...)`.
  A new `parkOnReply(exec)` write (for `MENU`) is modeled on this but sets
  `status=waiting_for_reply`, leaves `currentStepId` ON the MENU step, and sets `nextRunAt` =
  timeout deadline (if a timeout is configured) or `null` (wait forever — sweep's `nextRunAt <= now`
  never matches null, so no sweep auto-resume; confirmed sweep predicate L92).
- **L263 `complete`** `.set("currentStepIndex", ...)` → `.set("currentStepId", ...)`.
- **L280 `terminate`** `.set("currentStepIndex", ...)` → `.set("currentStepId", ...)`.

`claim` (L199-209) and `stillClaimed` (L216-219) predicate on `_id` + `status` + `stepRunStatus` —
**no index dependency**, unchanged by the graph model (the spec's "Ризики" mitigation explicitly
keeps these by `_id`+`stepRunStatus`).

**`FunnelTriggerServiceImpl.insertExecution` L196** `execution.setCurrentStepIndex(0)` → must seed
`setCurrentStepId(firstStepId)` (the id of `funnel.getSteps().get(0)` after backfill). This is the
**3rd index write** in the backend (grep missed it because it is the only literal-`0` call, not a
`getCurrentStepIndex` read — verify: `setCurrentStepIndex(0)` IS at L196). Keep `setCurrentStepIndex(0)`
additively for the linear drain if both fields are retained.

**Tests/helpers:** `FunnelExecutionEngineIT.seedExecution(...)` (section 8) builds executions with a
`currentStepIndex` — its seed helper must set `currentStepId` too. No production DTO/response carries
the index, so no API contract change.

### 10.2 `FunnelStep` model, `order`, and stable `id` backfill

**Current fields** (`FunnelStep.java` L13-41): `stepType`, `int order`, `text`, `parseMode`,
`imageUrl`, `caption`, `Integer delayValue`, `String delayUnit`, `tagSlug`, `customFieldKey`,
`Object customFieldValue`. Flat POJO, no `_class` (Decision 12). **No `id` field today.**

**How `order` is assigned:** `FunnelService.toSteps` L252-256 — `for (int i...) { step.setOrder(i) }`.
The array index IS the order, server-authoritative; the client never sends `order` (confirmed
`types/funnel.ts` L27-29 + `[funnelId].vue` L94-95). **This array-index rewrite is exactly why a
branch target stored as an index is unstable across reorder** — graph targets MUST be stable ids.

**Where `id` is added:** new `private String id;` field on `FunnelStep` (+ getter/setter), copied in
`copyOf` (10.3). `toSteps` (L252-266) must **preserve an incoming `id`** from the DTO if present and
**mint a new one** (e.g. `new ObjectId().toHexString()` or a UUID) when the DTO has none (new step in
the editor). `order` can stay (cheap, still array-authoritative) but is no longer load-bearing for
navigation — `next`/button targets reference `id`.

**Backfill precedent — CONFIRMED, cite this file:**
`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/admin/SuperAdminSeeder.java`
— `@Component implements ApplicationRunner`, `run(ApplicationArguments)` wrapped in try/catch that
**logs and never propagates** ("the app must boot even if seeding fails", L17-19, L42-47), idempotent
on every startup. This is the exact pattern for a `FunnelStepIdBackfill implements ApplicationRunner`
that scans `funnels` (and optionally in-flight `funnel_executions.stepsSnapshot`) and mints an `id`
for every step missing one, idempotently. **No `@PostConstruct` / `CommandLineRunner` / JobRunr
migration-job precedent exists** — `ApplicationRunner` (SuperAdminSeeder) is the only startup-hook
precedent in `backend/src/main` (grep over `PostConstruct|CommandLineRunner|ApplicationRunner|
@EventListener|ContextRefreshedEvent` returns only `SuperAdminSeeder`, `WebhookPayloadSizeFilter`,
`TelegramWebhookController`). For in-flight executions, the alternative to a snapshot backfill is the
engine's **index↔id dual path** (10.1): a legacy snapshot with no ids keeps draining on
`currentStepIndex`. The spec wants both: backfill funnel definitions additively; let linear in-flight
runs drain on the index path.

### 10.3 `FunnelStep.copyOf` + the snapshot deep-copy chain

**Current `copyOf`** (`FunnelStep.java` L57-74): field-wise reference copy of 11 scalar fields. The
Javadoc L46-56 explicitly states the contract — "every field is an immutable value type ... a
field-wise reference copy is therefore a sufficient deep copy. No collection-defensive-copy logic is
needed (and is intentionally absent)". A mutable `List<Button>` **breaks this stated contract** and
needs explicit deep copy (spec "Ограничения" + Decision 3).

**Current chain:** `FunnelTriggerServiceImpl.insertExecution` L199 `setStepsSnapshot(deepCopySteps(funnel.getSteps()))`
→ `deepCopySteps` L209-217 (`for (step : steps) snapshot.add(FunnelStep.copyOf(step))`). The list of
steps is freshly allocated (`new ArrayList<>()`), and each element is `copyOf`-ed — so step-level
isolation holds **as long as `copyOf` deep-copies every mutable field inside a step**.

**What must change:**
- (a) **mutable `List<Button>`:** `copyOf` must do `copy.buttons = source.buttons == null ? null :
  source.buttons.stream().map(Button::copyOf).collect(toCollection(ArrayList::new))` (a new list of
  new `Button` instances) — a shallow `new ArrayList<>(source.buttons)` is INSUFFICIENT because the
  `Button` objects themselves are shared/mutable. Add a `Button.copyOf` (or make `Button` an
  immutable `record`, which the spec's plain-data buttons allow — then a `new ArrayList<>(...)`
  shallow copy of an immutable-record list IS a sufficient deep copy and is simpler; **recommend
  `Button` as a record**).
- (b) **graph fields:** `copyOf` must also copy the new scalar `id` and `next` (both `String`,
  immutable — plain reference copy like the existing fields). Each `Button`'s `targetStepId` /
  `url` / `label` / `type` are immutable scalars; if `Button` is a record they ride along free. A
  MENU's optional `timeoutTargetStepId` + `timeoutValue`/`timeoutUnit` are scalars too.
- The deep-copy Javadoc (L46-56) must be rewritten — the "no mutable collections" invariant is now
  false; document the buttons deep-copy explicitly.

`FunnelStepTest` (section 8) is the unit guard — add a case asserting that mutating a source step's
buttons list/elements after `copyOf` does NOT touch the copy.

### 10.4 Engine advance + id→step lookup

**Confirmed:** the snapshot is `List<FunnelStep>` (`FunnelExecution.stepsSnapshot` L58;
`exec.getStepsSnapshot()` L148). A step is fetched today **by position only** —
`snapshot.get(exec.getCurrentStepIndex())` at L158 (main loop). The two `+1` advance spots are
**L129** (delay-resume) and **L162** (CONTINUE). **No id→step lookup exists anywhere** in the
codebase — it is a NEW helper.

**Graph resolution:** add `private static FunnelStep stepById(List<FunnelStep> snapshot, String id)`
— either a linear scan (`snapshot.stream().filter(s -> id.equals(s.getId())).findFirst()`) or build
a `Map<String,FunnelStep>` once per `runExecution` tick (cheap, ≤50 steps). The main loop becomes:
resolve `step = stepById(snapshot, exec.getCurrentStepId())`; a **null** resolution = the cursor
points at a deleted/broken edge id → `terminate(failed)` with a greppable marker (graph integrity in
the snapshot is normally guaranteed by activate-time validation, but snapshot isolation means a
mid-flight definition edit can't corrupt it; a null here means a genuinely broken snapshot). Advance:
`CONTINUE → exec.setCurrentStepId(step.getNext() != null ? step.getNext() : nextStepIdInList(step))`
where the default `next` = the id of the following list element (preserves Phase-1 linear semantics
for non-MENU steps that never set `next`).

`resumeOnCallback(String executionId, String targetStepId)` (NEW engine entry point, NOT the sweep):
a `claim`-style CAS predicated on `status==waiting_for_reply && stepRunStatus==pending`, then
`setCurrentStepId(targetStepId)`, flip `status→running`, run the same step loop. **Supersedes**
section 1 L60-67 which used a `matchedTargetIndex` int.

### 10.5 Frontend: every `order`/index navigation + reorder site

- **`types/funnel.ts`:**
  - `StepType` union (L10-16) — add `| 'MENU'`. NO `WAIT_FOR_REPLY`/`BRANCH` (supersedes section 2
    L106 + section 7 L346 which listed all three).
  - `FunnelStep` interface (L30-41) — add `id?: string | null`, `next?: string | null`, and
    `buttons?: Button[]` + timeout fields; add a `Button` interface
    (`{ type: 'callback' | 'url'; label: string; targetStepId?: string | null; url?: string | null }`).
    **Targets MUST be stable ids, not array indices** (see reorder hazard below).
  - L26-29 comment ("Position in the steps array IS the order ... the editor never sends `order`")
    stays true for `order`, but navigation no longer depends on position — document that targets are
    ids.
- **`[funnelId].vue` reorder/mutation handlers** (verified L119-142):
  - `onAddStep(step)` L119-121 — `steps.value = [...steps.value, step]`. A new MENU/step needs an
    `id`; either the client mints it or (cleaner) the **server mints on `toSteps` and returns it** in
    the PATCH response (`applyResponse` L109 already replaces local steps with the server copy), so
    after the first persist every step has a server `id`.
  - `onSaveStep(index, step)` L123-125 — map-replace at index. Must preserve the step's existing `id`.
  - `onDeleteStep(index)` L127-129 — `filter` out the index. **Deleting a step that is some button's
    `targetStepId` creates a broken edge** → must surface as the activate-time `funnel_broken_edge`
    422 (per spec); the editor may also warn inline.
  - **`onMove(from, to)` L131-137 — `arr.splice` reorder.** This is the reorder hazard: because
    targets are **ids, not indices, a move is SAFE — no remap needed** (this is precisely why the
    graph model uses ids). **Contradiction w/ section 7 L355-356 + section 9 L459-463:** those
    proposed "remap every branch target index on move" — under the approved id model that remap is
    **unnecessary**; ids survive reorder untouched. The frontend just splices; targets stay valid.
  - `persist()` L96-117 PATCHes the full `steps.value` array; server `toSteps` re-mints/preserves
    ids. No `order` sent (unchanged).
- **Branch-target picker:** `SearchableSelect.vue` options = the other steps' `{ value: step.id,
  label: step summary }` + an `End` sentinel (callback target = `null`/`END` → completes the
  funnel). Confirmed `SearchableSelect` props contract is `{value,label,hint?}[]` (section 7).
- `FunnelStepsList.vue` `summary(step)` switch — add a `MENU` arm (button count). `FunnelStepForm.vue`
  `STEP_TYPES` + `schemaFor` + a buttons sub-editor (add/remove button rows, per-button type +
  label + target/url). Locale keys `funnels.steps.type.MENU`, button-editor labels, `funnel_broken_edge`
  message in `en.json` + `uk.json`.

### 10.6 `waiting_for_reply` — exact static-assertion + `$in` literal sites (re-verified)

Section 2 was **partially stale** on line numbers; verified current literals below. `ExecutionStatus`
(`ExecutionStatus.java` L9-14) = `running, waiting, completed, cancelled, failed` (lowercase). Add
`waiting_for_reply` (lowercase, with the underscore — matches `StepRunStatus.in_progress` precedent).

**Static class-load drift assertion** — `FunnelExecution.java` **L34-41** asserts
`"running".equals(running.name())` + `"waiting".equals(waiting.name())`. Extend to assert
`"waiting_for_reply".equals(waiting_for_reply.name())`.

**Enum-drift unit test** — `FunnelStatusEnumTest.statusEnumsArePersistedLowercase` (verified
`backend/src/test/java/com/botfunnel/funnel/FunnelStatusEnumTest.java` L16-29) asserts each
`ExecutionStatus` name. Add `assertThat(ExecutionStatus.waiting_for_reply.name()).isEqualTo("waiting_for_reply")`.

**Index-literal `$in` / `.in(...)` sites — exhaustive, verified line numbers:**
| Site | File:Line | Current literals | Add `waiting_for_reply`? |
|---|---|---|---|
| Re-enter unique partial filter | `FunnelExecution.java:27` | `['running', 'waiting']` | **YES** — a reply-parked exec is in-flight; must be guarded |
| `FunnelIndexesIT` assertion | `FunnelIndexesIT.java:105` | `.containsExactly("running", "waiting")` | **YES** — must mirror the new filter literal/order |
| Sweep query `.in(...)` | `FunnelExecutionEngine.java:91` | `running.name(), waiting.name()` | **YES** (per spec reconciliation) — `nextRunAt<=now` naturally skips null-deadline menus; only timeout-due ones match |
| Claim CAS `.in(...)` | `FunnelExecutionEngine.java:201` | `running.name(), waiting.name()` | **YES** — same reasoning; resume-via-sweep is the timeout path |
| `cancelActiveFor` `.in(...)` | `FunnelTriggerServiceImpl.java:156` | `running.name(), waiting.name()` | **YES** — `/stop` must cancel a reply-parked exec |
| `cancelExistingForPair` `.in(...)` | `FunnelTriggerServiceImpl.java:177` | `running.name(), waiting.name()` | **YES** — re-enter restart must cancel a reply-parked exec |
| `FunnelService.delete` `.in(...)` | `FunnelService.java:150` | `running.name(), waiting.name()` | **YES** — funnel delete must cancel reply-parked execs |

> **Important reconciliation vs section 1/2:** the *earlier* research (section 1 L52-54, section 2
> L98-99) said `waiting_for_reply` should be **EXCLUDED** from the sweep/claim `.in(...)`. The
> **approved user-spec ("Ограничения" / "Риски") OVERRIDES that:** `waiting_for_reply` IS included in
> sweep+claim, and the sweep's `nextRunAt <= now` predicate (L92) is what excludes no-timeout menus
> (their `nextRunAt` is null → never matches). This makes the **timeout** path reuse the existing
> sweep with zero new sweep branch, while the **callback** path is the separate `resumeOnCallback`
> entry point. The delay-resume `+1` at L128-131 keys on `status==waiting` only, so a
> `waiting_for_reply` row picked up by the sweep (timeout fired) needs its OWN resume arm there
> (advance to the MENU's `timeoutTargetStepId`, NOT `+1`).

(Unrelated `$in` at `SubscriberExport.java:18` uses `['PENDING','RUNNING']` for a different
collection — NOT a funnel-execution status, leave untouched.)

### 10.7 `callback_data` — 64-byte limit + encoding

**Confirmed: NO `callback_data` handling exists in the backend today** (grep over `src/main` finds no
`callback_data`, `reply_markup`, `inline_keyboard`, or `answerCallbackQuery` — the `callback_query`
slot on `TelegramUpdate` is an opaque `JsonNode` that is logged-and-dropped, section 4). So the 64-byte
limit is a **new** constraint to enforce at build + validate time. Telegram hard limit:
`callback_data` is **1–64 bytes** (UTF-8).

**What must be encoded** — the callback must uniquely identify **(the parked execution) + (the specific
button)** AND let the engine validate the button belongs to the execution's CURRENT `MENU` step (a
subscriber can be parked in two funnels' menus simultaneously — different `/start` payloads):
- `executionId` — Mongo `_id`, ObjectId hex = **24 chars / 24 bytes**.
- a short **button key** — a stable per-button discriminator (e.g. button index within the step
  `0..7`, since spec caps at 8 buttons → 1 char; or a short minted button id). 1–2 bytes.
- a delimiter (`:` or `|`) — 1 byte.

**Fits easily:** `24 + 1 + 1 = 26 bytes` ≤ 64. Even with a step-id discriminator added for
extra-defense (`executionId:stepKey:buttonKey`) there is room. **Recommended scheme:**
`"{executionId}:{buttonIndex}"` (≤ ~27 bytes). The engine resume (`advanceOnCallback`):
1. split → `executionId`, `buttonIndex`.
2. load the execution; require `status == waiting_for_reply` (else stale/double-click → silent no-op
   + `answerCallbackQuery`).
3. resolve the CURRENT step via `stepById(snapshot, exec.getCurrentStepId())`; require it is a `MENU`;
   require `buttonIndex` in range AND the button is a `callback` type.
4. the button's `targetStepId` (or END) → `resumeOnCallback(executionId, targetStepId)`.
5. validating step-membership: because `currentStepId` is read from the execution itself (not from
   `callback_data`), a callback whose execution already advanced past the MENU (currentStepId now
   points elsewhere) is naturally rejected at step 2/3 — **no need to encode the step id** for
   correctness; the `executionId + currentStepId` server-side check covers "wrong step". Encoding the
   step id is optional belt-and-suspenders only.
- **Malformed / oversized / unparseable `callback_data`** → treat exactly as a stale button per spec:
  `answerCallbackQuery` + silent no-op. Validate `≤64` bytes at MENU build time (frontend label cap is
  separate: button LABEL ≤64 chars is a Telegram display limit, NOT the callback_data limit — the
  data string is engine-generated, the label is author-typed).
