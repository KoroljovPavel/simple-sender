# Code Research: 18-funnel-canvas (Phase 9, "Drag-and-drop visual funnel canvas")

Goal: an EDITABLE drag-and-drop canvas editor for funnels, built with Vue Flow (`@vue-flow/core`) +
`@dagrejs/dagre` auto-layout, rendering the EXISTING funnel graph model. It augments/replaces the current
vertical-list editor. The backend graph model already exists and is stable: server-minted step `id`, `next`
default edge, `Button.targetStepId` callback edges, timeout-target edges, Phase-8 `List<Trigger>` with
per-trigger `entryStepId` mid-graph entry, disconnected subflows allowed.

All paths absolute. Line numbers from the state read on 2026-06-13. Stack: Nuxt `^4.4.0` + Vue 3 + TS,
`@pinia/nuxt`, `@nuxtjs/i18n ^9.5.6`, Vitest `^3.2.4` + `@nuxt/test-utils ^3.19.2`, `pnpm@10.33.2`.
Backend: Java/Spring + MongoDB.

**HEADLINE for the user-spec interview:** the assumption "no backend change except node positions" is
ESSENTIALLY correct — positions are the single new persisted concept — BUT adding a nullable position field
is NOT a one-line change. It touches **5 backend spots** (domain field+accessors, `copyOf`, DTO record,
`toSteps` forward mapper, `toStepDto` reverse mapper). See §7. No migration framework exists, so a nullable
field needs no migration (§7.4). Field-NAME collision: "position" already means step ORDER (array index)
throughout the codebase — pick a distinct name (e.g. `canvasPosition` / `layout` / `nodePosition`).

---

## 1. Entry point: the funnel editor page

`/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/pages/projects/[projectId]/funnels/[funnelId].vue`
(526 lines). The single editor page — the thing the canvas plugs into.

- State (L32-49): `funnel: FunnelResponse|null`, `steps: FunnelStep[]`, `triggers: FunnelTrigger[]`,
  `loaded`, `saveError`, etc. The page OWNS the steps + triggers arrays; children are props-in/emits-out.
- Load (L152-167): `funnelsStore.fetchOne(funnelId)` → `applyResponse()` (L144-150) copies
  `res.steps`/`res.triggers` into local refs. `onMounted` is client-gated (`import.meta.client`, L170).
- **Full-funnel PATCH** (`persist()`, L192-212): sends `{ name, description, triggers (sanitized),
  allowReEnter, steps }` via `funnelsStore.update`. The ENTIRE steps array is sent on every save
  (full-replace, not a delta). "Position = order, so the server rewrites `FunnelStep.order` from the array
  index — the client never sends `order`" (L190-191). A canvas with a separate `{x,y}` would ride INSIDE
  each step object in this same array.
- Step mutators (L214-237): `onAddStep`/`onSaveStep`/`onDeleteStep`/`onMove` mutate the local `steps` array
  by INDEX, then `void persist()`. Editor is index-addressed end-to-end (the canvas, keyed by step `id`,
  would diverge from this — flag for interview).
- Trigger autosave (L244-265): deep `watch(triggers)` → debounced 600ms `persist()`, gated on every trigger
  being "ready". Re-entrancy guard `applyingResponse` (L143) suppresses the watch cycle caused by
  re-assigning from the server response.
- Activate/pause/duplicate/test-run/stop-all/preview-toggle wired in the header (L267-340).
- Children mounted (template L467-495): `<FunnelStepsList>` (vertical list), `<FunnelTriggersPanel>`
  (`v-model:triggers`), `<FunnelMessagePreview>` (preview panel), `<AddStepDialog>`/`<EditStepDialog>`.

---

## 2. Frontend components (`frontend/components/funnels/`)

For each: responsibility + how it reads/writes funnel state + which edge-bearing fields it touches.

