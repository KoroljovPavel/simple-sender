<script setup lang="ts">
import { toast } from 'vue-sonner'
import { Badge } from '~/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '~/components/ui/dialog'
import FunnelStepsList from '~/components/funnels/FunnelStepsList.vue'
import FunnelTriggersPanel from '~/components/funnels/FunnelTriggersPanel.vue'
import FunnelMessagePreview from '~/components/funnels/FunnelMessagePreview.vue'
import FunnelCanvas from '~/components/funnels/FunnelCanvas.client.vue'
import type {
  CanvasPosition,
  FunnelNote,
  FunnelResponse,
  FunnelStatus,
  FunnelStep,
  FunnelTrigger,
  StepType,
} from '~/types/funnel'

definePageMeta({ layout: 'default' })

const { t, te } = useI18n()
const route = useRoute()
const localePath = useLocalePath()
const funnelsStore = useFunnelsStore()
const botStore = useBotStore()
// Error mapping lives in the page setup (NOT the store) — useApiError pulls useI18n().
const resolveError = useApiError()

const projectId = computed(() => String(route.params.projectId))
const funnelId = computed(() => String(route.params.funnelId))

const funnel = ref<FunnelResponse | null>(null)
const steps = ref<FunnelStep[]>([])
// Phase 8 (17-funnel-multi-entry): the funnel carries a LIST of triggers (Decision 1), each with its own
// triggerType/triggerValue/keywords + entryStepId. The panel (FunnelTriggersPanel) edits them via
// v-model:triggers; the page owns the array, the autosave gate and the PATCH payload.
const triggers = ref<FunnelTrigger[]>([])
// Phase 2 (18-funnel-canvas): free-floating canvas annotations. The page owns the array and rides it inside
// the full-replace PATCH alongside steps/triggers. Null from the backend (old funnels) → empty array.
const notes = ref<FunnelNote[]>([])
const loaded = ref(false)
const loadError = ref<string | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)
const activateError = ref<string | null>(null)

// Task 5 author-tooling. Test for me writes its error to its OWN ref so the inline hint sits beside the
// Test-for-me button (not the activate banner). Stop-all reuses the list's Dialog-confirm pattern.
const testError = ref<string | null>(null)
const testing = ref(false)
const stopOpen = ref(false)
const stopping = ref(false)

// ─── Task 6: message-preview panel ────────────────────────────────────────────
// Header toggle mounts <FunnelMessagePreview> to the right (desktop-first ≥1024px). The panel previews
// the step in focus: the row clicked in the (read-only) list, else the first message step, so turning
// Preview on without a clicked row still shows something. No message step → neutral empty state.
// Task 7 (Decision 2): the canvas owns step editing (its own side panel), so the former page-level Edit
// dialog and its "edited step wins" preview-pin branch are gone — preview is now purely selection-driven.
const previewOpen = ref(false)
const MESSAGE_STEP_TYPES: StepType[] = ['MESSAGE']
// A step row click drives the preview to THAT step. -1 = nothing clicked → fall back to the first
// message step (the original default). Cleared when the clicked index no longer points at a real step.
const previewSelectedIndex = ref(-1)
function onSelectStep(index: number) {
  previewSelectedIndex.value = index
}
// Selection priority: clicked step > first message step.
const firstMessageIndex = computed(() => steps.value.findIndex((s) => MESSAGE_STEP_TYPES.includes(s.stepType)))
const previewIndex = computed(() => {
  if (steps.value[previewSelectedIndex.value]) return previewSelectedIndex.value
  return firstMessageIndex.value
})
const previewStep = computed<FunnelStep | null>(() => steps.value[previewIndex.value] ?? null)
// 1-based number of the previewed step for the panel heading (null when no step is in focus).
const previewStepNumber = computed<number | null>(() => (previewIndex.value >= 0 ? previewIndex.value + 1 : null))

const botUsername = computed(() => botStore.current?.telegramUsername ?? null)
const status = computed<FunnelStatus | null>(() => funnel.value?.status ?? null)
const STATUS_VARIANT: Record<FunnelStatus, 'secondary' | 'default' | 'outline'> = {
  draft: 'secondary',
  active: 'default',
  paused: 'outline',
}
// CANONICAL — mirror FunnelService.TRIGGER_VALUE_PATTERN (on_start; empty = bare /start) and the
// event_name slug EVENT_NAME_PATTERN. These gate the auto-save only; the server is still the validator.
const TRIGGER_VALUE_RE = /^[A-Za-z0-9_-]{0,64}$/
const EVENT_NAME_RE = /^[A-Za-z0-9_-]{1,64}$/

