# Code Research: 15-message-composer (Epic 6 — Message Composer)

Goal under research: replace flat single-`text` `SEND_MESSAGE` and single-image `SEND_IMAGE` with a
composer node holding an ordered `List<ContentBlock>` (text/image/video/audio/file/album/location/
contact/invoice/…), last block optionally carrying an inline keyboard (generalizing `MENU`). One node
emits N Telegram messages in sequence. This breaks **Decision 12** (funnel steps are flat per-type
`FunnelStep` fields, no `_class` discriminator).

This document records REALITY (what exists vs net-new). It does NOT propose a design.

NOTE: `work/15-message-composer/{user-spec.md, tech-spec.md, decisions.md}` are all still unfilled
TEMPLATES (no prior research/decisions for this feature). The "Decision N" references below are the
EXISTING funnel-epic decisions baked into code comments (Phases 1–5), not this feature's decisions.

---

## 1. Current `FunnelStep` model (flat, Decision 12)

**File:** `backend/src/main/java/com/botfunnel/funnel/FunnelStep.java` (192 lines)

Plain mutable POJO embedded by Spring Data into `Funnel.steps` and `FunnelExecution.stepsSnapshot`.
NO `_class` discriminator (Decision 12). NO bean-validation on the class — validation lives in
`FunnelService` (§6). Every field is a scalar immutable value type EXCEPT `buttons`.

Fields (line refs):
- `StepType stepType` (L18) — the discriminator. `int order` (L19) — server-authoritative array position.
- Graph model (Phase 2): `String id` (L23, server-minted ObjectId-hex / preserved), `String next` (L24,
  default outgoing edge; null = next-in-list).
- MENU only: `List<Button> buttons` (L28), `Integer timeoutValue` (L29), `String timeoutUnit` (L30,
  "MIN"|"HOUR"|"DAY"), `String timeoutTargetStepId` (L31, null = completed).
- **SEND_MESSAGE:** `String text` (L34), `String parseMode` (L35, null|HTML|MarkdownV2).
- **SEND_IMAGE:** `String imageUrl` (L38), `String caption` (L39).
- DELAY: `Integer delayValue`, `String delayUnit` (L42-43).
- ADD_TAG/REMOVE_TAG: `String tagSlug` (L46).
- SET_CUSTOM_FIELD: `String customFieldKey`, `Object customFieldValue` (L49,58 — validated immutable
  scalar: Double/Boolean/Instant/String).
- EMIT_EVENT: `String eventName` (L62).
- SUBSCRIBE_TO_FUNNEL: `String targetFunnelId`, `String targetEntryStepId`, `boolean endParentAfter`
  (L70-72).

**`copyOf(FunnelStep)` deep copy** (L95-127): null in → null out. Every scalar copied by reference
(immutable). The Phase-2 `buttons` list is the ONLY field copied **defensively**:
`copy.buttons = source.buttons == null ? null : new ArrayList<>(source.buttons)` (L125) — shallow list
copy is safe because `Button` is a record (immutable). This is the snapshot-isolation contract
(Decision 3): every funnel-step field must be an immutable scalar OR a defensively-copied container.
A `List<ContentBlock>` would be a new mutable container requiring the SAME defensive copy treatment in
`copyOf` (and `ContentBlock` would have to be immutable to keep the shallow copy valid).

**StepType dispatch:** `StepType` is a plain enum, NOT a Jackson polymorphic base.
**File:** `backend/src/main/java/com/botfunnel/funnel/StepType.java` (L8-23):
`SEND_MESSAGE, SEND_IMAGE, DELAY, ADD_TAG, REMOVE_TAG, SET_CUSTOM_FIELD, MENU, EMIT_EVENT,
SUBSCRIBE_TO_FUNNEL`. Dispatch is a `switch (step.getStepType())` in `StepExecutor.execute` (§2) and in
`FunnelService.validateSteps` (§6). Both are exhaustive `switch` over the enum — adding a composer type
(or generalizing MENU) touches both switches AND the persistence shape.

**SEND_MESSAGE / SEND_IMAGE / MENU representation today:** flat sibling fields on the same POJO. A step
"is" a SEND_MESSAGE purely because `stepType == SEND_MESSAGE` and `text` is populated; `imageUrl`,
`buttons`, etc. are simply left null. There is no nesting, no list of content. MENU = `text` + `parseMode`
+ `buttons` + optional timeout triplet.

**`Button` record:** `backend/src/main/java/com/botfunnel/funnel/Button.java` (L16):
`record Button(String type, String label, String targetStepId, String url)`. `type` = "callback"|"url";
`targetStepId` for callback (null = End); `url` for url buttons. Immutable.

---

## 2. `StepExecutor` dispatch

**File:** `backend/src/main/java/com/botfunnel/funnel/StepExecutor.java` (389 lines), `@Component`, stateless.

