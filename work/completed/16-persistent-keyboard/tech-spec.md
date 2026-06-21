---
created: 2026-06-10
status: approved
branch: dev
size: M
---

# Tech Spec: 16-persistent-keyboard

## Solution

Two new funnel step types — `SET_KEYBOARD` and `CLEAR_KEYBOARD` — give the author a persistent
bottom reply keyboard (Telegram `ReplyKeyboardMarkup`) and a way to remove it
(`ReplyKeyboardRemove`). Both steps send a mandatory text message (single text field + `parse_mode`,
rendered through the same `VariableTemplateRenderer` as every other send step) because Telegram
attaches `reply_markup` only to a message.

Both steps are **fire-and-forget**: `StepExecutor` returns `cont()` (`Outcome.CONTINUE`), the
execution never parks, and the keyboard survives execution completion. A button tap arrives in the
webhook as an ordinary `message.text` update and routes through the **existing Phase-3 keyword
dispatch unchanged** — "button → funnel" is a text convention (button text contains-matches a
keyword of an active keyword funnel), reinforced by a non-blocking editor hint computed client-side
from the funnels store.

No engine changes beyond the two `StepExecutor` cases: `TelegramSender` already accepts an
arbitrary `Object replyMarkup` on its 6-arg `sendText` (serialized verbatim by Jackson), the
webhook/dispatcher path is untouched, and there is no keyboard stack / auto-restore — returning to
the main menu is an authoring pattern (`SET_KEYBOARD` at the end of branches).

The preview surface (backend `POST /{funnelId}/steps/{stepId}/preview` + frontend panel) is
extended to render both new steps: rendered text via `VariableTemplateRenderer` + button rows as a
bottom-keyboard mock. No DB migration: all new fields are nullable on the embedded `FunnelStep`
(flat, no `_class`), existing funnels are unaffected.

## Architecture

### What we're building/modifying

**Backend (`com.botfunnel.funnel`):**
- **`StepType`** — two new constants `SET_KEYBOARD`, `CLEAR_KEYBOARD` (before `UNKNOWN`).
- **`FunnelStep`** — new nullable fields: `keyboardText`, `keyboardParseMode`,
  `keyboardRows: List<KeyboardRow>`, `isPersistent`, `oneTimeKeyboard`; all carried through
  `copyOf` (rows defensively copied — load-bearing for snapshot isolation and duplicate).
- **`KeyboardRow` / `KeyboardButton`** — new flat immutable records (pattern: `ContentBlock`/`Button`).
- **`FunnelStepDto`** (+ new `KeyboardRowDto`/`KeyboardButtonDto`) — DTO mirrors; wired in **three**
  places: DTO record, `toSteps` (DTO→domain), `toStepDto` (domain→DTO, else saved keyboards never
  return to the editor).
- **`FunnelService`** — `validateSteps` two new cases: `validateSetKeyboard` (mandatory non-blank
  text ≤4096, parse mode, 1..10 rows × 1..4 buttons, button text non-blank ≤64, duplicate button
  texts blocked) and `validateClearKeyboard` (mandatory text + parse mode); `previewStep` branch for
  both new types.
- **`StepExecutor`** — two new switch cases: render text via `renderTrimmed`, build the
  `reply_markup` Map (`ReplyKeyboardMarkup` with `resize_keyboard:true` hardcoded /
  `ReplyKeyboardRemove`), send via 6-arg `sendText`, return `cont()`; error mapping identical to
  every send step (`TelegramSendException → fromTerminalReason`, `BotTokenInvalidException →
  fail("invalid_bot_token")`, `AppException → fail(code)`).
- **`PreviewStepRequest` / `PreviewStepResponse`** — request gains the keyboard fields; response
  gains `kind: "keyboard"` + nullable `keyboardRows` (raw labels), text rides `renderedBlocks` as a
  single TEXT `RenderedBlock`.

**Frontend (`frontend/`):**
- **`types/funnel.ts`** — `StepType` union + `FunnelStep` keyboard fields + new reply-button row
  types (NOT the inline `Button`) + preview DTO mirrors (`kind` union gains `"keyboard"`).
- **`FunnelStepForm.vue`** — two step types in `STEP_TYPES`, per-type `v-if` form blocks (text +
  parse mode; rows sub-editor following the established `menuButtons`/`blocks` local-reactive-array
  idiom with `*Touched` hand-validation; «Постійна» / «Сховати після натискання» checkboxes),
  byte-for-byte validation mirror, non-blocking keyword hint per button (computed from
  `useFunnelsStore` — active `triggerType==='keyword'` funnels, `containsAnyKeyword` mirror),
  `onSubmit` cases preserving `id`/`next`.
