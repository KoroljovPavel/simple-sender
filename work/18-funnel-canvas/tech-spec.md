---
created: 2026-06-13
status: approved
branch: dev
size: L
---

# Tech Spec: 18-funnel-canvas

## Solution

Add a visual drag-and-drop canvas editor on top of the **existing** funnel graph model (Vue Flow
`@vue-flow/core` + `@dagrejs/dagre` auto-layout). The canvas is the **single editing surface** for graph
structure and edges; the vertical `FunnelStepsList` becomes **read-only** (view only).

The change splits into three thin layers over an already-complete graph backend:

1. **Backend additive persistence** — one new persisted concept, **node coordinates**. `canvasPosition {x,y}`
   embedded on `FunnelStep` and `Trigger`, plus a new top-level `Funnel.notes[]` array
   (`{id, text, canvasPosition}`). All nullable/additive, no migration. Each new field must be carried
   through **five** existing seams (domain field+accessors, `FunnelStep.copyOf`, the DTO record, the forward
   mapper `toSteps` / trigger pass `applyTriggers`, the reverse mapper `toStepDto` / `toTriggerDtos`) or it
   silently drops on the round-trip (`@JsonIgnoreProperties(ignoreUnknown)`).

2. **Backend execution semantics** — make the drawn graph the single source of truth: (a) `advanceToNext`
   treats `next == null` as **end of branch** (no fall-through to the next array element); (b) `on_start`
   gains an **explicit entry step** by **reusing the existing `Trigger.entryStepId`** field (today
   event-only) — the start node's edge writes `on_start.entryStepId`, the `requireNoEntryStep` validation
   relaxes for `on_start`, and `FunnelTriggerServiceImpl.fire()` starts the execution at that entry step via
   the existing `insertExecutionAt` primitive instead of the hardcoded step 0. After this, **array order no
   longer affects execution**. `resolveStartCursor` is untouched (Phase-5 seam preserved). No new field.

3. **Frontend canvas** — a pure model↔graph mapping layer (unit-tested), a client-only Vue Flow canvas
   surface (typed output handles → draw-to-connect → model fields, broken-edge highlight, cross-funnel exit
   badge, viewport culling), authoring interactions (palette, optional start node, delete-with-edge-cleanup,
   side panel reusing the existing step/trigger forms), and editor-page wiring (drag→`canvasPosition`→
   debounced PATCH, read-only list, `funnels.canvas.*` i18n).

## Architecture

### What we're building/modifying

**Backend (additive + 3 execution-semantics edits):**
- **`FunnelStep` / `Trigger` domain** — add nullable `canvasPosition` (embedded `{x,y}`) + accessors;
  `FunnelStep.copyOf` carries it.
- **`Funnel` domain** — add nullable `List<Note> notes` (`Note = {id, text, canvasPosition}`).
- **DTOs** (`FunnelStepDto`, `TriggerDto`, `FunnelResponse`, `UpdateFunnelRequest`) — add `canvasPosition` /
  `notes` components; new `NoteDto`.
- **`FunnelService` mappers** — `toSteps`/`toStepDto`, the trigger pass `applyTriggers` (forward) /
  `toTriggerDtos` (reverse), notes forward+reverse.
