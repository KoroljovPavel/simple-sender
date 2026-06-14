<script setup lang="ts">
// Side panel for the funnel canvas (18-funnel-canvas, Task 6, Decision 10). Clicking a node opens this panel,
// which mounts the EXISTING field editors UNCHANGED:
//   - step node            → FunnelStepForm with :hide-target-pickers="true" (the canvas EDGES own the
//                            button/timeout/SUBSCRIBE targets, so the form hides those three pickers; the
//                            underlying values still flow through on submit — never dropped/zeroed).
//   - trigger / start node → FunnelTriggerSettings (defineModel-only, designed for canvas remount, Decision 11).
//
// FunnelStepForm is mounted with the ONLY new edit it received: the opt-in hideTargetPickers prop. Its field
// logic and the emitted FunnelStep are otherwise identical to the list/dialog callers.
//
// Mirror the AddStepDialog/EditStepDialog remount idiom: bump a key whenever a different node opens so the
// child form re-seeds its initialValues from the new node (the forms read `initial`/model on setup only).
import FunnelStepForm from '~/components/funnels/FunnelStepForm.vue'
import FunnelTriggerSettings from '~/components/funnels/FunnelTriggerSettings.vue'
import type { FunnelStep, FunnelTrigger, FunnelTriggerType } from '~/types/funnel'

// The selected canvas node, in the shape the canvas surface hands down: its kind + the originating model
// object (step for step nodes, trigger for trigger/start nodes). null when nothing is selected.
export interface SelectedNode {
  kind: 'step' | 'trigger' | 'start' | 'note'
  step?: FunnelStep | null
  trigger?: FunnelTrigger | null
  // For a note node: its text (seeds the editor) + its index in the notes[] array (so the parent can route
  // the edit back to the right note record).
  noteText?: string | null
  noteIndex?: number | null
  // Optional node id (used by the parent to route edits back to the right model object).
  nodeId?: string
  botUsername?: string | null
  deepLink?: string | null
}

// Mirrors the backend NoteDto.NOTE_TEXT_MAX (@Size cap, → 422 over the limit). Bounds the textarea so the
// author cannot author a note that the server would reject (Decision 7 — text length cap).
const NOTE_TEXT_MAX = 2000

const props = defineProps<{
  node: SelectedNode | null
}>()

const emit = defineEmits<{
  // A step node's form submitted an updated FunnelStep.
  submit: [step: FunnelStep]
  // A trigger/start node's settings changed (the trigger object, rebuilt from the editor's models).
  'update:trigger': [trigger: FunnelTrigger]
  // A note node's text changed — the parent updates notes[noteIndex].text and persists (Decision B / MINOR-2).
  'update:note-text': [payload: { noteIndex: number; text: string }]
  // The author closed/deselected the panel.
  close: []
}>()

const { t } = useI18n()

const isStep = computed(() => props.node?.kind === 'step')
const isTrigger = computed(() => props.node?.kind === 'trigger' || props.node?.kind === 'start')
const isNote = computed(() => props.node?.kind === 'note')

// ── Note text editor (Decision B / MINOR-2) ───────────────────────────────────────────────────────────
// Minimal text editing for a NOTE node: a bounded <textarea> seeded from the selected note's text. Editing
// it emits update:note-text so the parent updates notes[].text and persists via the existing path. Text is
// only ever rendered via escaped {{ }} interpolation (here + FunnelCanvasNode) — never v-html (XSS guard).
const noteText = ref<string>('')
watch(
  () => props.node,
  (n) => {
    if (n && n.kind === 'note') noteText.value = n.noteText ?? ''
  },
  { immediate: true },
)

function onNoteInput(event: Event): void {
  const text = (event.target as HTMLTextAreaElement).value
  noteText.value = text
  const idx = props.node?.noteIndex
  if (idx == null) return
  emit('update:note-text', { noteIndex: idx, text })
}

// Remount key — bumped whenever a different node opens so the child form/settings re-seed from the new model.
const formKey = ref(0)
watch(
  () => props.node,
  (n) => {
    if (n) formKey.value++
  },
  { immediate: true },
)

// ── Trigger editor models (FunnelTriggerSettings is defineModel-only) ─────────────────────────────────
// Seeded from the selected trigger on open; an edit emits a rebuilt FunnelTrigger upward so the parent can
// patch the right trigger in its array. on_start (start node) is type-locked — its type cannot change here.
const triggerType = ref<FunnelTriggerType>('on_start')
const triggerValue = ref<string>('')
const keywords = ref<string[]>([])