- **`FunnelMessagePreview.vue`** — renderable gate extended to the new types, preview payload
  carries the keyboard fields, watch source extended, new render branch: rendered text + bottom
  keyboard mock (rows of chips); `{{ }}` only, no `v-html`.
- **`i18n/locales/{uk,en}.json`** — type labels, form labels, validation messages, hint text —
  both locales (prebuild parity gate).

**Not modified:** `TelegramSender` (6-arg `sendText` already carries `Object replyMarkup`),
`ProcessTelegramUpdateJob` / `FunnelEventService` (tap routing is the existing keyword dispatch),
`FunnelExecutionEngine` (CONTINUE is an existing outcome), Mongo indexes/converters.

### How it works

1. **Authoring:** editor form emits a `FunnelStep` with the keyboard fields → `FunnelController`
   PATCH → `toSteps` maps DTO→domain → `validateSteps` enforces caps/duplicates/text → saved as
   flat nullable fields on the embedded step. Response maps back via `toStepDto`.
2. **Execution:** sweep claims the step → `StepExecutor` case renders `keyboardText` via
   `VariableTemplateRenderer`/`renderTrimmed` → builds
   `{"keyboard":[[{"text":...}]], "is_persistent":…, "resize_keyboard":true, "one_time_keyboard":…}`
   (or `{"remove_keyboard":true}`) → `sender.sendText(botId, chatId, text, parseMode, null, markup)`
   → returns `cont()` → engine advances; after the last step the run is `completed`. The keyboard
   stays in the chat.
3. **Tap:** subscriber taps a button → Telegram sends `message.text` = button label → webhook →
   `ProcessTelegramUpdateJob.dispatchKeyword` → menu-precedence probe → contains-match against
   active keyword funnels → executions created (fan-out possible; re-enter guard semantics apply).
   Nothing new in this path.
4. **Preview:** panel posts the live form state (text, parse mode, rows) → `previewStep` branches
   on the SAVED step's type → renders text through the same renderer → panel shows rendered text +
   keyboard mock. (Unsaved steps 404 → neutral placeholder — existing behavior, same as MESSAGE.)

### Shared resources

None.

## Decisions

### Decision 1: Two new step types, not a MESSAGE-composer extension
**Decision:** `SET_KEYBOARD` / `CLEAR_KEYBOARD` are standalone `StepType`s.
**Rationale:** Ratifies user-spec («Технические решения»): `MESSAGE.buttons` is coupled to the
inline keyboard + `waiting_for_reply` park — the opposite semantics of a fire-and-forget reply
keyboard. Code research confirms the coupling in `StepExecutor.message()`.
**Alternatives considered:** reply-keyboard fields on the MESSAGE composer — rejected (park
coupling, `validateMessage` overload).

### Decision 2: Keyboard rows stored as `List<KeyboardRow>` of flat records [TECHNICAL]
**Decision:** `FunnelStep.keyboardRows: List<KeyboardRow>`, `record KeyboardRow(List<KeyboardButton>
buttons)`, `record KeyboardButton(String text)` (+ parallel DTO records). User-spec explicitly
delegated this to tech-spec.
**Rationale:** (a) zero `List<List<...>>` precedent in the backend; the proven Mongo round-trip
pattern is record-in-list-in-record (`ContentBlock.items`); (b) future per-button fields
(`request_contact`, `web_app`, …) become nullable record components — Telegram's `KeyboardButton`
object form supports exactly this; (c) `copyOf` needs only `new ArrayList<>(rows)` (records are
immutable — same argument as `blocks`/`buttons`); (d) maps 1:1 to the wire shape.
**Alternatives considered:** `List<List<String>>` — persists fine in BSON but breaks the
record-DTO mirroring convention and the future-fields path; rejected.

### Decision 3: One shared text-field pair for both step types [TECHNICAL]
**Decision:** Both steps use the same `keyboardText` + `keyboardParseMode` fields (mandatory text;
4096 cap; `{user.*}`/`{custom.<key>}` variables).
**Rationale:** Supports the user-spec constraint "reply_markup attaches only to a message → both
steps send mandatory text". One field pair instead of two avoids dead fields per type — the same
flat-nullable-field convention as every other per-type field on `FunnelStep`.
**Alternatives considered:** reusing `blocks` with a single TEXT block — rejected; user-spec pins
"single text field, not a blocks composer".

