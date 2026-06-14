<script setup lang="ts">
import { VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection } from '@vue-flow/core'
// Base Vue Flow stylesheet (Decision 12 / AC). Also registered globally in nuxt.config (Task 2); Vite dedups,
// so importing it here too is harmless and satisfies the strict "component imports it" acceptance criterion.
import '@vue-flow/core/dist/style.css'
import FunnelCanvasNode from './FunnelCanvasNode.vue'
import FunnelCanvasPalette from './FunnelCanvasPalette.vue'
import FunnelCanvasSidePanel from './FunnelCanvasSidePanel.vue'
import type { SelectedNode } from './FunnelCanvasSidePanel.vue'
import type { CanvasPosition, FunnelNote, FunnelResponse, FunnelStep, FunnelTrigger, StepType } from '~/types/funnel'
import {
  buildEdges,
  buildNodes,
  connectEdge,
  deleteStepNode,
  detectBrokenEdges,
  layoutNodes,
  type BrokenEdge,
  type CanvasEdge,
  type CanvasNode,
  type EdgeFieldKind,
  type EdgeRef,
} from '~/composables/useFunnelCanvas'

// Client-only Vue Flow canvas surface (18-funnel-canvas, Task 5). The `*.client.vue` filename (Decision 12)
// keeps Vue Flow's setup-time window/DOM access off the SSR path (`window is not defined`); the base stylesheet
// is imported here (AC) and also registered globally in nuxt.config (Task 2) — Vite dedups.
//
// PROPS-IN / EMITS-OUT — this component does NOT read useFunnelsStore. It takes the funnel model parts as props,
// feeds them through the Task 4 `useFunnelCanvas` mapping layer to obtain nodes/edges + broken-edge flags, and
// emits the updated arrays when the author draws an edge. Persistence + page wiring are Task 7.
//
// It owns the SINGLE useVueFlow() instance for the editor page (Shared resources table) — consumers (Tasks 6–7)
// must reuse it, never create a second.

const props = defineProps<{
  steps: FunnelStep[]
  triggers: FunnelTrigger[]
  notes?: FunnelNote[] | null
  botUsername?: string | null
  deepLink?: string | null
}>()

const emit = defineEmits<{
  'update:steps': [steps: FunnelStep[]]
  'update:triggers': [triggers: FunnelTrigger[]]
  'update:notes': [notes: FunnelNote[]]
  // An EXPLICIT side-panel Save (Зберегти) of a step. The page persists on update:steps as usual, but this
  // companion event marks the save as user-initiated so the page can surface feedback when a completed array
  // still cannot persist (another node incomplete) — an explicit Save must never be a silent no-op.
  'step-save': [step: FunnelStep]
  // Task 7: a finished node drag re-emits the dragged node id + its final canvas {x,y}. The PAGE owns the
  // model match (nodeId → step/trigger/note) and persistence — the canvas only relays Vue Flow's event so it
  // stays the SOLE owner of the single useVueFlow() instance (no second instance on the page).
  'node-drag-stop': [payload: { nodeId: string; position: CanvasPosition }]
}>()

const { t } = useI18n()

// Compose the minimal FunnelResponse the mapping layer consumes from the model props. Only the fields the
// mapping layer reads (steps / triggers / notes) are meaningful; the rest are filled with inert placeholders.
const model = computed<FunnelResponse>(() => ({
  id: '',
  projectId: '',
  name: '',
  description: null,
  status: 'draft',
  triggers: props.triggers ?? [],
  allowReEnter: false,
  steps: props.steps ?? [],
  notes: props.notes ?? null,
  deepLink: null,
  createdAt: '',
  updatedAt: '',
}))

// Forward mapping: nodes (laid out) + edges, sourced from the mapping layer — never re-implemented here.
// `buildEdges` is computed ONCE here and reused by both the layout (rawNodes) and flowEdges — a single
// shared source so the edge set is not recomputed twice per update.
const rawEdges = computed<CanvasEdge[]>(() => buildEdges(model.value))

const rawNodes = computed<CanvasNode[]>(() => {
  const nodes = buildNodes(model.value)
  return layoutNodes(model.value, nodes, rawEdges.value)
})

const brokenEdges = computed<BrokenEdge[]>(() => detectBrokenEdges(model.value))