**Dispatch** (`execute`, L92-114): a `switch` returning `StepResult`. Each side-effecting branch
(SEND_MESSAGE/SEND_IMAGE/MENU) catches `TelegramSendException` → `fromTerminalReason(ex)`,
`BotTokenInvalidException` → `fail("invalid_bot_token")`, `AppException` → `fail(codeOrStatus(ex))`.

- **SEND_MESSAGE** (`sendMessage`, L210-229): render via `VariableTemplateRenderer.render(text, parseMode,
  subscriber)`; if rendered > `MAX_MESSAGE_LENGTH` (4096, L48) → WARN `FUNNEL_STEP_TEXT_TRIMMED` + trim +
  CONTINUE (over-length is NOT a failure); `sender.sendText(botId, chatId, rendered, parseMode, null)`
  (5-arg, no reply_markup); → `cont()`.
- **SEND_IMAGE** (`sendImage`, L231-253): render `caption` (only when non-null) + trim to
  `MAX_CAPTION_LENGTH` (1024, L49) with WARN `FUNNEL_STEP_CAPTION_TRIMMED`; `sender.sendPhoto(botId, chatId,
  imageUrl, caption, parseMode, null)`; → `cont()`. Telegram fetches `imageUrl` itself (no backend
  dereference → no SSRF).
- **MENU** (`menu`, L149-169): render `text` (same trim rules as SEND_MESSAGE); build inline keyboard via
  `buildReplyMarkup` (L175-196); compute `menuDeadline` (L201-208); `sender.sendText(..., parseMode, null,
  replyMarkup)` (6-arg overload); → `waitForReply(deadline)`. Keyboard JSON:
  `{"inline_keyboard":[[{text, callback_data|url}]]}`, one button per row; callback_data wire contract
  `"{executionId}:{buttonIndex}"` via `CALLBACK_DATA_SEPARATOR` (`:`, L59).

**`VariableTemplateRenderer` applies to TEXT and CAPTION ONLY.** File:
`backend/src/main/java/com/botfunnel/funnel/VariableTemplateRenderer.java`. Pure static. Substitutes
`{user.first_name|last_name|username}` and `{custom.<key>}`; escapes substituted values per parseMode
(None=no escape, HTML escapes `& < > "`, MarkdownV2 escapes the full special set, L54). Single-pass,
never throws, never logs values (PII). Net-new content types (video/audio/file/location/contact/invoice)
have caption/text-equivalents that would each need to decide whether the renderer applies. The renderer
requires a non-null `Subscriber` (L65 `requireNonNull`).

**`Outcome` enum** (L351): `{ CONTINUE, DELAY, CANCEL, FAIL, WAIT_FOR_REPLY, COMPLETE }`.
`StepResult` record (L360): `(Outcome outcome, Duration delay, String reasonCode, Instant nextRunAt)`
with static factories `cont()`, `complete()` (terminal success — SUBSCRIBE_TO_FUNNEL endParentAfter),
`delay(d)`, `cancel(code)`, `fail(code)`, `waitForReply(nextRunAt)`. There is NO outcome representing
"partial progress within a node" — one step → exactly one `StepResult`.

**At-most-once / claim-CAS ordering:** the side effect (the Telegram send) runs INSIDE `StepExecutor`
BEFORE the engine's claim-conditional advance. The send returns normally on success or THROWS a typed
exception on terminal failure. The subscriber blocked/deleted flip happens inside `TelegramSender`
(Decision 4) — `StepExecutor` only maps the terminal reason to an outcome (`fromTerminalReason`, L318:
BLOCKED_BY_USER / CHAT_NOT_FOUND → CANCEL, else FAIL). The execution still holds `stepRunStatus=in_progress`
during the send; the engine only commits the advance afterward (see §5). **Consequence for "one node emits
N messages":** today exactly one send maps to one advance, so a crash after the send but before the advance
leaves the step `in_progress` (stuck, never re-sent). A node emitting N sends has NO per-block cursor — a
crash after block K would, on naive replay, re-send blocks 1..K (the at-most-once guarantee is currently
**per step**, not per block).

**`SentMessage` return value is DISCARDED** in `StepExecutor` — `sendText`/`sendPhoto` return
`SentMessage(chatId, messageId, sentAt)` but the funnel path ignores it (L159, L219, L243 call them as
statements). Only `SubscriberController` (L252) reads `.messageId()`. So there is no existing per-message
id capture the composer could reuse for partial-send tracking.

---

## 3. `TelegramSender`

**File:** `backend/src/main/java/com/botfunnel/bot/TelegramSender.java` (516 lines), `@Component`.

**Existing public methods:**
- `SentMessage sendText(botId, chatId, text, parseMode, ownerId)` (L128) — 5-arg, delegates to 6-arg with
  `replyMarkup=null`.