### FunnelStepsList.vue (161 lines) — the vertical-list editor the canvas augments/replaces
- Props (L10): `steps: FunnelStep[]`, `selectedIndex?: number`. No store, no v-model.
- Emits (L11): `move:[from,to]`, `edit:[index]`, `delete:[index]`, `add:[]`, `select:[index]` — all by
  ARRAY INDEX, never by step id.
- Edge fields: touches NONE directly. Position-in-array = order = implicit `next`. Reorder emits
  `move(from,to)`; parent PATCHes the whole array. Order IS the edge model here — exactly what the canvas
  replaces with explicit drawable `next` edges.

### FunnelStepForm.vue (1757 lines) — the shared per-type step editor (where target pickers live today)
- Signature (L25-30): `defineProps<{ initial?: FunnelStep|null; submitLabel: string; siblingSteps?:
  FunnelStep[] }>()`, `defineEmits<{ submit:[step:FunnelStep]; cancel:[] }>()`. Builds a `FunnelStep`
  locally, `emit('submit', step)` (L1004). Reads `useFunnelsStore` only for the SUBSCRIBE target funnel
  list + lazy `fetchOne`.
- Handles all 9 step types (`STEP_TYPES`, L36); a `switch` in `onSubmit` (L902-1003) narrows the emitted
  step to the active type's fields.
- **Edge management (the dropdowns the canvas replaces with drag-edges):**
  - Buttons (L213-316, emit L919-927): local `ButtonRow[]` (`menuButtons`, L233); each row
    `{type:'callback'|'url', label, targetStepId, url}`. Max 8 (L80), ≥1 callback required. On submit
    `targetStepId = '__END__' ? null : id`; url buttons get `targetStepId:null`.
  - Timeout (L244-273, emit L929-934): `menuTimeoutValue`/`menuTimeoutUnit`/`menuTimeoutTarget`; emitted
    only when engaged; End sentinel → null.
  - `next`: **never edited** — only carried through as `props.initial?.next` (L914, 984, 999). The canvas
    is the FIRST UI that lets a user set `next` explicitly.
  - SUBSCRIBE (L498-560, emit L955-966): `subscribeTargetFunnelId` + `subscribeEntryStepId` +
    `endParentAfter`. Entry options async-loaded from the TARGET funnel (`watch → fetchOne`, L544).
  - Structural constraint (Decision 2): buttons/timeout attach ONLY when the last block is non-album
    (`buttonsAllowed`) — the canvas must respect which MESSAGE nodes can carry button edges.

### FunnelMessagePreview.vue (393 lines) — Telegram-look step preview
- Prop-in only (L16): `{ step: FunnelStep|null; stepNumber?: number|null }`; no emits. Reads store
  `preview` action, debounced 600ms (L136). READ-ONLY w.r.t. step state.
- Reads `step.buttons` (L99) only to render button LABELS as chips (L384) — does NOT show/edit
  `targetStepId`. Strict no-`v-html` / http(s)-only media guard. Reusable as a node-body renderer inside a
  canvas card; carries no edge UI.

### FunnelTriggersPanel.vue (278 lines) — Phase-8 multi-entry triggers list
- Props (L20): `triggers`, `steps`, `botUsername?`, `deepLink?`. Emits `update:triggers:[triggers]`
  (full-array replace), plus add/delete/select. Mounted as `v-model:triggers`. Owns `trigger.entryStepId`
  (L140 `onEntryChange`).
- Per-row `SearchableSelect` (L252) over `entryOptions` (this funnel's OWN steps, from the `steps` prop —
  not async). `ENTRY_START='__START__'` (L37) maps to the FIRST step's id (mid-entry is event-only; backend
  requires non-null entry step). Touches no next/buttons/timeout/targetFunnel.

### FunnelTriggerSettings.vue (305 lines) — portable per-trigger form
- `defineModel` only (L23-25): `triggerType`, `triggerValue`, `keywords`; props `botUsername`/`deepLink`/
  `lockType` (L18). Designed (Decision 11) to remount unchanged inside a future canvas node form. Edits
  trigger type/value/keywords only — `entryStepId` is owned by the parent panel, NOT here.

