<script setup lang="ts">
import { Handle, Position, useVueFlow } from '@vue-flow/core'
import type { FunnelStep, FunnelTrigger } from '~/types/funnel'
import type { CanvasNodeData } from '~/composables/useFunnelCanvas'

// Custom Vue Flow node renderer for the funnel canvas (18-funnel-canvas, Task 5; redesigned in handles-redesign).
// Registered under the node-type slots of FunnelCanvas.client.vue (`#node-step` / `#node-trigger` /
// `#node-start` / `#node-note`).
//
// LIGHTWEIGHT body by design (§8.4 / Risk: performance): renders only the localized type + a short label.
// The expensive FunnelMessagePreview (debounced backend call) is reserved for the FOCUSED state, surfaced by
// the parent — NOT mounted here. Output is text-only via {{ }} — never v-html (matches FunnelMessagePreview /
// FunnelStepForm strict no-v-html convention).
//
// HANDLE DESIGN (handles-redesign — UX feedback):
//  • INPUT: there is NO separate input dot. The WHOLE card is the drop zone — a SINGLE node-covering target
//    Handle (`funnel-handle--card-target`) sits over the card. It is `pointer-events:none` at rest (so it
//    never swallows a node click/move) and only becomes droppable + highlighted WHILE a connection drag is in
//    progress (driven by Vue Flow's reactive `connectionStartHandle`). The covering handle carries Vue Flow's
//    `nodrag` class, so it never blocks node-body dragging.
//  • OUTPUTS: one small MONOCHROME circle PER output, stacked vertically + evenly spaced along the right edge
//    (so next & timeout no longer share a pixel → no overlap/flicker), each with a short text label.
//  • Hover affordance uses ONLY transform: scale() + box-shadow (no width/height/top/left change → no jitter).
//
// Typed OUTPUT handle IDS are UNCHANGED (the connect path depends on them): `next`, `btn:<index>` per CALLBACK
// button, `timeout`, and `entry` for the start/trigger entry. Only their POSITION/STYLE/LABELS changed.

const props = defineProps<{
  // Vue Flow passes the node id + data through the `#node-<type>` slot binding.
  id: string
  data: CanvasNodeData
  // The originating model object, threaded through `data` consumers via the parent. We read it from the
  // model arrays the parent passes alongside via props so the node body can show a localized label.
  step?: FunnelStep | null
  trigger?: FunnelTrigger | null
  noteText?: string | null
  // Broken visual state for an outgoing field that dangles (driven by the canvas).
  broken?: boolean
}>()

const { t } = useI18n()

// A connection drag is in progress when Vue Flow has recorded a source start handle. This single shared store
// field (the parent owns the one useVueFlow instance; this resolves to the SAME store inside the provider)
// drives the whole-card droppable affordance: the card-covering target handle only captures the drop + lights
// up while connecting, so at rest it never swallows a node click or a node-body drag.
const { connectionStartHandle } = useVueFlow()
const isConnecting = computed(() => connectionStartHandle.value != null)

// CALLBACK buttons in declaration order — URL buttons carry no edge (no targetStepId), so they are excluded.
// buttonIndex is the position WITHIN this filtered list, matching the mapping layer's forward+reverse keying.
const callbackButtons = computed(() =>
  (props.step?.buttons ?? []).filter((b) => b.type === 'callback'),
)

// MESSAGE steps can carry a timeout target edge (timeout* fields). The handle is shown for MESSAGE steps so
// the author can wire timeoutTargetStepId; other step types do not park, so no timeout handle.
const hasTimeoutHandle = computed(() => props.step?.stepType === 'MESSAGE')

// A step node carries `next` + per-button + (MESSAGE) timeout output handles.
const isStep = computed(() => props.data.kind === 'step')
// Start / trigger nodes carry the single `entry` output handle.
const isEntry = computed(() => props.data.kind === 'start' || props.data.kind === 'trigger')
const isNote = computed(() => props.data.kind === 'note')

// Truncate a long output label so the stacked row stays compact (16 chars + …).
const LABEL_MAX = 16
function truncate(label: string): string {
  return label.length > LABEL_MAX ? `${label.slice(0, LABEL_MAX)}…` : label
}

