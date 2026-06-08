<script setup lang="ts">
import { toast } from 'vue-sonner'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '~/components/ui/dialog'
import { Badge } from '~/components/ui/badge'
import CreateFunnelDialog from '~/components/funnels/CreateFunnelDialog.vue'
import type { FunnelStatusFilter, FunnelStatus, FunnelSummaryResponse } from '~/types/funnel'

definePageMeta({ layout: 'default' })

const { t } = useI18n()
const route = useRoute()
const localePath = useLocalePath()
const funnelsStore = useFunnelsStore()
// Error mapping lives in the page setup (NOT the store) — useApiError pulls useI18n().
const resolveError = useApiError()

const projectId = computed(() => String(route.params.projectId))
const STATUS_FILTERS: FunnelStatusFilter[] = ['all', 'draft', 'active', 'paused']
const statusFilter = ref<FunnelStatusFilter>('all')

const createOpen = ref(false)
const deleteTarget = ref<FunnelSummaryResponse | null>(null)
const deleting = ref(false)
const loadError = ref<string | null>(null)
const deleteError = ref<string | null>(null)

// Task 5 author tooling — Stop-all confirm uses its OWN refs (separate from the delete dialog).
const stopTarget = ref<FunnelSummaryResponse | null>(null)
const stopping = ref(false)
const stopError = ref<string | null>(null)

const STATUS_VARIANT: Record<FunnelStatus, 'secondary' | 'default' | 'outline'> = {
  draft: 'secondary',
  active: 'default',
  paused: 'outline',
}

async function load() {
  loadError.value = null
  try {
    await funnelsStore.fetch(statusFilter.value)
  } catch (err) {
    loadError.value = resolveError(err, 'funnels.list')
  }
}

onMounted(() => {
  if (import.meta.client) load()
})

watch(statusFilter, () => {
  if (import.meta.client) load()
})

function openFunnel(funnel: FunnelSummaryResponse) {
  void navigateTo(localePath(`/projects/${projectId.value}/funnels/${funnel.id}`))
}

function askDelete(funnel: FunnelSummaryResponse) {
  deleteError.value = null
  deleteTarget.value = funnel
}

async function confirmDelete() {
  const target = deleteTarget.value
  if (!target) return
  deleting.value = true
  deleteError.value = null
  try {
    await funnelsStore.delete(target.id)
    deleteTarget.value = null
  } catch (err) {
    deleteError.value = resolveError(err, 'funnels.delete')
  } finally {
    deleting.value = false
  }
}

// Duplicate: the store's duplicate() can't append the new row (its id isn't in the loaded list — syncRow
// is a no-op for it), so refetch the list respecting the current filter to surface the fresh draft copy.
// success/failure → toast (no business code to surface inline here).
async function duplicateFunnel(funnel: FunnelSummaryResponse) {
  try {
    await funnelsStore.duplicate(funnel.id)
    toast.success(t('funnels.editor.duplicateResult'))
    await funnelsStore.fetch(statusFilter.value)
  } catch (err) {
    toast.error(resolveError(err, 'funnels.duplicate'))
  }
}

function askStopAll(funnel: FunnelSummaryResponse) {
  stopError.value = null
  stopTarget.value = funnel
}

