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
      class="funnel-handle funnel-handle--target"
      data-test="funnel-canvas-handle-target"
      type="target"
      :position="Position.Top"
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

    <!-- Typed OUTPUT handles. Each id maps to a mapping-layer EdgeRef field via the @connect handler. -->
    <template v-if="isStep">
      <!-- default `next` edge -->
      <Handle
        id="next"
        class="funnel-handle funnel-handle--next"
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
        class="funnel-handle funnel-handle--button"
        :data-test="`funnel-canvas-handle-button-${btnIndex}`"
        type="source"
        :position="Position.Bottom"
        :title="t('funnels.canvas.handle.button', { label: btn.label })"
      />
      <!-- timeout edge (MESSAGE only) -->
      <Handle
        v-if="hasTimeoutHandle"
        id="timeout"
        class="funnel-handle funnel-handle--timeout"
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
      class="funnel-handle funnel-handle--entry"
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

/* Connection handles (handles-visibility-fix).
   Vue Flow's default handle is a tiny (~6px), low-contrast dot — effectively invisible, so the author can't
   see where to start a drag. We give every handle an explicit visible size + a distinct background + border,
   sitting ON the node border (the node box has NO overflow:hidden, so nothing clips them), with a hover/active
   grow affordance. :deep() is required: <Handle> renders its own .vue-flow__handle child element, outside this
   component's scoped-style hash, so the class we pass through is only reachable via :deep().
   Output kinds are color-coded so the author can tell next / button / timeout / entry apart at a glance; each
   handle also carries a localized native title (tooltip) for an explicit label on hover. */
.funnel-canvas-node :deep(.funnel-handle) {
  width: 12px;
  height: 12px;
  border: 2px solid #ffffff;
  border-radius: 9999px;
  background: #6366f1;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.25);
  cursor: crosshair;
  transition:
    transform 0.1s ease,
    box-shadow 0.1s ease;
}

/* Visible grab affordance: enlarge + highlight on hover and while connecting. */
.funnel-canvas-node :deep(.funnel-handle:hover),
.funnel-canvas-node :deep(.funnel-handle.connectionindicator:hover),
.funnel-canvas-node :deep(.funnel-handle.vue-flow__handle-connecting) {
  transform: scale(1.4);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.35);
}

/* Per-kind colors so the author understands which output is which (alongside the native title tooltip). */
.funnel-canvas-node :deep(.funnel-handle--target) {
  background: #94a3b8; /* slate — input/target */
}
.funnel-canvas-node :deep(.funnel-handle--next) {
  background: #2563eb; /* blue — default next */
}
.funnel-canvas-node :deep(.funnel-handle--button) {
  background: #16a34a; /* green — callback button */
}
.funnel-canvas-node :deep(.funnel-handle--timeout) {
  background: #f59e0b; /* amber — timeout */
}
.funnel-canvas-node :deep(.funnel-handle--entry) {
  background: #9333ea; /* purple — start/trigger entry */
}
</style>