### SearchableSelect.vue (196 lines) — the reusable combobox under EVERY target/tag/field picker
- `v-model` (`modelValue: string` in, `update:modelValue` out, L13/33). Parent owns `options:
  {value,label,hint?}[]` + the fetch. `showValue` default true; target pickers pass `:show-value="false"`.
  Falls back to raw value if a stored id no longer matches an option (`selectedLabel`, L45) — relevant for
  DANGLING edges after a step delete. THIS is the widget the canvas drag-edges replace for `targetStepId` /
  `timeoutTargetStepId` / `targetEntryStepId` / `entryStepId`.

### AddStepDialog.vue (51) / EditStepDialog.vue (53) — thin modal wrappers around FunnelStepForm
- Add: props `open`, `siblingSteps?`; emits `update:open`, `add:[step]`. Edit: props `open`, `step`,
  `index`, `siblingSteps?`; emits `update:open`, `save:[index,step]`. Bump a `formKey` per open to remount
  the form clean. Relay the form's submit upward by INDEX.

### CreateFunnelDialog.vue (128) — create a draft funnel (metadata only)
- Props `open`, `projectId`; emits `update:open`, `created:[funnel]`. Writes directly via
  `funnelsStore.create` then `navigateTo` the editor. No step/edge fields — not relevant to the canvas.

### Edge-type inventory (what the canvas must own)
| Edge | Frontend field | Current UI | Null/sentinel semantics |
|------|---------------|-----------|------------------------|
| default next | `FunnelStep.next` | NONE (implicit list order) | `null` = fall through to next step in list |
| button callback | `Button.targetStepId` | SearchableSelect, sentinel `__END__` | `null` = End funnel |
| message timeout | `FunnelStep.timeoutTargetStepId` (+`timeoutValue`/`timeoutUnit`) | SearchableSelect, `__END__` | `null` = End funnel |
| trigger entry | `FunnelTrigger.entryStepId` | SearchableSelect, `__START__` | maps to FIRST step id (event-only) |
| cross-funnel | `targetFunnelId` + `targetEntryStepId` (SUBSCRIBE) | 2 SearchableSelects, `__START__` | points into ANOTHER funnel |

**Load-bearing:** "no edge drawn" must serialize to `null`, NOT `""`. An empty-string id 422s as
`funnel_broken_edge`. Edges require SAVED nodes (`id != null`); the form already filters unsaved steps out
of target options (`menuTargetOptions`, FunnelStepForm L283). The canvas inherits this: you cannot draw an
edge to an unsaved node, so node creation must persist (mint id) before it can be wired.

---

## 3. Frontend types (`frontend/types/funnel.ts`, 287 lines)

Mirrors backend DTOs. Edge-bearing fields and per-type fields:

- `FunnelStep` (L125-166). Server-minted `id?: string|null` (L158, null until first save). Edge fields:
  `next?: string|null` (L159, default edge); `buttons?: Button[]|null` (L160); `timeoutValue?`/
  `timeoutUnit?: DelayUnit` / `timeoutTargetStepId?: string|null` (L161-165); cross-funnel `targetFunnelId?`
  / `targetEntryStepId?` / `endParentAfter?` (L140-142). Per-type: MESSAGE→`blocks` (L128); DELAY→
  `delayValue`/`delayUnit`; tag→`tagSlug`; field→`customFieldKey`/`customFieldValue`; EMIT_EVENT→`eventName`;
  keyboard→`keyboardText`/`keyboardParseMode`/`keyboardRows`/`isPersistent`/`oneTimeKeyboard` (L150-154).
  **No `order` field** — array position IS the order. **NO position/x/y field** (see §4).
- `Button` (L56-61): `type:'callback'|'url'`, `label`, `targetStepId?`, `url?` (targetStepId/url mutually
  exclusive by type).
