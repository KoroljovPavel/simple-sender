<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'
import type { FunnelStep, FunnelTrigger } from '~/types/funnel'
import type { CanvasNodeData } from '~/composables/useFunnelCanvas'

// Custom Vue Flow node renderer for the funnel canvas (18-funnel-canvas, Task 5). Registered under the
// node-type slots of FunnelCanvas.client.vue (`#node-step` / `#node-trigger` / `#node-start` / `#node-note`).
//
// LIGHTWEIGHT body by design (§8.4 / Risk: performance): renders only the localized type + a short label.
// The expensive FunnelMessagePreview (debounced backend call) is reserved for the FOCUSED state, surfaced by
// the parent — NOT mounted here. Output is text-only via {{ }} — never v-html (matches FunnelMessagePreview /
// FunnelStepForm strict no-v-html convention).
//
// Typed OUTPUT handles (Decision 9) carry a stable id so the canvas @connect handler can resolve which model
// field a drawn edge writes (the mapping layer's EdgeRef): `next`, `btn:<index>` per CALLBACK button, `timeout`,
// and `entry` for the start/trigger entry. An INPUT (target) handle receives incoming edges.

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
    :class="['funnel-canvas-node', { 'funnel-canvas-node--broken': broken }]"
  >
    <!-- INPUT (target) handle: receives incoming edges. Start/trigger entry nodes are pure sources (no
         inbound edge), and a note has no edges at all — so neither gets a target handle. -->
    <Handle
      v-if="!isEntry && !isNote"
      data-test="funnel-canvas-handle-target"
      type="target"
      :position="Position.Top"
    />

    <!-- Lightweight body: localized type + short title. NO FunnelMessagePreview here (perf, §8.4). -->
    <div class="funnel-canvas-node__body" data-test="funnel-canvas-node-body">
      <span class="funnel-canvas-node__title">{{ title }}</span>
    </div>

    <!-- Cross-funnel SUBSCRIBE exit badge (Decision 11) — surfaced on the node, NOT as an outgoing edge. -->
    <span
      v-if="exitBadge"
      data-test="funnel-canvas-exit-badge"
      class="funnel-canvas-node__exit-badge"
    >{{ t('funnels.canvas.exitBadge') }}</span>

    <!-- Typed OUTPUT handles. Each id maps to a mapping-layer EdgeRef field via the @connect handler. -->
    <template v-if="isStep">
      <!-- default `next` edge -->
      <Handle
        id="next"
        data-test="funnel-canvas-handle-next"
        type="source"
        :position="Position.Bottom"
        :title="t('funnels.canvas.handle.next')"
      />
      <!-- one source handle per CALLBACK button (id-addressable: btn:<index>) -->
      <Handle
        v-for="(btn, btnIndex) in callbackButtons"
        :id="`btn:${btnIndex}`"
        :key="`btn:${btnIndex}`"
        :data-test="`funnel-canvas-handle-button-${btnIndex}`"
        type="source"
        :position="Position.Bottom"
        :title="t('funnels.canvas.handle.button', { label: btn.label })"
      />
      <!-- timeout edge (MESSAGE only) -->
      <Handle
        v-if="hasTimeoutHandle"
        id="timeout"
        data-test="funnel-canvas-handle-timeout"
        type="source"
        :position="Position.Bottom"
        :title="t('funnels.canvas.handle.timeout')"
      />
    </template>

    <!-- start / trigger entry handle -->
    <Handle
      v-else-if="isEntry"
      id="entry"
      data-test="funnel-canvas-handle-entry"
      type="source"
      :position="Position.Bottom"
      :title="t('funnels.canvas.handle.entry')"
    />

    <!-- a note node has no edges at all -->
    <template v-else-if="isNote" />
  </div>
</template>

<style scoped>
.funnel-canvas-node {
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
</style>