// Stop-all: cancel all active runs of the funnel; the count (0 included) is reported via toast. A failure
// shows inline in the dialog body (like deleteError), the dialog stays open.
async function confirmStopAll() {
  const target = stopTarget.value
  if (!target) return
  stopping.value = true
  stopError.value = null
  try {
    const { cancelled } = await funnelsStore.stopAllExecutions(target.id)
    stopTarget.value = null
    toast.success(t('funnels.editor.stopAll.result', { count: cancelled }))
  } catch (err) {
    stopError.value = resolveError(err, 'funnels.stopAll')
  } finally {
    stopping.value = false
  }
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString()
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between gap-4">
      <h1 class="text-2xl font-semibold">{{ t('funnels.title') }}</h1>
      <button
        type="button"
        data-test="funnels-create-trigger"
        class="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        @click="createOpen = true"
      >
        {{ t('funnels.createButton') }}
      </button>
    </div>

    <div class="flex items-center gap-2">
      <label for="funnels-status-filter" class="text-sm text-gray-600">{{ t('funnels.filter.label') }}</label>
      <select
        id="funnels-status-filter"
        v-model="statusFilter"
        data-test="funnels-status-filter"
        class="rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
      >
        <option v-for="s in STATUS_FILTERS" :key="s" :value="s">{{ t(`funnels.filter.${s}`) }}</option>
      </select>
    </div>

    <p
      v-if="loadError"
      data-test="funnels-load-error"
      class="flex items-center justify-between rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
    >
      <span>{{ loadError }}</span>
      <button
        type="button"
        data-test="funnels-retry"
        class="rounded-md border border-red-300 px-2.5 py-1 text-sm hover:bg-red-100"
        @click="load"
      >
        {{ t('common.retry') }}
      </button>
    </p>

    <p v-else-if="funnelsStore.loading" data-test="funnels-loading" class="px-4 py-10 text-center text-sm text-gray-500">
      {{ t('common.loading') }}
    </p>

    <div
      v-else-if="funnelsStore.funnels.length === 0"
      data-test="funnels-empty"
      class="rounded-md border border-dashed bg-gray-50 px-4 py-10 text-center text-sm text-gray-600"
    >
      <span class="block font-medium">{{ t('funnels.emptyState.title') }}</span>
      <span class="mt-1 block">{{ t('funnels.emptyState.body') }}</span>
      <button
        type="button"
        data-test="funnels-empty-cta"
        class="mt-4 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        @click="createOpen = true"
      >
        {{ t('funnels.emptyState.cta') }}
      </button>
    </div>

    <ul v-else data-test="funnels-list" class="space-y-2">
      <li
        v-for="funnel in funnelsStore.funnels"
        :key="funnel.id"
        :data-test="`funnel-row-${funnel.id}`"
        class="flex items-center justify-between gap-4 rounded-md border px-4 py-3 hover:bg-gray-50"
      >
        <button
          type="button"
          :data-test="`funnel-open-${funnel.id}`"
          class="flex min-w-0 flex-1 items-center gap-3 text-left"
          @click="openFunnel(funnel)"
        >
          <span class="min-w-0 flex-1">
            <span class="block truncate font-medium">{{ funnel.name }}</span>
            <span v-if="funnel.description" class="block truncate text-sm text-gray-500">{{ funnel.description }}</span>
          </span>
          <Badge :variant="STATUS_VARIANT[funnel.status]">{{ t(`funnels.status.${funnel.status}`) }}</Badge>
        </button>
        <button
          type="button"
          :data-test="`funnel-duplicate-${funnel.id}`"
          class="rounded-md border px-2.5 py-1 text-sm hover:bg-gray-50"
          @click="duplicateFunnel(funnel)"
        >
          {{ t('funnels.editor.duplicate') }}
        </button>
        <button
          type="button"
          :data-test="`funnel-stop-all-${funnel.id}`"
          class="rounded-md border border-red-300 px-2.5 py-1 text-sm text-red-700 hover:bg-red-50"
          @click="askStopAll(funnel)"
        >
          {{ t('funnels.editor.stopAll.button') }}
        </button>
        <button
          type="button"
          :data-test="`funnel-delete-${funnel.id}`"
          class="rounded-md border border-red-300 px-2.5 py-1 text-sm text-red-700 hover:bg-red-50"
          @click="askDelete(funnel)"
        >
          {{ t('funnels.deleteConfirm.confirm') }}
        </button>
      </li>
    </ul>

    <CreateFunnelDialog v-model:open="createOpen" :project-id="projectId" />

    <Dialog
      :open="deleteTarget !== null"
      @update:open="(v: boolean) => { if (!v) deleteTarget = null }"
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{{ t('funnels.deleteConfirm.title') }}</DialogTitle>
          <DialogDescription>{{ t('funnels.deleteConfirm.message') }}</DialogDescription>
        </DialogHeader>

        <p v-if="deleteError" data-test="funnel-delete-error" class="text-sm text-red-600">{{ deleteError }}</p>

        <DialogFooter>
          <button
            type="button"
            data-test="funnel-delete-cancel"
            class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
            @click="deleteTarget = null"
          >
            {{ t('funnels.deleteConfirm.cancel') }}
          </button>
          <button
            type="button"
            data-test="funnel-delete-confirm"
            :disabled="deleting"
            class="rounded-md bg-red-600 px-3 py-1.5 text-sm text-white hover:bg-red-700 disabled:opacity-50"
            @click="confirmDelete"
          >
            {{ t('funnels.deleteConfirm.confirm') }}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog
      :open="stopTarget !== null"
      @update:open="(v: boolean) => { if (!v) stopTarget = null }"
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{{ t('funnels.editor.stopAll.title') }}</DialogTitle>
          <DialogDescription>{{ t('funnels.editor.stopAll.message') }}</DialogDescription>
        </DialogHeader>

        <p v-if="stopError" data-test="funnel-stop-all-error" class="text-sm text-red-600">{{ stopError }}</p>

        <DialogFooter>
          <button
            type="button"
            data-test="funnel-stop-all-cancel"
            class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
            @click="stopTarget = null"
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
