<script setup lang="ts">
import { Badge } from '~/components/ui/badge'
import FunnelStepsList from '~/components/funnels/FunnelStepsList.vue'
import AddStepDialog from '~/components/funnels/AddStepDialog.vue'
import EditStepDialog from '~/components/funnels/EditStepDialog.vue'
import FunnelTriggerSettings from '~/components/funnels/FunnelTriggerSettings.vue'
import type { FunnelResponse, FunnelStatus, FunnelStep } from '~/types/funnel'

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
const triggerValue = ref('')
const loaded = ref(false)
const saving = ref(false)
const saveError = ref<string | null>(null)
const activateError = ref<string | null>(null)

const addOpen = ref(false)
const editOpen = ref(false)
const editIndex = ref(-1)
const editStep = computed<FunnelStep | null>(() => steps.value[editIndex.value] ?? null)

const botUsername = computed(() => botStore.current?.telegramUsername ?? null)
const status = computed<FunnelStatus | null>(() => funnel.value?.status ?? null)
const STATUS_VARIANT: Record<FunnelStatus, 'secondary' | 'default' | 'outline'> = {
  draft: 'secondary',
  active: 'default',
  paused: 'outline',
}
const TRIGGER_VALUE_RE = /^[A-Za-z0-9_-]{0,64}$/

function statusOf(err: unknown): number | null {
  const e = err as { statusCode?: number; status?: number; response?: { status?: number } }
  return e?.statusCode ?? e?.status ?? e?.response?.status ?? null
}

// 422 carries a business code in the body (err.data.code) — map it to a localized inline message; fall
// back to the status/generic message via useApiError when the code is absent or unmapped.
function resolveFunnelError(err: unknown, contextKey: string): string {
  const code = (err as { data?: { code?: string } })?.data?.code
  if (code && te(`errors.funnels.${code}`)) return t(`errors.funnels.${code}`)
  return resolveError(err, contextKey)
}

function applyResponse(res: FunnelResponse) {
  funnel.value = res
  steps.value = [...res.steps]
}

async function load() {
  try {
    const res = await funnelsStore.fetchOne(funnelId.value)
    applyResponse(res)
    triggerValue.value = res.triggerValue ?? ''
    loaded.value = true
  } catch (err) {
    // Anti-IDOR uniform 404 for missing/cross-owner funnel → graceful redirect to the list.
    if (statusOf(err) === 404) {
      await navigateTo(localePath(`/projects/${projectId.value}/funnels`))
      return
    }
    saveError.value = resolveError(err, 'funnels.list')
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
    const res = await funnelsStore.update(funnelId.value, {
      name: funnel.value.name,
      description: funnel.value.description,
      triggerType: 'on_start',
      triggerValue: triggerValue.value,
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

// Persist trigger_value edits (debounced) so the activated funnel uses the value the user sees in the
// deep-link preview. Only persist a syntactically valid value (avoid a 422 round-trip on every keystroke).
let triggerTimer: ReturnType<typeof setTimeout> | null = null
watch(triggerValue, (v) => {
  if (!loaded.value) return
  if (!TRIGGER_VALUE_RE.test(v ?? '')) return
  if (triggerTimer) clearTimeout(triggerTimer)
  triggerTimer = setTimeout(() => void persist(), 600)
})
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
  const saved = await persist()
  if (!saved) {
    activateError.value = saveError.value
    return
  }
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
</script>

<template>
  <div v-if="funnel" class="space-y-6">
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

    <p v-if="activateError" data-test="funnel-activate-error" class="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
      {{ activateError }}
    </p>
    <p v-if="saveError" data-test="funnel-save-error" class="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
      {{ saveError }}
    </p>

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
      v-model:trigger-value="triggerValue"
      :bot-username="botUsername"
      :deep-link="funnel.deepLink"
    />

    <AddStepDialog v-model:open="addOpen" @add="onAddStep" />
    <EditStepDialog v-model:open="editOpen" :step="editStep" :index="editIndex" @save="onSaveStep" />
  </div>
</template>