- `SentMessage sendText(botId, chatId, text, parseMode, ownerId, Object replyMarkup)` (L143) — 6-arg.
  Builds body map `{text, [parse_mode], [reply_markup]}` (each added only-if-non-null), endpoint
  `/bot{token}/sendMessage`.
- `SentMessage sendPhoto(botId, chatId, imageUrl, caption, parseMode, ownerId)` (L194) — body
  `{photo, [caption], [parse_mode]}`, endpoint `/bot{token}/sendPhoto`.
- `void answerCallbackQuery(botId, callbackQueryId, text)` (L173) — non-content ack (no audit, no
  subscriber hook, chatId null). Swallows all failures (WARN `TELEGRAM_ANSWER_CALLBACK_FAILED`).

**SHARED request-build path exists** — `private SentMessage send(botId, chatId, endpoint, contentFields,
ownerId)` (L213-251). The ONLY per-method difference is the `endpoint` string + the `contentFields` map.
Everything else is shared: CONNECTED-bot filter (L228), retry/backoff/429 loop, audit event, Decision-4
subscriber hook, typed mapping. So adding `sendVideo`/`sendAudio`/`sendDocument`/`sendLocation`/`sendContact`
would each be a thin method building a `Map<String,Object> contentFields` + calling `send(...)` with the
right endpoint (`/sendVideo`, `/sendAudio`, `/sendDocument`, `/sendLocation`, `/sendContact`,
`/sendInvoice`). They inherit ALL retry/audit/scrub behaviour for free.

**`sendMediaGroup` (album) is the awkward exception:** Telegram `/sendMediaGroup` takes a `media` JSON
ARRAY and returns an array of Messages (not one `message_id`). `mapBodyToSentMessage` (L425-443) assumes a
single object result with `message_id`; an album response would NOT have a top-level `message_id` and would
fall into the `ok=false or missing message_id` throw (L441). So `sendMediaGroup` needs a DIFFERENT result
mapper than the shared `send` path provides. Similarly `sendInvoice` returns a single Message (fits), but
its body shape is heavily nested (prices, provider_token) — body-build only.

**Retry ladder / 429 (shared, applies to every endpoint):**
- `sendWithRateLimitRetry` (L281) — outer 429 loop wrapping the 5xx loop. On 429: wait
  `min(retry_after, 30s)` + jitter (0–200ms), clamped to the 30s deadline.
- `sendWith5xxRetry` (L298) — `MAX_RETRIES=3` (L72), backoff `1s/2s/4s` (`INITIAL_5XX_BACKOFF=1s`,
  `MAX_5XX_BACKOFF=4s`, L73-74). Transient → retry; exhausted → `transient_failure_exhausted`.
- `DEFAULT_OVERALL_TIMEOUT=30s` (L69), checked before each attempt/sleep (`checkDeadline`, L456).
- `sendOnce` (L328): decrypts the token, posts, maps 4xx via `map4xx`/`toThrowable`.

**429 handling:** `toThrowable` (L389) maps rawStatus 429 → `TelegramRateLimitException(retry_after)`
(L396-403); 401 → `BotTokenInvalidException`; other 4xx → `TelegramSendException(status, desc, attempts,
terminalReason)`. `terminalReasonFor` (L414): 403 → BLOCKED_BY_USER; 400 + "chat not found" →
CHAT_NOT_FOUND; else OTHER.

**Token decrypt:** per-call AES-GCM in `sendOnce` (L328-348). Base64-decode IV+ciphertext →
`tokenEncryptor.decrypt` → `requireValidTokenShape`. Plaintext token lives only in a local for one HTTP
attempt (never field/log/serialized).

**Audit events:** `EVENT_TELEGRAM_MESSAGE_SENT` ("telegram_message_sent", L50) on success via
`eventService.logEvent(ownerId, ..., sentMetadata(botId, sm))`; `EVENT_TELEGRAM_SEND_FAILED`
("telegram_send_failed", L51) on terminal auditable failure. Metadata = `{botId, chatId, messageId}` /
`{botId, chatId, attempts, errorCode, errorDescription}`. **One audit event per send call** — N sends in a
composer node = N audit events today (no batching concept).

**Scrubber sites:** `TelegramApiClient.scrubTokens(...)` wraps every logged message and error
description (L190, L241, L317, L392, L402, L405, L440, L274). Any new endpoint inherits this because it
goes through `send` → `sendWith*Retry` → `sendOnce`.

**Decision-4 subscriber hook** (`dispatchSubscriberHook`, L256-277): AFTER the audit, on BLOCKED_BY_USER →
`markBlockedByChatId`, CHAT_NOT_FOUND → `markDeletedByChatId`. Keyed by `bot.getTelegramBotId()` (Long),
not the Mongo String id. Swallow-and-warn on hook failure.

---