### Decision 4: Fire-and-forget `Outcome.CONTINUE`; no keyboard stack
**Decision:** Both steps return `cont()`; no park, no `waiting_for_reply`, no per-subscriber
keyboard state in the engine.
**Rationale:** Ratifies user-spec: keyboard survives execution completion; return-to-main-menu is
an authoring pattern (`SET_KEYBOARD` at the end of branches). The tap comes back as plain text via
keyword dispatch — not a callback.
**Alternatives considered:** keyboard stack / auto-restore — rejected in user-spec (no state per
subscriber).

### Decision 5: Wire markup built as ad-hoc `Map` in `StepExecutor`; `resize_keyboard` hardcoded [TECHNICAL]
**Decision:** New builder(s) analogous to `buildReplyMarkup` produce
`{"keyboard":[[{"text":…}]], "is_persistent":…, "resize_keyboard":true, "one_time_keyboard":…}` and
`{"remove_keyboard":true}` as `LinkedHashMap`/`List`; buttons use the object form `{"text": …}`
(not bare strings) for forward-compat. `resize_keyboard` is always `true` and not stored/shown.
**Rationale:** Supports user-spec AC on the `/sendMessage` body. There is no typed
`InlineKeyboardMarkup` record to extend — the inline keyboard is already an ad-hoc Map; the sender
serializes `Object replyMarkup` verbatim (verified). Wire shapes verified against current Telegram
Bot API via Context7 (closes user-spec Risk 5). `selective` / `input_field_placeholder` deliberately
unused.
**Alternatives considered:** typed wire DTOs — rejected; no precedent, no consumer besides Jackson.

### Decision 6: Validation reuses `funnel_step_invalid`; duplicate-button check is new logic
**Decision:** All new rules throw `invalidStep(...)` → 422 `funnel_step_invalid` (message varies):
mandatory non-blank text ≤4096, valid parse mode, 1..10 rows, 1..4 buttons per row, button text
non-blank ≤64 (= keyword cap, so a button text can always be an exact keyword), duplicate trimmed
button texts within one keyboard blocked (`HashSet`). `CLEAR_KEYBOARD` validates text/parse mode
only and rejects `keyboardRows`, `isPersistent`, and `oneTimeKeyboard` being present (symmetric
strict rejection — no silently-ignored fields).
**Rationale:** Supports user-spec ACs on validation. `funnel_step_invalid` already exists in both
locale files (`errors.funnels.funnel_step_invalid`) — zero frontend error-mapping work; the form
mirrors every rule client-side so the 422 is a backstop. Duplicate check has no precedent in
`validateButtons` (inline keyboards allow dup labels) — new logic, mirrored in the form.
**Alternatives considered:** dedicated error codes per rule — rejected; `validateMessage` precedent
is one code + varying message.

### Decision 7: Preview — branch on saved step type, `kind: "keyboard"`, text rides `renderedBlocks` [TECHNICAL]
**Decision:** `PreviewStepRequest` gains `keyboardText`, `keyboardParseMode`, `keyboardRows`.
`previewStep` branches on the SAVED step's type (consistent with today's gate): for both new types
it renders the request's text via `VariableTemplateRenderer` and returns
`PreviewStepResponse(renderedBlocks=[one TEXT RenderedBlock], sampleData, kind="keyboard",
keyboardRows=<raw labels | null>)`. Button labels are returned verbatim (no variable rendering —
labels are the keyword link; variables would break matching), but the echoed rows are CLAMPED to
the validation caps (≤10 rows × ≤4 buttons, label ≤64) so preview never reflects an unbounded
unvalidated payload. The TS `kind` union and response interface are mirrored. Both new step types
get preview coverage (SET_KEYBOARD with rows, CLEAR_KEYBOARD with `keyboardRows=null`).
**Rationale:** Supports user-spec AC "preview renders both new steps". Minimal change set: reuses
the existing `RenderedBlock` plumbing and the unknown-field tolerance of the request DTO. The
saved-type gate is existing behavior (a just-changed unsaved type renders per the old type — same
as MESSAGE↔action switches today; not a regression).
**Alternatives considered:** new top-level `renderedText` field — rejected (parallel plumbing for
no gain); branching on `request.stepType()` — rejected (today that field is dead code; changing the
gate semantics is out of scope).

