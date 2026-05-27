<script setup lang="ts">
import { toast } from 'vue-sonner'
import {
  Dialog,
  DialogScrollContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '~/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '~/components/ui/table'
import { Badge } from '~/components/ui/badge'
import type { BadgeVariants } from '~/components/ui/badge'

const props = defineProps<{ open: boolean; projectId: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const { t } = useI18n()
const resolveError = useApiError()

// Mirror of backend ExportResponse. downloadUrl is non-null only for DONE rows with a still-valid
// token; it is null otherwise — that null is the backend's explicit "flip to Refresh URL" signal
// (Decision 15), so the row state machine keys off downloadUrl presence, NOT a client-side clock
// comparison (avoids clock-skew false positives).
interface ExportResponse {
  exportId: string
  status: string // PENDING | RUNNING | DONE | FAILED | PURGED (enum name)
  rowCount: number | null
  createdAt: string
  completedAt: string | null
  downloadUrl: string | null
  expiresAt: string | null
}
interface ExportRow extends ExportResponse {
  disabled?: boolean
}

const rows = ref<ExportRow[]>([])
const loading = ref(false)
const refreshing = ref<string | null>(null)

// Refresh-URL outcomes that are PERMANENT for the row (file gone) → disable. Retryable outcomes
// (409 in-flight, 429 rate-limited, 503 fail-CLOSED) leave the button enabled.
const TERMINAL_REFRESH_CODES = new Set(['export_purged', 'export_failed'])

function errorCode(err: unknown): string | null {
  return (err as { data?: { code?: string } })?.data?.code ?? null
}

async function loadExports() {
  loading.value = true
  try {
    // NOTE (Task 9 contract): the backend GET /exports currently returns DONE rows only and ignores
    // `limit` (SubscriberExportController.listExports). So in production the PENDING/RUNNING progress
    // and FAILED/PURGED branches below are forward-compatible but not yet reachable — they ARE covered
    // by RecentExportsDialog.spec.ts with mocked rows. `limit=20` is sent per the task's endpoint shape.
    const list = await useApi()<ExportResponse[]>(
      `/api/v1/projects/${props.projectId}/subscribers/exports`,
      { query: { limit: 20 } },
    )
    rows.value = list
  } catch (err) {
    console.warn('[exports] failed to load recent exports', err)
    toast.error(resolveError(err, 'subscribers.exports.list') || t('errors.generic'))
  } finally {
    loading.value = false
  }
}

// NO auto-polling of PENDING/RUNNING rows (Decision 8 / Task 9 cost discussion): the operator refreshes
// by closing + reopening the dialog. Fetch only when the dialog opens (Task 2 seeded no "refresh list"
// label, so the dedicated button is omitted — see decisions.md i18n gap note).
watch(
  () => props.open,
  (open) => {
    if (open) loadExports()
  },
  { immediate: true }, // also fetch when mounted already-open (deep-link / unit test)
)

function badgeVariant(status: string): BadgeVariants['variant'] {
  switch (status) {
    case 'DONE':
      return 'default'
    case 'FAILED':
      return 'destructive'
    case 'PURGED':
      return 'secondary'
    default: // PENDING / RUNNING
      return 'outline'
  }
}
function isInFlight(status: string): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}
function statusLabel(status: string): string {
  return t(`exports.status.${status.toLowerCase()}`)
}
function formatDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString()
}

async function refreshUrl(row: ExportRow) {
  refreshing.value = row.exportId
  try {
    const resp = await useApi()<{ downloadUrl: string; expiresAt: string }>(
      `/api/v1/projects/${props.projectId}/subscribers/exports/${encodeURIComponent(row.exportId)}/refresh-url`,
      { method: 'POST' },
    )
    // 200 → fresh token: flip the row back to a working Download button.
    row.downloadUrl = resp.downloadUrl
    row.expiresAt = resp.expiresAt
    row.disabled = false
  } catch (err) {
    // 409 export_in_flight / 410 export_purged|export_failed / 429 refresh_rate_limited /
    // 503 service_unavailable (fail-CLOSED, Decision 15) → user-visible toast. Only PERMANENT outcomes
    // disable the row; retryable ones stay enabled so the operator can try again.
    toast.error(resolveError(err, 'subscribers.exports.refresh') || t('errors.generic'))
    if (TERMINAL_REFRESH_CODES.has(errorCode(err) ?? '')) row.disabled = true
  } finally {
    refreshing.value = null
  }
}
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogScrollContent class="sm:max-w-2xl">
      <DialogHeader>
        <DialogTitle>{{ t('exports.recentDialog.title') }}</DialogTitle>
        <DialogDescription class="sr-only">{{ t('exports.recentDialog.title') }}</DialogDescription>
      </DialogHeader>

      <p
        v-if="!loading && rows.length === 0"
        data-test="recent-exports-empty"
        class="rounded-md border border-dashed bg-gray-50 px-4 py-6 text-center text-sm text-gray-600"
      >
        {{ t('exports.recentDialog.empty') }}
      </p>

      <Table v-else>
        <TableHeader>
          <TableRow>
            <TableHead>{{ t('exports.recentDialog.columns.createdAt') }}</TableHead>
            <TableHead>{{ t('exports.recentDialog.columns.status') }}</TableHead>
            <TableHead>{{ t('exports.recentDialog.columns.rowCount') }}</TableHead>
            <TableHead class="text-right">{{ t('exports.recentDialog.columns.actions') }}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="row in rows" :key="row.exportId" :data-test="`recent-export-row-${row.exportId}`">
            <TableCell>{{ formatDate(row.createdAt) }}</TableCell>
            <TableCell>
              <Badge :variant="badgeVariant(row.status)" :data-test="`recent-export-status-${row.exportId}`">
                {{ statusLabel(row.status) }}
              </Badge>
            </TableCell>
            <TableCell>{{ row.rowCount ?? '—' }}</TableCell>
            <TableCell class="text-right">
              <!-- DONE + valid token → Download -->
              <a
                v-if="row.status === 'DONE' && row.downloadUrl"
                :data-test="`recent-export-download-${row.exportId}`"
                :href="row.downloadUrl"
                download
                class="inline-block rounded-md border border-blue-600 px-3 py-1 text-sm font-medium text-blue-700 hover:bg-blue-50"
              >
                {{ t('exports.recentDialog.actions.download') }}
              </a>
              <!-- DONE + expired token (downloadUrl null) → Refresh URL -->
              <button
                v-else-if="row.status === 'DONE' && !row.downloadUrl"
                type="button"
                :data-test="`recent-export-refresh-${row.exportId}`"
                :disabled="row.disabled || refreshing === row.exportId"
                class="rounded-md border px-3 py-1 text-sm hover:bg-gray-50 disabled:opacity-50"
                @click="refreshUrl(row)"
              >
                {{ t('exports.recentDialog.actions.refreshUrl') }}
              </button>
              <!-- PENDING / RUNNING → progress indicator, no action button -->
              <span
                v-else-if="isInFlight(row.status)"
                :data-test="`recent-export-progress-${row.exportId}`"
                class="inline-flex items-center gap-1 text-sm text-gray-500"
              >
                <span class="h-3 w-3 animate-spin rounded-full border-2 border-gray-300 border-t-gray-600" />
                {{ statusLabel(row.status) }}
              </span>
              <!-- FAILED / PURGED → no action -->
              <span v-else class="text-sm text-gray-400">—</span>
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </DialogScrollContent>
  </Dialog>
</template>