- `FunnelTrigger` (L44-49): `triggerType`, `triggerValue?`, `keywords?`, `entryStepId?`.
- `StepType` (L12-24): MESSAGE | DELAY | ADD_TAG | REMOVE_TAG | SET_CUSTOM_FIELD | EMIT_EVENT |
  SUBSCRIBE_TO_FUNNEL | SET_KEYBOARD | CLEAR_KEYBOARD.
- `UpdateFunnelRequest` (L213-219) — the PATCH body: `{ name?, description?, triggers?, allowReEnter?,
  steps? }`. A canvas position would be a new optional field inside each `FunnelStep`.
- `FunnelResponse` (L186-201): full funnel (metadata + steps + triggers + deepLink).

---

## 4. Existing node-position / layout / x-y storage — NONE (confirmed both sides)

- Frontend: `grep` of `position|layout|coordinate|x:|y:` over `frontend/types/` hits only comments (L19,
  L120) about "position in the steps array IS the order". No `{x,y}` anywhere.
- Backend: `grep -rniE "position|layout|coordinate|nodeX|nodeY"` over `backend/src/main/java` → only
  comments ("steps position IS the order", "park on the new position") and unrelated identifiers
  (`CONTENT_DISPOSITION`, `lastButtonClicked...coordinate`). **No position/layout/x/y field on `Funnel`,
  `FunnelStep`, or any DTO.** As expected — positions are the single new persisted field.
- **NAME-COLLISION WARNING:** "position" already means array-index/step-order throughout. Use a distinct
  field name (`canvasPosition` / `layout` / `nodePosition`) to avoid confusion.

---

## 5. i18n pattern + Vitest test patterns for the editor

### i18n
- Locale files: `frontend/i18n/locales/en.json` (34.6 KB), `frontend/i18n/locales/uk.json` (48.8 KB). Config
  in `nuxt.config.ts` L70-95: locales uk (default) + en, `langDir: 'locales'`, `strategy:
  prefix_except_default`. Browser-language detection disabled under Vitest (L84).
- Existing `funnels.*` namespace (en.json): `funnels.title`, `funnels.createButton`, `funnels.createDialog`,
  `funnels.deleteConfirm`, `funnels.filter`, `funnels.status.{draft,active,paused}`, `funnels.emptyState`,
  `funnels.form`, `funnels.editor.*` (~20 keys: stepsTitle/saving/activate/pause/duplicate/preview/stopAll/
  preview* …), `funnels.steps.*` (~150+ keys: add/edit/delete/dialog/form/validation …), `funnels.trigger.*`,
  `funnels.triggersPanel.*` (mainEntry/eventTriggers/entryLabel/entryStart …), `errors.funnels.*`
  (create/update/funnel_no_steps/funnel_broken_edge/funnel_subscribe_* …). Canvas keys would go under e.g.
  `funnels.canvas.*` in BOTH files.
- **PARITY GATE — two layers:**
  1. Build gate: `package.json` `"prebuild": "node scripts/check-locales.mjs"` (runs before `nuxt build`).
     `frontend/scripts/check-locales.mjs` `checkLocales()` (L47-58) flattens both files to key sets
     (`collectKeySet`) and exits non-zero if uk/en key sets differ (L72-81). Key-SET equality only (empty
     `""` counts as present).
  2. Test gate: `frontend/tests/i18n/required-keys.spec.ts` — a hardcoded `REQUIRED_KEYS` array (L34+);
     asserts each is present in BOTH uk.json and en.json (L98-111); additionally asserts NON-EMPTY string
     values for a subset (e.g. `funnels.triggersPanel.*`, L113-135, via `loadValues`). New canvas keys that
     must render visible text should be added to `REQUIRED_KEYS`.
- NO CI workflow exists (`.github/workflows/` absent) and NO pre-commit/husky hooks — the parity gate is
  enforced only via `pnpm prebuild` and `vitest`.

### Vitest editor/component tests
- Config: `frontend/vitest.config.ts` — `defineVitestConfig({ test: { environment: 'nuxt', globals: true,
  include: ['tests/**/*.{spec,test}.ts'] } })`. Runner: `pnpm test` → `vitest`.