// The ordered output descriptors for this node: stable handle id + localized label. The id is UNCHANGED from
// the original scheme (next / btn:<i> / timeout / entry) — only the rendering (stacked + labeled) is new.
interface OutputHandle {
  id: string
  label: string
  testId: string
}
const outputs = computed<OutputHandle[]>(() => {
  if (isEntry.value) {
    return [{ id: 'entry', label: t('funnels.canvas.handle.entry'), testId: 'funnel-canvas-handle-entry' }]
  }
  if (!isStep.value) return []
  const list: OutputHandle[] = [
    { id: 'next', label: t('funnels.canvas.handle.outputNext'), testId: 'funnel-canvas-handle-next' },
  ]
  callbackButtons.value.forEach((btn, i) => {
    list.push({
      id: `btn:${i}`,
      label: truncate(btn.label ?? ''),
      testId: `funnel-canvas-handle-button-${i}`,
    })
  })
  if (hasTimeoutHandle.value) {
    list.push({ id: 'timeout', label: t('funnels.canvas.handle.outputTimeout'), testId: 'funnel-canvas-handle-timeout' })
  }
  return list
})

// Localized node title — type label (step / trigger) or the start / note label. Plain text via {{ }}.
const title = computed<string>(() => {
  if (props.data.kind === 'start') return t('funnels.canvas.startNode')
  if (props.data.kind === 'note') return t('funnels.canvas.note')
  if (props.data.kind === 'trigger' && props.trigger) {
    return t(`funnels.trigger.type.${props.trigger.triggerType}`)
  }
  if (props.data.kind === 'step' && props.step) {
    return t(`funnels.steps.type.${props.step.stepType}`)
  }
  return ''
})

const exitBadge = computed(() => props.data.exitBadge ?? null)
</script>

<template>
  <div
    data-test="funnel-canvas-node"
    :data-node-kind="data.kind"
    :data-node-id="id"
    :class="[
      'funnel-canvas-node',
      { 'funnel-canvas-node--broken': broken, 'funnel-canvas-node--droppable': isConnecting && !isEntry && !isNote },
    ]"
  >
    <!-- INPUT = the WHOLE card. A single node-covering target Handle (NO separate input dot). At rest it is
         pointer-events:none (never swallows a click / node-body drag); while a connection drag is in progress
         it becomes droppable + the card lights up. Start/trigger entry nodes are pure sources, and a note has
         no edges — so neither gets a target handle. The handle keeps NO id so `targetHandle` stays null and the
         connect path resolves the destination by node id (conn.target), exactly as before. -->
    <Handle
      v-if="!isEntry && !isNote"
      class="funnel-handle funnel-handle--card-target"
      data-test="funnel-canvas-handle-target"
      type="target"
      :position="Position.Left"
      :title="t('funnels.canvas.handle.input')"
    />

    <!-- Lightweight body: localized type + short title. NO FunnelMessagePreview here (perf, §8.4). -->
    <div class="funnel-canvas-node__body" data-test="funnel-canvas-node-body">
      <span class="funnel-canvas-node__title">{{ title }}</span>
      <!-- Note body — author free text. Rendered text-only via {{ }}, NEVER v-html (Decision 7 stored-XSS
           guard, mirrors FunnelMessagePreview/FunnelStepForm). An injection payload shows as escaped text. -->
      <span
        v-if="isNote && noteText"
        data-test="funnel-canvas-note-text"
        class="funnel-canvas-node__note-text"
      >{{ noteText }}</span>
    </div>

    <!-- Cross-funnel SUBSCRIBE exit badge (Decision 11) — surfaced on the node, NOT as an outgoing edge. -->
    <span
      v-if="exitBadge"
      data-test="funnel-canvas-exit-badge"
      class="funnel-canvas-node__exit-badge"
    >{{ t('funnels.canvas.exitBadge') }}</span>

    <!-- OUTPUTS: one monochrome circle PER output, stacked + evenly spaced along the right edge, each with a
         short label. Each id maps to a mapping-layer EdgeRef field via the @connect handler (ids UNCHANGED). -->
    <div v-if="outputs.length > 0" class="funnel-canvas-node__outputs" data-test="funnel-canvas-node-outputs">
      <div
        v-for="(out, i) in outputs"
        :key="out.id"
        class="funnel-output-row"
        :style="{ top: `${((i + 1) / (outputs.length + 1)) * 100}%` }"
      >
        <span class="funnel-output-row__label" data-test="funnel-canvas-output-label">{{ out.label }}</span>
        <Handle
          :id="out.id"
          class="funnel-handle funnel-handle--output"
          :data-test="out.testId"
          :data-handle-id="out.id"
          type="source"
          :position="Position.Right"
          :title="out.label"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.funnel-canvas-node {
  position: relative;
  min-width: 160px;
  border: 1px solid #cbd5e1;
  border-radius: 0.375rem;
  background: #ffffff;
  padding: 0.5rem 0.75rem;
  font-size: 0.875rem;
}

