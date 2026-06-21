# Code Research: 16-persistent-keyboard

Feature: persistent bottom reply keyboard for Telegram funnels via two new step types
`SET_KEYBOARD` (sends a text message + `ReplyKeyboardMarkup`) and `CLEAR_KEYBOARD` (sends a
text message + `ReplyKeyboardRemove`). No new branching mechanism — taps arrive as ordinary text
and route through the existing Phase-3 keyword dispatch. Editor warns (non-blocking) when a button
text matches no active keyword funnel.

Repo root: `/Users/pavlokorolov/IdeaProjects/simple-sender`. Backend: Java 21 / Spring MVC /
MongoDB. Frontend: Nuxt **4.4** (`frontend/package.json` `"nuxt": "^4.4.0"`; architecture.md
confirms Nuxt 4 from project start) / Vue 3 / vee-validate+zod / Pinia / vue-i18n.

Legend: [FACT] = verified in code; [HYPOTHESIS] = inference for the implementer to confirm.

NOTE: `work/16-persistent-keyboard/user-spec.md` is now **approved** [FACT — updated 2026-06-10]
and pins several decisions this research previously left open: two new step types (not
MESSAGE-rider); **single text field** + parse_mode (NOT a blocks composer); `resize_keyboard`
hardcoded `true`; `is_persistent` default on / `one_time_keyboard` default off; caps ≤10 rows ×
≤4 buttons, button text ≤64, duplicates blocked; Outcome.CONTINUE; preview EXTENDED (not
placeholder); storage shape of rows = tech-spec decision. `tech-spec.md` / `decisions.md` are
still empty templates.

## Updated: 2026-06-10 — verification & deepening pass

All line numbers below re-verified against current `main` (commit 6a89465). Sections marked
[UPDATED 2026-06-10] were corrected/extended; the most important deltas:
- Preview gates on the **SAVED step's stepType**, not the request's (§6 — load-bearing nuance).
- **`toStepDto` (domain→DTO) mapper was missing** from the touch list — without it saved
  keyboards never reach the editor (§5).
- Engine IT currently has NO wire-body assertions; the canonical `reply_markup` body test lives
  in `TelegramSenderIT` (§12).
- Telegram ReplyKeyboardMarkup/Remove wire shapes verified via Context7 (§13 — now FACT).
- Storage-shape recommendation for keyboard rows added (§9).

---

## 1. StepType enum + new-vs-ride-on-MESSAGE decision

**`backend/.../funnel/StepType.java`** [FACT]
- Flat enum discriminator (Decision 12 — no `_class`). Current constants: `MESSAGE`, `DELAY`,
  `ADD_TAG`, `REMOVE_TAG`, `SET_CUSTOM_FIELD`, `EMIT_EVENT`, `SUBSCRIBE_TO_FUNNEL`, `UNKNOWN`
  (tolerant-read sentinel, NOT author-selectable).
- Adding `SET_KEYBOARD` / `CLEAR_KEYBOARD` = append two enum constants here. Every exhaustive
  `switch (stepType)` in the codebase must then add the two cases (compiler enforces in
  `StepExecutor.execute`, `FunnelService.validateSteps`; `StepType` switches are statement-form).

**Could SET_KEYBOARD ride on MESSAGE?** [FACT on structure / HYPOTHESIS on recommendation]
- A `MESSAGE` step holds `List<ContentBlock> blocks` and an OPTIONAL inline keyboard via `buttons`
  (`List<Button>`) attached to the last non-album block, plus optional `timeout*` (park-on-reply).
  See `StepExecutor.message()` (lines 176-210): when the step `hasButtons`, the message sends with
  an inline `reply_markup` AND parks `WAIT_FOR_REPLY`. That park behavior is exactly what the
  reply-keyboard feature must NOT do (a reply keyboard is fire-and-forget; the tap returns as plain
  text, not a callback). Therefore the inline-keyboard `buttons` field and the reply-keyboard concept
  are semantically different (inline = callback_data + park; reply = ReplyKeyboardMarkup + continue).
- A new `StepType` is the cleaner fit: it sidesteps the `buttons`→inline-keyboard+park coupling and
  avoids overloading `validateMessage`. Confirm in tech-spec.

**Discriminator/serialization conventions** [FACT]
- `StepType` is UPPERCASE; persisted via Spring Data enum name(). No `@JsonValue`.
- Read path is tolerant: `StepTypeReadConverter` (`@ReadingConverter`) maps any unknown persisted
  value to `UNKNOWN` so the sweep doesn't crash on legacy docs. New constants need no converter change.
- `StepType.UNKNOWN` is rejected by `validateSteps` (case UNKNOWN -> throw) and terminal-fails in
  `StepExecutor` — keep that pattern; new types must add real cases to both switches.

## 2. FunnelStep model + copyOf snapshot isolation

**`backend/.../funnel/FunnelStep.java`** [FACT]
- Flat POJO; `stepType` + `order` always set, the rest type-specific & nullable. No bean validation
  here (lives in DTO/service).
- New keyboard fields go here as nullable fields with getters/setters. Likely shape
  [HYPOTHESIS — confirm in tech-spec]:
  - SET_KEYBOARD: a mandatory message text (reuse `blocks`? or a dedicated `keyboardText`/single
    TEXT block?), plus `List<List<String>> keyboardRows` (≤10 rows × ≤4 buttons) or a flat
    `List<KeyboardButtonRow>` record list, plus `Boolean isPersistent`, `resizeKeyboard`,
    `oneTimeKeyboard`.
  - CLEAR_KEYBOARD: a message text only (ReplyKeyboardRemove carries no buttons).
- **`copyOf(FunnelStep)` (lines 99-129) [FACT — LOAD-BEARING]:** deep copy for the execution snapshot
  (Decision 3). EVERY new field MUST be added here or it is silently dropped from the snapshot the
  engine runs from. Scalars copy by reference; any new mutable `List` (keyboard rows) must be
  defensively copied (`new ArrayList<>(...)`, null stays null) exactly like `buttons`/`blocks` at
  lines 126-127. This is the single most error-prone spot for the feature (`FunnelStep.copyOf` is
  exercised by `duplicate()` AND the execution snapshot factory).

## 3. StepExecutor — per-type dispatch + send semantics

**`backend/.../funnel/StepExecutor.java`** [FACT]
- `execute(FunnelStep, FunnelExecution, Subscriber, Bot)` is a `switch` over `StepType` returning
  `StepResult` (lines 108-133). Add `case SET_KEYBOARD -> ...` and `case CLEAR_KEYBOARD -> ...`.
