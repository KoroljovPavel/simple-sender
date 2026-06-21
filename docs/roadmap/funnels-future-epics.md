# Funnels — Future Epics (vision capture)

Companion to `docs/roadmap/funnels.md`. This file is the **durable record** of a competitor-driven
feature exploration done after the MVP shipped (Phases 1–2 done, Phase 3 next). The source was a
competitor admin's funnel builder; reference screenshots are committed under
`docs/roadmap/assets/funnels-vision/` so the vision survives the chat's ephemeral image cache.

Nothing here is committed scope yet — each item gets its own `user-spec → tech-spec → tasks` cycle
when its phase becomes active (roadmap rule: only ONE phase specced at a time). This doc exists so
the ideas, the **mapping to our actual architecture**, and the complexity calls are not lost.

Cross-references: `workflow/improvements.md` (already analyzed `EMIT_EVENT` / `SUBSCRIBE_TO_FUNNEL` /
`API_CALL` steps), `workflow/06-воронки/README.md` (original vision), `workflow/12-nice-to-have/README.md`.

---

## How the competitor's model differs from ours (the core insight)

| Concept | Competitor | Our current model |
|---|---|---|
| Message | One "Send message" node = **ordered list of content blocks** (text/image/video/audio/file/album/map/invoice/…), buttons attach per block | `SEND_MESSAGE` (text + optional inline buttons) and `SEND_IMAGE` are **separate flat step types** (Decision 12 — flat per-type fields, no `_class` discriminator) |
| Menu | Bottom **persistent reply keyboard** (ReplyKeyboardMarkup) | Phase-2 `MENU` = **inline keyboard** (callback_query, parks `waiting_for_reply`). A different Telegram primitive. |
| Trigger | A **node on the canvas** ("Тригер") that fires on a named event and runs its own branch | Trigger lives **on the funnel** (`triggerType`+`triggerValue`, partial-unique → exactly one active funnel per trigger) |
| Funnel shape | One canvas may hold **several disconnected subgraphs** (main flow + draft + trigger-entry subflow) | One **connected graph** from a single start; `currentStepId` cursor; per-execution snapshot |
| Editor | Visual **canvas** (drag nodes, draw arrows, zoom/pan) | Vertical-list editor over the same graph data model (Phase 2 already minted stable step ids + `target` edges for a future canvas) |

---

## Epic 6 — Message composer (multi-block messages)

**Screens:** `01-message-composer-settings.png` (block palette in the editor),
`02-message-composer-telegram.png` (how the stacked blocks render in Telegram).

**Idea.** Replace the single-text `SEND_MESSAGE` with a composer: one funnel node holds an **ordered
list of content blocks**. Block types seen in the competitor: Text, Video, Audio, Image, File,
Album (media group), Map/Location, Invoice, Delay, Contact, Input, Receipt, Animation, Print, Card
(carousel). Buttons attach to a block.

**Mapping to our code.**
- Content model change: a step holds `List<ContentBlock>` with a discriminator — **breaks Decision 12**
  (flat, no `_class`). This is the main architectural cost.
- `TelegramSender` today has only `sendText` / `sendPhoto`. Needs `sendVideo`, `sendAudio`,
  `sendDocument`, `sendMediaGroup` (album), `sendLocation`, `sendContact`, `sendInvoice` — each a
  distinct Telegram Bot API method with its own payload/limits.
- **Graph is unaffected:** one node simply emits N Telegram messages in sequence. The last block's
  buttons = a generalization of our existing `MENU` step (rich preamble + keyboard). So `MENU`
  likely folds into "a composer node whose last block carries an inline keyboard".
- "Карусель" / "Card" (palette item, screen 07) = the Album/media-group block → lives in this epic.

**Complexity:** Large (new content model + UI block editor + ~7 new sender methods).
**Value:** High — matches the author's mental model 1:1.

---

## Epic 7 — Persistent reply keyboard

**Screens:** `03-persistent-menu-settings.png` (rows-of-buttons config),
`04-persistent-menu-telegram.png` (bottom keyboard: "Мої подарунки / Мій баланс / Грати / …").

**Idea.** A persistent bottom menu that stays under the chat input.

**Critical distinction.** What Phase 2 shipped (`MENU`) is an **inline keyboard** (buttons under a
message, taps arrive as `callback_query`). The screenshot is a **ReplyKeyboardMarkup**
(`is_persistent: true`, `resize_keyboard`) — a *different* Telegram primitive. Consequence: taps on
a reply keyboard arrive as **ordinary text messages**, not `callback_query`. Therefore branching must
**match on text** — which couples this epic to keyword/free-text matching (deferred alongside Phase 3
keyword triggers).