// Is a SINGLE trigger draft complete enough to PATCH? Per-element (Phase 8): the backend rejects an
// incomplete trigger with a 422 (keyword needs ≥1 keyword; tag_added/custom_field_set/event need a
// non-empty triggerValue), so switching ONE trigger's type — before its required value is filled — must
// NOT auto-save. The debounced autosave only fires once EVERY trigger is ready (triggers.every(...)), and
// activate() reuses the same gate so a genuine activate of an incomplete funnel still surfaces the 422.
function triggerReady(trigger: FunnelTrigger): boolean {
  switch (trigger.triggerType) {
    case 'on_start':
      return TRIGGER_VALUE_RE.test(trigger.triggerValue ?? '')
    case 'keyword':
      return (trigger.keywords ?? []).length >= 1
    case 'tag_added':
    case 'custom_field_set':
      return (trigger.triggerValue ?? '').trim().length > 0
    case 'event':
      return EVENT_NAME_RE.test(trigger.triggerValue ?? '')
    default:
      return false
  }
}

// A funnel always needs at least the on_start main entry, so the panel always has a row to render even
// when the backend returns an empty/absent triggers array (edge case).
function defaultTriggers(): FunnelTrigger[] {
  return [{ triggerType: 'on_start', triggerValue: '', keywords: null, entryStepId: null }]
}

function statusOf(err: unknown): number | null {
  const e = err as { statusCode?: number; status?: number; response?: { status?: number } }
  return e?.statusCode ?? e?.status ?? e?.response?.status ?? null
}

// 422 carries a business code in the body — map it to a localized inline message; fall back to the
// status/generic message via useApiError when the code is absent or unmapped. ofetch exposes the parsed
// body as `data`; the 404 interceptor's re-thrown shape nests it under `response._data` — read both.
function errorCode(err: unknown): string | undefined {
  const e = err as { data?: { code?: string }; response?: { _data?: { code?: string } } }
  return e?.data?.code ?? e?.response?._data?.code
}
function resolveFunnelError(err: unknown, contextKey: string): string {
  const code = errorCode(err)
  if (code && te(`errors.funnels.${code}`)) return t(`errors.funnels.${code}`)
  return resolveError(err, contextKey)
}

// Re-entrancy guard: applyResponse() re-assigns `triggers` from the server, which the deep watch would
// otherwise treat as a fresh edit and re-schedule a PATCH — an infinite persist→applyResponse→watch loop
// (the server-echoed array always has a new identity). Suppress exactly one watch cycle when we apply a
// server response.
let applyingResponse = false
function applyResponse(res: FunnelResponse) {
  funnel.value = res
  steps.value = [...res.steps]
  // Notes ride the same full-replace round-trip; the server mints each note's id (so re-read here picks up the
  // minted ids without dropping them). Null/absent (old funnels) → empty array.
  notes.value = (res.notes ?? []).map((n) => ({ ...n }))
  applyingResponse = true
  // Fall back to a lone on_start trigger so the panel always has the main entry (edge case: empty/absent).
  triggers.value = res.triggers?.length ? res.triggers.map((tr) => ({ ...tr })) : defaultTriggers()
}

async function load() {
  loadError.value = null
  try {
    const res = await funnelsStore.fetchOne(funnelId.value)
    applyResponse(res)
    loaded.value = true
  } catch (err) {
    // Anti-IDOR uniform 404 for missing/cross-owner funnel → graceful redirect to the list.
    if (statusOf(err) === 404) {
      await navigateTo(localePath(`/projects/${projectId.value}/funnels`))
      return
    }
    // Any other load failure (5xx/network) → visible error + retry, NOT a blank page.
    loadError.value = resolveError(err, 'funnels.list')
  }
}

onMounted(() => {
  if (import.meta.client) {
    load()
    // Bot username feeds the live deep-link preview for a draft funnel (active uses the server deepLink).
    void botStore.fetch(projectId.value)
  }
})

// Per-element trigger sanitizer: send ONLY the fields the element's type owns — never leak a stale value
// from a previously-selected type (Edge cases). keyword owns `keywords` and clears triggerValue; the other
// types own `triggerValue` and clear `keywords`. entryStepId is carried as-is (null for non-event types).
function sanitizeTrigger(tr: FunnelTrigger): FunnelTrigger {
  const isKeyword = tr.triggerType === 'keyword'
  return {
    triggerType: tr.triggerType,
    triggerValue: isKeyword ? null : (tr.triggerValue ?? ''),
    keywords: isKeyword ? (tr.keywords ?? []) : [],
    entryStepId: tr.entryStepId ?? null,
  }
}