- `StepResult` factories: `cont()` (CONTINUE), `delay()`, `cancel()`, `fail()`, `complete()`,
  `waitForReply()`. **For the keyboard steps the outcome should be `cont()`** — send the message,
  then advance (NO park; the reply keyboard tap returns later as a plain-text message, handled by a
  fresh keyword dispatch, not a callback). [HYPOTHESIS — matches the "no engine keyboard-stack" note.]
- Failure mapping pattern to mirror (lines 202-209): `TelegramSendException -> fromTerminalReason`
  (BLOCKED_BY_USER/CHAT_NOT_FOUND -> cancel; else fail), `BotTokenInvalidException ->
  fail("invalid_bot_token")`, `AppException -> fail(codeOrStatus)`.
- Text rendering: `VariableTemplateRenderer.render(template, parseMode, subscriber)` then
  `renderTrimmed(...)` for the 4096 cap + codes-only WARN (`LOG_TEXT_TRIMMED`). The mandatory
  SET_KEYBOARD / CLEAR_KEYBOARD text should go through the same renderTrimmed path. PII rule
  (Decision 16): logs carry ids/codes only, never rendered text.
- Inline-keyboard builder for reference: `buildReplyMarkup(step, execution)` (lines 298-319) builds
  `{"inline_keyboard":[[{text, callback_data|url}]]}` as a `LinkedHashMap`/`List`. The new reply
  keyboard needs an ANALOGOUS builder producing
  `{"keyboard":[[{"text":...}]], "is_persistent":..., "resize_keyboard":..., "one_time_keyboard":...}`
  for SET_KEYBOARD and `{"remove_keyboard":true}` for CLEAR_KEYBOARD, passed as the `replyMarkup`
  arg of `sender.sendText(...)`.
- `sendBlock` TEXT branch (line 223) shows the call shape:
  `sender.sendText(bot.getId(), chatId, text, parseMode, null, replyMarkup)`.

**`Outcome` enum** (line 429): `CONTINUE, DELAY, CANCEL, FAIL, WAIT_FOR_REPLY, COMPLETE`. Keyboard
steps use CONTINUE — no enum change needed. [FACT]

## 4. TelegramSender — reply_markup serialization, retry/scrub/audit

**`backend/.../bot/TelegramSender.java`** [FACT]
- `sendText` has TWO overloads:
  - 5-arg: `sendText(String botId, Long chatId, String text, String parseMode, String ownerId)`
    (lines 134-139) — delegates to the 6-arg with `replyMarkup=null`.
  - 6-arg: `sendText(..., String ownerId, Object replyMarkup)` (lines 149-161) — **already accepts an
    arbitrary `Object replyMarkup`** placed into the `/sendMessage` body under `reply_markup` ONLY
    when non-null (same idiom as `parse_mode`). **No sender change is required to carry a
    ReplyKeyboardMarkup / ReplyKeyboardRemove** — pass the appropriate `Map` as `replyMarkup`. [FACT
    — KEY FINDING: the union concern in the risk list is moot at the sender level; `replyMarkup` is
    untyped `Object` serialized verbatim by Jackson.]
- Other sends: `sendPhoto/sendVideo/sendAudio/sendDocument` (lines 200-247) route through
  `sendSingleMedia` and do NOT accept a `reply_markup` (they have no such param). `sendMediaGroup`
  likewise has no reply_markup. So a reply keyboard can only ride on a `sendText` call — which fits
  SET_KEYBOARD/CLEAR_KEYBOARD (both send a TEXT message). [FACT]
- Shared pipeline `sendMapped` (lines 310-350): per-call AES-GCM token decrypt, CONNECTED-bot filter,
  5xx backoff (1/2/4s, 3 retries), 429 retry_after outer loop, 30s deadline, audit events
  (`telegram_message_sent` / `telegram_send_failed`), Decision-4 subscriber hook (block/delete flip),
  token-scrubbed logging. Any reply_markup rides this unchanged. [FACT]
- DTO/record shapes: there is NO typed `InlineKeyboardMarkup` record — the inline keyboard is built
  ad-hoc as `Map<String,Object>` in `StepExecutor.buildReplyMarkup`. So no union-type DTO exists to
  extend; the reply keyboard is similarly a plain `Map`. [FACT]
- `answerCallbackQuery` (lines 179-198) is inline-only; irrelevant to reply keyboards.

## 5. Validation — FunnelService.validateSteps

**`backend/.../funnel/FunnelService.java`** [FACT]
- `validateSteps(List<FunnelStep>, String projectId)` (lines 767-807, package-private, reused by
  update/activate/test-run) is a `switch` over `StepType`. Add `case SET_KEYBOARD -> validateSetKeyboard(step)`
  and `case CLEAR_KEYBOARD -> validateClearKeyboard(step)`.
- After the per-type switch every step also runs the generic edge pass:
  `requireExistingTarget(step.getNext(), stepIds)` and `requireExistingTarget(getTimeoutTargetStepId(), stepIds)`
  (lines 804-805). Keyboard steps that leave `next`/timeout null are fine (null next = next-in-list).
- Error code convention: business 422 with a machine-readable code. `CODE_INVALID_STEP =
  "funnel_step_invalid"` (line 87) is the generic one used by `invalidStep(msg)`. Reuse it for the new
  per-field rules (mirrors validateMessage), OR add dedicated codes. [HYPOTHESIS — reuse
  `funnel_step_invalid` for consistency with validateMessage.]
- Relevant existing limits/patterns to model the new ones on: `validateMessage` (lines 822-852),
  `validateButtons` (lines 929-956), `MAX_BUTTONS=8` (line 117), `MAX_BUTTON_LABEL=64` (line 118),
  `MAX_BLOCKS=10` (126). New caps (user-spec): ≤10 rows, ≤4 buttons/row, button text ≤64
  (deliberately = keyword cap `MAX_KEYWORD_LENGTH=64` so a button text can always be an exact keyword).
