<script setup lang="ts">
import type { FunnelStep, PreviewStepResponse, StepType } from '~/types/funnel'

// Message-preview panel for the funnel editor (Task 6). Renders the CURRENT (possibly unsaved) step the
// author is editing AS IT WILL LOOK IN TELEGRAM — variables substituted + parse_mode-escaped on the
// backend (Decision 9, Task 2). The front-end only calls the store `preview` action and outputs the
// rendered string.
//
// CRITICAL (stored-XSS guard, OWASP A03 — Decision 9 / tech-spec Risks): the rendered string carries
// Telegram escaping (MarkdownV2/HTML), which is NOT browser-safe. It is output EXCLUSIVELY as text via
// {{ }} interpolation — NEVER v-html / innerHTML (matches the "never v-html" convention in
// FunnelStepForm.vue). Line breaks are preserved via CSS (whitespace-pre-wrap), not markup.
const props = defineProps<{ step: FunnelStep | null; stepNumber?: number | null }>()

const { t } = useI18n()

// Heading for the focused step: "Крок {N} · {localized type}". Shown for BOTH message and non-message
// steps so the author always knows WHICH step is previewed; hidden only in the empty (no step) state.
// {type} reuses the existing funnels.steps.type.* keys — never a hardcoded type name. Output is plain
// text via {{ }} (the heading composes localized strings, no markup).
const stepHeading = computed<string | null>(() => {
  if (!props.step || props.stepNumber == null) return null
  return t('funnels.editor.previewStepHeading', {
    number: props.stepNumber,
    type: t(`funnels.steps.type.${props.step.stepType}`),
  })
})
const route = useRoute()
const funnelsStore = useFunnelsStore()
// Error mapping lives in the component setup (NOT the store) — useApiError pulls useI18n() and the store
// action re-throws unmapped (store contract). 404 / network both resolve to a neutral panel message.
const resolveError = useApiError()

const funnelId = computed(() => String(route.params.funnelId))

// Message steps render a Telegram message; everything else gets a neutral placeholder (no backend call).
const MESSAGE_TYPES: StepType[] = ['SEND_MESSAGE', 'SEND_IMAGE', 'MENU']
const isMessageStep = computed(() => !!props.step && MESSAGE_TYPES.includes(props.step.stepType))

// The preview body for the active step: SEND_IMAGE previews its caption, SEND_MESSAGE/MENU their text.
// MENU has no required text on the backend — an empty body is fine (renders empty/neutral, no crash).
function previewText(step: FunnelStep): string {
  return (step.stepType === 'SEND_IMAGE' ? step.caption : step.text) ?? ''
}

const rendered = ref<PreviewStepResponse | null>(null)
const errorMessage = ref<string | null>(null)
const loading = ref(false)

async function runPreview() {
  const step = props.step
  // No step / non-message step → nothing to fetch; the template shows the empty/placeholder state.
  if (!step || !isMessageStep.value) {
    rendered.value = null
    errorMessage.value = null
    return
  }
  loading.value = true
  errorMessage.value = null
  try {
    rendered.value = await funnelsStore.preview(funnelId.value, step.id ?? '', {
      stepType: step.stepType,
      text: previewText(step),
      parseMode: step.parseMode ?? null,
    })
  } catch (err) {
    // 404 (unknown step) / network / 5xx → neutral in-panel message, never a throw or blank screen.
    rendered.value = null
    errorMessage.value = resolveError(err, 'funnels.preview')
  } finally {
    loading.value = false
  }
}

// Debounced reactive preview — mirrors the scheduleTriggerPersist/triggerTimer idiom in [funnelId].vue so
// typing in the editor does not bombard the backend on every keystroke.
let previewTimer: ReturnType<typeof setTimeout> | null = null
function schedulePreview() {
  if (previewTimer) clearTimeout(previewTimer)
  previewTimer = setTimeout(() => void runPreview(), 600)
}

// Re-run when the focused step OR its previewable fields change. immediate so the panel renders on mount
// (tests rely on the first call happening without an edit). Run directly on first tick, debounce edits.
let primed = false
watch(
  () => [
    props.step?.stepType,
    props.step?.id,
    props.step?.text,
    props.step?.caption,
    props.step?.parseMode,
  ],
  () => {
    if (!primed) {
      primed = true
      void runPreview()
    } else {
      schedulePreview()
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (previewTimer) clearTimeout(previewTimer)
})
</script>

<template>
  <aside
    data-test="funnel-preview-panel"
    class="rounded-md border bg-gray-50 p-4 text-sm"
    aria-live="polite"
  >
    <h3 class="mb-3 font-semibold text-gray-700">{{ t('funnels.editor.preview') }}</h3>

    <!-- Which step is previewed: "Крок {N} · {type}". Shown for any focused step (message or not); plain
         text via {{ }}, never v-html. -->
    <p
      v-if="stepHeading"
      data-test="funnel-preview-step-heading"
      class="mb-3 text-xs font-medium text-gray-500"
    >{{ stepHeading }}</p>

    <!-- No step in focus → neutral empty state (not an error). -->
    <p v-if="!step" data-test="funnel-preview-empty" class="text-gray-500">
      {{ t('funnels.editor.previewPlaceholder') }}
    </p>

    <!-- Non-message step → neutral placeholder; the backend is never called. -->
    <p
      v-else-if="!isMessageStep"
      data-test="funnel-preview-placeholder"
      class="text-gray-500"
    >
      {{ t('funnels.editor.previewPlaceholder') }}
    </p>

    <template v-else>
      <p v-if="loading" data-test="funnel-preview-loading" class="text-gray-500">
        {{ t('common.loading') }}
      </p>

      <!-- Error (404 unknown step / network / 5xx) → neutral in-panel message, never blank/500. -->
      <p
        v-else-if="errorMessage"
        data-test="funnel-preview-error"
        class="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-amber-800"
      >
        {{ errorMessage }}
      </p>

      <template v-else-if="rendered">
        <!-- Sample-data indicator: backend substituted sample placeholders (e.g. bot owner not linked). -->
        <p
          v-if="rendered.sampleData"
          data-test="funnel-preview-sample-data"
          class="mb-2 inline-block rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-800"
        >
          {{ t('funnels.editor.previewSampleData') }}
        </p>

        <!-- The Telegram-rendered string — output as TEXT ONLY ({{ }}), NEVER v-html. Telegram escaping is
             NOT browser-safe; v-html here would be a stored-XSS sink (OWASP A03). Line breaks via CSS. -->
        <div
          data-test="funnel-preview-rendered"
          class="whitespace-pre-wrap break-words rounded-md border bg-white px-3 py-2 text-gray-900"
        >{{ rendered.rendered }}</div>
      </template>
    </template>
  </aside>
</template>
