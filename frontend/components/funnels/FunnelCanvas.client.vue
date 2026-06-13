<script setup lang="ts">
import { VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection } from '@vue-flow/core'
import FunnelCanvasNode from './FunnelCanvasNode.vue'
import type { FunnelNote, FunnelResponse, FunnelStep, FunnelTrigger } from '~/types/funnel'
import {
  buildEdges,
  buildNodes,
  connectEdge,
  detectBrokenEdges,
  layoutNodes,
  type BrokenEdge,
  type CanvasNode,
  type EdgeFieldKind,
  type EdgeRef,
} from '~/composables/useFunnelCanvas'

// Client-only Vue Flow canvas surface (18-funnel-canvas, Task 5). The `*.client.vue` filename (Decision 12)
// keeps Vue Flow's setup-time window/DOM access off the SSR path (`window is not defined`); the base stylesheet
// is registered globally in nuxt.config (Task 2).
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
}>()

const emit = defineEmits<{
  'update:steps': [steps: FunnelStep[]]
  'update:triggers': [triggers: FunnelTrigger[]]
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
const rawNodes = computed<CanvasNode[]>(() => {
  const nodes = buildNodes(model.value)
  const edges = buildEdges(model.value)
  return layoutNodes(model.value, nodes, edges)
})

const brokenEdges = computed<BrokenEdge[]>(() => detectBrokenEdges(model.value))

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
  buildEdges(model.value).map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    data: e.data,
  })),
)

// THE single useVueFlow instance for the editor page (Shared resources). fitView runs once nodes have real
// measured dimensions (onNodesInitialized — NOT onMounted). onConnect handles draw-to-connect.
const { onConnect, onNodesInitialized, fitView } = useVueFlow()

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

// Exposed for the component test to drive a draw-to-connect deterministically (asserting the field write +
// emit) without simulating a real Vue Flow drag — live drag is user-verified, not unit-tested (Testing
// Strategy / no E2E). Production wiring goes through the onConnect callback above.
defineExpose({ handleConnect })
</script>

<template>
  <div data-test="funnel-canvas" class="funnel-canvas" :aria-label="t('funnels.canvas.aria')">
    <VueFlow
      :nodes="flowNodes"
      :edges="flowEdges"
      :only-render-visible-elements="true"
      :min-zoom="0.2"
      :max-zoom="2"
      fit-view-on-init
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
      :aria-label="t('funnels.canvas.brokenEdge')"
    >
      <li
        v-for="b in brokenEdges"
        :key="b.edgeId"
        data-test="funnel-canvas-broken-edge"
        :data-edge-field="b.fieldKind"
        :data-edge-reason="b.reason"
        class="funnel-canvas__broken-edge"
      >{{ t('funnels.canvas.brokenEdge') }}</li>
    </ul>
  </div>
</template>

<style scoped>
.funnel-canvas {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 480px;
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