- **Error style [FACT — UPDATED 2026-06-10]:** every per-step rule throws
  `invalidStep(String message)` (lines 1162-1164) = `AppException.unprocessableEntity(CODE_INVALID_STEP, message)`
  where `CODE_INVALID_STEP="funnel_step_invalid"` (line 87). Messages are **plain-English developer
  strings, NOT i18n'd on the backend** (e.g. `"MESSAGE button label exceeds 64 characters"`). The
  frontend never shows the message — it maps the body's `code`:
  - `pages/.../funnels/[funnelId].vue` `errorCode()` (lines 118-121) reads
    `e?.data?.code ?? e?.response?._data?.code`; `resolveFunnelError(err, ctx)` (lines 122-126)
    tries `t('errors.funnels.{code}')` (key `errors.funnels.funnel_step_invalid` EXISTS in both
    locales), else falls through to `useApiError()(err, ctx)` which maps by HTTP status
    (`errors.{ctx}.{status}` → `errors.{ctx}.generic` → `errors.generic`) — composable
    `composables/useApiError.ts` (status-only, no code logic).
  - New validation rules can reuse `funnel_step_invalid` (message varies) with zero frontend
    error-mapping work; the FORM mirrors each rule client-side with localized
    `funnels.steps.validation.*` strings, so the 422 is a backstop, not primary UX.
- Per-rule helpers to mirror for the new `validateSetKeyboard`/`validateClearKeyboard`:
  `requireParseMode` (TEXT-block parse-mode check), label rules in `validateButtons` 937-942
  (non-blank + ≤64). Duplicate-button check is NEW (no existing precedent in validateButtons —
  inline keyboards allow dup labels; use a `HashSet` over trimmed labels).
- **Editor hint backend support:** the "button text has no matching active keyword funnel" warning is
  NON-BLOCKING and is described as an editor hint. The contains-match logic lives in
  `FunnelEventService.containsAnyKeyword` (see §7). The editor can compute the hint client-side from
  the funnels store (no new endpoint strictly required) OR a backend helper. There is no existing
  "does this text match a keyword funnel" endpoint. [FACT]

**`toSteps` DTO->domain mapper (lines 658-698)** [FACT — re-verified]: maps every DTO field onto
FunnelStep. New keyboard DTO fields must be wired here too (`step.setKeyboardRows(...)`, etc.) or
they are dropped on save. Exact wiring points: per-step setters at lines 679-694 (`setNext` 679,
`setButtons` 680 via `toButtons` 700-709, `setBlocks` 685 via `toBlocks` 717+, scalars 686-694).
Insert the new setters alongside; if rows are typed records add a `toKeyboardRows(List<...Dto>)`
helper mirroring `toButtons`/`toBlocks` (null in → null out, `blankToNull` on strings).

**`toStepDto` domain->DTO mapper (lines 1203-1222)** [FACT — UPDATED 2026-06-10, previously
MISSING from this research]: the round-trip inverse used by `toResponse` (line 1169) for every
GET/POST/PATCH response. New keyboard fields MUST also be added to the `FunnelStepDto` record and
wired here (mirror `toButtonDtos` 1224-1231 / `toBlockDtos` 1236-1249), or a saved keyboard step
comes back to the editor with the fields silently dropped. Three wiring points total per field:
DTO record component + `toSteps` + `toStepDto`.

**`FunnelStepDto` exact current shape** [FACT — UPDATED 2026-06-10]
(`dto/FunnelStepDto.java`, record, `@JsonIgnoreProperties(ignoreUnknown=true)`, only
`@NotNull StepType stepType` bean-validated). Positional components, in order:
`stepType, id, next, buttons (List<ButtonDto>), timeoutValue, timeoutUnit, timeoutTargetStepId,
blocks (List<ContentBlockDto>), delayValue, delayUnit, tagSlug, customFieldKey,
customFieldValue (Object), eventName, targetFunnelId, targetEntryStepId, endParentAfter (boolean)`
— 17 components. Appending keyboard components changes the positional constructor used by tests
(e.g. `FunnelServiceEmitEventTest.emitStep` lines 75-78 documents the positional order in a
comment — update it).

## 6. Preview endpoint [UPDATED 2026-06-10 — exact shapes + saved-step-type gate]

User-spec now REQUIRES extending preview to both new steps (backend render via
VariableTemplateRenderer + frontend panel). Current state, verified:

**Endpoint** [FACT]: `FunnelController` `@PostMapping("/{funnelId}/steps/{stepId}/preview")`
(lines 122-128) → `FunnelService.previewStep(ownerId, projectId, funnelId, stepId, request)`.

**DTOs (both records)** [FACT]:
- `dto/PreviewStepRequest.java`:
  `record PreviewStepRequest(String stepType, List<ContentBlockDto> blocks)` —
  `@JsonIgnoreProperties(ignoreUnknown=true)`. NOTE: `stepType` is a **String**, not the enum.
- `dto/PreviewStepResponse.java`:
  `record PreviewStepResponse(List<RenderedBlock> renderedBlocks, boolean sampleData, String kind)`
  with nested `record RenderedBlock(String type, String text, String parseMode, String mediaUrl,
  String caption, List<RenderedMediaItem> items)` and `record RenderedMediaItem(String mediaUrl,
  String caption)`. `kind` values today: **`"message"` | `"non_message"`** (exactly two; mirrored
  as a TS union in `types/funnel.ts` line 218 — extending `kind` means touching BOTH).

**`previewStep` flow (lines 396-421)** [FACT — KEY NUANCE]:
1. `requireFunnel` (anti-IDOR 404 first), 2. `findStep(funnel, stepId)` → 404 for unknown stepId
   (so **preview only works for a SAVED step id**; the request body supplies the *content*, the
   saved step supplies the *identity*), 3. resolve owner subscriber or stub (`sampleData`),
4. **gate: `isMessageStep(step.getStepType())` (line 410) — the SAVED step's type, NOT
   `request.stepType()`**. `request.stepType()` is currently UNUSED in the service (grep: only
   `dto.stepType()` in `toSteps` line 671 matches `stepType()` — despite the DTO javadoc claiming
   the server uses it). `isMessageStep` (lines 469-471) = `type == StepType.MESSAGE`.
5. MESSAGE → `renderBlocks(request.blocks(), subscriber)` (427-449): per block
   `VariableTemplateRenderer.render(text|caption, parseMode, subscriber)`, mediaUrl verbatim
   (anti-SSRF), → `new PreviewStepResponse(rendered, sampleData, "message")` (416).
6. Anything else → `new PreviewStepResponse(List.of(), sampleData, "non_message")` (420).

**Minimal change set to render SET_KEYBOARD/CLEAR_KEYBOARD** [HYPOTHESIS — shape for tech-spec]:
- Extend `PreviewStepRequest` with the new step fields (keyboard text + parseMode + rows — or
  reuse the chosen DTO sub-records). Unknown-field tolerance means old clients keep working.