// PATCH the FULL funnel (metadata + trigger array + entire ordered steps array). Position = order, so the
// server rewrites FunnelStep.order from the array index — the client never sends `order`.
async function persist(): Promise<boolean> {
  if (!funnel.value) return false
  saving.value = true
  saveError.value = null
  try {
    const res = await funnelsStore.update(funnelId.value, {
      name: funnel.value.name,
      description: funnel.value.description,
      triggers: triggers.value.map(sanitizeTrigger),
      allowReEnter: funnel.value.allowReEnter,
      steps: steps.value,
      // Full-replace like steps/triggers: a fresh note carries id:null and the server mints it (Decision 7).
      notes: notes.value,
    })
    applyResponse(res)
    return true
  } catch (err) {
    saveError.value = resolveFunnelError(err, 'funnels.update')
    return false
  } finally {
    saving.value = false
  }
}

// ─── Task 7 (18-funnel-canvas): canvas wiring ─────────────────────────────────
// The canvas is the SINGLE live structural-editing surface (Decision 2). It is props-in/emits-out: it
// receives steps/triggers/notes and emits the whole updated array when the author draws an edge, adds/deletes
// a node or edits a node in the side panel. We relay each emitted array into the local model and reuse the
// SAME debounced full-replace persist() — never a second save path.
function onCanvasSteps(next: FunnelStep[]) {
  steps.value = next
  void persist()
}
function onCanvasTriggers(next: FunnelTrigger[]) {
  triggers.value = next
  void persist()
}
function onCanvasNotes(next: FunnelNote[]) {
  notes.value = next
  void persist()
}

// A finished node drag re-emits { nodeId, position }. Resolve the node id back to the matching model object
// (start → the on_start trigger; trigger:<i> / note:<i> → that array element; otherwise a step matched by id)
// and write its canvasPosition, then persist via the existing debounced full-replace path. An unsaved step
// node (synthetic `unsaved-step:<n>` id, no real id yet) has nothing to match → no-op until it is persisted.
function onNodeDragStop(payload: { nodeId: string; position: CanvasPosition }) {
  const { nodeId, position } = payload
  if (nodeId === 'start') {
    const idx = triggers.value.findIndex((tr) => tr.triggerType === 'on_start')
    if (idx < 0) return
    triggers.value = triggers.value.map((tr, i) => (i === idx ? { ...tr, canvasPosition: position } : tr))
  } else if (nodeId.startsWith('trigger:')) {
    const idx = Number(nodeId.slice('trigger:'.length))
    if (!triggers.value[idx]) return
    triggers.value = triggers.value.map((tr, i) => (i === idx ? { ...tr, canvasPosition: position } : tr))
  } else if (nodeId.startsWith('note:')) {
    const idx = Number(nodeId.slice('note:'.length))
    if (!notes.value[idx]) return
    notes.value = notes.value.map((n, i) => (i === idx ? { ...n, canvasPosition: position } : n))
  } else {
    const idx = steps.value.findIndex((s) => s.id != null && s.id === nodeId)
    if (idx < 0) return
    steps.value = steps.value.map((s, i) => (i === idx ? { ...s, canvasPosition: position } : s))
  }
  void persist()
}

// Persist trigger edits (debounced) so the activated funnel uses the values the user sees. Only schedule a
// PATCH once EVERY trigger's required value is present (triggers.every(triggerReady)) — switching one
// trigger's type alone, or editing toward a still-empty value, must NOT auto-save (the backend 422s an
// incomplete trigger). A genuine activate of an incomplete funnel still surfaces that 422 via
// activate()→persist().
let triggerTimer: ReturnType<typeof setTimeout> | null = null
function scheduleTriggerPersist() {
  if (!loaded.value) return
  // Skip the watch cycle caused by applyResponse() re-assigning `triggers` from the server (not a user
  // edit) — otherwise persist→applyResponse→watch→persist loops forever.
  if (applyingResponse) {
    applyingResponse = false
    return
  }
  // ALWAYS cancel a pending flush first: if a later edit makes the array incomplete (e.g. an empty event
  // trigger was just added), an earlier scheduled PATCH must NOT fire — otherwise it would flush a
  // not-ready array and 422. Only (re)arm the timer when EVERY trigger is ready.
  if (triggerTimer) clearTimeout(triggerTimer)
  triggerTimer = null
  if (!triggers.value.every(triggerReady)) return
  triggerTimer = setTimeout(() => void persist(), 600)
}
// Deep watch: trigger edits mutate fields INSIDE the array elements, not just the array reference.
watch(triggers, scheduleTriggerPersist, { deep: true })
onBeforeUnmount(() => {
  if (triggerTimer) clearTimeout(triggerTimer)
})