.funnel-canvas-node--broken {
  border-color: #f87171;
  box-shadow: 0 0 0 2px rgba(248, 113, 113, 0.35);
}

/* Whole-card drop affordance: while a connection drag is in progress every connectable card lights up as a
   droppable target. Box-shadow + background only — no geometry change (no reflow/jitter). */
.funnel-canvas-node--droppable {
  border-color: #6366f1;
  background: #eef2ff;
  box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.45);
}

.funnel-canvas-node__title {
  font-weight: 600;
  color: #374151;
}

.funnel-canvas-node__exit-badge {
  display: inline-block;
  margin-top: 0.25rem;
  border-radius: 0.25rem;
  background: #fef3c7;
  padding: 0.05rem 0.4rem;
  font-size: 0.75rem;
  color: #92400e;
}

/* ── Stacked output rows ──────────────────────────────────────────────────────────────────────────────────
   Each output sits at a fixed `top` (computed inline by index) along the right edge, so next / button / timeout
   never share a pixel — the old next/timeout overlap + color flicker is gone. The label sits to the LEFT of the
   circle (inside the card), the circle straddles the right border. */
.funnel-canvas-node__outputs {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 0;
}

.funnel-output-row {
  position: absolute;
  right: 0;
  display: flex;
  align-items: center;
  gap: 0.25rem;
  transform: translateY(-50%);
  pointer-events: none; /* only the circle (re-enabled below) is interactive; the label never blocks the canvas */
}

.funnel-output-row__label {
  white-space: nowrap;
  border-radius: 0.25rem;
  background: rgba(241, 245, 249, 0.95);
  padding: 0 0.25rem;
  font-size: 0.6875rem;
  line-height: 1.3;
  color: #475569;
}

/* ── Connection handles (handles-redesign) ───────────────────────────────────────────────────────────────
   MONOCHROME: every handle uses one neutral/accent color (no per-kind color → no confusion, no next/timeout
   color flicker). :deep() is required: <Handle> renders its own .vue-flow__handle child element outside this
   component's scoped-style hash, so the class we pass through is only reachable via :deep(). */
.funnel-canvas-node :deep(.funnel-handle--output) {
  position: static; /* the row owns positioning; the circle just sits inside the flex row */
  width: 12px;
  height: 12px;
  border: 2px solid #ffffff;
  border-radius: 9999px;
  background: #6366f1; /* single monochrome accent for ALL outputs */
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.25);
  cursor: crosshair;
  pointer-events: all; /* re-enable on the circle (the row is pointer-events:none) */
  transform: none;
  transition:
    transform 0.1s ease,
    box-shadow 0.1s ease;
}

/* Grab affordance: scale + shadow ONLY — never width/height/top/left → no layout reflow, no jitter. */
.funnel-canvas-node :deep(.funnel-handle--output:hover),
.funnel-canvas-node :deep(.funnel-handle--output.vue-flow__handle-connecting) {
  transform: scale(1.4);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.35);
}

/* Whole-card target: a transparent handle covering the entire node. `nodrag` (Vue Flow default on handles)
   keeps node-body dragging working; pointer-events:none at rest keeps node clicks working. It only captures
   the drop WHILE a connection is in progress (the parent toggles the droppable card state via .--droppable). */
.funnel-canvas-node :deep(.funnel-handle--card-target) {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  transform: none;
  border: none;
  border-radius: 0.375rem;
  background: transparent;
  pointer-events: none;
}
.funnel-canvas-node--droppable :deep(.funnel-handle--card-target) {
  pointer-events: all;
}
</style>
