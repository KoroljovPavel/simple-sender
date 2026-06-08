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
import AddStepDialog from '~/components/funnels/AddStepDialog.vue'
import EditStepDialog from '~/components/funnels/EditStepDialog.vue'
import FunnelTriggerSettings from '~/components/funnels/FunnelTriggerSettings.vue'
import FunnelMessagePreview from '~/components/funnels/FunnelMessagePreview.vue'
import type { FunnelResponse, FunnelStatus, FunnelStep, FunnelTriggerType, StepType } from '~/types/funnel'

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
const triggerType = ref<FunnelTriggerType>('on_start')
const triggerValue = ref('')
const keywords = ref<string[]>([])
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

const addOpen = ref(false)
const editOpen = ref(false)
const editIndex = ref(-1)
const editStep = computed<FunnelStep | null>(() => steps.value[editIndex.value] ?? null)

// ─── Task 6: message-preview panel ────────────────────────────────────────────
// Header toggle mounts <FunnelMessagePreview> to the right (desktop-first ≥1024px). The panel previews
// the step in focus: the one being edited (edit dialog open) wins; otherwise the first message step, so
// turning Preview on without an open dialog still shows something. No message step → neutral empty state.
const previewOpen = ref(false)
const MESSAGE_STEP_TYPES: StepType[] = ['SEND_MESSAGE', 'SEND_IMAGE', 'MENU']
const previewStep = computed<FunnelStep | null>(() => {
  if (editStep.value) return editStep.value
  return steps.value.find((s) => MESSAGE_STEP_TYPES.includes(s.stepType)) ?? null
})

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

// Is the CURRENT trigger draft complete enough to PATCH? The backend rejects an incomplete trigger with a
// 422 (keyword needs ≥1 keyword; tag_added/custom_field_set/event need a non-empty triggerValue), so
// switching the type alone — before the required value is filled — must NOT auto-save. activate() reuses
// this so a genuine activate of an incomplete funnel still round-trips and surfaces the inline 422.
function triggerReady(): boolean {
  switch (triggerType.value) {
    case 'on_start':
      return TRIGGER_VALUE_RE.test(triggerValue.value ?? '')
    case 'keyword':
      return (keywords.value ?? []).length >= 1
    case 'tag_added':
    case 'custom_field_set':
      return (triggerValue.value ?? '').trim().length > 0
    case 'event':
      return EVENT_NAME_RE.test(triggerValue.value ?? '')
    default:
      return false
  }
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

function applyResponse(res: FunnelResponse) {
  funnel.value = res
  steps.value = [...res.steps]
}

async function load() {
  loadError.value = null
  try {
    const res = await funnelsStore.fetchOne(funnelId.value)
    applyResponse(res)
    triggerType.value = (res.triggerType as FunnelTriggerType) ?? 'on_start'
    triggerValue.value = res.triggerValue ?? ''
    keywords.value = res.keywords ?? []
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

// PATCH the FULL funnel (metadata + trigger + entire ordered steps array). Position = order, so the
// server rewrites FunnelStep.order from the array index — the client never sends `order`.
async function persist(): Promise<boolean> {
  if (!funnel.value) return false
  saving.value = true
  saveError.value = null
  try {
    // Send only the value the active type owns — never leak a stale value from a previously-selected type
    // (Edge cases). keyword owns `keywords` and clears triggerValue; on_start/tag_added/custom_field_set/
    // event own `triggerValue` and clear `keywords`.
    const isKeyword = triggerType.value === 'keyword'
    const res = await funnelsStore.update(funnelId.value, {
      name: funnel.value.name,
      description: funnel.value.description,
      triggerType: triggerType.value,
      triggerValue: isKeyword ? null : triggerValue.value,
      keywords: isKeyword ? keywords.value : [],
      allowReEnter: funnel.value.allowReEnter,
      steps: steps.value,
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

function onAddStep(step: FunnelStep) {
  steps.value = [...steps.value, step]
  void persist()
}
function onSaveStep(index: number, step: FunnelStep) {
  steps.value = steps.value.map((s, i) => (i === index ? step : s))
  void persist()
}
function onDeleteStep(index: number) {
  steps.value = steps.value.filter((_, i) => i !== index)
  void persist()
}
function onMove(from: number, to: number) {
  if (to < 0 || to >= steps.value.length) return
  const arr = [...steps.value]
  const [moved] = arr.splice(from, 1)
  arr.splice(to, 0, moved)
  steps.value = arr
  void persist()
}
function openEdit(index: number) {
  editIndex.value = index
  editOpen.value = true
}

// Persist trigger edits (debounced) so the activated funnel uses the value the user sees. Only schedule a
// PATCH once the active type's required value is present (triggerReady) — switching the type alone, or
// editing toward a still-empty value, must NOT auto-save (the backend 422s an incomplete trigger). A
// genuine activate of an incomplete funnel still surfaces that 422 via activate()→persist().
let triggerTimer: ReturnType<typeof setTimeout> | null = null
function scheduleTriggerPersist() {
  if (!loaded.value) return
  if (!triggerReady()) return
  if (triggerTimer) clearTimeout(triggerTimer)
  triggerTimer = setTimeout(() => void persist(), 600)
}
watch([triggerType, triggerValue, keywords], scheduleTriggerPersist, { deep: true })
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
        <div class="flex items-center justify-between gap-4">
          <h2 class="text-lg font-semibold">{{ t('funnels.editor.stepsTitle') }}</h2>
          <button
            type="button"
            data-test="funnel-add-step"
            class="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
            @click="addOpen = true"
          >
            {{ t('funnels.steps.add') }}
          </button>
        </div>

        <FunnelStepsList
          :steps="steps"
          @move="onMove"
          @edit="openEdit"
          @delete="onDeleteStep"
          @add="addOpen = true"
        />

        <FunnelTriggerSettings
          v-model:trigger-type="triggerType"
          v-model:trigger-value="triggerValue"
          v-model:keywords="keywords"
          :bot-username="botUsername"
          :deep-link="funnel.deepLink"
        />
      </div>

      <FunnelMessagePreview v-if="previewOpen" :step="previewStep" class="mt-6 lg:mt-0" />
    </div>

    <AddStepDialog v-model:open="addOpen" :sibling-steps="steps" @add="onAddStep" />
    <EditStepDialog
      v-model:open="editOpen"
      :step="editStep"
      :index="editIndex"
      :sibling-steps="steps"
      @save="onSaveStep"
    />

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