### Decision 8: Keyword hint computed client-side from the funnels store
**Decision:** The editor warns (non-blocking, amber hint — `subscribeInactiveHint` precedent) next
to any button whose lowercased text contains no keyword of any `status==='active' &&
triggerType==='keyword'` funnel — mirror of `containsAnyKeyword`. Data comes from
`useFunnelsStore().fetch('all')` via the existing `ensureFunnelsLoaded()` lazy loader; no new
endpoint.
**Rationale:** Ratifies user-spec (variant B: text convention + non-blocking hint).
`FunnelSummaryResponse` already carries `status`/`triggerType`/`keywords` (lowercase-normalized).
**Alternatives considered:** backend match endpoint — rejected in user-spec (live store data
suffices; hint is advisory by design).

### Decision 9: Engine IT gains its first wire-body assertions, borrowing the `TelegramSenderIT` idiom [TECHNICAL]
**Decision:** The user-spec AC "engine IT asserts the `/sendMessage` body via MockWebServer" is
implemented by adding body assertions to `FunnelExecutionEngineIT` (`@Tag("slow")`) using the
JsonNode idiom from `TelegramSenderIT.sendText_withReplyMarkup_includesInlineKeyboardInBody` and
the `takeRequest`/`readUtf8` drain pattern from `FunnelTestRunSendIT`.
**Rationale:** Code research found the engine IT currently asserts only counts/statuses — the
canonical body-assert idiom lives elsewhere; this decision pins where it comes from so the task
doesn't invent a new pattern.
**Alternatives considered:** asserting bodies only in `TelegramSenderIT` — rejected; the AC
explicitly requires the full sweep→wire cycle.

### Decision 10: No migration; nullable fields only
**Decision:** All new fields are nullable on the embedded `FunnelStep` (flat, no `_class`); no
startup hook, no index work, no converter change (`StepTypeReadConverter` handles new constants
automatically).
**Rationale:** Ratifies user-spec («Ограничения»): existing funnels read new fields as `null`;
schema-add convention for nullable fields (patterns.md).
**Alternatives considered:** none viable — there is nothing to migrate.

## Data Models

**Backend — `FunnelStep` new nullable fields (embedded in `funnels.steps[]` and execution snapshots):**

```java
// FunnelStep.java — all nullable, type-specific (SET_KEYBOARD / CLEAR_KEYBOARD)
private String keyboardText;            // mandatory by validation, ≤4096, variables + parse mode
private String keyboardParseMode;       // null | "HTML" | "MarkdownV2" (same domain as TEXT block)
private List<KeyboardRow> keyboardRows; // SET_KEYBOARD only: 1..10 rows
private Boolean isPersistent;           // SET_KEYBOARD only: default true (form), Telegram is_persistent
private Boolean oneTimeKeyboard;        // SET_KEYBOARD only: default false (form), one_time_keyboard
// resize_keyboard is NOT stored — hardcoded true in the StepExecutor builder

// New flat immutable records (no _class), pattern: ContentBlock/MediaItem/Button
public record KeyboardRow(List<KeyboardButton> buttons) {}   // 1..4 buttons
public record KeyboardButton(String text) {}                 // non-blank, ≤64, future: request_contact etc.
```

`copyOf` additions: scalars by reference; `keyboardRows` → `new ArrayList<>(source.keyboardRows)`
(null stays null) — same one-liner as `blocks`/`buttons`.

**DTO mirrors:** `FunnelStepDto` appends `keyboardText, keyboardParseMode,
keyboardRows (List<KeyboardRowDto>), isPersistent, oneTimeKeyboard` (positional record — update the
positional-order comment in `FunnelServiceEmitEventTest.emitStep`); new `KeyboardRowDto(List<KeyboardButtonDto>
buttons)` / `KeyboardButtonDto(String text)`. Wired in `toSteps` AND `toStepDto`.

**Preview DTOs:**

```java
// PreviewStepRequest: + String keyboardText, String keyboardParseMode, List<KeyboardRowDto> keyboardRows
// PreviewStepResponse: + String kind value "keyboard" (union: "message" | "non_message" | "keyboard")
//                      + List<List<String>> keyboardRows  // raw labels for the mock; null unless SET_KEYBOARD
```

**Frontend — `types/funnel.ts`:**