## 4. MENU step internals (the "last block carries a keyboard" reuse target)

**reply_markup build:** `StepExecutor.buildReplyMarkup(step, execution)` (L175-196). Reads
`step.getButtons()`; null/empty → null (no keyboard). Else one `{text, callback_data|url}` per row.
callback_data = `execution.getId() + ":" + i` (button index). Sent via the 6-arg `sendText` overload.

**callback_data wire contract:** EXACTLY `"{24-hex-executionId}:{0-based-buttonIndex}"`. Parsed strictly
on the way back in `FunnelTriggerServiceImpl.parseCallbackData` (L339-366): max 64 bytes
(`MAX_CALLBACK_DATA_BYTES`, L88), exactly one `:`, left = ObjectId-hex, right = int in
`0..MAX_BUTTON_INDEX`. **Generalizing "last block carries a keyboard" must preserve this exact format** —
the index must still map 1:1 to a position in the keyboard's button list on the parked step.

**Park-on-reply flow:**
1. `menu(...)` returns `waitForReply(deadline)` → engine `parkForReply` (§5): status=`waiting_for_reply`,
   `stepRunStatus=pending`, cursor stays on the MENU step, `nextRunAt=deadline` (or null = wait forever).
2. Button tap → webhook → `ProcessTelegramUpdateJob.handleCallbackQuery` (L161-165) →
   `funnelTriggerService.advanceOnCallback(projectId, chatId, callback_data, callbackQueryId)`.
3. **`advanceOnCallback`** (`FunnelTriggerServiceImpl`, L210-317): resolve CONNECTED bot → strict-parse
   callback_data → resolve subscriber via `findByChat` → load execution by id → IDOR check (subscriberId +
   projectId must match, L254) → must be `waiting_for_reply` (L262) → cursor must point at a MENU step
   (L270) → button index in range AND `type=="callback"` (L277-287) → delegate to
   `executionEngine.resumeOnCallback(execId, subscriberId, button.targetStepId())` (L295). On a won claim,
   record `funnel_button_clicked` analytics + `lastButtonClicked` (L298, `recordButtonClick` L391). Always
   `answerCallbackQuery` in `finally` (best-effort spinner clear). Never throws outward.
4. **`resumeOnCallback`** (`FunnelExecutionEngine`, L181-200): own claim-CAS scoped by subscriberId
   (`claimForCallback`, L323-336) → sets status=running, currentStepId=targetStepId, stepRunStatus=
   in_progress in one `findAndModify` → applies pre-step gates → `drive`.

**Timeout-resume** (separate path, no callback): a `waiting_for_reply` execution picked up by the sweep
(deadline elapsed) follows `menuStep.getTimeoutTargetStepId()` (engine L141-157); null target → complete.

**Button record structure:** see §1. "Last block carries a keyboard" would reuse `buildReplyMarkup`'s
JSON shaping, `parseCallbackData`'s strict format, the IDOR/owner check, and `resumeOnCallback`'s claim.
The current code resolves the keyboard from `step.getButtons()` — a composer would need the buttons to live
on (or be derivable from) the LAST block while keeping the `{executionId}:{buttonIndex}` index addressing
stable, and `advanceOnCallback` L269-287 would need to read buttons from the composer's last block instead
of `menu.getButtons()`.

---

## 5. `FunnelExecutionEngine` (sweep, atomic claim, one-step-one-side-effect)

**File:** `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java` (561 lines).

**Sweep:** `@Recurring(id="funnel-sweep", interval=PT30S)` `sweep()` (L91-118). Finds due executions
(status in {running, waiting, waiting_for_reply} AND `nextRunAt <= now`), oldest-first, capped at
`batchSize` (200). Per execution → `runExecution(id)` in try/catch (one bad execution never aborts the
tick).

**Per-step atomic claim:** `claim(id, now)` (L304-315) — single `findAndModify` CAS: predicate
`status in {...} AND nextRunAt<=now AND stepRunStatus==pending`, flips `stepRunStatus pending→in_progress`,
returns the doc (returnNew). null = lost race. `StepRunStatus` enum:
`backend/src/main/java/com/botfunnel/funnel/StepRunStatus.java`. Statuses persist as lowercase `.name()`
(Decision 14). **Consecutive non-yielding steps run under ONE claim** — execution stays `in_progress`
across the whole `drive` loop; it returns to `pending` only on a Delay/Menu yield.

**`drive` loop** (L230-299): resolves `currentStep` by `currentStepId` (graph mode) or `currentStepIndex`
(legacy linear drain), executes via `stepExecutor.execute`, then `switch (result.outcome())`:
- CONTINUE → `advanceToNext(exec, step)` (L397-416, follows `step.next` or next-in-list) →
  `persistProgress(exec, now)` (L468-482, claim-conditional CAS via `stillClaimed`). Commit happens
  **while still holding the in_progress claim**.