- In `previewStep`, branch on `step.getStepType()` (the saved type — consistent with today's
  gate): `case SET_KEYBOARD/CLEAR_KEYBOARD` → render the request's text through
  `VariableTemplateRenderer.render(text, parseMode, subscriber)` (same call shape as
  `renderBlocks`), return rendered text + (for SET_KEYBOARD) the raw button-label rows.
- Response: either a new `kind` value (e.g. `"keyboard"`) + new nullable fields
  (`renderedText`/`keyboardRows`), or reuse `renderedBlocks` with a single TEXT RenderedBlock +
  a new `keyboardRows` field. Either way `types/funnel.ts` `PreviewStepResponse`/`kind` union
  (lines 215-219) must mirror it. Keep the no-identity-leak contract (no ownerChatId).
- Because the gate reads the SAVED type, previewing a step whose type the author just CHANGED in
  the dialog (not yet saved) renders per the OLD type — this is the existing behavior for
  MESSAGE↔action switches too; not a regression, but worth a tech-spec note.

## 7. Webhook plain-text dispatch — tap routing & menu precedence

**`backend/.../webhook/ProcessTelegramUpdateJob.java`** [FACT]
- `dispatch(...)` (lines 160-221): a reply-keyboard tap is an ordinary `message.text` update (NOT a
  callback_query). For a private chat with non-`/` text it:
  1. upserts the subscriber (`upsertFromTelegramUpdate`),
  2. calls `dispatchKeyword(projectId, telegramBotId, chatId, text)` (line 218),
  3. logs `telegram_message_received`.
  **CONFIRMED: a reply-keyboard button tap flows through this existing path unchanged** — the button
  label is sent as `message.text`. [FACT — KEY FINDING.]
- `dispatchKeyword` (lines 230-256):
  - resolves subscriber,
  - **menu-precedence probe** `hasWaitingForReplyExecution(projectId, subscriberId)` (lines 262-267):
    if the subscriber is parked in a `waiting_for_reply` (inline-MENU) execution, keyword dispatch is
    SUPPRESSED (`LOG_KEYWORD_SUPPRESSED_WAITING_FOR_REPLY`). **RISK:** a reply-keyboard tap is also a
    plain text. If the subscriber happens to be simultaneously parked in an INLINE-menu MESSAGE step
    (`waiting_for_reply`), the reply-keyboard tap would be suppressed. Reply keyboards and inline
    menus can coexist on the same chat; document this interaction. The keyboard steps themselves emit
    CONTINUE (no park), so SET_KEYBOARD/CLEAR_KEYBOARD do NOT create `waiting_for_reply` rows — the
    only collision is with a separate inline-menu MESSAGE step that is concurrently parked. [FACT on
    mechanism / HYPOTHESIS on real-world likelihood.]
  - then `funnelEventService.dispatchForSubscriber(projectId, subId, TRIGGER_KEYWORD, text, 0)` —
    human root, originDepth 0.
- Whole block is error-isolated (swallow-all, Decision 12). [FACT]

**`FunnelEventService` keyword matching** [FACT]
- `matchingKeywordFunnels` (lines 360-371) lowercases the text, loads active keyword funnels, and
  `containsAnyKeyword(text, funnel.getKeywords())`.
- `containsAnyKeyword` (lines 375-385): case-insensitive `lowercasedText.contains(keyword)` for each
  stored keyword (keywords are normalized lowercase on save). So a button text "Згенерувати бонус"
  matches a keyword funnel whose keywords list contains a lowercased substring of it (e.g.
  "згенерувати бонус" or "бонус"). **This is what wires "button -> funnel" by text convention.** [FACT]
- Keyword normalization caps (`FunnelService`, lines 80-81, 606-651): `MAX_KEYWORDS=50`,
  `MAX_KEYWORD_LENGTH=64`; lowercase(Locale.ROOT), trim, drop blanks, de-dupe (LinkedHashSet). [FACT]
- Fan-out: one tap can start N executions (one per matching active keyword funnel), bounded by the
  three backstops (depth cap, per-subscriber auto-enroll rate-limit, fan-out ceiling). Human root
  (depth 0) is exempt from the volume limit. [FACT]

## 8. Re-enter guard on repeat taps

[FACT]
- Re-enter guard = partial-unique index on `funnel_executions` over `(funnelId, subscriberId)`
  filtered to `status ∈ {running, waiting, waiting_for_reply}` (see `ExecutionStatus` javadoc +
  `cancelInFlightExecutions` $in at FunnelService lines 238-239).
- On a repeat tap of the SAME button while the keyword funnel's execution is still in-flight:
  - `Funnel.allowReEnter == false` (the default — `create()` sets it false, line 169): the second
    enroll throws `DuplicateKeyException`, swallowed in `FunnelEventService.insertOneFunnel`
    (method starts line 328) as a benign no-op (`LOG_DISPATCH_REENTER_IGNORED`, constant line 76).
    The in-flight run continues untouched. [FACT]
  - `allowReEnter == true`: `cancelExistingForPair` then a fresh insert — the in-flight run is
    cancelled and restarted from step 0. [FACT]
- Implication for the use-case (tap "Згенерувати бонус" twice fast): with the default allowReEnter
  =false the second tap is a no-op; the bonus game does not double-run. The main-menu SET_KEYBOARD
  funnel and the bonus keyword funnel are separate funnels, so they don't contend on the same index. [FACT]

## 9. Serialization / collection schema impact

[FACT]
- Steps serialize as flat per-type fields (Decision 12 — no `_class`). New keyboard fields are just
  more nullable fields on the embedded `FunnelStep` doc inside `funnels.steps[]`. No migration needed
  for existing funnels (new fields read as null). [FACT]
- Phase-6 `ContentBlock`/`MediaItem` are flat immutable records (no `_class`) embedded in
  `FunnelStep.blocks` (`ContentBlock.java`, `Button.java`). If keyboard rows are modeled as a record
  list, follow the same flat-record pattern (no Jackson type info). [FACT]

**Storage-shape recommendation for keyboard rows** [UPDATED 2026-06-10 — verified patterns +
recommendation; final call = tech-spec decision]:
- `grep -rn "List<List" backend/src/main/java/` → **zero matches**: there is NO nested
  `List<List<...>>` field anywhere in the backend; introducing one would be a first.
