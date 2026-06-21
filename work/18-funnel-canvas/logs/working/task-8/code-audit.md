# Task 8 — Code Audit Report: 18-funnel-canvas

Holistic full-feature audit of the FINAL state across all 7 implementation tasks (full files, not diffs),
looking for cross-component issues that per-task reviews miss. Machine-readable copy:
[../audit/code-auditor.json](../audit/code-auditor.json).

**Verdict: issues_found** — 2 major, 2 minor. No blockers, no crashes. Finding #1 is a real persistence
regression that should be fixed before sign-off.

## Files reviewed (final state, full read)

Backend: FunnelStep.java, Trigger.java, Funnel.java, CanvasPosition.java, Note.java, FunnelService.java,
FunnelExecutionEngine.java, FunnelTriggerServiceImpl.java, dto/{FunnelStepDto, TriggerDto, FunnelResponse,
UpdateFunnelRequest, CanvasPositionDto, NoteDto}.java
Frontend: types/funnel.ts, composables/useFunnelCanvas.ts, components/funnels/{FunnelCanvas.client.vue,
FunnelCanvasNode.vue, FunnelCanvasPalette.vue, FunnelCanvasSidePanel.vue, FunnelStepForm.vue,
FunnelStepsList.vue, FunnelTriggersPanel.vue}, pages/.../funnels/[funnelId].vue, nuxt.config.ts

## Dimension results

### Shared resources (Architecture → Shared resources table) — CLEAN
- `useVueFlow()` — exactly ONE instance, owned by `FunnelCanvas.client.vue:133`. Palette / side panel / page
  wiring are pure props-in/emits-out consumers; a repo-wide grep finds no second `useVueFlow()`. Node-drag is
  relayed via an emit (not a second instance). Contract satisfied.
- dagre — transient, `new dagre.graphlib.Graph()` constructed inside `dagreLayout` per `layoutNodes` run
  (`useFunnelCanvas.ts:333`). No module-level singleton; lives only in the mapping layer. Contract satisfied.

### Backend round-trip (5 silent-drop seams) — CLEAN
`canvasPosition` (step + trigger) and `notes[]` are threaded through every seam:
domain + accessors (FunnelStep/Trigger/Funnel + CanvasPosition/Note records); `FunnelStep.copyOf` (reference
copy, line 161); DTO records (FunnelStepDto/TriggerDto/NoteDto + CanvasPositionDto with @AssertTrue finite
guard); forward mappers (`toSteps` 1082, `applyTriggers` 783, `applyNotes` 832); reverse mappers (`toStepDto`
1763, `toTriggerDtos` 1705, `toNoteDtos` 1717). `toCanvasPosition`/`toCanvasPositionDto` null-safe both ways.
Notes get server-minted ObjectId (NOTE_ID_PATTERN strict-reject), MAX_NOTES + NOTE_TEXT_MAX service re-check,
seenIds dup guard. `Trigger.canvasPosition` correctly EXCLUDED from equals/hashCode (preserves the
17-funnel-multi-entry redirect re-scan). `duplicate()` intentionally omits notes (documented).

### Engine semantics coherence — CLEAN
`advanceToNext` (FunnelExecutionEngine.java:493) is `graphMode`-gated: graph run with `next==null` → End
marker (currentStepId=null), no array fall-through; legacy index-drain run (no step ids) keeps +1 advance.
`on_start` entry enters via `FunnelService.onStartEntryStepId` + `insertExecutionAt` in both `fire()`
(FunnelTriggerServiceImpl.java:172-183) and `testRun` (FunnelService.java:472, approved deviation).
`onStartEntryStepId` is the single canonical package-private helper; the impl delegates to it (no duplicate).
on_start entryStepId validation is funnel-scoped (`requireOnStartEntryStep`, null-tolerant; non-event types
still `requireNoEntryStep`).

### Edge semantics across layers — CLEAN
mapping layer (`useFunnelCanvas.ts`) ↔ node handles (`FunnelCanvasNode.vue` ids next/btn:<i>/timeout/entry) ↔
connect resolver (`FunnelCanvas.resolveEdgeRef`) ↔ model fields all agree: default→next,
button→Button.targetStepId (keyed by index among CALLBACK buttons only), timeout→timeoutTargetStepId,
start/trigger→Trigger.entryStepId, cross-funnel SUBSCRIBE→exitBadge (not an edge). "No edge" is strict `null`
(never `""`) on connect/disconnect/build; `""` is surfaced as a broken edge, not a real edge.