- DELAY → `scheduleDelay` (status=waiting, stepRunStatus=pending, nextRunAt). WAIT_FOR_REPLY → `parkForReply`.
  CANCEL/FAIL → `terminate`. COMPLETE → `complete`.
- Per-tick budget `maxStepsPerTick` (100, L73/233) bounds runaway graph loops.

**One step → one side effect TODAY.** The execution model is: claim → run a step (one send) → commit the
cursor advance, all under the same claim. There is NO sub-step state. The crash semantics
(class javadoc L36-44, and L262-264): a crash AFTER the side effect but BEFORE `persistProgress` leaves the
step un-advanced + `in_progress` → **stuck, never re-sent** (deliberate at-most-once-over-liveness).

**Implication for "one node emits N messages":** the engine has no notion of "I sent block K of N". With
the current model a composer node is a single step; either:
(a) the N sends happen inside one `StepExecutor` branch (one claim, one advance) — then a mid-node crash
strands the whole node `in_progress` (no re-send, but blocks 1..K-1 already went out and are lost-forward),
or
(b) sub-block cursoring would need NEW persisted state on `FunnelExecution` (e.g. a `currentBlockIndex`)
and NEW claim-conditional writes between blocks to make partial-send-on-crash re-entrant. Neither exists
today. The at-most-once CAS (`persistProgress`/`stillClaimed`, L460-482) is the only re-entrancy seam and
it operates at step granularity.

**`FunnelExecution` doc:** `backend/src/main/java/com/botfunnel/funnel/FunnelExecution.java`.
`stepsSnapshot: List<FunnelStep>` (L67, deep copy at fire, Decision 3). Cursor:
`currentStepIndex` (L56) + `currentStepId` (L60). `stepRunStatus` (L61). `lastButtonClicked` (L65,
analytics). Indexes: `(status, nextRunAt)` sweep index + unique partial `(funnelId, subscriberId)` filtered
`status in {running,waiting,waiting_for_reply}` (re-enter guard). Auto-created via
`auto-index-creation=true` (§8). A static class-load assertion (L34-43) pins the partialFilter literals to
`ExecutionStatus.name()`.

**`FunnelExecutionFactory`** (`backend/.../funnel/FunnelExecutionFactory.java`): builds the snapshot at
enroll via `deepCopySteps` → `FunnelStep.copyOf` (L130-138). Any new mutable field on `FunnelStep` must be
handled by `copyOf` or snapshot isolation breaks.

---

## 6. Funnel validation + save

**File:** `backend/src/main/java/com/botfunnel/funnel/FunnelService.java` (1007 lines).

**`validateSteps(List<FunnelStep>, projectId)`** (L656-699, package-private) — shared by `update`,
`activate`, `testRun`. Caps total steps at `maxSteps` (50). Builds the set of step ids, then a per-type
`switch`:
- SEND_MESSAGE (L672): `requireText` (non-empty, ≤4096, L839) + `requireParseMode` (null|HTML|MarkdownV2,
  L848).
- SEND_IMAGE (L676): `requireImageUrl` (non-empty, http(s) prefix, L854) + `requireParseMode`.
- MENU (L683): `validateMenu` (L706-736) — ≥1 button, ≥1 callback button, ≤8 buttons (`MAX_BUTTONS`),
  label non-empty ≤64, callback targetStepId null-or-existing, url buttons strict http(s) via
  `requireHttpUrl` (L815, URI scheme check, anti-SSRF), optional timeout pair via `requireTimeout` (L878).
- DELAY/ADD_TAG/REMOVE_TAG/SET_CUSTOM_FIELD/EMIT_EVENT/SUBSCRIBE_TO_FUNNEL — their own `require*`.
- Graph-edge pass (every step, L696-697): `requireExistingTarget(next)` + `requireExistingTarget(
  timeoutTargetStepId)` → 422 `funnel_broken_edge` for dangling edges.

All failures → 422 with a business code (e.g. `CODE_INVALID_STEP="funnel_step_invalid"`, L81). Adding a
composer type means a NEW `validateSteps` case validating each block in `List<ContentBlock>` (per-type
caption/text/url rules, per-block Telegram limits) + the keyboard-on-last-block rule.

**DTO mapping:** `toSteps(List<FunnelStepDto>)` (L591-634) mints/preserves step ids, rejects duplicate ids,
copies every flat field onto `FunnelStep`. `toStepDto` (L954-976) is the inverse. **Both enumerate every
field explicitly** — a `List<ContentBlock>` field must be threaded through `FunnelStepDto`, `toSteps`,
`toStepDto`, `FunnelResponse`, AND `FunnelStep.copyOf`. `FunnelStepDto`
(`backend/.../funnel/dto/FunnelStepDto.java`) is a flat record with `@NotNull StepType` + all nullable
type-specific fields (`@JsonIgnoreProperties(ignoreUnknown=true)`).