```ts
type StepType = ... | 'SET_KEYBOARD' | 'CLEAR_KEYBOARD'

interface KeyboardButton { text: string }          // distinct from inline Button
interface KeyboardRow { buttons: KeyboardButton[] }

interface FunnelStep {
  // ...existing
  keyboardText?: string | null
  keyboardParseMode?: string | null
  keyboardRows?: KeyboardRow[] | null
  isPersistent?: boolean | null
  oneTimeKeyboard?: boolean | null
}
// PreviewStepRequest/PreviewStepResponse mirrors; kind: 'message' | 'non_message' | 'keyboard'
```

**Telegram wire shapes (verified via Context7, 2026-06-10):**

```json
// SET_KEYBOARD → sendMessage.reply_markup
{"keyboard": [[{"text": "Згенерувати бонус"}]], "is_persistent": true,
 "resize_keyboard": true, "one_time_keyboard": false}
// CLEAR_KEYBOARD → sendMessage.reply_markup
{"remove_keyboard": true}
```

## Dependencies

### New packages
None.

### Using existing (from project)
- `TelegramSender` — 6-arg `sendText(botId, chatId, text, parseMode, ownerId, Object replyMarkup)`;
  no sender change. Retry/scrub/audit pipeline (`telegram_message_sent`/`telegram_send_failed`) unchanged.
- `VariableTemplateRenderer` / `StepExecutor.renderTrimmed` — text rendering + 4096 cap.
- `FunnelEventService` keyword dispatch + re-enter guard + three backstops — tap routing, untouched.
- `useFunnelsStore` (`fetch('all')`), `FunnelSummaryResponse.keywords/status/triggerType` — hint data.
- Vitest + `@nuxt/test-utils` (`mountSuspended`, `mockNuxtImport`), MockWebServer, Testcontainers
  (`@Tag("slow")` lane) — test infrastructure.

## Testing Strategy

**Feature size:** M

### Unit tests
- `FunnelStepTest` (copyOf): keyboard scalars survive copy; `keyboardRows` copied to a NEW list
  instance; null rows stay null; mutating the copy's rows does not affect the source. (User-spec
  Risk 1 — load-bearing.)
- New `FunnelServiceKeyboardStepTest` (clone `FunnelServiceEmitEventTest` pattern): valid
  SET_KEYBOARD/CLEAR_KEYBOARD accepted; rejections — missing/blank text, text >4096, 0 rows,
  >10 rows, 0 buttons in a row, >4 buttons in a row, blank button text, button text >64, duplicate
  button texts, keyboard-only fields (`keyboardRows`/`isPersistent`/`oneTimeKeyboard`) present on
  CLEAR_KEYBOARD, invalid parse mode — all 422 `funnel_step_invalid`.
- `FunnelStepExecutorTest`: markup captor asserts `keyboard` rows/labels, `is_persistent`,
  `resize_keyboard:true`, `one_time_keyboard` for SET_KEYBOARD; `remove_keyboard:true` for
  CLEAR_KEYBOARD; outcome CONTINUE for both; error-mapping trio (`BLOCKED_BY_USER`/`CHAT_NOT_FOUND`
  → cancel, other Telegram errors → fail, `invalid_bot_token` → fail); text rendered through
  variables/parse mode.
- Frontend Vitest: `FunnelStepForm.keyboard.spec.ts` — form blocks render for BOTH types, rows
  sub-editor add/remove/caps, validation mirror (duplicates, blank, >64, text >4096), no
  `resize_keyboard` control rendered, checkboxes defaults (persistent on, one-time off), keyword
  hint shown/hidden against mocked store funnels, emitted step shape preserves `id`/`next`
  (incl. a CLEAR_KEYBOARD emit case). `FunnelMessagePreview.spec.ts` — keyboard render branch for
  SET_KEYBOARD (text + rows mock) AND CLEAR_KEYBOARD (text only), payload carries keyboard fields,
  no-v-html source guard stays green.

### Integration tests
- `FunnelExecutionEngineIT` (`@Tag("slow")`): full sweep cycle for a funnel with SET_KEYBOARD →
  assert `/sendMessage` body `reply_markup.keyboard`/`is_persistent`/`resize_keyboard`/
  `one_time_keyboard` (JsonNode idiom); CLEAR_KEYBOARD → `remove_keyboard:true`; execution ends
  `completed`, never `waiting_for_reply`; keyword-dispatch case — a button-label text starts the
  matching keyword funnel via `FunnelEventService.dispatchForSubscriber` (the webhook leg
  `ProcessTelegramUpdateJob` → `dispatchKeyword` is already covered by the existing
  `ProcessTelegramUpdateJobTest.plainText_dispatchesKeyword`).