- **`FunnelExecutionEngine.advanceToNext`** — `next == null` ⇒ end of branch.
- **`FunnelService.applyTriggers` (`requireNoEntryStep`)** — accept `entryStepId` on `on_start`, validating
  it resolves to a step of THIS funnel (reuse `requireEventEntryStep`'s `stepIds.contains` check).
- **`FunnelTriggerServiceImpl.fire()`** — start `on_start` execution at the matched trigger's `entryStepId`
  via `insertExecutionAt`; **`FunnelService.testRun`** aligned to the same entry (see Deviations).

**Frontend (new canvas layer; existing field editors reused unchanged):**
- **`@vue-flow/core` + `@dagrejs/dagre`** — new deps; client-only render.
- **Types** (`frontend/types/funnel.ts`) — `canvasPosition` on `FunnelStep`/`FunnelTrigger`, `notes[]` on
  `Funnel`/`FunnelResponse`/`UpdateFunnelRequest`.
- **Model↔graph mapping layer** (composable/pure module) — steps+triggers+notes → Vue Flow nodes+edges;
  edge → model field (`next` / `Button.targetStepId` / `timeoutTargetStepId` / `entryStepId`); dagre layout;
  broken-edge detection; notes isolation.
- **Canvas surface component** (`*.client.vue`) — VueFlow render, typed handles, draw-to-connect, broken-edge
  highlight, cross-funnel exit badge, zoom/pan, viewport culling.
- **Authoring interactions** — palette (existing step types + trigger + note), optional `on_start` start
  node, node delete with edge auto-cleanup + warning, side panel reusing `FunnelStepForm` /
  `FunnelTriggerSettings` (target `SearchableSelect`s removed on canvas).
- **Editor page wiring** (`funnels/[funnelId].vue`) — mount canvas client-only, drag-stop →
  `canvasPosition` → debounced `persist()`, `FunnelStepsList` read-only, notes persistence,
  `funnels.canvas.*` i18n (uk/en parity).

### How it works

**Load:** page `fetchOne` → `FunnelResponse` (now carries `canvasPosition` + `notes[]`). Mapping layer
builds nodes (steps + optional `on_start` start node + event-trigger nodes + notes) and edges (default
`next`, per-button callback, timeout, trigger/start entry). Nodes with a stored `canvasPosition` render at
those coords; nodes without → **dagre** auto-layout over the **full** node/edge set (forest-aware, never
assumes a single root) → `fitView` after `nodes-initialized`.

**Edit content:** click node → side panel mounts the existing `FunnelStepForm` / `FunnelTriggerSettings`
unchanged, minus the target `SearchableSelect`s (edges replace them).

**Draw edge:** drag from a typed output handle to a target node → mapping layer writes the right field on the
source model object; "no edge" serializes as `null` (never `""`). Edges only to **saved** nodes (a new node
is persisted to mint its `id` before it can be wired).

**Move:** `@node-drag-stop` writes the final `{x,y}` into the node's model object (`canvasPosition` /
`notes[]`), then the existing debounced full-replace `persist()` PATCHes.

**Delete:** remove the node; mapping layer auto-cleans every edge pointing at it (`next` / button / timeout /
`trigger.entryStepId`) and the UI warns "N connections will be disconnected".

**Execution (after semantics change):** `/start` → webhook → `FunnelTriggerServiceImpl.fire()` matches the
active `on_start` funnel, reads the matched trigger's `entryStepId`, and `insertExecutionAt(entryStepId)`.
The engine walks `next`; `next == null` ends the branch. Array order is now purely a serialization detail.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| Vue Flow instance (`useVueFlow`) | Canvas surface component (client-only) | Authoring interactions, page wiring | 1 per editor page mount |
| dagre graph | Mapping layer (`useLayout` composable) | Canvas surface (initial layout only) | 1 per layout run (transient) |

No backend shared resources (additive fields + existing services/repositories only).

## Decisions

### Decision 1: Vue Flow + dagre, editable from the first version
**Decision:** Build the canvas on `@vue-flow/core` + `@dagrejs/dagre`, editable immediately (not a read-only
v1). **Rationale:** ready-made Vue graph editor with auto-layout; building read-only first means writing the
canvas twice. **Serves:** US "Технические решения" §1, AC "Canvas рендерить усі вузли", "dagre автолейаут".
**Alternatives:** custom SVG/d3 canvas (rejected — far more code, reinvents pan/zoom/connect); read-only-first
(rejected — double work).

### Decision 2: Canvas is the single editing surface; list goes read-only
**Decision:** Edge/structure editing happens only on the canvas; `FunnelStepsList` stays mounted but
read-only. **Rationale:** two editable surfaces drift out of sync. **Serves:** US "Ограничения" (single
editing surface), AC "Вертикальний список лишається read-only". **Alternatives:** keep both editable
(rejected — desync risk); remove the list now (rejected — keep as fallback/view until canvas stabilizes).

### Decision 3: `next` becomes an explicit drawn edge
**Decision:** The default `next` edge is drawn by hand; array order stops driving flow. **Rationale:** the
whole point of the canvas is a visible flow — an invisible Y-coordinate edge contradicts it. **Serves:** US
"Как должно работать" §4, AC "Виконання відповідає намальованому графу". **Alternatives:** keep implicit
order-based `next` (rejected — invisible edges defeat the canvas).

### Decision 4: `next == null` = end of branch (engine change)
**Decision:** `FunnelExecutionEngine.advanceToNext` treats `next == null` as end-of-branch (cursor → null),
removing fall-through to the next array element. **Rationale:** a node without a drawn `next` must terminate
its branch, matching the drawn graph; today it silently advances to `array[here+1]`. **Serves:** AC "Вузол
без ребра next виконується як кінець гілки". **Safe because:** prod data is wiped on deploy (Decision 8),
list is read-only, no legacy funnel relies on implicit fall-through. Covered by `FunnelExecutionEngineIT`.
**Alternatives:** keep fall-through and infer `next` from order on save (rejected — array order would still
secretly drive flow, contradicting Decision 3).