async function activate() {
  activateError.value = null
  // Flush pending edits (steps + trigger_value) FIRST so the server validates the on-screen state.
  if (triggerTimer) {
    clearTimeout(triggerTimer)
    triggerTimer = null
  }
  // persist() surfaces its own failure via saveError — don't duplicate it into activateError.
  if (!(await persist())) return
  try {
    applyResponse(await funnelsStore.activate(funnelId.value))
  } catch (err) {
    activateError.value = resolveFunnelError(err, 'funnels.activate')
  }
}

async function pause() {
  activateError.value = null
  try {
    applyResponse(await funnelsStore.pause(funnelId.value))
  } catch (err) {
    activateError.value = resolveFunnelError(err, 'funnels.pause')
  }
}

// ─── Task 5: author tooling (Duplicate / Stop-all / Test for me) ──────────────
// Duplicate: success → toast + navigate into the NEW funnel's editor (server returns the fresh draft copy
// with its own id); any failure → toast, no navigation (no inline — there's no business code to surface).
async function duplicate() {
  try {
    const created = await funnelsStore.duplicate(funnelId.value)
    toast.success(t('funnels.editor.duplicateResult'))
    await navigateTo(localePath(`/projects/${projectId.value}/funnels/${created.id}`))
  } catch (err) {
    toast.error(resolveError(err, 'funnels.duplicate'))
  }
}

// Test for me: a 422 with a MAPPED business code (funnel_owner_not_linked / validation) → inline hint
// beside the button; any other failure (network/5xx/unmapped) → toast. Mirrors the resolveFunnelError
// branch, but inspect the code first to pick the surface.
async function testRun() {
  testError.value = null
  testing.value = true
  try {
    await funnelsStore.testRun(funnelId.value)
    toast.success(t('funnels.editor.testForMeResult'))
  } catch (err) {
    const code = errorCode(err)
    if (code && te(`errors.funnels.${code}`)) {
      testError.value = t(`errors.funnels.${code}`)
    } else {
      toast.error(resolveError(err, 'funnels.testRun'))
    }
  } finally {
    testing.value = false
  }
}

// Stop-all: destructive → confirm via the shared Dialog. Confirm cancels all active runs and reports the
// count (0 is not an error). Cancel just closes the dialog.
async function confirmStopAll() {
  stopping.value = true
  try {
    const { cancelled } = await funnelsStore.stopAllExecutions(funnelId.value)
    stopOpen.value = false
    toast.success(t('funnels.editor.stopAll.result', { count: cancelled }))
  } catch (err) {
    stopOpen.value = false
    toast.error(resolveError(err, 'funnels.stopAll'))
  } finally {
    stopping.value = false
  }
}
</script>

