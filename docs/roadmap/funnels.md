# Funnels — Delivery Roadmap

Master checklist for the Funnels epic (project.md → Epic 06, "the heart of the product").
The epic is split into **vertical, independently shippable phases**. Each phase runs the full
cycle: `user-spec → tech-spec → tasks → implementation → done → work/completed/`.

**Rule:** only ONE phase is fully specced at a time. Detailed specs for later phases would go
stale before implementation — this map is the high-level source of truth for done vs. todo.

Source vision: `workflow/06-воронки/README.md` (abstract reference TZ — note its BullMQ/pg-boss
stack notes are illustrative; actual engine = **JobRunr + MongoDB**, see `architecture.md`).

---

## Status legend
`todo` · `in progress` · `done`

## Phases

| # | Phase | Folder | Delivers | Depends on | Status |
|---|-------|--------|----------|------------|--------|
| 1 | **Linear funnels (end-to-end)** | `10-funnels` | CRUD funnels; linear step types (Send Message, Send Image, Wait/Delay, Add Tag, Remove Tag, Set Custom Field); variable templating; engine (JobRunr scheduler, `funnel_executions`, idempotency, statuses); `/start` trigger; anti-spam re-enter guard; `/stop` cancellation; `paused` status; basic vertical-list editor. **No buttons/branches.** | — | done   |
| 2 | **Interactive + keyboard** | `11-funnels-interactive` | Inline keyboards in `TelegramSender` (`reply_markup`); `callback_query` handling in webhook; `Wait for Reply` step; `Branch by Button`; composite **"Menu" step** (message + buttons + wait + branch in one node); branch-analytics hooks. | Phase 1 (engine) | done   |
| 3 | **Additional triggers** | `12-funnels-triggers` | Keyword trigger; Tag-added trigger; API-event trigger (`POST /api/v1/events`); **`EMIT_EVENT` step + event-catch** with 1:1 → 1:N trigger relax (`improvements.md` "Крок 1"). | Phase 2 | done   |
| 4 | **Author tooling** | `13-funnels-tooling` | "Test for me" (link service account to Telegram); duplicate funnel; "Force stop all running executions"; message preview + pre-save validation. | Phase 2 | done   |
| 5 | **Funnel composition** | `14-funnels-composition` | `SUBSCRIBE_TO_FUNNEL` step ("Почати потік"); cross-funnel jump + return (from deferred list below). | Phase 3 | done   |
| 6 | **Message composer** | `15-message-composer` | Multi-block `MESSAGE` node (text/image/video/audio/file/album — location/invoice/carousel deferred, Decision 7); per-item album type + type-mixing (Decision 5); new `TelegramSender` methods (sendVideo/sendAudio/sendDocument/sendMediaGroup); replaces `SEND_MESSAGE`/`SEND_IMAGE`/`MENU`. | Phase 2 | done¹  |
| 7 | **Persistent keyboard** | `16-persistent-keyboard` | Persistent bottom **reply keyboard** (ReplyKeyboardMarkup, NOT inline); set/clear steps; tap-routing via Phase-3 keyword funnels (user-spec approved — no new branching mechanism). | Phase 3 (text-match) | done   |
| 8 | **Multi-entry graph (model)** | `17-funnel-multi-entry` | `List<Trigger>` per funnel; `event`-type trigger **nodes** carrying an `entryStepId` (mid-graph entry); one execution per funnel/subscriber — a fired trigger **redirects** the in-flight cursor or **starts fresh** at the entry step (respects `allowReEnter`); index reshape; minimal **vertical-list** editor (Triggers panel, NO canvas). Fire-side = existing `EMIT_EVENT`/API. **Scope: model + engine only** — split from the canvas (Decision: user-spec approved 2026-06-13). | Phase 3 | done²  |
| 9 | **Funnel canvas editor** | `18-funnel-canvas` | Full **editable** drag-and-drop canvas (Vue Flow `@vue-flow/core` + `@dagrejs/dagre` auto-layout) over the Phase-8 multi-entry model: node CRUD, drawn arrows, zoom/pan, persisted node positions, disconnected-subflow visualization. The 4–6 week `12-nice-to-have` rendering layer, promoted to a phase. | Phase 8 | todo   |
| 10 | **Keyboard button routing** | `19-keyboard-button-routing` | Per-button **step routing** for `SET_KEYBOARD` reply-keyboard buttons: each button leads to a step of the funnel (its own drawn output edge on the canvas), instead of today's text→keyword-trigger dispatch. Backend: add `targetStepId` to `KeyboardButton` + reply-keyboard tap routing in the engine (button text → resolve the funnel's keyboard step → follow that button's edge); the `SET_KEYBOARD` `next`/"Далі" edge stays for the fire-and-forget continuation. Frontend: per-button output handles on the keyboard node (mirrors MESSAGE inline-button edges). | Phase 9 (canvas) | todo   |
| 11 | **Variable picker** | `20-variable-picker` | Insert-variable affordance on text fields (frontend; enumerate `user.*` + custom fields). Backend: template `Button.url`/`label` (currently NOT rendered — only message text + caption are). | Phase 2 | todo   |