**Message-preview endpoint:** `POST /api/v1/projects/{projectId}/funnels/{funnelId}/steps/{stepId}/preview`
(`FunnelController.previewStep`, L122-129) → `FunnelService.previewStep` (L379-400). Renders the CURRENT
(possibly unsaved) `request.text`+`request.parseMode` (NOT the saved step) for message steps
(`isMessageStep` = SEND_MESSAGE|SEND_IMAGE|MENU, L402-404); non-message → neutral `non_message` placeholder.
Request: `PreviewStepRequest(stepType, text, parseMode)`. Response: `PreviewStepResponse(rendered,
sampleData, kind)`. A composer with N blocks does NOT fit the single-`text` preview request/response shape —
the preview is built around one rendered string.

**`FunnelMessagePreview.vue`** (`frontend/components/funnels/FunnelMessagePreview.vue`, 218 lines): calls
`funnelsStore.preview(funnelId, stepId, {stepType, text, parseMode})`. `MESSAGE_TYPES = [SEND_MESSAGE,
SEND_IMAGE, MENU]` (L37). For SEND_IMAGE shows `<img :src>` above the rendered caption. Renders the string
ONLY as `{{ }}` text (NEVER v-html — stored-XSS guard, OWASP A03). Debounced 600ms. A composer would need
this panel to render an ordered list of heterogeneous blocks, not one text/caption.

**`SearchableSelect.vue`** (`frontend/components/funnels/SearchableSelect.vue`, 196 lines): presentational
combobox; parent owns the fetch + passes `options: {value,label,hint?}[]`, `modelValue: string`,
`showValue?` (default true via withDefaults). Reused for tag picker, custom-field-key picker, MENU
callback target/timeout target, SUBSCRIBE entry. Reusable as-is for any new single-select in composer block
forms.

---

## 7. Frontend types

**File:** `frontend/types/funnel.ts`.
- `StepType` union (L10-20) mirrors backend byte-for-byte (incl. SUBSCRIBE_TO_FUNNEL).
- `Button` interface (L32-37): `{type: 'callback'|'url', label, targetStepId?, url?}`.
- `CURRENT_DATE_TOKEN = '@now'` (L45) — mirrors `StepExecutor.CURRENT_DATE_TOKEN`.
- `FunnelStep` interface (L51-81): flat, all per-type fields optional; `buttons?: Button[]`, `timeout*`,
  graph `id`/`next`. Mirrors `FunnelStepDto`.
- `PreviewStepRequest`/`PreviewStepResponse` (L142-156). `FunnelResponse.steps: FunnelStep[]` (L113).

**Editor page:** `frontend/pages/projects/[projectId]/funnels/[funnelId].vue` (491 lines). Mounts
`FunnelMessagePreview` (L451) driven by `previewStep`/`previewIndex` (L69-76); preview toggle (L348);
two-column layout (L416-418). Steps list + Add/Edit dialogs feed `FunnelStepForm`.

**`FunnelStepForm.vue`** (`frontend/components/funnels/FunnelStepForm.vue`, 1086 lines): the per-type step
editor used by AddStepDialog + EditStepDialog. `STEP_TYPES` array (L32-42), one big `<template v-if>` per
type, vee-validate + zod schema computed over `selectedType` (`schemaFor`, L371-421). MENU has a manual
button sub-editor (`menuButtons` reactive array, L91; `ButtonRow` type L73; `addMenuButton`/
`removeMenuButton`; validation `menuButtonsValid` L176). On submit (`onSubmit`, L528-607) it narrows the
flat model to only the active type's fields and emits a `FunnelStep`. Client validation MIRRORS backend
`validateSteps` byte-for-byte (regexes at L46-53). A composer node = a NEW, much larger sub-editor (an
ordered list of typed blocks, each with its own widget) — the single biggest frontend surface.

---

## 8. Migration surface

**Existing backfill pattern:** `backend/src/main/java/com/botfunnel/funnel/FunnelStepIdBackfill.java`
(134 lines). `@Component implements ApplicationRunner`, runs once at startup, idempotent,
**logs-but-never-throws** (boots even if Mongo is down — mirrors `SuperAdminSeeder`). Strategy: iterate
both collections at the raw `org.bson.Document` level (NOT repository save — avoids re-serializing legacy
shapes through the current schema and dropping unknown fields), partial `$set` of only the changed
sub-field.
- `backfillFunnels()` (L58-75): `funnels` collection, `$set` the whole `steps` array with minted ids.
- `backfillExecutions()` (L77-104): `funnel_executions` collection, `$set` `stepsSnapshot` + seed
  `currentStepId` from `currentStepIndex`.