// Reason → i18n key. Each broken-edge reason gets an accurate, actionable message:
// missing_target = a non-null edge points at a deleted step ("target deleted" is accurate);
// on_start_entry_null = the start node was never wired (nothing deleted — "draw an edge").
// This Record over the BrokenEdge['reason'] union is exhaustive today, so the lookup below
// always resolves. The `?? generic` fallback is intentional future-proofing: if a new reason
// is added to the union, an unknown reason degrades to the generic brokenEdge message instead
// of rendering an empty/undefined key.
const BROKEN_EDGE_MESSAGE_KEYS: Record<BrokenEdge['reason'], string> = {
  missing_target: 'funnels.canvas.brokenEdgeMissingTarget',
  on_start_entry_null: 'funnels.canvas.brokenEdgeStartNotConnected',
}
const brokenEdgeMessageKey = (reason: BrokenEdge['reason']): string =>
  BROKEN_EDGE_MESSAGE_KEYS[reason] ?? 'funnels.canvas.brokenEdge'

// A node OWNS a broken outbound field when a broken edge's source resolves to it. step → its id; start/trigger
// entry → the start/trigger node id. This drives the per-node broken highlight (a dangling target has no node
// to point an edge at, so the broken state is surfaced on the SOURCE node — the only renderable surface).
const brokenSourceIds = computed<Set<string>>(() => {
  const ids = new Set<string>()
  for (const b of brokenEdges.value) {
    if (b.fieldKind === 'entry' && b.triggerIndex != null) {
      const trig = (props.triggers ?? [])[b.triggerIndex]
      ids.add(trig?.triggerType === 'on_start' ? 'start' : `trigger:${b.triggerIndex}`)
    } else if (b.sourceStepId) {
      ids.add(b.sourceStepId)
    }
  }
  return ids
})

// Vue Flow node objects. The custom node type is the mapping layer's node `type` (step/trigger/start/note),
// bound to FunnelCanvasNode via the `#node-<type>` slots. `broken` and the originating model object are passed
// through `data` so the lightweight node body can render a localized label + the broken/exit visual states.
const flowNodes = computed(() =>
  rawNodes.value.map((n) => ({
    id: n.id,
    type: n.type,
    position: n.position,
    data: {
      ...n.data,
      broken: brokenSourceIds.value.has(n.id),
      step: n.data.stepIndex != null ? (props.steps ?? [])[n.data.stepIndex] : null,
      trigger: n.data.triggerIndex != null ? (props.triggers ?? [])[n.data.triggerIndex] : null,
      noteText: n.data.noteIndex != null ? (props.notes ?? [])?.[n.data.noteIndex]?.text ?? null : null,
    },
  })),
)

// Edges to SAVED nodes only (the mapping layer enforces this); a dangling field is omitted here and surfaced
// via the broken-source-node highlight above.
const flowEdges = computed(() =>
  rawEdges.value.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    data: e.data,
  })),
)

// THE single useVueFlow instance for the editor page (Shared resources). fitView runs once nodes have real
// measured dimensions (onNodesInitialized — NOT onMounted). onConnect handles draw-to-connect.
const { onConnect, onNodesInitialized, onNodeDragStop, fitView } = useVueFlow()

onNodesInitialized(() => {
  // Guard: fitView can throw on an empty graph in some environments — never let layout blank the canvas.
  try {
    fitView()
  } catch {
    /* no-op — an empty/degenerate graph just stays at the default viewport */
  }
})

// Resolve a drawn connection's source node + source handle into the mapping-layer EdgeRef, then write the
// target's saved id via connectEdge and emit the updated arrays. A connection to an UNSAVED node (id == null,
// i.e. the node id is the synthetic `unsaved-step:<n>`, or not a saved step) is rejected — you cannot mint an
// edge to an unsaved node (Decision: saved-node-only; "no edge" stays null, never "").
function handleConnect(conn: Connection): void {
  const ref = resolveEdgeRef(conn.source, conn.sourceHandle)
  if (!ref) return

  const savedStepIds = new Set((props.steps ?? []).map((s) => s.id).filter((id): id is string => !!id))
  // The target node id IS the step id for a saved step; an unsaved node carries a synthetic id that is not in
  // savedStepIds, so it is refused here (connectEdge would throw — we pre-check to keep it a silent no-op).
  if (!conn.target || !savedStepIds.has(conn.target)) return

  let nextModel: FunnelResponse
  try {
    nextModel = connectEdge(model.value, ref, conn.target)
  } catch {
    // Defensive: the mapping layer rejects an unsaved/empty target — treat as a no-op connection.
    return
  }

  // Emit only the array that actually changed (entry edges touch triggers; the rest touch steps).
  if (ref.fieldKind === 'entry') {
    emit('update:triggers', nextModel.triggers)
  } else {
    emit('update:steps', nextModel.steps)
  }
}