- Funnel specs present:
  - `frontend/tests/pages/funnel-editor.spec.ts` (1162 lines) — the editor page mount spec. Setup
    (L1-60): `mountSuspended` from `@nuxt/test-utils/runtime`; store mocked via
    `mockNuxtImport('useFunnelsStore', () => () => storeMock)` where `storeMock` is a `vi.hoisted()` object
    of `vi.fn()`s (fetchOne/update/activate/pause/duplicate/stopAllExecutions/testRun/preview/fetch) + a
    reactive `funnels` ref; `mockNuxtImport('navigateTo', () => navMock)`; `vi.mock('vue-sonner', () => ({
    toast: toastMock }))`. Teleport stubbed inline (L57-59).
  - `frontend/tests/components/funnels/FunnelStepForm.{keyboard,composer}.spec.ts`,
    `FunnelMessagePreview.spec.ts`, `FunnelTriggersPanel.spec.ts`, `CreateFunnelDialog.spec.ts`;
    `frontend/tests/components/FunnelStepsList.spec.ts`, `FunnelTriggerSettings.spec.ts`.
  - `frontend/tests/stores/funnels.spec.ts`, `frontend/tests/types/funnel-blocks.spec.ts`.
  - E2E: `frontend/e2e/funnels.spec.ts` (Playwright, `test:e2e`; skips without a live backend).
- Component pattern (e.g. FunnelTriggersPanel.spec.ts): `mountSuspended(Component, { props, attachTo:
  document.body })` → `await settle()` → assert on `wrapper.emitted('update:triggers')`. Representative:
  `it('adds an event trigger')` clicks `[data-test="funnel-trigger-add"]`, asserts the emitted array grows
  to 2 and `next[1].entryStepId === 's1'`. CreateFunnelDialog.spec.ts:
  `it('submits and navigates')` asserts `createMock` called with payload and `emitted('update:open')`.
- Helper: `frontend/tests/helpers/settle.ts` — flushes promises + ~50ms macrotask wait + `nextTick()`
  (covers vee-validate/zod async validation). Canvas/async-layout tests may need an extended settle.
- **NO existing canvas/graph/vue-flow test anywhere** (greenfield).

---

## 6. Pre-existing Vue Flow / graph-library / canvas usage — NONE

- `package.json`: no `@vue-flow/*`, `@dagrejs/dagre`, `dagre`, `d3`, or any graph lib. `node_modules` has no
  `@vue-flow`/`@dagrejs`/`dagre`. Both are NEW dependencies.
- NO `<ClientOnly>` usage and NO `*.client.vue` files anywhere in the frontend (grep over `**/*.vue`
  returned zero). This pattern would be introduced fresh for the canvas (see §9).
- `nuxt.config.ts` has NO `ssr: false` — the app is SSR-on. DevTools disabled (L24); a Vite alias stubs
  `@vue/devtools-api` (L34-42) — relevant since Vue Flow / its deps may also poke devtools.

---

## 7. Backend: where a per-step position field would go

### 7.1 FunnelStep domain
`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/FunnelStep.java`
— a flat EMBEDDED persistence POJO inside `Funnel.steps` (NOT a `@Document`; no class-level annotations,
plain `public class FunnelStep` L21, Decision 12 = no `_class`). JavaBean (explicit get/set per field).
Fields (L23-88): `StepType stepType` (L23), `int order` (L24); graph `String id` (L28), `String next`
(L29), `List<Button> buttons` (L34), `Integer timeoutValue` (L35), `String timeoutUnit` (L36), `String
timeoutTargetStepId` (L37); `List<ContentBlock> blocks` (L43); delay/tag/field/event/subscribe/keyboard
fields (L46-88). **A new nullable `position` would be added here** as an embedded sub-object (e.g. a
`StepPosition` record `Double x, Double y`) + getter/setter.