**Mapping to our code.**
- `TelegramSender` needs `reply_markup.keyboard` rows + `is_persistent` / `resize_keyboard` /
  `one_time_keyboard`.
- New step(s) to **set** and **clear** the persistent keyboard.
- Text-match branching in the engine (reuse whatever Phase 3 builds for keyword matching).

**Complexity:** Medium. Sequenced **after** Phase 3 because it shares text-match with triggers.
**Open question (confirm at spec time):** the screenshot is a reply keyboard, NOT the BotFather
command menu / `setChatMenuButton`. Spec assumes reply keyboard.

---

## Epic 8 — Multi-entry funnel graph (trigger nodes + independent subflows)

> **SPLIT (user-spec approved 2026-06-13).** This epic became TWO roadmap phases:
> **Phase 8 `17-funnel-multi-entry`** = the model + engine + minimal vertical-list editor (below), and
> **Phase 9 `18-funnel-canvas`** = the editable Vue Flow canvas. The canvas can't precede the model it
> renders. Approved execution semantic: ONE execution per funnel/subscriber — a fired `event` trigger
> (carrying an `entryStepId`) **redirects** the in-flight cursor or **starts fresh** mid-graph (respects
> `allowReEnter`). The **fire-side already exists** (`EMIT_EVENT` step / API events = the competitor's
> "Запустити подію" action, screens `15`–`18`); Phase 8 adds only the **listen-side** trigger node. The
> composite "Виконати дії" action grouping stays an editor refinement (see below), out of Phase 8 scope.