- The established two-level-nesting precedent that demonstrably round-trips through Spring Data
  Mongo (no `_class`, registered read converters only for enums) is **record-in-list-in-record**:
  `FunnelStep.blocks: List<ContentBlock>` where `ContentBlock.items: List<MediaItem>` — i.e. a
  list field inside an immutable record element. `FunnelStep.copyOf` shallow-copies only the OUTER
  list and the codebase explicitly accepts that as sufficient because the elements are records
  (ContentBlock javadoc spells this argument out).
- **Recommendation:** mirror that precedent —
  `FunnelStep.keyboardRows: List<KeyboardRow>` with
  `record KeyboardRow(List<KeyboardButton> buttons)` and `record KeyboardButton(String text)`
  (+ a parallel `KeyboardRowDto`/`KeyboardButtonDto` pair, mirroring Button/ButtonDto).
  Rationale: (a) consistent with ContentBlock/MediaItem/Button (flat immutable records, no
  `_class`); (b) future per-button fields (`request_contact`, `request_location`, `web_app`)
  become nullable record components exactly like `Button.url` — a raw `List<List<String>>` would
  need a breaking reshape; (c) `copyOf` needs only `new ArrayList<>(source.keyboardRows)` (same
  one-liner as `blocks`/`buttons` at lines 126-127); (d) the StepExecutor builder maps it 1:1 to
  the Telegram `{"keyboard":[[{"text":...}]]}` shape.
- The simpler `List<List<String>>` would also persist fine in Mongo (arrays of arrays of strings
  are native BSON) [HYPOTHESIS — no in-repo precedent to point at], but it breaks the project's
  record-DTO mirroring convention and the future-fields path; not recommended.
- Plus scalars on FunnelStep: `String keyboardText`, `String keyboardParseMode` (or reuse one
  text+parseMode pair shared by both step types — CLEAR_KEYBOARD needs text only), `Boolean
  isPersistent`, `Boolean oneTimeKeyboard` (`resize_keyboard` is hardcoded true per user-spec —
  no stored field needed). All nullable, all copy-by-reference in `copyOf`.
- `FunnelStepDto` (`dto/FunnelStepDto.java`) is `@JsonIgnoreProperties(ignoreUnknown=true)` with only
  `@NotNull stepType` bean-validated; all per-type fields are nullable and validated in
  `FunnelService` -> 422. Add the new keyboard DTO fields here. [FACT]
- `FunnelMongoConfig` registers `StepTypeReadConverter`; no schema/index work for new step types. [FACT]

## 10. Frontend — FunnelStepForm.vue & types

**`frontend/components/funnels/FunnelStepForm.vue`** (1412 lines) [FACT — re-verified, deepened]
- `STEP_TYPES` array (lines 36-44) drives the step-type `<select>` (renders
  `t('funnels.steps.type.{ty}')`, line 828). Add `'SET_KEYBOARD'`, `'CLEAR_KEYBOARD'`.
- `selectedType = ref<StepType>(props.initial?.stepType ?? 'MESSAGE')` (line 89).
  `schemaFor(type)` (584-616) returns ONE `z.object` whose per-type fields are real rules only for
  the active type, `z.any()` otherwise (so stale values from a switched branch never block submit);
  `schema = computed(() => toTypedSchema(schemaFor(selectedType.value)))` (618) feeds
  `useForm({ validationSchema: schema, initialValues: {...} })` (620-630) + `defineField` per
  scalar field. **Sub-editor arrays are deliberately NOT in this schema.**
- **Sub-editor idiom (the pattern the keyboard rows editor must follow)** [FACT]:
  - Composer `blocks = ref<BlockRow[]>(initialBlocks())` (153): rows carry a `uid` (monotonic
    `nextUid()`, 107-110) used as the `v-for :key` so reorder preserves component identity;
    `initialBlocks()` (130-152) seeds from `props.initial` when editing (type-guarded:
    `props.initial?.stepType === 'MESSAGE'`), else one blank row. Mutators `addBlock`/`removeBlock`
    /`moveBlock(from,to)` via `splice` (159-171); cap-guard `if (length >= MAX) return`.
  - Hand-validation: `composerError` computed (array-level, 323-327), `blockError(row)` per-row
    (328-374) returning localized string or null, `blockWarning` soft warnings (376-385),
    `composerValid` computed (390-395). Errors render only after `composerTouched.value = true`
    is set in `onSubmit` (752-754) — same for `menuTouched`.
  - MENU buttons: `menuButtons = ref<ButtonRow[]>(initialButtonRows())` (221),
    `menuButtonLabelError`/`menuButtonUrlError` per-row (289-301), `menuButtonsValid` (312-318).
- `onSubmit = handleSubmit((values) => { switch (selectedType.value) ... emit('submit', step) })`
  (745-815). The MESSAGE case (749-783) shows the full sub-editor submit shape: mark touched →
  `if (!composerValid.value) return` → build the narrow `FunnelStep` carrying ONLY the active
  type's fields + preserve `id`/`next` from `props.initial` (759-760 — the new cases must do this
  too, or re-saving a step breaks its graph edges). Add `case 'SET_KEYBOARD'`/`'CLEAR_KEYBOARD'`.
- Per-type form markup: `<template v-if="selectedType === 'MESSAGE'">` at line 835; other types
  follow as sibling `v-if` templates. Add two new blocks. `data-test` attributes on every
  interactive element (e.g. `step-add-block`, `step-block-${index}`) — specs select by these.
- Checkbox precedent for `is_persistent`/`one_time_keyboard`: `subscribeEndParent`
  ref + checkbox at `data-test="step-subscribe-end-parent"` (491-493, template 1382). [FACT]
- Validation mirror convention: all client checks mirror backend byte-for-byte; localized error
  strings via `t('funnels.steps.validation.*')`. [FACT]

**Funnels store usage for the keyword hint (verified mechanics)** [FACT]
- `useFunnelsStore()` + `storeToRefs` → `storeFunnels` (lines 442-443); lazy loader
  `ensureFunnelsLoaded()` (448-459) calls `funnelsStore.fetch('all')` exactly once
  (`funnelsRequested` flag), triggered by the `watch(selectedType, ..., { immediate: true })`
  (461-469) when type is SUBSCRIBE_TO_FUNNEL — extend the same watch with
  `if (ty === 'SET_KEYBOARD') ensureFunnelsLoaded()`.