### Decision 5: `on_start` explicit entry via reused `Trigger.entryStepId` (no new field)
**Decision:** The start node's outgoing edge writes `on_start.entryStepId`; in `applyTriggers` the
`on_start` case **stops calling `requireNoEntryStep`** and instead validates the id through the **same
funnel-scoped check as `event`** (`requireEventEntryStep` → `stepIds.contains(id)`), so an `on_start`
`entryStepId` must resolve to a step of THIS funnel (no dangling/cross-funnel/arbitrary id accepted). The
`on_start` edge may be absent (`entryStepId == null`) — that is a broken-edge state, not a hard validation
error, surfaced like every other broken edge (highlight on canvas, 422 on activate). `fire()` and `testRun`
start the execution at that step via the existing `insertExecutionAt(..., entryStepId)` (today used by
Phase-5/8 event triggers) instead of the hardcoded `insertExecution(..., 0)`. `resolveStartCursor` is
untouched (its step-0 fallback is defence-in-depth, not the primary guard). **Rationale:** `entryStepId`
already round-trips and already has an entry primitive — reuse beats adding a field; routing through the
funnel-scoped check keeps the relaxation from widening the input-trust surface. **Serves:** AC "on_start
заводить підписника у крок, на який указує ребро стартового вузла". **Alternatives:** new
`Funnel.onStartEntryStepId` field (rejected — extra round-trip surface for no gain); keep `array[0]` start
(rejected — contradicts AC); accept any id on `on_start` (rejected — A04 input-validation regression).

### Decision 6: Per-node coordinates as additive nullable fields, distinct name `canvasPosition`
**Decision:** `canvasPosition {x,y}` (nullable, embedded) on `FunnelStep` and `Trigger`; notes coords inside
`notes[]`. Name is `canvasPosition`, not `position`. **Rationale:** `position` already means step **order**
(array index) across the codebase; nullable means no migration (missing field → `null` → auto-layout).
**Serves:** US "Ограничения" (additive nullable), AC "canvasPosition round-trip", "жодної міграції".
**Alternatives:** `position`/`layout`/`nodePosition` (rejected — `position` collides; `canvasPosition` is
unambiguous).

### Decision 7: Notes live outside `steps[]` in `Funnel.notes[]`
**Decision:** A note (`{id, text, canvasPosition}`) is stored in a separate `Funnel.notes[]` array, never in
`steps[]`. **Note `id` is server-minted** (new `ObjectId().toHexString()` when the incoming id is null,
preserved otherwise — mirroring `FunnelService.toSteps` step-id minting); the frontend sends `id: null` for a
fresh note. **Note `text` is rendered as escaped text via `{{ }}` — never `v-html`** (matches the existing
strict no-`v-html` guard in `FunnelMessagePreview`/`FunnelStepForm`; prevents stored XSS). **Bounds:**
`text` is `@Size`-capped and `notes[]` has an array cap with a service-side re-check (mirroring the
`MAX_TRIGGERS` DoS guard). **Rationale:** the engine iterates `steps[]`; keeping notes outside guarantees the
engine never executes them — cheaper and safer than teaching the engine to skip a note `StepType`.
**Serves:** AC "Замітка не виконується рушієм". **Alternatives:** a `NOTE` step type ignored by the engine
(rejected — pollutes the execution path and step validation); client-supplied note id (rejected — id shape
must be server-controlled like steps).

### Decision 8: No migration — additive nullable only
**Decision:** No migration framework, no backfill for the new fields. **Rationale:** no mongock/flyway exists;
nullable embedded fields read as `null` on old docs; prod data is wiped manually on deploy (no prod yet).
**Serves:** US "Ограничения" (no migration), AC "застосунок стартує без помилки на воронках без координат".
[TECHNICAL] — derived from existing schema-evolution posture, no user requirement dictates the mechanism.

### Decision 9: Draw-to-connect via typed output handles + auto-clean on delete
**Decision:** Each node exposes typed output handles (one default `next`, one per callback button, one
timeout, start/trigger entry); dragging from a handle to a target writes the matching field. Deleting a node
auto-cleans all inbound edges. **Rationale:** removes the manual `SearchableSelect` target pickers and keeps
the graph consistent. **Serves:** US "Как должно работать" §4,7, AC "Намальоване ребро проставляє правильне
поле", "Видалення вузла авто-чистить". **Alternatives:** keep `SearchableSelect` for targets (rejected —
defeats the visual editor).