- `FunnelControllerIT`: PATCH a funnel with both new step types → GET → all keyboard fields
  survive the full HTTP round-trip (`toSteps`/`toStepDto` wiring); preview returns
  `kind="keyboard"` + rendered text + raw rows for SET_KEYBOARD and `keyboardRows=null` for
  CLEAR_KEYBOARD; 422 for an invalid keyboard step.
- **Covered by existing tests (cited, not rewritten):** re-enter guard semantics on repeat taps
  (user-spec AC) — `FunnelEventServiceTest.reEnter_duplicateOnOneFunnel_doesNotAbortOtherMatches`
  (`allowReEnter=false` → no-op) and `reEnter_allowReEnterFunnel_cancelsExistingThenInserts`
  (`true` → restart); menu precedence —
  `ProcessTelegramUpdateJobTest.plainText_keywordSuppressedWhileWaitingForReply`. This feature
  changes nothing on those paths; QA (Task 11) verifies the suites stay green.

### E2E tests
None (per user-spec: reply keyboard renders only in a real Telegram client; frontend covered by
Vitest component specs).

## Agent Verification Plan

**Source:** user-spec "Как проверить".

### Verification approach
1. Default backend lane: `cd backend && ./gradlew test` — validateSteps, StepExecutor, copyOf green.
2. Slow lane: `cd backend && ./gradlew test -PrunSlow=true` — engine IT wire-format assertions green.
3. Frontend: `cd frontend && pnpm test` — form + hint + preview specs green.
4. Local run: create a funnel with SET_KEYBOARD via API (curl against localhost:8080), activate,
   test-run → execution `completed`; payload visible in logs/MockWebServer during ITs.
5. Locale parity: `cd frontend && pnpm build` (prebuild gate) passes for uk/en.

Per-task smoke checks are in each task's Verify-smoke / Verify-user fields. Final manual
verification (real Telegram client) is the user's — via `work/16-persistent-keyboard/smoke.md`
(authored as part of this feature, Task 7).

### Tools required
bash, curl. No MCP tools — reply keyboards render only in a real Telegram client; live verification
is manual by the user.

## Risks

| Risk | Mitigation |
|------|-----------|
| `copyOf` misses a new field → keyboard silently dropped from snapshot/duplicate | Dedicated copyOf unit tests per field + defensive list copy (Task 1); flagged load-bearing in code-research §2 |
| Contains-match false positives on free text (user-spec Risk 2) | Accepted by user-spec; author validates on real funnels; no code change |
| Menu precedence: taps suppressed while parked on an inline menu (user-spec Risk 3) | Accepted + documented; keyboard steps never park themselves (CONTINUE) — collision only with a separate parked MESSAGE step |
| Hint misleads after renaming a button (user-spec Risk 4) | Hint computed live from the store, non-blocking by design |
| Wire format drift vs Telegram Bot API (user-spec Risk 5) | **Closed:** shapes verified via Context7 (code-research §13); engine IT asserts exact body |
| Engine IT has no body-assert precedent — task could invent a fragile pattern | Decision 9 pins the `TelegramSenderIT` JsonNode + `FunnelTestRunSendIT` drain idioms |
| Positional `FunnelStepDto` ctor grows — existing tests break silently | Update the positional-order comment + all positional ctor call sites in tests (Task 1) |
| Preview of an unsaved/just-retyped step renders per the old saved type | Existing behavior for all types (saved-type gate); documented in Decision 7, not a regression |
| Tap spam / keyword fan-out abuse via the persistent keyboard | Accepted (user-spec): taps are human roots (depth 0) bounded by the existing re-enter guard; funnel-originated chains stay bounded by the three Phase-3 backstops; no new code |

## User-Spec Deviations

None.

## Acceptance Criteria

Технические критерии приёмки (дополняют пользовательские из user-spec):

- [ ] POST/PATCH funnel with SET_KEYBOARD/CLEAR_KEYBOARD round-trips: response (and subsequent GET)
  carries all keyboard fields back (`toStepDto` wired) — verified by IT/unit tests.
- [ ] All validation rejections return 422 with `code: funnel_step_invalid` (rows/buttons caps,
  blank/over-64/duplicate button text, missing text, keyboard-only fields present on
  CLEAR_KEYBOARD, bad parse mode).