- `stores/funnels.ts` `fetch(status)` (35-48): `'all'` omits the `?status=` param and OVERWRITES
  the shared `funnels` list (documented trade-off, FunnelStepForm comment 434-441). Store actions
  NEVER call useApiError (re-throw contract; mapping lives in component setup).
- `FunnelSummaryResponse` carries everything the client-side hint needs [FACT]: `status:
  FunnelStatus` ('draft'|'active'|'paused'), `triggerType: string | null`, `keywords?: string[] |
  null` (normalized lowercase, populated only for `triggerType==='keyword'`) — both the TS
  interface (`types/funnel.ts` 122-136) and the backend record (`FunnelSummaryResponse.java`).
  Hint logic mirror of `containsAnyKeyword`: button label `.toLowerCase()`, warn when NO funnel
  with `status==='active' && triggerType==='keyword'` has a keyword `kw` such that
  `label.includes(kw)`. Existing non-blocking-hint precedent: `subscribeTargetInactive` computed
  (504-506) + amber hint `<p>` `data-test="step-subscribe-inactive-hint"` (template 1356-1359,
  key `funnels.steps.form.subscribeInactiveHint`).

**Editor "no matching keyword funnel" hint source** [FACT — consolidated into the store-usage
block above; NO new endpoint required. `FunnelSummaryResponse.keywords` / `FunnelResponse.keywords`
at types/funnel.ts lines 131, 149.]
- `FunnelTriggerSettings.vue` is where keyword chips are authored (`keywords` defineModel,
  lowercase-normalize on add). Reference for the lowercase/contains semantics. [FACT]

**`frontend/types/funnel.ts`** [FACT]
- `StepType` union (lines 12-20) — add the two literals.
- `FunnelStep` interface (lines 90-119) — add nullable keyboard fields mirroring the backend DTO
  (e.g. `keyboardRows?`, `isPersistent?`, `resizeKeyboard?`, `oneTimeKeyboard?`, plus the message text
  field if not reusing `blocks`).
- `Button` interface here is the INLINE button (callback/url) — do NOT reuse it for reply-keyboard
  buttons (reply buttons are plain `{text}` only). Add a distinct type. [FACT]

**`frontend/components/funnels/FunnelMessagePreview.vue`** (315 lines)
[FACT — UPDATED 2026-06-10, full mechanics for the extension]
- Props: `{ step: FunnelStep | null; stepNumber?: number | null }` (16). Heading
  `funnels.editor.previewStepHeading` ("Крок {N} · {type}") renders for ANY focused step using the
  `funnels.steps.type.*` label (32-38) — new types get a heading for free once the type key exists.
- Gate: `isMessageStep = computed(() => props.step?.stepType === 'MESSAGE')` (line 50). Non-message
  → neutral `funnels.editor.previewPlaceholder`, **backend never called** (template 154-161,
  `runPreview` early-return 79-84). Extend this gate (e.g. `isRenderableStep`) for the two new types.
- **Request composition** (`runPreview`, 77-100): builds the body from the live form/step state —
  `funnelsStore.preview(funnelId, step.id ?? '', { stepType: step.stepType, blocks: step.blocks ?? [] })`.
  `funnelId` from `useRoute().params.funnelId` (40-46). For the new steps the payload must carry the
  keyboard text/parseMode/rows instead of (or alongside) `blocks`. NOTE `step.id ?? ''`: an UNSAVED
  step (no id yet) produces a stepId-less URL → backend `findStep` 404 → caught and shown as a
  neutral in-panel error via `resolveError(err, 'funnels.preview')` (93-97; `errors.funnels.preview.*`
  keys do NOT exist → falls back to `errors.generic`). Existing behavior; same will hold for new steps.
- **Reactivity**: debounced 600ms `schedulePreview` (104-108); `watch(() => [props.step?.stepType,
  props.step?.id, props.step?.blocks], ..., { immediate: true, deep: true })` (114-126). The watch
  SOURCE must be extended with the new keyboard fields or edits to them won't re-trigger preview.
- **Render**: `previewBlocks` computed (60-64) pairs `rendered.renderedBlocks` with `step.blocks`
  and CLAMPS to the shorter length — for a keyboard step with no `blocks`, this pairing yields [],
  so the new steps need their own render branch (rendered text + keyboard mock), not the block
  stack. Inline `buttons` chips render from `props.step?.buttons` (75, template 301-311) — local
  data, not the response; a bottom-keyboard mock can similarly render rows from the form state
  while the TEXT comes from the backend render (variables substituted).
- XSS convention: all text via `{{ }}`, never `v-html` (the spec has a static source check that
  greps the SFC for `v-html`); media via `:src` with http(s) scheme guard. Button labels/keyboard
  text must follow. [FACT]
- `AddStepDialog.vue` / `EditStepDialog.vue` wrap `FunnelStepForm` (thread `siblingSteps`); no per-type
  logic there — adding step types needs no dialog change. [FACT]

## 11. i18n [UPDATED 2026-06-10 — exact key groups + gate mechanism]

[FACT — all re-verified 2026-06-10; uk/en parity diff = empty set; 126 keys under `funnels.steps`]
- Locales: `frontend/i18n/locales/{uk,en}.json` (Nuxt 4 `restructureDir: 'i18n'`; `@nuxtjs/i18n@^9`).
- **Parity gate mechanism**: `frontend/package.json` `"prebuild": "node scripts/check-locales.mjs"`
  — `scripts/check-locales.mjs` flattens both JSON trees and `process.exit(1)` listing keys missing
  on either side; runs automatically before `pnpm build` (the script itself is unit-tested by
  `scripts/check-locales.test.mjs`). Any new key goes in BOTH files or CI build fails.
- **Exact existing groups needing new siblings** (each currently has the 7 author types / MESSAGE-era
  entries):
  - `funnels.steps.type.{MESSAGE..SUBSCRIBE_TO_FUNNEL}` → add `.SET_KEYBOARD`, `.CLEAR_KEYBOARD`
    (used by the form `<select>`, the steps-list, the preview heading, menu/subscribe target pickers).
  - `funnels.steps.form.*` → new labels: keyboard text, parse mode (can reuse existing
    `form.text` / `form.parseMode` / `form.parseModeNone` if wording fits), row/button add-remove
    (`form.menuAddButton`-style), `is_persistent` + `one_time_keyboard` checkbox labels,
    no-keyword-funnel hint text (precedent: `form.subscribeInactiveHint`).
  - `funnels.steps.validation.*` → new rules: text required, rows 1..10, buttons per row 1..4,
    button text required / ≤64, duplicate button text (precedents:
    `validation.menuButtonLabelRequired`, `.menuButtonLabelMax`, `.composerTooMany`).
  - `funnels.steps.summary.*` → the steps-list one-line summaries (`summary.message`,
    `summary.delay`, ... exist; check `FunnelStepsList`-equivalent usage) — add for both new types
    if the list renders a per-type summary.
  - `funnels.editor.preview*` → if the panel gets new states (keyboard mock caption etc.);
    existing: `preview`, `previewPlaceholder`, `previewSampleData`, `previewStepHeading`,
    `previewImageAlt/Unavailable`, `previewMediaType.*`.
  - `errors.funnels.*` → ONLY if new 422 codes are introduced; reusing `funnel_step_invalid`
    needs no new error key. (`errors.funnels.preview.*` does not exist — preview errors fall back
    to `errors.generic`.)