### 7.2 FunnelStep.copyOf — MUST carry the new field
`FunnelStep.java:116-154`. Deep-copy snapshot (used by duplicate / execution snapshot). Enumerates EVERY
scalar by assignment (`copy.id = source.id;` …) + defensively copies the three mutable lists. **A new
`position` field will NOT be copied unless explicitly added here.** If `position` is an immutable record,
a reference copy (`copy.position = source.position;`) suffices. (For the execution snapshot, layout coords
are dead weight but harmless — carrying them keeps the deep-copy contract total.)

### 7.3 Step-id minting — ObjectId hex
`FunnelService.toSteps` L940: `step.setId(incomingId != null ? incomingId : new
org.bson.types.ObjectId().toHexString());` — preserves a client-supplied id, mints a new ObjectId hex for
new steps, rejects duplicate ids (L937-938). Legacy backfill: `FunnelStepIdBackfill.java` (startup
`ApplicationRunner`, idempotent `$set` of ObjectId hex). Implication for canvas: a newly-dropped node has
NO id until first PATCH; the response echoes the minted id, which the editor then uses for edges.

### 7.4 DTO + tolerant read
`backend/.../funnel/dto/FunnelStepDto.java` — a `record` (L27) with `@JsonIgnoreProperties(ignoreUnknown =
true)` (L26). Only `@NotNull StepType stepType` bean-validated. Fields (L27-61) mirror the frontend type
(stepType, id, next, buttons, timeout*, blocks, delay*, tag/field/event/subscribe/keyboard). `order`
intentionally absent. **Because of `ignoreUnknown=true`, a `position` sent today is SILENTLY DROPPED** — the
field must be added to the record to round-trip.
- `StepType` enum has an `UNKNOWN` sentinel; tolerant Mongo READ via `StepTypeReadConverter`
  (`StepType.valueOf` in try/catch → `UNKNOWN` on bad value) so a corrupt/legacy step type never crashes the
  read/sweep; author input is rejected separately in `validateSteps` (`case UNKNOWN -> throw`, 422). Not
  needed for a position field, but confirms the codebase's tolerant-read posture.

### 7.5 PATCH endpoint + update DTO
`backend/.../funnel/FunnelController.java`: `@PutMapping("/{funnelId}") update(...)` (L64-69) and
`@PatchMapping("/{funnelId}") patch(...)` (L71-76) BOTH take `@Valid @RequestBody UpdateFunnelRequest` and
call `funnelService.update(currentUserId(), projectId, funnelId, request)`. `UpdateFunnelRequest` (record,
`@JsonIgnoreProperties(ignoreUnknown=true)` L25): `@Size(max=128) name`, `@Size(max=1024) description`,
`Boolean allowReEnter`, `@Valid @Size(max=MAX_TRIGGERS) List<TriggerDto> triggers`, `@Valid
List<FunnelStepDto> steps`. **Full-replace** — the whole steps array is authoritative each save, so
positions ride inside each `FunnelStepDto`. No new endpoint needed.

### 7.6 DTO→domain mapping is EXPLICIT (silent-drop risk both directions)
`FunnelService.toSteps` (L920-969) maps field-by-field explicitly (`setStepType`/`setId`/`setNext`/… through
L965). The REVERSE mapper `toStepDto` (~L1588-1614) is also explicit positional `FunnelStepDto`
construction. **A position field requires edits in BOTH** or the saved position never returns to the editor
(comment L1607-1609 warns about exactly this historically-missed reverse direction, caught by a round-trip
integration test). Optional bounds-validation would go in `validateSteps`.

### 7.7 Migration — none needed
`backend/build.gradle` has NO mongock/flyway/liquibase/mongobee. Schema evolution is ad-hoc startup
`ApplicationRunner` backfills. A NULLABLE `position` needs no migration: existing docs read `position ==
null` (missing embedded field → null), and the frontend auto-layouts those nodes. Matches Decision 4
("manual wipe, no prod").