- [ ] `FunnelStep.copyOf` carries every new field; `keyboardRows` is a new list instance.
- [ ] Engine slow-lane IT asserts exact `/sendMessage` `reply_markup` wire bodies for both steps and
  `completed` (not `waiting_for_reply`) final status.
- [ ] Preview endpoint returns `kind="keyboard"`, rendered text (variables substituted), raw label
  rows; frontend panel renders text + keyboard mock without `v-html`.
- [ ] Locale parity gate passes (uk/en); all new UI strings keyed.
- [ ] No regressions: default backend lane, slow lane, and frontend suite all green.

## Implementation Tasks

### Wave 1 (независимые)

#### Task 1: Backend step model + validation
- **Description:** Add the two `StepType` constants, new nullable keyboard fields on `FunnelStep`
  (incl. `copyOf` with defensive row-list copy), `KeyboardRow`/`KeyboardButton` records + DTO
  mirrors, wiring through `toSteps` AND `toStepDto`, and `validateSetKeyboard`/`validateClearKeyboard`
  per Decision 6. Result: funnel CRUD round-trips both new step types (verified by a PATCH→GET
  `FunnelControllerIT` case) and rejects invalid ones with 422 `funnel_step_invalid`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/StepType.java`,
  `funnel/FunnelStep.java`, `funnel/FunnelService.java`, `funnel/dto/FunnelStepDto.java`,
  new `funnel/KeyboardRow.java`, `funnel/KeyboardButton.java`, `funnel/dto/KeyboardRowDto.java`,
  `funnel/dto/KeyboardButtonDto.java`; tests: `FunnelStepTest.java`,
  new `FunnelServiceKeyboardStepTest.java`, `FunnelControllerIT.java` (round-trip case)
- **Files to read:** `work/16-persistent-keyboard/code-research.md` (§1, §2, §5, §9),
  `funnel/ContentBlock.java`, `funnel/Button.java`, `FunnelServiceEmitEventTest.java`

#### Task 2: Frontend step form + i18n
- **Description:** Add both step types to the editor: `STEP_TYPES` entries, per-type form blocks
  (text + parse mode; rows sub-editor following the `menuButtons`/`blocks` local-array idiom with
  `*Touched` hand-validation mirroring the Decision 6 rules byte-for-byte; «Постійна» default-on
  and «Сховати після натискання» default-off checkboxes), per-button non-blocking keyword hint from
  the funnels store (Decision 8), `onSubmit` cases preserving `id`/`next`. All new strings in uk + en.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** funnel editor on localhost:3000 — add a SET_KEYBOARD step, build button rows,
  see the amber hint under a button whose text matches no active keyword funnel; save and reopen —
  fields persist.
- **Files to modify:** `frontend/types/funnel.ts`,
  `frontend/components/funnels/FunnelStepForm.vue`, `frontend/i18n/locales/uk.json`,
  `frontend/i18n/locales/en.json`; new `frontend/tests/components/funnels/FunnelStepForm.keyboard.spec.ts`
- **Files to read:** `work/16-persistent-keyboard/code-research.md` (§10, §11),
  `frontend/tests/components/funnels/FunnelStepForm.composer.spec.ts`,
  `frontend/components/funnels/FunnelTriggerSettings.vue`

### Wave 2 (зависит от Wave 1)

#### Task 3: StepExecutor execution of both steps
- **Description:** Two switch cases in `StepExecutor.execute`: render the mandatory text via
  `renderTrimmed`, build the `reply_markup` Maps per Decision 5, send via 6-arg `sendText`, return
  `cont()`; error mapping identical to other send steps; defensive guard for null/empty
  `keyboardRows` on a SET_KEYBOARD snapshot (terminal-fail, mirroring invalid-state handling —
  validation normally prevents this). Logs ids/codes only (PII rule), never rendered text or labels.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/StepExecutor.java`;
  tests: `FunnelStepExecutorTest.java`
- **Files to read:** `work/16-persistent-keyboard/code-research.md` (§3, §4, §13),
  `bot/TelegramSender.java` (sendText overloads)