## 12. Tests — patterns to follow [UPDATED 2026-06-10 — exact classes + method names]

[FACT — all re-verified]
- **`FunnelStepExecutorTest.java`** (725 lines) — pure-Mockito unit test (mock `sender`). Markup
  assertion idiom (lines 399-405, in `message_keyboardOnlyOnLastNonAlbumBlock_waitForReply`):
  `ArgumentCaptor<Object> markupCaptor; verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("last"),
  any(), eq(null), markupCaptor.capture());` then cast to `Map<String,Object>` and walk
  `markup.get("inline_keyboard")`. New tests assert `markup.get("keyboard")` /
  `("is_persistent")` / `("resize_keyboard")` / `("one_time_keyboard")` / `("remove_keyboard")`.
  Stubbing: `when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
  .thenReturn(new SentMessage(...))`. Outcome assertions:
  `message_noButtons_returnsContinue` (CONTINUE), `message_blockKThrowsTelegramSend_returnsCancelOrFail_stepNotAdvanced`,
  `message_invalidBotToken_fails`, `message_appException_fails` (the error-mapping trio to clone).
- **`FunnelStepTest.java`** — `copyOf` method-name patterns to clone for the keyboard fields:
  `deepCopyProducesIndependentStep`, `copyOf_preservesEventName`,
  `copyOf_copiesBlocksToNewListInstance`, `copyOf_nullBlocksStaysNull`,
  `copyOf_mutatingCopyBlocksDoesNotAffectSource`. [Critical — Risk 1 of user-spec.]
- **validateSteps tests** — there is NO standalone validateSteps unit class; the established
  pattern is **`FunnelServiceEmitEventTest`** (extends `AbstractIntegrationTest`, `@Autowired
  FunnelService`, seeds user+project, builds a positional `FunnelStepDto` helper, then
  `assertThatThrownBy(() -> funnelService.update(...reqWithStep(dto)))` asserting the
  `AppException` code). HTTP-level 422 checks live in `FunnelControllerIT`
  (`testRunInvalidStepsReturns422`, jsonPath `$.code` = `funnel_step_invalid`). Add a
  `FunnelServiceKeyboardStepTest` following the EmitEvent pattern.
- **`FunnelExecutionEngineIT.java`** (1785 lines, `@Tag("slow")`, Testcontainers Mongo+Redis,
  static `MockWebServer TELEGRAM`) — **CORRECTION: it currently asserts only counts/statuses
  (`sentCount()`, `reload(execId).getStatus()`), NOT request bodies**; its `Dispatcher` usages
  (lines 346-362, 385+) drive race scenarios, not body asserts. Wire-body assertion idioms to
  borrow instead:
  - `FunnelTestRunSendIT` lines 131-134: `RecordedRequest sent = TELEGRAM.takeRequest(); String
    body = sent.getBody().readUtf8();` (mind the cumulative request log — drain like line 174).
  - **`TelegramSenderIT.sendText_withReplyMarkup_includesInlineKeyboardInBody` (lines 286-314)** —
    the canonical reply_markup body test: `JsonNode body = OBJECT_MAPPER.readTree(req.getBody()
    .readUtf8()); body.path("reply_markup").path("inline_keyboard")...`; plus
    `sendText_nullReplyMarkup_omitsKeyField` (317-331). The user-spec engine-IT acceptance check
    (assert `reply_markup.keyboard` / `is_persistent` / `one_time_keyboard` / `remove_keyboard` in
    the swept `/sendMessage` body) = engine IT seeding helpers (`seedActiveSubscriber`,
    `seedExecution`, `enqueueOk`, `engine.sweep()`) + the TelegramSenderIT JsonNode idiom.
- **`TelegramSenderTest.java`** — `MockWebServer` + `takeRequest(2, SECONDS)` + Jackson
  `TypeReference` body parse (`sendText_parseModeNull_omitsFieldFromBody` line 212). Has NO
  reply_markup tests (that coverage lives in TelegramSenderIT). Sender needs no change → no new
  tests required here.
- **Frontend** (Vitest + `@nuxt/test-utils`):
  - `frontend/tests/components/funnels/FunnelStepForm.composer.spec.ts` (365 lines) — setup:
    `mockNuxtImport('useApi', () => () => () => Promise.resolve([]))`,
    `mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))`, helpers
    `mountForm(initial)` via `mountSuspended`, `addBlock(type)`, `submitForm()`, `settle` helper;
    selects by `data-test`. Asserts the EMITTED step shape from `wrapper.emitted('submit')`.
  - `frontend/tests/components/funnels/FunnelMessagePreview.spec.ts` (395 lines) — setup:
    `const { previewMock } = vi.hoisted(...)`;
    `mockNuxtImport('useFunnelsStore', () => () => ({ preview: previewMock }))` (mocks the STORE,
    not useApi) + `mockNuxtImport('useRoute', ...)`; includes a **static no-v-html source guard**
    (reads the SFC source with `readFileSync` and asserts no `v-html`) — keep that guard passing.

## 13. Risks / unknowns (flag)

