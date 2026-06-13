<script setup lang="ts">
// Node palette for the funnel canvas (18-funnel-canvas, Task 6, Decision 10). Lists EXACTLY the 9 existing
// executable step types + a trigger entry + a note entry + an optional start-node entry. NO new executable
// step types are introduced here — the list mirrors FunnelStepForm's STEP_TYPES byte-for-byte.
//
// PROPS-IN / EMITS-OUT — like the canvas surface it owns no model state. Activating an entry emits an
// add-request; the parent (FunnelCanvas.client.vue) performs the actual mapping-layer create + persist (a
// new step/trigger node must be persisted to mint its id before it can be wired — Decision 10).
//
// The start-node entry is DISABLED while an on_start trigger already exists (a funnel has at most one start
// node — Decision 11). The disabled flag is driven entirely by the `onStartExists` prop.
import type { StepType } from '~/types/funnel'

const props = defineProps<{
  // True when the funnel already has an on_start trigger → the start-node palette entry is disabled.
  onStartExists: boolean
}>()

const emit = defineEmits<{
  'add-step': [type: StepType]
  'add-trigger': []
  'add-note': []
  'add-start': []
}>()

const { t } = useI18n()

// MUST stay in lockstep with FunnelStepForm.STEP_TYPES — the 9 existing executable step types, no more.
const STEP_TYPES: StepType[] = [
  'MESSAGE',
  'DELAY',
  'ADD_TAG',
  'REMOVE_TAG',
  'SET_CUSTOM_FIELD',
  'EMIT_EVENT',
  'SUBSCRIBE_TO_FUNNEL',
  'SET_KEYBOARD',
  'CLEAR_KEYBOARD',
]
</script>

<template>
  <aside data-test="funnel-canvas-palette" class="funnel-canvas-palette" :aria-label="t('funnels.canvas.palette.title')">
    <h3 class="funnel-canvas-palette__title">{{ t('funnels.canvas.palette.title') }}</h3>

    <section class="funnel-canvas-palette__section">
      <h4 class="funnel-canvas-palette__heading">{{ t('funnels.canvas.palette.steps') }}</h4>
      <button
        v-for="ty in STEP_TYPES"
        :key="ty"
        type="button"
        :data-test="`funnel-canvas-palette-step-${ty}`"
        :data-step-type="ty"
        class="funnel-canvas-palette__item"
        @click="emit('add-step', ty)"
      >
        {{ t(`funnels.steps.type.${ty}`) }}
      </button>
    </section>

    <section class="funnel-canvas-palette__section">
      <h4 class="funnel-canvas-palette__heading">{{ t('funnels.canvas.palette.other') }}</h4>
      <button
        type="button"
        data-test="funnel-canvas-palette-start"
        class="funnel-canvas-palette__item"
        :disabled="props.onStartExists"
        :title="props.onStartExists ? t('funnels.canvas.palette.startNodeExists') : t('funnels.canvas.palette.startNode')"
        @click="emit('add-start')"
      >
        {{ t('funnels.canvas.palette.startNode') }}
      </button>
      <button
        type="button"
        data-test="funnel-canvas-palette-trigger"
        class="funnel-canvas-palette__item"
        @click="emit('add-trigger')"
      >
        {{ t('funnels.canvas.palette.trigger') }}
      </button>
      <button
        type="button"
        data-test="funnel-canvas-palette-note"
        class="funnel-canvas-palette__item"
        @click="emit('add-note')"
      >
        {{ t('funnels.canvas.palette.note') }}
      </button>
    </section>
  </aside>
</template>

<style scoped>
.funnel-canvas-palette {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  border-right: 1px solid #e5e7eb;
  padding: 0.75rem;
  width: 12rem;
  overflow-y: auto;
}

.funnel-canvas-palette__title {
  font-size: 0.875rem;
  font-weight: 600;
  color: #111827;
}

.funnel-canvas-palette__heading {
  margin-bottom: 0.25rem;
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: #6b7280;
}

.funnel-canvas-palette__section {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.funnel-canvas-palette__item {
  border: 1px solid #d1d5db;
  border-radius: 0.375rem;
  background: #ffffff;
  padding: 0.375rem 0.5rem;
  text-align: left;
  font-size: 0.8125rem;
  color: #374151;
  cursor: pointer;
}

.funnel-canvas-palette__item:hover:not(:disabled) {
  background: #f3f4f6;
}

.funnel-canvas-palette__item:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
</style>