// Map a source node id + source handle id back to the model field the edge owns. Handle ids are minted by
// FunnelCanvasNode: `next`, `btn:<index>`, `timeout`, `entry`. The source node id is the step id (next/button/
// timeout) or the start/trigger node id (entry).
function resolveEdgeRef(sourceNodeId: string | null, sourceHandle: string | null | undefined): EdgeRef | null {
  if (!sourceNodeId) return null

  if (sourceNodeId === 'start' || sourceNodeId.startsWith('trigger:')) {
    const triggerIndex =
      sourceNodeId === 'start'
        ? (props.triggers ?? []).findIndex((tr) => tr.triggerType === 'on_start')
        : Number(sourceNodeId.slice('trigger:'.length))
    if (triggerIndex < 0 || Number.isNaN(triggerIndex)) return null
    return { fieldKind: 'entry', sourceStepId: null, triggerIndex, buttonIndex: null }
  }

  // Step source — the handle id discriminates which step field the edge writes.
  const fieldKind = handleFieldKind(sourceHandle)
  if (!fieldKind) return null
  if (fieldKind === 'button') {
    const buttonIndex = Number((sourceHandle ?? '').slice('btn:'.length))
    if (Number.isNaN(buttonIndex)) return null
    return { fieldKind, sourceStepId: sourceNodeId, triggerIndex: null, buttonIndex }
  }
  return { fieldKind, sourceStepId: sourceNodeId, triggerIndex: null, buttonIndex: null }
}

function handleFieldKind(handle: string | null | undefined): EdgeFieldKind | null {
  if (handle === 'next') return 'next'
  if (handle === 'timeout') return 'timeout'
  if (handle && handle.startsWith('btn:')) return 'button'
  if (handle === 'entry') return 'entry'
  return null
}

onConnect(handleConnect)

// Task 7: relay a finished node drag upward. Vue Flow hands us the dragged node + its final position; we
// pass the node id + {x,y} to the page, which writes it into the matching model object's canvasPosition and
// persists. We do NOT mutate the model here (props-in/emits-out) — the page is the single save path.
function handleNodeDragStop(payload: { node: { id: string; position: { x: number; y: number } } }): void {
  const node = payload?.node
  if (!node?.id || !node.position) return
  emit('node-drag-stop', { nodeId: node.id, position: { x: node.position.x, y: node.position.y } })
}

onNodeDragStop(handleNodeDragStop)

// ── Task 6: authoring interactions (palette / start-node lifecycle / delete / side panel) ─────────────

// A funnel has at most one on_start start node (Decision 11) → the palette's start-node entry disables when
// one already exists.
const onStartExists = computed(() => (props.triggers ?? []).some((tr) => tr.triggerType === 'on_start'))

// ── Palette: add a node ────────────────────────────────────────────────────────────────────────────────
// Adding a node appends a fresh model object (id null for steps — it has no id until the page persists it,
// minting the id via the PATCH round-trip before the node can be wired, Decision 10). The canvas only mutates
// and emits the model arrays; persistence + id minting are the page's job (Task 7).

// A minimal fresh step skeleton per type — just enough for the node to render + the form to seed. The page's
// persist() round-trip mints the id; until then the node carries id: null and refuses inbound edges.
function newStep(type: StepType): FunnelStep {
  const base = { stepType: type, id: null } as FunnelStep
  if (type === 'MESSAGE') return { ...base, blocks: [{ type: 'TEXT', text: '' }] }
  return base
}

function addStep(type: StepType): void {
  emit('update:steps', [...(props.steps ?? []), newStep(type)])
}

// Adding the start node creates an on_start trigger (its outgoing edge writes on_start.entryStepId later via
// the mapping layer). Guarded by onStartExists so we never create a second one.
function addStart(): void {
  if (onStartExists.value) return
  const trigger: FunnelTrigger = { triggerType: 'on_start', triggerValue: '', entryStepId: null }
  emit('update:triggers', [...(props.triggers ?? []), trigger])
}