**A flat→composer backfill would touch the SAME two collections** AND the SAME two arrays:
- `funnels.steps[*]` — each SEND_MESSAGE → composer with one text block; each SEND_IMAGE → composer with one
  image block; each MENU → composer with content block(s) + keyboard on the last block.
- `funnel_executions.stepsSnapshot[*]` — IN-FLIGHT executions carry deep-copied snapshots that must drain
  under the NEW shape OR keep draining under the old shape (the engine reads `stepType` + flat fields).
  Like `FunnelStepIdBackfill`, it must be all-or-nothing per array element and tolerant of legacy shapes.
- If a `_class`/block-type discriminator is introduced, the engine's `graphMode` probe
  (`snapshot.get(0).getId() != null`, engine L374) and `currentStep` resolution must still work on mixed
  old/new snapshots until all in-flight runs complete.

**`auto-index-creation` interplay:** `spring.data.mongodb.auto-index-creation=true`
(`application.properties:9`). Indexes (`FunnelExecution` compound + partial) are created from annotations at
startup. A migration does not touch indexes, but any new collection/field that needs an index relies on this
flag.

**Mongock NOT yet adopted:** `workflow/improvements.md:192-213` — there is NO formal migration tool
(mongock/liquibase/flyway absent from `build.gradle`). Migrations today = idempotent `ApplicationRunner`
backfills (the `FunnelStepIdBackfill` pattern). The improvements note recommends adopting Mongock LATER and
turning OFF `auto-index-creation` then; until that happens, the composer backfill must follow the
`ApplicationRunner` + raw-`Document` + log-never-throw convention.

---

## 9. Risks / integration points

**Decision-12 break blast radius** (every site that enumerates flat fields):
- `FunnelStep` POJO + `copyOf` (must defensively copy the new `List<ContentBlock>`; blocks must be
  immutable to keep the shallow-copy contract).
- `StepType` enum + the TWO exhaustive switches (`StepExecutor.execute`, `FunnelService.validateSteps`).
- `FunnelStepDto` + `toSteps`/`toStepDto`/`FunnelResponse`/`FunnelSummaryResponse` mappers.
- Frontend `FunnelStep` type, `FunnelStepForm.vue` (per-type sub-editors), `FunnelMessagePreview.vue`,
  preview request/response DTOs.
- `FunnelStepIdBackfill`-style migration over `funnels.steps` AND `funnel_executions.stepsSnapshot`.
- A discriminator (`_class` or a block `type` field) directly contradicts the Decision-12 comments baked
  into `FunnelStep.java`, `StepType.java`, `Button.java`, `FunnelStepDto.java`, `FunnelExecution`-adjacent
  code — those javadocs would need rewriting.

**Per-type Telegram API limits / send semantics:**
- Text 4096 (`MAX_MESSAGE_LENGTH`), caption 1024 (`MAX_CAPTION_LENGTH`) — already enforced (trim+WARN).
  Video/audio/document captions are also 1024; album captions attach to the first item only.
- `sendMediaGroup` returns an ARRAY of Messages — incompatible with the single-`message_id`
  `mapBodyToSentMessage` (L425-443). Albums also cap at 2–10 items and only photo/video may mix.
- `sendInvoice`, `sendLocation`, `sendContact`, `sendVenue` have NO caption and CANNOT carry a normal
  inline keyboard the same way (invoice needs a special pay button) — the "last block carries a keyboard"
  rule has per-type exceptions.
- Rate-limit reality: one composer node now fires N sequential sends; per-chat Telegram limits (~1 msg/sec
  to a chat, ~30/sec global) make a multi-block node far more likely to hit 429 than today's single send —
  the shared 429 loop (§3) serializes within ONE send but there is no inter-block pacing.

**At-most-once / snapshot isolation (the deepest risk):** the engine's claim-CAS is **per step**, not per
block (§5). A node emitting N messages with a mid-node crash either re-sends 1..K on replay or strands the
node `in_progress`. Any partial-send guarantee requires NEW persisted sub-cursor state on `FunnelExecution`
and NEW claim-conditional writes between blocks — neither exists. Snapshot isolation (Decision 3) requires
`ContentBlock` to be immutable and `copyOf` to defensively copy the list.

**Preview panel:** `FunnelMessagePreview.vue` + the `/preview` endpoint are built around ONE rendered
string per step (text or caption). A composer needs an ordered multi-block preview; the stored-XSS guard
(text-only `{{ }}`, never v-html) and the `:src` image binding pattern must extend to every new visual
block type.

**Analytics hooks:** today `telegram_message_sent`/`telegram_send_failed` fire once per send (so N per
node), and `funnel_button_clicked` + `lastButtonClicked` (`FunnelTriggerServiceImpl.recordButtonClick`,
L391; analytics keyed by `currentStepId:buttonIndex`) assume the keyboard lives on the MENU step. Moving the
keyboard onto a composer's last block must keep `lastButtonClicked`'s `currentStepId:buttonIndex` coordinate
and `advanceOnCallback`'s button-index → button lookup (L277-287) consistent.