> **Triggers are global (many-to-many).** A fired event name can be heard by **any** funnel, and one
> funnel can hold **many** trigger nodes. The many-to-many fan-out (one event → N funnels) is the
> 1:1 → 1:N relax folded into **Phase 3**; many trigger NODES inside one funnel is **Phase 8**.
> Author use-case: broadcasts/external sources emit a named event → a dedicated "trigger funnel"
> responds with a message. An "Action" block (group Add/Remove Tag + Set Field in the step picker) is
> a UI-only editor refinement — see `funnels-future-epics.md`, not a standalone phase.

> **Phases 5–10 are vision-captured, not yet specced** (Phase 8 user-spec approved 2026-06-13). Full
> descriptions, competitor-screen references, and the mapping to our actual architecture live in
> **[`funnels-future-epics.md`](funnels-future-epics.md)** (screens under
> `assets/funnels-vision/`). They came from a post-MVP competitor exploration — "MVP is loved, users
> want more". Numbers are provisional; priority may be reordered. **Note:** the original "Epic 8" was
> split into **Phase 8 (multi-entry model)** + **Phase 9 (editable canvas)** — the canvas cannot
> precede the model it renders, and bundling them would conflate a low-risk schema change with a
> 4–6 week UI build.

> **Per-execution analytics** (drop-off, branch distribution) is NOT a funnels phase — it belongs
> to the Analytics epic (`workflow/09-аналітика`). Cross-reference, do not duplicate.

## Notes
- Phases 1→2→3→4 is the dependency order. Priority of 3 ↔ 4 may be swapped later; 1 and 2 are the
  foundation and cannot be reordered.
- Each completed phase: move `work/{folder}` → `work/completed/`, flip status to `done` here,
  and update the integration notes in `architecture.md` (`funnel` module).
- ¹ Phase 6 (`15-message-composer`): implementation + audit + pre-deploy QA complete (backend 1108/slow-lane engine ITs 57/57, frontend 487 green; 16 commits on `main`, unpushed). Deploy + post-deploy "Test for me" deferred — no target environment yet. Manual `funnels`/`funnel_executions` wipe required on first deploy (`deployment.md`).
- ² Phase 8 (`17-funnel-multi-entry`): implementation + audit (security CLEAN) + pre-deploy QA complete (backend 1284 tests — all 17 feature classes green incl. `FunnelExecutionEngineIT` 67 slow-lane; frontend 532 green, i18n parity; 13 commits on `dev`). 18/18 acceptance criteria PASS; 4 live checks (curl API-event, in-flight-redirect bash, Telegram loop, manual wipe + clean start) DEFERRED to post-deploy (Task 12) — no target environment yet. Manual `funnels`/`funnel_executions` wipe required on first deploy (`deployment.md`).

## Deferred beyond the 4 phases (future — surfaced during Phase 2 planning)

These were raised while specifying Phase 2 (`11-funnels-interactive`) and consciously pushed out.
They build ON TOP of the interactive branching engine, so they are cheap to add later.

- **Cross-funnel jump** — a MENU button that ends the current execution and starts ANOTHER funnel.
  Needs cycle protection (A→B→A), a target re-enter guard, and snapshot-handoff semantics.
- **Cross-funnel return** — a sub-funnel (reached via a cross-funnel jump) finishes and resumes the
  PARENT funnel's execution at the menu it branched from. Consequence of cross-funnel jump.
  (Intra-funnel return-to-menu is IN Phase 2 — a MENU button can target an earlier menu step.)
- **VIP-style full transfer** — on a tag/condition (e.g. a "VIP" tag), cancel the subscriber's
  current execution(s) and move them entirely onto a different funnel.
- **API-request (HTTP) step** — call an external API from inside a funnel (already listed as
  `12-nice-to-have` in `workflow/06-воронки/README.md`).
- **Canvas (visual) editor** — **PROMOTED to Phase 9 (`18-funnel-canvas`)** — drag-and-drop canvas
  with blocks linked by drawn arrows, zoom/pan, node positions. Phase 2 already ships the **graph DATA
  model** (each step has a stable id; MENU buttons carry `target` edges to any step id, enabling fan-in
  and loops) but edits it through the existing **vertical-list editor** (button targets picked via
  SearchableSelect, no drawn arrows). The canvas is mostly a RENDERING layer over the same data — no
  tree→graph migration needed. Library plan: Vue Flow + `@dagrejs/dagre` auto-layout. Still a 4–6 week
  build; depends on the Phase 8 multi-entry model (`project.md`, `workflow/06-воронки/README.md`).
- **Free-text wait-for-reply** — Phase 2 waits on button callbacks only; matching a typed text
  answer (and a fallback "no match" branch) is deferred (closer to Phase 3 keyword triggers).
- **Funnel versioning (draft / publish + migrate in-flight)** — editing produces a draft version;
  in-flight subscribers keep running the old version until the author "publishes", at which point
  running executions are MOVED onto the new version. The hard, unsafe part is remapping a live
  execution's `currentStepId` onto an edited graph (the step may be deleted/changed/re-wired — no
  safe automatic mapping). Phase 2 keeps Phase-1 **snapshot isolation** instead: in-flight runs
  finish on the version they started; new `/start`s use the saved version. Versioning is a separate
  epic-sized feature, orthogonal to interactive keyboards.