#### Task 4: Backend preview extension
- **Description:** Extend `PreviewStepRequest` with the keyboard fields and `PreviewStepResponse`
  with `kind="keyboard"` + clamped raw `keyboardRows`; `previewStep` branches on the saved step's
  type and renders the request's text via `VariableTemplateRenderer` (Decision 7). Result: preview
  endpoint renders both new step types (SET_KEYBOARD with rows, CLEAR_KEYBOARD without).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/dto/PreviewStepRequest.java`,
  `dto/PreviewStepResponse.java`, `funnel/FunnelService.java`; tests: `FunnelControllerIT.java`
- **Files to read:** `work/16-persistent-keyboard/code-research.md` (§6)

### Wave 3 (зависит от Wave 2)

#### Task 5: Frontend preview panel
- **Description:** Extend `FunnelMessagePreview.vue`: renderable gate for the new types, preview
  payload carries the keyboard fields, watch source extended, new render branch — rendered text +
  bottom-keyboard mock (rows of chips) for SET_KEYBOARD, rendered text for CLEAR_KEYBOARD. Mirror
  the preview DTO types (`kind` union). Keep the no-v-html source guard green.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** editor on localhost:3000 with a saved SET_KEYBOARD step — preview panel shows the
  rendered text (variables substituted) and the button rows as a keyboard mock.
- **Files to modify:** `frontend/components/funnels/FunnelMessagePreview.vue`,
  `frontend/types/funnel.ts`, `frontend/i18n/locales/{uk,en}.json`;
  tests: `frontend/tests/components/funnels/FunnelMessagePreview.spec.ts`
- **Files to read:** `work/16-persistent-keyboard/code-research.md` (§6, §10)

#### Task 6: Engine slow-lane wire-format ITs
- **Description:** Add `FunnelExecutionEngineIT` cases per Decision 9: full sweep → assert the
  exact `/sendMessage` `reply_markup` wire bodies for both step types, execution `completed` and
  never parked; plus a keyword-dispatch case proving a button-label text starts the matching
  keyword funnel via `FunnelEventService.dispatchForSubscriber` (the webhook leg is already covered
  by `ProcessTelegramUpdateJobTest.plainText_dispatchesKeyword`).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test -PrunSlow=true --tests '*FunnelExecutionEngineIT*'` → green
- **Files to modify:** `backend/src/test/java/com/botfunnel/funnel/FunnelExecutionEngineIT.java`
- **Files to read:** `work/16-persistent-keyboard/code-research.md` (§12),
  `bot/TelegramSenderIT.java` (reply_markup body idiom), `funnel/FunnelTestRunSendIT.java`
  (takeRequest drain idiom)

#### Task 7: Manual smoke checklist (smoke.md)
- **Description:** Author `work/16-persistent-keyboard/smoke.md` — the user's manual verification
  checklist per user-spec «Пользователь проверяет»: real bot → activate a menu funnel with
  SET_KEYBOARD → persistent keyboard visible and survives execution end → tap starts the keyword
  funnel → bonus scenario end-to-end (child keyboard, several moves, return to main menu in both
  branches) → CLEAR_KEYBOARD removes the keyboard. Ukrainian (user-facing doc).
- **Skill:** documentation-writing
- **Reviewers:** code-reviewer
- **Files to modify:** new `work/16-persistent-keyboard/smoke.md`
- **Files to read:** `work/16-persistent-keyboard/user-spec.md` («Как проверить»),
  `docs/staging-smoke/09-subscribers.md` (checklist format precedent)

### Audit Wave

#### Task 8: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified in this
  feature (from decisions.md + tech-spec "Files to modify"). Review holistically for cross-component
  issues: validation mirror parity (backend ↔ form), copyOf completeness, builder/wire consistency,
  architectural consistency with Phases 1-6 patterns. Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 9: Security Audit
- **Description:** Full-feature security audit. Read all source files created/modified in this
  feature. OWASP Top 10 across components: rendered-text XSS surface in preview (no v-html), input
  validation completeness (caps, duplicates), no PII in logs (ids/codes only), anti-IDOR on preview
  path, no injection via button labels into the wire body. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 10: Test Audit
- **Description:** Full-feature test quality audit. Read all test files created in this feature.
  Verify coverage of every user-spec AC, meaningful assertions (wire bodies, not just counts), test
  pyramid balance (unit-heavy, slow-lane ITs for invariants), no-v-html guard intact. Write audit
  report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 11: Pre-deploy QA
- **Description:** Acceptance testing: run default backend lane, slow lane (`-PrunSlow=true`),
  frontend suite, locale parity gate (`pnpm build`); verify every acceptance criterion from
  user-spec and tech-spec; local API run per Agent Verification Plan step 4. Manual real-Telegram
  checks are deferred to the user via `smoke.md` (no live environment).
- **Skill:** pre-deploy-qa
- **Reviewers:** none