// A generic (event) trigger node — its type is then editable in the side panel (FunnelTriggerSettings).
function addTrigger(): void {
  const trigger: FunnelTrigger = { triggerType: 'event', triggerValue: '', entryStepId: null }
  emit('update:triggers', [...(props.triggers ?? []), trigger])
}

function addNote(): void {
  emit('update:notes', [...(props.notes ?? []), { id: null, text: '', canvasPosition: null }])
}

// ── Node selection → side panel ──────────────────────────────────────────────────────────────────────
const selectedNode = ref<SelectedNode | null>(null)

function selectNode(node: CanvasNode): void {
  const { kind, stepIndex, triggerIndex, noteIndex } = node.data
  selectedNode.value = {
    kind,
    nodeId: node.id,
    stepIndex,
    step: stepIndex != null ? (props.steps ?? [])[stepIndex] ?? null : null,
    trigger: triggerIndex != null ? (props.triggers ?? [])[triggerIndex] ?? null : null,
    noteIndex: noteIndex,
    noteText: noteIndex != null ? (props.notes ?? [])[noteIndex]?.text ?? '' : null,
    botUsername: props.botUsername ?? null,
    deepLink: props.deepLink ?? null,
  }
}

function clearSelection(): void {
  selectedNode.value = null
}

// Relay the side panel's step edit back into the steps array, matched by the node's stable ARRAY INDEX (not by
// id). A freshly-added palette node carries id:null until the first PATCH mints one (Decision 10) — matching
// by id silently dropped that node's edit (save-noop-fix / H1: the empty seed stayed in the array, stepReady()
// was false, persistSteps() withheld → no PATCH, no error). The index is present + stable for unsaved AND saved
// nodes, so it always routes the edit. Persistence + id minting are the page's job (Task 7).
// This is an EXPLICIT Save (the author clicked Зберегти), so we ALSO emit `step-save` — the page surfaces
// feedback if the completed array still cannot persist (e.g. another node is incomplete), never a silent no-op.
function onPanelStepSubmit(step: FunnelStep): void {
  const sel = selectedNode.value
  if (!sel || sel.kind !== 'step' || sel.stepIndex == null) return
  const idx = sel.stepIndex
  if (!Number.isInteger(idx) || !(props.steps ?? [])[idx]) return
  // Staleness guard: routing by array index is correct ONLY while the index still points at the SAME step the
  // panel was opened on. If the array shifted while the panel was open (e.g. a different node was deleted →
  // reindex), index `idx` may now hold a DIFFERENT saved step — writing here would overwrite the wrong node.
  // Bail when BOTH the selected step's id and the id currently at `idx` are non-null AND differ. The fresh-node
  // path is unaffected: an unsaved seed carries id:null, so one side is null and the guard never trips — it
  // still writes by index (the original save-noop-fix). Only a saved→saved index mismatch is caught.
  const selId = sel.step?.id ?? null
  const atIdxId = (props.steps ?? [])[idx]?.id ?? null
  if (selId != null && atIdxId != null && selId !== atIdxId) return
  // Emit ordering is LOAD-BEARING: update:steps must fire BEFORE step-save. The page commits the new content to
  // steps.value on update:steps; onCanvasStepSave (step-save handler) then reads that committed array for its
  // readiness check. Reversing the order would let the save-feedback check run against the stale pre-edit array.
  const next = (props.steps ?? []).map((s, i) => (i === idx ? step : s))
  emit('update:steps', next)
  emit('step-save', step)
}

// Relay the side panel's note-text edit back into the notes array by index, then emit update:notes so the
// page persists it via the existing path. text is written verbatim; rendering escapes it ({{ }}, never v-html).
function onPanelNoteText(payload: { noteIndex: number; text: string }): void {
  const next = (props.notes ?? []).map((n, i) => (i === payload.noteIndex ? { ...n, text: payload.text } : n))
  emit('update:notes', next)
}

function onPanelTriggerUpdate(trigger: FunnelTrigger): void {
  const sel = selectedNode.value
  if (!sel || (sel.kind !== 'trigger' && sel.kind !== 'start')) return
  const idx = (props.triggers ?? []).findIndex((tr) => tr === sel.trigger)
  if (idx < 0) return
  const next = (props.triggers ?? []).map((tr, i) => (i === idx ? trigger : tr))
  emit('update:triggers', next)
}