**Callback wire contract:** `{executionId}:{buttonIndex}` (≤64 bytes, strict parse) must survive
generalization; the parked step's button list (currently `menu.getButtons()`, L277) must still resolve the
same index.

---

## Key file index (absolute paths)

Backend (`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/`):
- `funnel/FunnelStep.java` — flat step POJO + `copyOf` deep copy.
- `funnel/StepType.java` — step discriminator enum.
- `funnel/Button.java` — inline-keyboard button record.
- `funnel/StepExecutor.java` — per-type dispatch, send semantics, Outcome/StepResult, MENU keyboard build.
- `funnel/FunnelExecutionEngine.java` — sweep, claim-CAS, drive loop, resumeOnCallback, park/delay.
- `funnel/FunnelExecution.java` — execution doc (stepsSnapshot, cursor, indexes).
- `funnel/FunnelExecutionFactory.java` — snapshot deep copy at enroll.
- `funnel/FunnelService.java` — validateSteps, toSteps/toStepDto, previewStep.
- `funnel/FunnelController.java` — REST endpoints incl. `/steps/{stepId}/preview`.
- `funnel/FunnelTriggerServiceImpl.java` — advanceOnCallback, parseCallbackData, recordButtonClick.
- `funnel/VariableTemplateRenderer.java` — text/caption variable substitution + parseMode escaping.
- `funnel/FunnelStepIdBackfill.java` — ApplicationRunner backfill pattern (the migration template).
- `funnel/StepRunStatus.java`, `funnel/ExecutionStatus.java` — engine status enums.
- `funnel/dto/FunnelStepDto.java`, `dto/PreviewStepRequest.java`, `dto/PreviewStepResponse.java`,
  `dto/ButtonDto.java`, `dto/FunnelResponse.java`.
- `bot/TelegramSender.java` — sendText/sendPhoto/answerCallbackQuery, shared `send`, retry/429/audit/scrub.
- `bot/dto/SentMessage.java` — `(chatId, messageId, sentAt)` (discarded by the funnel path).
- `bot/TelegramSendException.java`, `bot/TelegramRateLimitException.java`, `bot/BotTokenInvalidException.java`,
  `bot/TelegramApiClient.java` (scrubTokens, isTransient, token-shape).
- `webhook/ProcessTelegramUpdateJob.java` — callback_query → advanceOnCallback dispatch.

Frontend (`/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/`):
- `types/funnel.ts` — StepType/Button/CURRENT_DATE_TOKEN/FunnelStep/preview DTOs.
- `components/funnels/FunnelStepForm.vue` — per-type step sub-editors (incl. MENU button editor).
- `components/funnels/FunnelMessagePreview.vue` — single-string Telegram preview panel.
- `components/funnels/SearchableSelect.vue` — reusable combobox.
- `pages/projects/[projectId]/funnels/[funnelId].vue` — editor page wiring preview + form.
- `stores/funnels.ts` — funnels store (fetch/fetchOne/preview/update actions).

Config / migration:
- `backend/src/main/resources/application.properties` — `auto-index-creation=true` (L9); funnel engine
  knobs (`max-steps=50`, `sweep-batch-size=200`, `max-steps-per-tick=100`, `scheduler-interval=PT30S`).
- `workflow/improvements.md:192-213` — Mongock-not-yet note; keep using ApplicationRunner backfills.

Representative existing tests (patterns to follow):
- `backend/src/test/java/com/botfunnel/funnel/FunnelStepExecutorTest.java` — JUnit5 + Mockito
  (`mock(TelegramSender.class)` etc.), e.g. `sendMessageOver4096TrimsAndContinues()`,
  `sendImageCaptionEscapedAndTrimmedTo1024()`.
- `backend/src/test/java/com/botfunnel/bot/TelegramSenderTest.java` — `okhttp3.mockwebserver.MockWebServer`
  for HTTP-level retry/429/timeout coverage (44KB).
- `backend/src/test/java/com/botfunnel/funnel/FunnelExecutionEngineIT.java` (73KB) — engine integration
  (claim/drive/park) against real Mongo.
- `backend/src/test/java/com/botfunnel/funnel/FunnelControllerIT.java` (76KB) — full funnel CRUD/validation.
- `backend/src/test/java/com/botfunnel/funnel/FunnelStepIdBackfillTest.java` — backfill idempotency/shape.
- `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java` — renderer escaping.
- Frontend: `frontend/tests/components/funnels/FunnelMessagePreview.spec.ts`,
  `frontend/tests/components/EditStepDialog.spec.ts`, `frontend/tests/pages/funnel-editor.spec.ts` (Vitest).