watch(
  () => props.node,
  (n) => {
    if (n && (n.kind === 'trigger' || n.kind === 'start') && n.trigger) {
      triggerType.value = (n.trigger.triggerType as FunnelTriggerType) ?? 'on_start'
      triggerValue.value = n.trigger.triggerValue ?? ''
      keywords.value = n.trigger.keywords ? [...n.trigger.keywords] : []
    }
  },
  { immediate: true },
)

// Re-emit the rebuilt trigger whenever any model the settings editor owns changes — the parent owns
// persistence (Task 7). entryStepId / canvasPosition are edge/layout-owned, carried through from the node.
watch([triggerType, triggerValue, keywords], () => {
  if (!isTrigger.value || !props.node?.trigger) return
  emit('update:trigger', {
    ...props.node.trigger,
    triggerType: triggerType.value,
    triggerValue: triggerValue.value,
    keywords: keywords.value,
  })
})

function onStepSubmit(step: FunnelStep) {
  emit('submit', step)
}
</script>

<template>
  <aside
    v-if="node"
    data-test="funnel-canvas-side-panel"
    class="funnel-canvas-side-panel"
    :aria-label="t('funnels.canvas.sidePanel.title')"
  >
    <header class="funnel-canvas-side-panel__header">
      <h3 class="funnel-canvas-side-panel__title">{{ t('funnels.canvas.sidePanel.title') }}</h3>
      <button
        type="button"
        data-test="funnel-canvas-side-panel-close"
        :aria-label="t('funnels.canvas.sidePanel.close')"
        class="funnel-canvas-side-panel__close"
        @click="emit('close')"
      >
        ×
      </button>
    </header>

    <!-- Step node → the UNCHANGED FunnelStepForm with the canvas target pickers hidden. -->
    <FunnelStepForm
      v-if="isStep"
      :key="formKey"
      :initial="node.step ?? null"
      :hide-target-pickers="true"
      :submit-label="t('funnels.steps.form.submitSave')"
      @submit="onStepSubmit"
    />

    <!-- Trigger / start node → the portable FunnelTriggerSettings (defineModel-only). -->
    <FunnelTriggerSettings
      v-else-if="isTrigger"
      :key="formKey"
      v-model:trigger-type="triggerType"
      v-model:trigger-value="triggerValue"
      v-model:keywords="keywords"
      :lock-type="node.kind === 'start'"
      :bot-username="node.botUsername ?? null"
      :deep-link="node.deepLink ?? null"
    />

    <!-- Note node → a minimal bounded text editor (Decision B). Text renders/previews only via escaped
         {{ }} (never v-html) — an injection payload shows as literal text. -->
    <div v-else-if="isNote" data-test="funnel-canvas-note-editor">
      <label for="funnel-canvas-note-text" class="mb-1 block text-sm font-medium">
        {{ t('funnels.canvas.noteEditor.label') }}
      </label>
      <textarea
        id="funnel-canvas-note-text"
        data-test="funnel-canvas-note-textarea"
        :value="noteText"
        :maxlength="NOTE_TEXT_MAX"
        rows="4"
        class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        :placeholder="t('funnels.canvas.noteEditor.placeholder')"
        @input="onNoteInput"
      ></textarea>
    </div>
  </aside>
  <aside
    v-else
    data-test="funnel-canvas-side-panel-empty"
    class="funnel-canvas-side-panel funnel-canvas-side-panel--empty"
  >
    <p class="funnel-canvas-side-panel__empty">{{ t('funnels.canvas.sidePanel.empty') }}</p>
  </aside>
</template>

<style scoped>
.funnel-canvas-side-panel {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  border-left: 1px solid #e5e7eb;
  padding: 0.75rem;
  width: 22rem;
  overflow-y: auto;
}

.funnel-canvas-side-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.funnel-canvas-side-panel__title {
  font-size: 0.875rem;
  font-weight: 600;
  color: #111827;
}

.funnel-canvas-side-panel__close {
  border: none;
  background: transparent;
  font-size: 1.25rem;
  line-height: 1;
  color: #6b7280;
  cursor: pointer;
}

.funnel-canvas-side-panel--empty {
  align-items: center;
  justify-content: center;
}

.funnel-canvas-side-panel__empty {
  font-size: 0.8125rem;
  color: #9ca3af;
}
</style>