### Naming / dead code / XSS-surface / error handling — CLEAN
`canvasPosition` everywhere (never `position` for coords). Page-level Add/Edit dialogs + handlers correctly
removed by Task 7 (no leftover dead code, no TODO/FIXME). Note text rendered via `{{ }}` in
FunnelCanvasNode (never v-html). nuxt.config CSS plumbing correct (`@vue-flow/core/dist/style.css` global +
re-imported in the component, Vite dedups). Read-only FunnelStepsList gates all structural affordances behind
`v-if="!props.readonly"` while keeping the `select` emit.

## Findings

### MAJOR-1 — canvasPosition dropped on a side-panel step edit
`frontend/components/funnels/FunnelStepForm.vue:911` (onSubmit). The form rebuilds the step per stepType and
re-attaches only `id` + `next` from `props.initial` (925-926, 995-996, 1010-1011); it has zero references to
`canvasPosition`. A canvas side-panel edit (`FunnelCanvasSidePanel.onStepSubmit` →
`FunnelCanvas.onPanelStepSubmit:286`, full array-element replace, no merge) therefore persists the step with
`canvasPosition=undefined` → the manually-placed node position is lost on every field edit (degrades to dagre
auto-layout on next read). This is the same silent-drop class the trigger sanitizer bug-fix in Task 7 closed,
still live on the step-edit path. No test covers it (drag-stop persistence is tested; a post-form-submit
position check is not).
**Fix:** carry `canvasPosition: props.initial?.canvasPosition ?? undefined` into each rebuilt step literal, OR
merge it from the prior element in `onPanelStepSubmit`; add a side-panel-edit regression test.

### MAJOR-2 — triggers editable in two surfaces (vs Decision 2 "single editing surface")
`frontend/pages/.../[funnelId].vue:523`. The page mounts the canvas (with its FunnelTriggerSettings side panel)
AND a still fully-editable `FunnelTriggersPanel` (`v-model:triggers`, add/edit/delete). Decision 2 made only
`FunnelStepsList` read-only; the trigger panel was left two-way editable. Both write the same `triggers` ref
(no structural model desync, and `FunnelTriggersPanel.replaceAt` spreads the element so canvasPosition/entry
are preserved), but there are now two competing trigger-editing UIs with two save timings (canvas → direct
synchronous `persist()`; panel → 600ms debounce). This is the "two editable surfaces drift / confusing dual
UI" state Decision 2 set out to avoid.
**Fix:** decide intent explicitly — either keep the panel and align the spec wording (graph/steps are
canvas-only; triggers are panel-edited), or make `FunnelTriggersPanel` read-only like the steps list and let
the canvas own trigger editing. Today the spec and the implementation disagree.

### MINOR-1 — unused parallel mapping exports bypass the real save path
`frontend/composables/useFunnelCanvas.ts:574` (+ `modelToGraph`, `disconnectEdge`, `applyEdgeValue`). Exported
and unit-tested but not used by any production component — the page builds its PATCH inline
(`persist()` + `sanitizeTrigger`) and the canvas calls `connectEdge`/`deleteStepNode` directly.
`buildPatchPayload` correctly serializes notes + canvasPosition, but the live save path never calls it, so its
test guards a payload shape the app never emits.
**Fix:** route `persist()` through `buildPatchPayload` (single source of truth), or drop the unused exports +
their orphaned tests.

### MINOR-2 — notes have no text editor
`frontend/components/funnels/FunnelCanvasSidePanel.vue:136`. `addNote` creates a note with `text:''`; the side
panel renders nothing for `kind==='note'` and the node renders text read-only — so a created note is
permanently blank (no authoring path). Out of the strict cross-layer audit scope but leaves the feature
dead-ended.
**Fix:** add a minimal note-text editor for `kind==='note'`, or confirm note authoring is deferred and track it.

## Conclusion
Backend, shared-resource contract, engine semantics and edge-field consistency are production-ready. The
frontend has one real persistence regression (MAJOR-1) and one spec/implementation divergence (MAJOR-2) to
resolve before sign-off, plus two minors. Recommend: needs fixes (MAJOR-1 at minimum).