- [FACT] **ReplyKeyboardMarkup vs inline `reply_markup` union concern is NOT a problem at the sender
  level** — `sendText`'s 6th param is `Object replyMarkup`, serialized verbatim. The only union concern
  is in the NEW StepExecutor builder, which must produce the right shape per step type
  (`keyboard` vs `inline_keyboard` vs `remove_keyboard`).
  **Telegram Bot API shapes VERIFIED via Context7 (`/websites/core_telegram_bots_api`, 2026-06-10)**
  [FACT — closes user-spec Risk 5]:
  - `sendMessage.reply_markup` accepts `InlineKeyboardMarkup | ReplyKeyboardMarkup |
    ReplyKeyboardRemove | ForceReply` (JSON-serialized object).
  - `ReplyKeyboardMarkup`: `keyboard` (Array of Array of KeyboardButton, required),
    `is_persistent` (Boolean, optional — "always show the keyboard"), `resize_keyboard` (Boolean,
    optional), `one_time_keyboard` (Boolean, optional — hides after use, re-openable via a special
    button), `input_field_placeholder` (String, optional), `selective` (Boolean, optional). We use
    only the first four; `selective`/`input_field_placeholder` deliberately unused.
  - `KeyboardButton`: for plain text buttons a bare String is allowed instead of the object; the
    object form is `{"text": "..."}` with at most one extra type-defining field
    (`request_contact`/`request_location`/`web_app`/... — future fields, supports the typed-record
    storage recommendation in §9). Build `{"text": ...}` objects for forward-compat.
  - `ReplyKeyboardRemove`: `{"remove_keyboard": true}` (+ optional `selective`).
  - So: SET_KEYBOARD → `{"keyboard":[[{"text":"..."}],...], "is_persistent":bool,
    "resize_keyboard":true, "one_time_keyboard":bool}`; CLEAR_KEYBOARD → `{"remove_keyboard":true}`.
- [FACT] **Menu precedence (`waiting_for_reply`) suppresses keyword dispatch** (`dispatchKeyword`
  lines 238-244). The new keyboard steps emit CONTINUE and never park, so they do not create the
  conflict themselves — but a subscriber concurrently parked in an inline-menu MESSAGE step would have
  reply-keyboard taps suppressed. Document; likely acceptable since authors won't mix an inline
  park-menu with a persistent reply keyboard in the same flow.
- [FACT] **Preview gate** (`isMessageStep`, backend §6 + frontend §10) excludes the new steps -> they
  preview as a neutral placeholder unless preview is extended. Decide and document.
- [FACT] **`FunnelStep.copyOf`** must carry every new field, with defensive copy for any list. Missing
  = snapshot drops the keyboard at execution AND `duplicate()` loses it.
- [HYPOTHESIS] Telegram per-row button-count and total caps: the feature spec says ≤10 rows × ≤4/row.
  Telegram itself is more permissive; enforce the spec caps in `validateSteps` + the editor.

## 14. Constraints & infra

[FACT]
- Backend Java 21, Spring MVC on virtual threads, MongoDB + Redis, JobRunr. Funnel max steps
  configurable `app.funnel.max-steps:50`.
- `@Tag("slow")` integration tests use Testcontainers (Mongo+Redis) — slow lane.
- Frontend **Nuxt 4.4** (`"nuxt": "^4.4.0"`, Node 24 LTS, `@nuxtjs/i18n@^9` with
  `restructureDir: 'i18n'`), vee-validate + zod, Pinia store (`stores/funnels`), vue-i18n with
  strict locale parity (prebuild gate — §11). Vitest + `@nuxt/test-utils` for component specs;
  Playwright e2e (`e2e/funnels.spec.ts`). User-spec: NO new e2e for this feature.
- All client validation must MIRROR backend `FunnelService` byte-for-byte (established convention).
- Deployment via GitHub CI/CD only (per project CLAUDE.md).

## Key files to touch (summary)

Backend (package `backend/src/main/java/com/botfunnel/funnel/`):
- `StepType.java` — add 2 enum constants (before UNKNOWN).
- `FunnelStep.java` — new nullable fields + getters/setters + **copyOf (lines 99-129; lists must be
  defensively copied like lines 126-127)**.
- NEW `KeyboardRow.java` / `KeyboardButton.java` records (+ `dto/KeyboardRowDto.java` /
  `KeyboardButtonDto.java`) — per §9 recommendation.
- `StepExecutor.java` — 2 cases in the `execute` switch (108-133) + reply-keyboard `Map` builder(s)
  analogous to `buildReplyMarkup` (298-319); CONTINUE outcome; text via `renderTrimmed` (272-281);
  error mapping via the existing catch trio (202-209 idiom).
- `FunnelService.java` — `validateSteps` 2 cases (782-800) + new `validateSetKeyboard`/
  `validateClearKeyboard`; `toSteps` wiring (679-694); **`toStepDto` wiring (1203-1222)**; preview
  branch in `previewStep` (410-420) [user-spec REQUIRES the preview extension].
- `dto/FunnelStepDto.java` — new record components (17 today; positional ctor used in tests).
- `dto/PreviewStepRequest.java` / `PreviewStepResponse.java` — new fields + `kind` decision (§6).
- TelegramSender — NO change required (6-arg `sendText`, lines 149-161, already carries
  `Object replyMarkup`, only-if-non-null body idiom).

Frontend:
- `types/funnel.ts` — `StepType` union (12-20) + `FunnelStep` fields (90-119) + reply-button/row
  types + `PreviewStepRequest`/`PreviewStepResponse`/`kind` mirrors (181-219).
- `components/funnels/FunnelStepForm.vue` — STEP_TYPES (36-44), per-type `v-if` form blocks,
  rows sub-editor (clone the menuButtons/blocks idiom), hand-validation + `*Touched`, `onSubmit`
  cases (745-815, preserve `id`/`next`), keyword-hint via `ensureFunnelsLoaded` + `storeFunnels`.
- `components/funnels/FunnelMessagePreview.vue` — gate (50), `runPreview` payload (89-92), watch
  source (114-126), render branch + bottom-keyboard mock.
- `stores/funnels.ts` — `preview` action signature unchanged unless the payload type changes
  (it takes `PreviewStepRequest` — type-level change only).
- `i18n/locales/{uk,en}.json` — new keys, both locales (prebuild parity gate).

Tests:
- `FunnelStepExecutorTest` (markup captor idiom 399-405), `FunnelStepTest` (copyOf),
  NEW `FunnelServiceKeyboardStepTest` (clone `FunnelServiceEmitEventTest` pattern),
  `FunnelExecutionEngineIT` (+ wire-body asserts borrowed from `TelegramSenderIT`
  `sendText_withReplyMarkup_includesInlineKeyboardInBody` / `FunnelTestRunSendIT` takeRequest
  idiom), `FunnelControllerIT` (422 + preview), `FunnelStepForm.*.spec.ts`,
  `FunnelMessagePreview.spec.ts` (keep the no-v-html source guard).