### Decision 10: Reuse existing field editors; palette = existing types only
**Decision:** Reuse `FunnelStepForm` / `FunnelTriggerSettings` in the side panel; the only edit to
`FunnelStepForm` is a new opt-in boolean prop (e.g. `hideTargetPickers`, default `false`) that conditionally
hides the target `SearchableSelect`s (button `targetStepId`, timeout target, SUBSCRIBE entry) — the form's
field-editing logic and emitted `FunnelStep` are otherwise untouched, so the list and dialog callers stay
identical. The drawn edges own those targets on canvas. Palette offers exactly the 9 existing step types +
trigger + note. **Rationale:** #18 adds a graph layer, not new field editors; an opt-in prop avoids forking a
1757-line component; new executable types stay in `12-nice-to-have`.
**Serves:** US "Как должно работать" §5,6, AC "Палітра містить рівно наявні типи", "вибір таргетів через
SearchableSelect відсутній". **Alternatives:** rewrite editors for the canvas (rejected — scope creep, risk).

### Decision 11: Optional start node; cross-funnel SUBSCRIBE as exit badge
**Decision:** Render the "Початковий Крок" start node only when the funnel has an `on_start` entry; render
cross-funnel `SUBSCRIBE_TO_FUNNEL` as an **exit badge**, not an edge. **Rationale:** an event-only funnel has
no start node; the SUBSCRIBE target lives in another funnel, outside this graph. **Serves:** US "Как должно
работать" §2,11, AC "Стартовий вузол опційний", "Cross-funnel показано бейджем-виходом". **Alternatives:**
always show a start node (rejected — wrong for event-only funnels); draw a cross-funnel edge (rejected —
target node isn't on this canvas).

### Decision 12: Vue Flow rendered client-only under Nuxt SSR
**Decision:** Render the canvas client-only (`*.client.vue` and/or `<ClientOnly>`), import
`@vue-flow/core/dist/style.css`; tune `vite.transpile`/`optimizeDeps` if needed. **Rationale:** Vue Flow
touches `window`/DOM at setup → SSR throws `window is not defined`; the app is SSR-on with no existing
client-only precedent. **Serves:** Risk 1, AC "SSR не падає". [TECHNICAL] — integration constraint of the
chosen library. **Alternatives:** disable SSR app-wide (rejected — over-broad); SSR the canvas (rejected —
not possible with Vue Flow).

## Data Models

**Backend (Java, embedded — no `@Document`, no `_class`):**
```
// FunnelStep.java, Trigger.java — new nullable embedded field
CanvasPosition { Double x; Double y; }   // immutable record; reference-copied in FunnelStep.copyOf
FunnelStep.canvasPosition : CanvasPosition | null
Trigger.canvasPosition    : CanvasPosition | null

// Funnel.java — new nullable top-level array
Funnel.notes : List<Note> | null
Note { String id; String text; CanvasPosition canvasPosition; }
```

**DTOs (records, `@JsonIgnoreProperties(ignoreUnknown=true)`):**
```
FunnelStepDto   += CanvasPositionDto canvasPosition
TriggerDto      += CanvasPositionDto canvasPosition
UpdateFunnelRequest += @Valid @Size(max=MAX_NOTES) List<NoteDto> notes   // service re-checks cap (DoS guard, mirrors MAX_TRIGGERS)
FunnelResponse  += List<NoteDto> notes
CanvasPositionDto { Double x; Double y; }   // finite-value validation (reject NaN/Infinity)
NoteDto { String id; @Size(max=NOTE_TEXT_MAX) String text; CanvasPositionDto canvasPosition; }   // id server-minted (null on create)
```
Bounds rationale (security): `notes[]` array cap + `text` length cap prevent payload-size DoS; `id` is
server-minted (never trusted from the body); coordinates must be finite Doubles.

**Frontend (`frontend/types/funnel.ts`):**
```
interface CanvasPosition { x: number; y: number }
FunnelStep    += canvasPosition?: CanvasPosition | null
FunnelTrigger += canvasPosition?: CanvasPosition | null
interface FunnelNote { id?: string | null; text: string; canvasPosition?: CanvasPosition | null }
Funnel/FunnelResponse += notes?: FunnelNote[] | null
UpdateFunnelRequest   += notes?: FunnelNote[] | null
```

Edge-field semantics (unchanged backend contract; the canvas writes these):
| Edge (handle) | Model field | "no edge" |
|---|---|---|
| default | `FunnelStep.next` | `null` |
| callback button | `Button.targetStepId` | `null` (= End) |
| timeout | `FunnelStep.timeoutTargetStepId` | `null` (= End) |
| start / trigger entry | `Trigger.entryStepId` | `null` (broken edge → 422 on activate) |
| cross-funnel SUBSCRIBE | `targetEntryStepId` (other funnel) | rendered as exit badge, not edge |

## Dependencies

### New packages
- `@vue-flow/core` — Vue 3 graph/flow editor (nodes, edges, handles, pan/zoom, drag).
- `@dagrejs/dagre` — directed-graph auto-layout for nodes without stored coordinates.

### Using existing (from project)
- `funnelsStore` (`fetchOne`/`update`) + the editor page's debounced full-replace `persist()` — saving.
- `FunnelStepForm`, `FunnelTriggerSettings` — reused in the side panel unchanged.
- `FunnelMessagePreview` — optional lightweight node body / focused preview.
- `FunnelExecutionFactory.insertExecutionAt` — existing entry-at-step primitive reused for `on_start`.
- `@nuxt/test-utils` `mountSuspended` + `mockNuxtImport('useFunnelsStore')` — component tests.
- i18n parity gate (`scripts/check-locales.mjs` + `tests/i18n/required-keys.spec.ts`).
- Existing `@vue/devtools-api` Vite alias stub — precedent for bundler quirks.

## Testing Strategy

**Feature size:** L

Assertions check **concrete field values on the right model object** (and assert a sibling field that must
NOT change), not counts or key-presence — a mis-wired handle must fail the test.

### Unit tests (Vitest mapping layer — always)
- Model → Vue Flow nodes/edges: steps + optional start node + event-trigger nodes + notes produce the
  correct node set and edges; assert each edge writes the correct field with the correct id value (`next` /
  `Button.targetStepId` of the right button / `timeoutTargetStepId` / entry).
- Edge → model field mapping, including "no edge" serializes to `null` (not `""`), and sibling edge fields
  stay unchanged.
- dagre auto-layout runs when coordinates are absent; stored coordinates are used as-is; **mixed** (some
  nodes positioned, some null — e.g. a new step added to a laid-out funnel) lays out only the unpositioned
  nodes.
- **Cycle** (return-to-menu) maps to nodes+edges and serializes without infinite recursion.
- Delete a node → all inbound edges (`next`/button/timeout/`trigger.entryStepId`) auto-cleaned; disconnect
  **count** is exact and the surviving edges are unchanged.
- **Broken-edge detection**: an edge to a missing/deleted target, and an `on_start` trigger with
  `entryStepId == null` (start node present, edge deleted), are flagged as broken.
- Notes isolated from `steps[]` (a note never appears as a step in the PATCH payload).
- Add node/edge produces a correct PATCH payload (saved-node-only edge constraint respected).
- Cross-funnel SUBSCRIBE renders as an exit badge, not an edge.

### Component tests (Vitest, `mountSuspended` + `mockNuxtImport('useFunnelsStore')`)
- Broken-edge **highlight renders** for a dangling edge / start node missing its entry edge.
- Start-node **lifecycle**: not rendered for an event-only funnel; rendered when `on_start` exists; deleting
  it removes the `on_start` entry; deleting only its edge leaves a highlighted broken edge; re-adding
  `on_start` from the palette is disabled while it already exists.
- Side panel mounts `FunnelStepForm` with `hideTargetPickers` → target `SearchableSelect`s are absent; the
  emitted step still carries the existing fields.
- Note text containing `<img src=x onerror=...>` renders **escaped** (no `v-html`, no script execution).

### Integration tests (backend — targeted)
- **Round-trip** `canvasPosition` (step + trigger) and `notes[]` through PATCH ↔ read (forward mapper +
  reverse mapper + **`FunnelStep.copyOf`**, asserted separately — copyOf is its own silent-drop seam) —
  values return to the editor, not silently dropped; new note gets a server-minted id; app starts on
  coordinate-less funnels without error.
- **`FunnelExecutionEngineIT`** (slow-lane): `next == null` = end of branch (no fall-through to the array
  neighbour); `on_start` with `entryStepId` enters the target step regardless of `order`; `applyTriggers`
  accepts `entryStepId` on an `on_start` trigger **only when it resolves to a step of this funnel** and
  **rejects** it on a non-event trigger (negative case); existing `funnel_broken_edge` 422 still fires on
  activate with a dangling edge (regression); the **step-budget loop backstop** still terminates a cyclic
  funnel (Phase 2–3 regression, given the `advanceToNext` rewrite).

### E2E tests
- **None.** Vue Flow drag/zoom/connect is brittle under Playwright; canvas interactions are covered by the
  Vitest component tests above, live drag/zoom by manual user check. The existing golden-path Playwright
  (locale switch) remains.

## Agent Verification Plan

**Source:** user-spec "Как проверить" section.

### Verification approach
Automated: `pnpm test` (canvas component + mapping units), `./gradlew test` incl. slow-lane
`FunnelExecutionEngineIT` (round-trip + engine semantics), `pnpm lint && pnpm typecheck`,
`node scripts/check-locales.mjs` + `required-keys.spec.ts` (i18n parity, `funnels.canvas.*` non-empty in
uk/en), `pnpm build` (SSR/prebuild parity — canvas client-only must not throw `window is not defined`).
Per-task smoke checks are in each task's Verify-smoke. Live drag/zoom/pan and the end-to-end
webhook↔engine↔Telegram flow are **user-verified** in a browser at ≥1024px (no live environment for an agent
yet — there is no deploy). No post-deploy task: deployment does not exist yet (verified on the first
available environment by the user).

### Tools required
`pnpm` / `./gradlew` / `node` (bash). No Telegram MCP / Playwright MCP needed for agent verification (live
canvas + bot flow are manual). Optional Playwright MCP could load the editor page to confirm it renders, but
drag/connect remain manual.

## Risks

| Risk | Mitigation |
|------|-----------|
| Vue Flow under Nuxt SSR (`window is not defined` / hydration) | Render client-only (`*.client.vue` / `<ClientOnly>`), import `@vue-flow/core/dist/style.css`, tune `vite.transpile`/`optimizeDeps`; reuse the editor page's `import.meta.client` gate; precedent: `@vue/devtools-api` alias stub (Decision 12). |
| Silent loss of `canvasPosition`/`notes[]` in the reverse mapper (`@JsonIgnoreProperties`) | Add the field to record + **both** mappers + `copyOf`; cover with a round-trip integration test (this direction was historically caught by IT). |
| `next == null` currently falls through by array order, not "end" | Targeted engine change (Decision 4); cover with `FunnelExecutionEngineIT`. |
| Array order vs drawn graph divergence | After Decisions 4–5 array order no longer drives execution; canvas keeps stable insertion order only for serialization; engine ITs assert entry + branch-end are order-independent. |
| `testRun` would start at `array[0]`, diverging from the `on_start` entry (see Deviations) | Align `testRun` to honour the `on_start` entry step; covered by the engine/round-trip ITs. |
| Performance on large graphs | `only-render-visible-elements` (viewport culling) + lightweight node body (full `FunnelMessagePreview` only on focus); funnels are small, edges bounded (≤8 buttons + next + timeout). |
| Disconnected subflows / orphans | dagre lays out the full forest; canvas never assumes a single root and preserves orphans on save. |
| dagre throws on a malformed/degenerate graph | Wrap the layout call; on failure fall back to a simple grid/stacked placement so the canvas still renders (never a blank screen). |
| Stored XSS via note text | Render note text via `{{ }}` only (no `v-html`); component test asserts an injection payload renders escaped. |

## User-Spec Deviations

- **Added: `FunnelService.testRun` honours the `on_start` entry step** (not in user-spec; user-spec scoped
  the engine change to `FunnelTriggerServiceImpl.fire()`). **What user-spec says:** only `fire()` is listed as
  the `on_start` start path. **What tech-spec does:** also starts the "Test for me" enrollment at the
  `on_start` entry step instead of the hardcoded `array[0]` (`FunnelService.testRun`,
  `insertExecution(..., 0)` → entry-aware). **Why:** after Decision 4 (`next == null` = end of branch),
  test-run from `array[0]` would execute the wrong/empty branch whenever the `on_start` entry isn't the first
  array element, contradicting AC "Виконання відповідає намальованому графу" for the author's own test. Serves
  that AC. → **[APPROVED 2026-06-13]**

## Acceptance Criteria

Технические критерии приёмки (дополняют пользовательские из user-spec):

- [ ] `canvasPosition` (step + trigger) and `notes[]` survive a PATCH↔read round-trip (forward + reverse
      mapper + `copyOf`); no silent drop.
- [ ] Application starts without error on funnels with no stored coordinates (nullable fields, no migration).
- [ ] `FunnelExecutionEngine`: `next == null` ends the branch (no fall-through to the next array element).
- [ ] `on_start` enrolment (via `fire()`) and author test-run enter the step targeted by the start node's
      edge, independent of array `order`.
- [ ] `applyTriggers` accepts `entryStepId` on an `on_start` trigger **only when it resolves to a step of
      this funnel** (funnel-scoped `stepIds.contains` check, reusing `requireEventEntryStep`); still rejects
      it on other non-event types (negative case asserted).
- [ ] "No edge" serializes as `null`, never `""`; edges only target saved nodes (`id != null`).
- [ ] `pnpm build` passes (canvas client-only, no `window is not defined`); `pnpm lint && pnpm typecheck`
      clean.
- [ ] i18n parity holds: `funnels.canvas.*` present and non-empty in both uk.json and en.json
      (`check-locales.mjs` + `required-keys.spec.ts`).
- [ ] New-field bounds enforced: `notes[]` array cap + note `text` length cap (service re-check) + finite
      coordinates; a fresh note receives a server-minted id.
- [ ] Note text is rendered escaped (no `v-html`) — an injection payload does not execute.
- [ ] No regressions in existing tests (`pnpm test`, `./gradlew test`).

## Implementation Tasks

### Wave 1 (независимые)

#### Task 1: Backend additive persistence — canvasPosition + notes[] round-trip
- **Description:** Add nullable `canvasPosition {x,y}` to `FunnelStep` and `Trigger` and a new
  `Funnel.notes[]` array (`{id,text,canvasPosition}`), carried through domain accessors, `FunnelStep.copyOf`,
  the DTO records, and the forward (`toSteps`, trigger pass `applyTriggers`) and reverse (`toStepDto`,
  `toTriggerDtos`) mappers so saved values return to the editor. Note `id` is server-minted (ObjectId hex,
  like step ids); add bounds (note `text` `@Size`, `notes[]` array cap with service re-check, finite
  coordinates). Result: a PATCH with coordinates/notes round-trips back on read; app starts on
  coordinate-less funnels.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*FunnelServiceIT*' --tests '*FunnelControllerIT*'` → round-trip passes
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelStep.java`, `backend/src/main/java/com/botfunnel/funnel/Trigger.java`, `backend/src/main/java/com/botfunnel/funnel/Funnel.java`, `backend/src/main/java/com/botfunnel/funnel/dto/FunnelStepDto.java`, `backend/src/main/java/com/botfunnel/funnel/dto/TriggerDto.java`, `backend/src/main/java/com/botfunnel/funnel/dto/FunnelResponse.java`, `backend/src/main/java/com/botfunnel/funnel/dto/UpdateFunnelRequest.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`
- **Files to read:** `work/18-funnel-canvas/code-research.md` (§7), `backend/src/main/java/com/botfunnel/funnel/dto/ButtonDto.java`

#### Task 2: Frontend foundation — Vue Flow/dagre deps + types
- **Description:** Add `@vue-flow/core` + `@dagrejs/dagre` deps and the client-only/SSR plumbing
  (CSS import, `vite` tuning if needed); extend `frontend/types/funnel.ts` with `canvasPosition` on step and
  trigger and `notes[]` on funnel/response/update-request. Needed as the base every frontend task builds on.
  Result: types compile, `pnpm build` passes with the new deps installed.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm install && pnpm build` → builds clean (no `window is not defined`)
- **Files to modify:** `frontend/package.json`, `frontend/nuxt.config.ts`, `frontend/types/funnel.ts`
- **Files to read:** `work/18-funnel-canvas/code-research.md` (§3,§6,§8.1), `frontend/types/funnel.ts`

### Wave 2 (зависит от Wave 1)

#### Task 3: Backend execution semantics — null next = end, on_start explicit entry
- **Description:** Make the drawn graph the source of truth: `advanceToNext` treats `next == null` as
  end-of-branch (no fall-through to the next array element); in `applyTriggers` the `on_start` case validates
  `entryStepId` through the funnel-scoped check (`requireEventEntryStep` / `stepIds.contains`) instead of
  `requireNoEntryStep`, so it accepts an entry only when it resolves to a step of this funnel and still
  rejects it on non-event types; `fire()` and `testRun` start the `on_start` execution at the matched
  trigger's `entryStepId` via `insertExecutionAt` instead of step 0. `resolveStartCursor` untouched, no new
  field; the step-budget loop backstop must still terminate cyclic funnels. Result: array order no longer
  affects execution; engine ITs green.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*FunnelExecutionEngineIT*'` (slow-lane) → green
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerServiceImpl.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`
- **Files to read:** `work/18-funnel-canvas/code-research.md` (§7,§8.2), `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionFactory.java`

#### Task 4: Frontend model↔graph mapping layer
- **Description:** Pure composable/module mapping the funnel model (steps + triggers + notes) to Vue Flow
  nodes+edges and back: edge → correct model field (`next`/`Button.targetStepId`/`timeoutTargetStepId`/
  entry, "no edge" → `null` not `""`), dagre auto-layout when coordinates absent, broken-edge detection,
  delete-node edge auto-cleanup with disconnect count, notes isolation from `steps[]`. Fully unit-tested.
  Result: deterministic model↔graph conversion the canvas component consumes.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm test -- canvas` → mapping unit specs pass
- **Files to modify:** `frontend/composables/useFunnelCanvas.ts` (new), `frontend/tests/composables/useFunnelCanvas.spec.ts` (new)
- **Files to read:** `work/18-funnel-canvas/code-research.md` (§2 edge table,§8.3), `frontend/types/funnel.ts`

### Wave 3 (зависит от Wave 2)

#### Task 5: Canvas surface component (client-only)
- **Description:** Client-only Vue Flow canvas rendering nodes/edges from the mapping layer with typed output
  handles (default `next` / per-button / timeout / start-trigger entry), draw-to-connect writing model
  fields, broken-edge highlight, cross-funnel SUBSCRIBE exit badge, zoom/pan, and
  `only-render-visible-elements` culling with a lightweight node body. Result: a renderable, connectable
  canvas of an existing funnel at ≥1024px.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** open the funnel editor at ≥1024px → canvas renders (dagre layout, fitView), handles
  visible, an edge can be drawn between two saved nodes
- **Files to modify:** `frontend/components/funnels/FunnelCanvas.client.vue` (new), `frontend/components/funnels/FunnelCanvasNode.vue` (new), `frontend/tests/components/funnels/FunnelCanvas.spec.ts` (new)
- **Files to read:** `work/18-funnel-canvas/code-research.md` (§2,§8.1,§8.4), `frontend/components/funnels/FunnelMessagePreview.vue`

### Wave 4 (зависит от Wave 3)

#### Task 6: Authoring interactions — palette, start node, delete, side panel
- **Description:** Palette to add the 9 existing step types + trigger + note (new node persisted to mint
  `id` before wiring); optional `on_start` start node (add/remove maps to the `on_start` entry; removing only
  its edge leaves a broken edge; re-adding disabled when present); node delete with edge auto-cleanup +
  "N connections" warning; side panel reusing `FunnelStepForm` (via the new `hideTargetPickers` prop) /
  `FunnelTriggerSettings`, with the target `SearchableSelect`s hidden on canvas. Result: a fully authorable
  canvas.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** add a node from the palette, add/remove the start node, delete a node and see the
  disconnect warning, click a node and edit it in the side panel (no target dropdowns)
- **Files to modify:** `frontend/components/funnels/FunnelCanvasPalette.vue` (new), `frontend/components/funnels/FunnelCanvasSidePanel.vue` (new), `frontend/components/funnels/FunnelCanvas.client.vue`, `frontend/components/funnels/FunnelStepForm.vue`
- **Files to read:** `work/18-funnel-canvas/code-research.md` (§2), `frontend/components/funnels/FunnelTriggerSettings.vue`, `frontend/components/funnels/SearchableSelect.vue`

### Wave 5 (зависит от Wave 4)

#### Task 7: Editor page wiring + read-only list + i18n + component tests
- **Description:** Mount the canvas client-only in `funnels/[funnelId].vue`, wire `@node-drag-stop` →
  `canvasPosition`/`notes[]` → existing debounced `persist()`, make `FunnelStepsList` read-only, persist
  notes, and add `funnels.canvas.*` keys to both uk.json and en.json (parity + `required-keys`). Cover with
  `mountSuspended` + `mockNuxtImport('useFunnelsStore')` component tests. Result: the canvas is the live
  editing surface; list is view-only; locales parity holds.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm test && node scripts/check-locales.mjs && pnpm lint && pnpm typecheck` → all green
- **Verify-user:** move a node, reload → position persists; assemble "menu with buttons → branches",
  activate, run `/start` in the bot → flow matches the drawn graph
- **Files to modify:** `frontend/pages/projects/[projectId]/funnels/[funnelId].vue`, `frontend/components/funnels/FunnelStepsList.vue`, `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`, `frontend/tests/i18n/required-keys.spec.ts`, `frontend/tests/pages/funnel-editor.spec.ts`
- **Files to read:** `work/18-funnel-canvas/code-research.md` (§1,§5), `frontend/stores/funnels.ts`

### Audit Wave

#### Task 8: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified in this feature (from decisions.md + tech-spec "Files to modify"). Review holistically for cross-component issues: duplicate resource initialization, shared resources compliance with Architecture decisions, architectural consistency (canvas mapping vs canvas component vs page wiring). Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 9: Security Audit
- **Description:** Full-feature security audit. Read all source files created/modified in this feature. Analyze for OWASP Top 10 across all components, focusing on note text rendering (no XSS / no `v-html`), PATCH payload validation, and the relaxed `entryStepId` validation path. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 10: Test Audit
- **Description:** Full-feature test quality audit. Read all test files created in this feature (canvas mapping units, component tests, backend round-trip + engine ITs). Verify coverage, meaningful assertions, test pyramid balance (units carry canvas logic, ITs carry persistence + engine semantics, E2E intentionally absent). Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 11: Pre-deploy QA
- **Description:** Acceptance testing: run all tests (`pnpm test`, `./gradlew test` incl. slow-lane engine ITs, `pnpm lint`/`typecheck`/`build`, locale parity), verify acceptance criteria from user-spec and tech-spec. Defer live drag/zoom and the end-to-end Telegram flow to user verification (no live environment yet).
- **Skill:** pre-deploy-qa
- **Reviewers:** none