// ── Node delete with edge auto-cleanup + disconnect-count warning ────────────────────────────────────
// requestDelete computes (does not yet apply) the deletion. For a step node the mapping layer's deleteStepNode
// reports the exact inbound-edge disconnect count; a "N connections will be disconnected" warning is shown
// before the author confirms. confirmDelete then emits the cleaned model. Deleting the start node removes the
// on_start trigger entry (Decision 11) — no step-edge count, but its own entry edge goes away with it.
interface PendingDelete {
  nodeId: string
  kind: 'step' | 'start' | 'trigger' | 'note'
  disconnectedCount: number
}
const pendingDelete = ref<PendingDelete | null>(null)

function nodeKindOf(nodeId: string): 'step' | 'start' | 'trigger' | 'note' {
  if (nodeId === 'start') return 'start'
  if (nodeId.startsWith('trigger:')) return 'trigger'
  if (nodeId.startsWith('note:')) return 'note'
  return 'step'
}

function requestDelete(nodeId: string): void {
  const kind = nodeKindOf(nodeId)
  let disconnectedCount = 0
  if (kind === 'step') {
    // Reuse the mapping layer for the exact inbound-edge count — never recompute it here.
    disconnectedCount = deleteStepNode(model.value, nodeId).disconnectedCount
  }
  pendingDelete.value = { nodeId, kind, disconnectedCount }
}

function cancelDelete(): void {
  pendingDelete.value = null
}

function confirmDelete(): void {
  const pending = pendingDelete.value
  if (!pending) return

  if (pending.kind === 'step') {
    const result = deleteStepNode(model.value, pending.nodeId)
    emit('update:steps', result.funnel.steps ?? [])
    // Inbound entry edges may have been nulled too — emit triggers so the page persists the cleanup.
    emit('update:triggers', result.funnel.triggers ?? [])
  } else if (pending.kind === 'start') {
    // Removing the start node removes the on_start trigger entry entirely (Decision 11).
    emit(
      'update:triggers',
      (props.triggers ?? []).filter((tr) => tr.triggerType !== 'on_start'),
    )
  } else if (pending.kind === 'trigger') {
    const idx = Number(pending.nodeId.slice('trigger:'.length))
    emit(
      'update:triggers',
      (props.triggers ?? []).filter((_, i) => i !== idx),
    )
  } else if (pending.kind === 'note') {
    const idx = Number(pending.nodeId.slice('note:'.length))
    emit(
      'update:notes',
      (props.notes ?? []).filter((_, i) => i !== idx),
    )
  }

  // If the deleted node was the selected one, clear the side panel.
  if (selectedNode.value?.nodeId === pending.nodeId) clearSelection()
  pendingDelete.value = null
}

// Exposed for the component test to drive a draw-to-connect deterministically (asserting the field write +
// emit) without simulating a real Vue Flow drag — live drag is user-verified, not unit-tested (Testing
// Strategy / no E2E). requestDelete/confirmDelete are likewise exposed so the delete flow (warning → apply)
// is unit-testable without a live node click. Production wiring goes through the onConnect callback above.
defineExpose({ handleConnect, handleNodeDragStop, selectNode, onPanelStepSubmit, requestDelete, confirmDelete })
</script>