### Backend touchpoint summary (flags "more than a trivial nullable add")
Adding per-step position requires **5 edits**: (1) `FunnelStep` field + accessors, (2) `FunnelStep.copyOf`,
(3) `FunnelStepDto` record component, (4) `FunnelService.toSteps` forward mapper, (5) `FunnelService.toStepDto`
reverse mapper. Plus a recommended round-trip IT. Everything else (no `@Document` change, no index, no
migration, tolerant read already present) is genuinely trivial. NOTHING contradicts "no backend change
except node positions" — the change stays confined to carrying an extra nullable sub-object through the
existing step graph.

---

## 8. Risks / feasibility

### 8.1 Vue Flow under Nuxt 4 SSR — the integration gotcha
Vue Flow is a browser-only component (touches `window`/DOM at setup); under SSR it throws `window is not
defined` / hydration mismatches. App is SSR-on (`nuxt.config.ts`, no `ssr:false`). Mitigations (Context7
`/websites/vueflow_dev`): wrap `<VueFlow>` in `<ClientOnly>` OR make the canvas a `*.client.vue` component
(neither pattern exists in the repo yet — §6). Required CSS imports: `@vue-flow/core/dist/style.css` (+
optional `theme-default.css`). May need `vite.transpile`/optimizeDeps tuning; note the existing
`@vue/devtools-api` Vite alias stub (§6) as precedent that this stack already special-cases bundler quirks.
The `onMounted`-client-gate pattern already in the editor page (L170) is the existing convention for
client-only init.

### 8.2 Disconnected-subflow rendering — editor MUST NOT assume a single root
The Phase-8 model allows orphan components / disconnected subflows (multiple trigger `entryStepId`s, draft
funnels mid-edit). Vue Flow + dagre handle a forest, but the auto-layout call must run on ALL nodes/edges,
not from one root. Trigger entries (§2 table) are entry points into mid-graph steps, so there can be several
"roots". The canvas must render every step node even if unreachable from step 1, and must not delete/hide
orphans on layout. Dagre handles disconnected components but lays them out as separate clusters — fine for
display; the editor must preserve orphans on save (they're valid drafts).

### 8.3 Persisting positions on drag — Vue Flow API
Bind `onNodeDragStop` (or `@node-drag-stop`) from `useVueFlow()` to capture the final `{x,y}` and write it
back into the corresponding `FunnelStep`, then reuse the existing debounced `persist()`. Auto-layout via the
`useLayout` composable wrapping `@dagrejs/dagre` (`graph.setNode/setEdge` → positions); fit with `fitView()`
after `nodes-initialized`. For nodes with no stored position (null from backend), run dagre on mount; for
nodes WITH a stored position, render as-is.

### 8.4 Performance for large graphs
Vue Flow re-renders all nodes/edges on viewport changes; for large funnels enable
`only-render-visible-elements` (viewport culling) and avoid heavy per-node components (the
FunnelMessagePreview render is expensive — consider a lightweight node body, lazy-render full preview only
on focus). Edge count is bounded by buttons (≤8/step) + next + timeout, so edge density is modest. Funnels
are small-to-medium author artifacts (not thousands of nodes), so perf risk is low but
`only-render-visible-elements` + memoized node components are cheap insurance.

### 8.5 Other flags for the interview
- Index-vs-id addressing mismatch: today's editor is index-addressed (list/dialogs/PATCH by array index);
  a canvas is id-addressed. Reconcile how reorder / `order` is derived when there's no list (positions don't
  imply order — the backend still rewrites `order` from array index, so the canvas must decide the steps
  ARRAY order independently of x/y, e.g. keep insertion order or sort by y).
- Dangling edges after delete: `SearchableSelect.selectedLabel` (L45) already shows raw id for a missing
  target; the canvas should visually flag an edge pointing at a deleted step (backend 422s
  `funnel_broken_edge` on activate).
- Null serialization: "no edge" = `null` not `""` (else 422). Edges only to saved nodes (`id != null`).
- Cross-funnel SUBSCRIBE edge (`targetEntryStepId`) points into ANOTHER funnel — likely NOT drawn on this
  canvas (out-of-graph); decide whether to render it as a terminal "exit" badge.