<template>
  <p
    v-if="loadError"
    data-test="funnel-load-error"
    class="flex items-center justify-between rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
  >
    <span>{{ loadError }}</span>
    <button
      type="button"
      data-test="funnel-load-retry"
      class="rounded-md border border-red-300 px-2.5 py-1 text-sm hover:bg-red-100"
      @click="load"
    >
      {{ t('common.retry') }}
    </button>
  </p>

  <p v-else-if="!funnel" data-test="funnel-editor-loading" class="px-4 py-10 text-center text-sm text-gray-500">
    {{ t('common.loading') }}
  </p>

  <div v-else class="space-y-6">
    <div class="flex items-center justify-between gap-4">
      <div class="flex min-w-0 items-center gap-3">
        <NuxtLink
          :to="localePath(`/projects/${projectId}/funnels`)"
          data-test="funnel-editor-back"
          class="rounded-md border px-2.5 py-1 text-sm hover:bg-gray-50"
        >
          ←
        </NuxtLink>
        <h1 class="truncate text-2xl font-semibold">{{ funnel.name }}</h1>
        <Badge v-if="status" :variant="STATUS_VARIANT[status]" :data-test="`funnel-status-${status}`">
          {{ t(`funnels.status.${status}`) }}
        </Badge>
      </div>
      <div class="flex items-center gap-2">
        <span v-if="saving" data-test="funnel-saving" class="text-sm text-gray-500">{{ t('funnels.editor.saving') }}</span>
        <button
          type="button"
          data-test="funnel-preview-toggle"
          :aria-pressed="previewOpen"
          class="rounded-md border px-4 py-2 text-sm font-medium hover:bg-gray-50"
          :class="previewOpen ? 'border-blue-500 bg-blue-50 text-blue-700' : ''"
          @click="previewOpen = !previewOpen"
        >
          {{ t('funnels.editor.preview') }}
        </button>
        <button
          type="button"
          data-test="funnel-test-run"
          class="rounded-md border px-4 py-2 text-sm font-medium hover:bg-gray-50"
          @click="testRun"
        >
          {{ t('funnels.editor.testForMe') }}
        </button>
        <button
          type="button"
          data-test="funnel-editor-duplicate"
          class="rounded-md border px-4 py-2 text-sm font-medium hover:bg-gray-50"
          @click="duplicate"
        >
          {{ t('funnels.editor.duplicate') }}
        </button>
        <button
          type="button"
          data-test="funnel-editor-stop-all"
          class="rounded-md border border-red-300 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-50"
          @click="stopOpen = true"
        >
          {{ t('funnels.editor.stopAll.button') }}
        </button>
        <button
          v-if="status === 'active'"
          type="button"
          data-test="funnel-pause"
          class="rounded-md border px-4 py-2 text-sm font-medium hover:bg-gray-50"
          @click="pause"
        >
          {{ t('funnels.editor.pause') }}
        </button>
        <button
          v-else
          type="button"
          data-test="funnel-activate"
          class="rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700"
          @click="activate"
        >
          {{ t('funnels.editor.activate') }}
        </button>
      </div>
    </div>

    <p
      v-if="testError"
      data-test="funnel-test-run-error"
      class="rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800"
    >
      {{ testError }}
    </p>

    <p v-if="activateError" data-test="funnel-activate-error" class="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
      {{ activateError }}
    </p>
    <p v-if="saveError" data-test="funnel-save-error" class="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
      {{ saveError }}
    </p>

    <!-- Two-column on ≥1024px when Preview is on: editor left, message-preview panel right (desktop-first
         per ux-guidelines). Below lg the panel stacks under the editor — no complex narrow layout. -->
    <div :class="previewOpen ? 'lg:grid lg:grid-cols-[minmax(0,1fr)_360px] lg:gap-6' : ''">
      <div class="min-w-0 space-y-6">
        <!-- Canvas (Task 7): the single live structural-editing surface. *.client.vue + <ClientOnly> keep Vue
             Flow's window/DOM access off the SSR path (Decision 12 — `window is not defined`). -->
        <div>
          <h2 class="mb-3 text-lg font-semibold">{{ t('funnels.canvas.title') }}</h2>
          <ClientOnly>
            <div data-test="funnel-canvas-host" class="h-[560px] rounded-md border">
              <FunnelCanvas
                :steps="steps"
                :triggers="triggers"
                :notes="notes"
                :bot-username="botUsername"
                :deep-link="funnel.deepLink"
                @update:steps="onCanvasSteps"
                @update:triggers="onCanvasTriggers"
                @update:notes="onCanvasNotes"
                @node-drag-stop="onNodeDragStop"
              />
            </div>
          </ClientOnly>
        </div>

        <div class="flex items-center justify-between gap-4">
          <h2 class="text-lg font-semibold">{{ t('funnels.editor.stepsTitle') }}</h2>
        </div>

        <!-- Read-only view (Decision 2): the canvas owns structural edits; the list stays mounted as a view.
             Only the non-structural `select` (preview driving) emit is wired. -->
        <FunnelStepsList
          :steps="steps"
          :readonly="true"
          :selected-index="previewOpen ? previewIndex : undefined"
          @select="onSelectStep"
        />

        <FunnelTriggersPanel
          v-model:triggers="triggers"
          :steps="steps"
          :bot-username="botUsername"
          :deep-link="funnel.deepLink"
        />
      </div>

      <FunnelMessagePreview v-if="previewOpen" :step="previewStep" :step-number="previewStepNumber" class="mt-6 lg:mt-0" />
    </div>

    <Dialog :open="stopOpen" @update:open="(v: boolean) => { if (!v) stopOpen = false }">
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{{ t('funnels.editor.stopAll.title') }}</DialogTitle>
          <DialogDescription>{{ t('funnels.editor.stopAll.message') }}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <button
            type="button"
            data-test="funnel-stop-all-cancel"
            class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
            @click="stopOpen = false"
          >
            {{ t('funnels.editor.stopAll.cancel') }}
          </button>
          <button
            type="button"
            data-test="funnel-stop-all-confirm"
            :disabled="stopping"
            class="rounded-md bg-red-600 px-3 py-1.5 text-sm text-white hover:bg-red-700 disabled:opacity-50"
            @click="confirmStopAll"
          >
            {{ t('funnels.editor.stopAll.confirm') }}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