<template>
  <div data-test="funnel-canvas" class="funnel-canvas" :aria-label="t('funnels.canvas.aria')">
    <!-- Palette (left): add a node of any of the 9 step types + trigger + note + the optional start node. -->
    <FunnelCanvasPalette
      :on-start-exists="onStartExists"
      @add-step="addStep"
      @add-trigger="addTrigger"
      @add-note="addNote"
      @add-start="addStart"
    />

    <div class="funnel-canvas__stage">
    <VueFlow
      :nodes="flowNodes"
      :edges="flowEdges"
      :only-render-visible-elements="true"
      :min-zoom="0.2"
      :max-zoom="2"
      @node-click="(e: { node: { id: string } }) => { const n = rawNodes.find((x) => x.id === e.node.id); if (n) selectNode(n) }"
    >
      <!-- Custom node renderers per mapping-layer node type. The slot props (id, data, …) are bound through. -->
      <template #node-step="nodeProps">
        <FunnelCanvasNode
          v-bind="nodeProps"
          :step="nodeProps.data.step"
          :broken="nodeProps.data.broken"
        />
      </template>
      <template #node-trigger="nodeProps">
        <FunnelCanvasNode
          v-bind="nodeProps"
          :trigger="nodeProps.data.trigger"
          :broken="nodeProps.data.broken"
        />
      </template>
      <template #node-start="nodeProps">
        <FunnelCanvasNode
          v-bind="nodeProps"
          :trigger="nodeProps.data.trigger"
          :broken="nodeProps.data.broken"
        />
      </template>
      <template #node-note="nodeProps">
        <FunnelCanvasNode v-bind="nodeProps" :note-text="nodeProps.data.noteText" />
      </template>
    </VueFlow>

    <!-- Broken-edge highlight surface (test/operator hook): a dangling target has no node to point an edge at,
         so each broken edge is listed here keyed to its source node, alongside the per-node red highlight. -->
    <ul
      v-if="brokenEdges.length > 0"
      data-test="funnel-canvas-broken-edges"
      class="funnel-canvas__broken-edges"
      :aria-label="t('funnels.canvas.brokenEdgesLabel')"
    >
      <li
        v-for="b in brokenEdges"
        :key="b.edgeId"
        data-test="funnel-canvas-broken-edge"
        :data-edge-field="b.fieldKind"
        :data-edge-reason="b.reason"
        class="funnel-canvas__broken-edge"
      >{{ t(brokenEdgeMessageKey(b.reason)) }}</li>
    </ul>

    <!-- Delete confirmation: shows the EXACT inbound-edge disconnect count (mapping-layer disconnectedCount)
         before the author confirms a node deletion (Decision 9). -->
    <div
      v-if="pendingDelete"
      data-test="funnel-canvas-delete-warning"
      :data-disconnect-count="pendingDelete.disconnectedCount"
      class="funnel-canvas__delete-warning"
      role="alertdialog"
    >
      <p class="funnel-canvas__delete-text">
        {{ t('funnels.canvas.delete.warning', { count: pendingDelete.disconnectedCount }) }}
      </p>
      <div class="funnel-canvas__delete-actions">
        <button
          type="button"
          data-test="funnel-canvas-delete-confirm"
          class="funnel-canvas__delete-confirm"
          @click="confirmDelete"
        >{{ t('funnels.canvas.delete.button') }}</button>
        <button
          type="button"
          data-test="funnel-canvas-delete-cancel"
          class="funnel-canvas__delete-cancel"
          @click="cancelDelete"
        >{{ t('funnels.canvas.sidePanel.close') }}</button>
      </div>
    </div>
    </div>

    <!-- Side panel (right): the selected node's field editor — FunnelStepForm (steps, target pickers hidden)
         or FunnelTriggerSettings (trigger/start). Clears on deselect. -->
    <FunnelCanvasSidePanel
      :node="selectedNode"
      @submit="onPanelStepSubmit"
      @update:trigger="onPanelTriggerUpdate"
      @update:note-text="onPanelNoteText"
      @close="clearSelection"
    />
  </div>
</template>

<style scoped>
.funnel-canvas {
  display: flex;
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 480px;
}

.funnel-canvas__stage {
  position: relative;
  flex: 1 1 auto;
  min-width: 0;
  height: 100%;
}

.funnel-canvas__delete-warning {
  position: absolute;
  top: 0.5rem;
  left: 50%;
  z-index: 10;
  transform: translateX(-50%);
  border: 1px solid #fca5a5;
  border-radius: 0.375rem;
  background: #fef2f2;
  padding: 0.5rem 0.75rem;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.12);
}

.funnel-canvas__delete-text {
  margin-bottom: 0.5rem;
  font-size: 0.8125rem;
  color: #b91c1c;
}

.funnel-canvas__delete-actions {
  display: flex;
  gap: 0.5rem;
}

.funnel-canvas__delete-confirm {
  border-radius: 0.25rem;
  background: #dc2626;
  padding: 0.25rem 0.625rem;
  font-size: 0.8125rem;
  color: #ffffff;
  cursor: pointer;
}

.funnel-canvas__delete-cancel {
  border: 1px solid #d1d5db;
  border-radius: 0.25rem;
  background: #ffffff;
  padding: 0.25rem 0.625rem;
  font-size: 0.8125rem;
  color: #374151;
  cursor: pointer;
}

.funnel-canvas__broken-edges {
  position: absolute;
  bottom: 0.5rem;
  left: 0.5rem;
  z-index: 5;
  margin: 0;
  padding: 0;
  list-style: none;
}

.funnel-canvas__broken-edge {
  border-radius: 0.25rem;
  background: #fee2e2;
  padding: 0.1rem 0.5rem;
  font-size: 0.75rem;
  color: #b91c1c;
}
</style>