**Screens:** `05-trigger-node.png` (a "Тригер" node: "Буде запущено, коли буде запущена подія
SOME_TRIGGER" → its own branch), `06-independent-steps.png` (same shape — disconnected subgraph),
`07-node-palette.png` + `08-canvas-overview.png` (full node palette and canvas with multiple
independent islands); `15-action-fire-event.png` + `16-trigger-node-vs-emit-event.png` (LISTEN node vs
FIRE action), `17-action-dropdown-integrations.png` + `18-action-dropdown-core.png` (the "Виконати дії"
action dropdown — mostly future/nice-to-have items).

**Idea (bundles user questions 3 + 4 — same model change).**
- **Trigger node:** a node placed inside the graph that is itself an entry point; it fires on a named
  event and runs its connected branch.
- **Independent subflows:** one funnel may contain several **disconnected** components — a main flow,
  a draft, a trigger-entry subflow.

**Author clarification (all vision screens are ONE funnel).** In the competitor:
- **One funnel holds many triggers** — multiple `Тригер` entry nodes scattered across the same canvas,
  each with its own branch.
- **Triggers are global — a fired event can be heard by ANY funnel** (many-to-many: one event name →
  several funnels/entry nodes react). This is decoupled pub/sub, not a per-funnel trigger.
- **Concrete use-case the author wants to reproduce:** broadcasts are already sent into this service;
  a dedicated "trigger funnel" listens for a named trigger and simply emits a message in response.
  So: *external/broadcast emits event → trigger funnel(s) respond*.

**Where the two parts land.**
- The **many-to-many fan-out** (one event name heard by N funnels) = the 1:1 → 1:N relax already
  folded into **Phase 3** (`improvements.md` "Крок 1", recommended option B: `Funnel` holds a
  `List<Trigger>`, drop unique for `event` type). Phase 3's `EMIT_EVENT` step + API-event already
  give the author "broadcast/external fires event → funnel responds".
- **Many trigger NODES inside one funnel + mid-graph entry + disconnected drafts** = this epic
  (model: `List<Trigger>` each pointing at an entry step id; engine starts at that `currentStepId`).
- If triggers must be **managed independently of any funnel** (reuse, own lifecycle/UI), revisit the
  standalone `Trigger` entity (`improvements.md` option C, currently rejected as YAGNI — the author's
  use-case so far is satisfied by the 1:N relax, no separate entity needed yet).

**Mapping to our code.**
- Today a funnel is a single connected graph from one start; one funnel-level trigger; partial-unique
  index → exactly one active funnel per trigger.
- Engine already navigates by `currentStepId`, so **starting mid-graph is feasible**. The real work:
  - `Funnel` holds `List<Trigger>`, each pointing at an **entry step id**.
  - The trigger system fans a fired event to the correct entry node (`FunnelTriggerService.fire`
    resolves entry → starts a snapshot execution at that `currentStepId`).
  - Re-enter / anti-spam semantics defined **per entry**, not per funnel.
  - Editor + model must tolerate orphan nodes (no single-root assumption); a "draft" subflow = a
    component with no live trigger yet.
- This is the **prerequisite mental model for the canvas editor** (nice-to-have) and the natural
  extension of Phase 3's event system.

**Complexity:** Large (schema + index relax + multi-entry execution + editor for disconnected graphs).

---

## Already-covered palette items (screens 07 / 08) — no new epic needed

The competitor's node palette maps onto existing roadmap entries; listed here so they aren't
re-discovered as "new":

| Palette node | Where it already lives |
|---|---|
| Почати потік (start flow) | `SUBSCRIBE_TO_FUNNEL` step → **Phase 5** `14-funnels-composition` (`improvements.md`) |
| Карусель / Card | Album/media-group block → **Epic 6** message composer |
| Виконати дії (run actions) | tag/field bundle — existing step types; condition/HTTP in `12-nice-to-have` |
| Умова (condition by field/tag) | `12-nice-to-have` (conditional branching) |
| Рандомайзер (A/B split) | `12-nice-to-have` (A/B tests) |
| Розклад (schedule) | `12-nice-to-have` ("run funnel daily at 10:00") |
| Smart AI | `12-nice-to-have` (AI step, OpenAI/Anthropic) |
| Пуш повідомлення | future channel work |
| Замітка (note) | trivial editor-only annotation node — fold into canvas editor |
| **Canvas (visual) editor** | already `12-nice-to-have` (4–6 weeks); Phase 2 laid the graph data model |
| API call (HTTP step) | `improvements.md` Step 3 + `12-nice-to-have` — gated on SSRF defenses |

---

## Epic 9 — Keyboard button routing (reply-keyboard buttons → steps)

**Screen:** `19-keyboard-button-routing.png` (a `SET_KEYBOARD` "Встановити клавіатуру" node on the
Phase-9 canvas — keyboard buttons "Кнопка 1 / 2 / 3" listed in the card, with a single `Далі` output
circle). The author's request: drop the generic `Далі` circle's prominence and give **each keyboard
button its own output edge that leads to a step of this funnel** — i.e. tapping a keyboard button
continues THIS execution to a chosen step, the same way a MESSAGE inline-button (`btn:<i>` →
`Button.targetStepId`) does.

> Surfaced during Phase 9 (`18-funnel-canvas`) manual testing (2026-06-16). Captured here because it
> is a **model + engine change**, not a canvas tweak — it does not belong inside the canvas phase.

**Why it is NOT how the engine works today (verified in code).**
- `KeyboardButton` is **text-only** by design — `record KeyboardButton(String text)` (Java) /
  `interface KeyboardButton { text }` (TS). No `targetStepId`, unlike the inline `Button`
  (`type,label,targetStepId,url`).
- `SET_KEYBOARD` is **fire-and-forget**: `StepExecutor.setKeyboard` sends the keyboard text +
  `ReplyKeyboardMarkup` and returns `StepResult.cont()` → the engine immediately advances along
  `step.next`. It never parks. So `next` ("Далі") is the only real runtime edge of the node.
- A reply-keyboard **tap arrives as an ordinary text message** (Telegram echoes the label as
  `message.text`) and is routed by **keyword dispatch** (`ProcessTelegramUpdateJob.dispatchKeyword` →
  `FunnelEventService.TRIGGER_KEYWORD`, contains-match against active funnels' `keywords`) — a
  completely separate flow that can start a **different** funnel. This is the Phase-7
  (`16-persistent-keyboard`) approved semantic: "tap-routing via Phase-3 keyword funnels, no new
  branching mechanism."
- Therefore drawing a per-button edge today would be a **fake edge** — the engine never routes a
  reply-keyboard tap to a step of the current funnel. The current canvas (one `Далі` output, buttons
  shown as non-routing chips) is faithful.

**What this epic changes.**
- **Model:** add `targetStepId` to `KeyboardButton` (Java record + TS) — breaks its "single-component
  by design" invariant.
- **Engine — the hard part:** the persistent keyboard **outlives the execution** (fire-and-forget;
  the execution that set it may already be complete). To route a tap to a button's `targetStepId` you
  must track, per subscriber, the **active keyboard** (which funnel/step set it) so a later text
  message can be resolved to that keyboard's button and **start/resume an execution at
  `targetStepId`**. This is a NEW routing mechanism (active-keyboard text-match), distinct from
  keyword triggers.
- **Precedence:** a tapped label may match BOTH a keyboard button (this funnel) AND a keyword trigger
  (another funnel). Define precedence (likely active-keyboard button wins over global keyword) and the
  no-match fallback — and reconcile with the existing `waiting_for_reply` inline-menu precedence in
  `dispatchKeyword`.
- **Frontend:** per-button output handles on the `SET_KEYBOARD` canvas node (mirror MESSAGE
  `btn:<i>` edges); the `next`/"Далі" edge stays for the fire-and-forget continuation (sending the
  keyboard then proceeding is still valid).

**Relationship to other phases.** Builds on Phase 7 (`16-persistent-keyboard`) and Phase 9 canvas
(`18-funnel-canvas`); it **revises** the Phase-7 decision that reply-keyboard taps route only via
keyword funnels. If per-button routing lands, the canvas keyboard node gains real per-button edges.

**Alternative (no model change).** For per-button → step branching **today**, use a **MESSAGE node
with inline callback buttons** — each already carries `targetStepId` and its own drawn output edge.
Reply keyboards remain the keyword-routed, cross-funnel primitive. This epic only matters if the
author specifically wants the *persistent bottom keyboard* (not inline) to branch within one funnel.

**Complexity:** Large (model field + new per-subscriber active-keyboard tap-routing in the engine +
precedence with keyword triggers + canvas edges). **Value:** High — matches the author's mental model
(buttons that "go somewhere"), but it is an execution-model change, not a UI tweak.

---

## Epic 10 — Variable picker on text fields

**Screens:** `10-variable-affordance.png` (a `$` affordance appears under a text field),
`11-variable-picker-modal.png` ("Вибір змінної" → pick `firstName`),
`12-variable-inserted.png` (placeholder inserted into the text as `{{ firstName }}`),
`13-button-action-types.png` (the `$` icon sits on the button **name** field too),
`14-button-url-variable.png` (the `$` icon on the **URL** field; value `https://{{siteUrl}}`).
So the `$` affordance is on **button label AND button URL**, not just message text.

**Idea.** Today the author must *know* the placeholder syntax to use a variable. The competitor shows
a `$`/variable affordance under **any** text field; picking a variable inserts it at the cursor. The
author notes the modal is clunky — inline autocomplete would be nicer. Crucially this works on
**every** text field, **including a button's URL** (e.g. a URL needs a token that lives in a variable).

**Mapping to our code.**
- **Mostly frontend.** Backend already renders `{user.*}` and `{custom.<key>}` (`VariableTemplateRenderer`).
  The picker just needs to enumerate available variables (`user.*` fixed set + the project's
  `customFieldDefinitions`) and insert OUR placeholder syntax at the caret. UX: prefer inline
  autocomplete (e.g. typing `{` or `$` opens a dropdown) over a modal.
- **Backend gap — button URL is NOT templated (verified).** `VariableTemplateRenderer.render` runs only
  on message **text** and image **caption** (`StepExecutor.java:112/173/196`). The `Button` record has
  a `url` field (`Button.java:16`) but it is never passed through the renderer. The author's
  "token-in-URL" case therefore needs a backend change: render `Button.url` (and likely `Button.label`)
  through the same renderer, with URL-aware escaping (do NOT apply Markdown/HTML escaping to a URL —
  needs a separate render mode). Treat this as a security-sensitive change (a templated URL can leak a
  custom field to a third party — intentional, but the author must understand it; PII/Decision 16).
- Syntax note: competitor uses `{{ firstName }}`; our picker inserts `{user.first_name}` /
  `{custom.<key>}` — keep our existing syntax, the picker just hides it.

**Complexity:** Small–Medium (frontend picker + the button-URL/label templating backend change).
**Value:** High, low risk — strong UX win, mostly additive.

## UX refinement (not a standalone phase) — "Action" block grouping

**Screen:** `09-action-block.png` — competitor's "Виберіть дію" dropdown groups actions in one block:
Unsubscribe, Open chat, Close chat, **Add tag**, **Remove tag**, Message, External request, **Update variable**.

**Idea / author's question.** Our action-type steps (Add Tag, Remove Tag, Set Custom Field) are each
a separate step type in the picker. Should we group them under one "Action" block for a tidier UI?

**Recommendation.** Do the **cheap version**: group these step types under one "Дія/Action" *category*
in the existing step picker — a UI/editor change only, **no data-model change** (each stays its own
`StepType` on the wire). A true composite "one node, many actions" block is a model change (a step
holding `List<Action>`) and isn't justified yet. Note: some competitor entries map to features we don't
have / have elsewhere — Open/Close chat = live-chat (out of scope), External request = HTTP step
(`12-nice-to-have`, SSRF-gated), Update variable = our `SET_CUSTOM_FIELD`. So this is partly relabeling,
partly future features — keep it as an **editor-grouping refinement**, fold into the canvas-editor /
message-composer work rather than a dedicated phase.

## Button configuration — richer actions, re-activation, link tracking

**Screens:** `13-button-action-types.png`, `14-button-url-variable.png` (competitor's "Редагувати кнопку").

Our `Button` record is `(type, label, targetStepId, url)` with `type ∈ {"callback","url"}` — i.e. we
already have the competitor's **Базова** (callback → branch) and **Відкрити сайт** (URL). The screens
surface three deltas:

1. **Richer button action types.** Competitor offers, beyond base/URL: **Замовлення** (order),
   **Оплатити замовлення** (pay order), **Підписка на реккурент** (recurring subscription),
   **Підписати на інший канал** (subscribe to another channel). Order/Pay/Recurring require Telegram
   **Payments / invoices** → e-commerce territory, **`12-nice-to-have`** (out of MVP). "Subscribe to
   another channel" ≈ a channel-join action (future channel work). Verdict: keep `Button.type` as
   `callback|url` for now; new action types are a separate, payments-gated effort — **do not** fold
   into Phase 3.
2. **"Дозволити активацію кнопки кілька разів" (per-button re-activation toggle).** Today a MENU
   parks `waiting_for_reply` and a press resumes/advances; this flag would let one button be pressed
   **repeatedly** without ending the park. Small behavior flag on `Button`/MENU semantics — note for
   whoever revisits MENU (Epic 6 composer or Epic 8). Decide: does a re-activatable button re-enter
   the same branch, or just re-fire a side effect?
3. **"Smart ID" link tracking** (screen 14 checkbox): append a subscriber identifier to the outbound
   URL so the contact's click/events are trackable. This is **click-tracking + UTM**, already listed
   under Analytics `12-nice-to-have` ("Click tracking з UTM-розміткою і коротким посиланням"). Tie it
   there, not to funnels. Security note: appending a subscriber id to a third-party URL leaks an
   identifier by design — author-intended, but document it.

## Phasing decisions captured

- **Phase 3 (`12-funnels-triggers`, next) scope** = keyword trigger + tag-added trigger + API-event
  trigger **plus** the `EMIT_EVENT` step and event-catch with the 1:1 → 1:N trigger relax
  (`improvements.md` "Крок 1" — explicitly half of Phase 3; API event + internal emit share one
  `event_name` namespace). `SUBSCRIBE_TO_FUNNEL` and the trigger-node/multi-entry model are
  **excluded** to keep the "one shippable phase at a time" rule.
- Epics 5–11 numbers are provisional; priority may be reordered after Phase 3/4.
- Persistent keyboard (Epic 7) is sequenced after Phase 3 because it reuses text-match.
- Trigger fan-out (one event → N funnels) is **Phase 3** (1:N relax); many trigger nodes inside one
  funnel is **Epic 8**; standalone `Trigger` entity stays deferred (YAGNI) until reuse is needed.
- Keyboard button routing (Epic 9, roadmap Phase 10 `19-keyboard-button-routing`) is a model+engine
  change (add `targetStepId` to `KeyboardButton` + per-subscriber active-keyboard tap-routing), NOT a
  UI tweak — it revises the Phase-7 "reply-keyboard taps route via keyword funnels" decision. Slotted
  **ahead of** the variable picker (2026-06-16, author request).
- Variable picker (Epic 10, roadmap Phase 11 `20-variable-picker`) is low-risk/mostly additive; its
  only backend coupling is templating `Button.url`/`label` (a security-reviewed change — URL leaks
  custom fields by design). Moved down one slot to sit after keyboard button routing.
- "Action" block = UI-only step-picker grouping, no data-model change; rides with the editor work.
