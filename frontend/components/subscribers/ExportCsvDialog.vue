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
import type { SegmentFilter } from '~/types/subscriber'
import { toExportFilter } from '~/types/subscriber'

const props = defineProps<{ open: boolean; projectId: string; filter: SegmentFilter }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const { t } = useI18n()

const submitting = ref(false)

// Compact human summary of the active segment so the user knows WHAT they are exporting. Dynamic
// values (search text, slugs) are rendered raw — only labels go through i18n.
const summary = computed(() => {
  const parts: string[] = []
  if (props.filter.search) parts.push(`"${props.filter.search}"`)
  if (props.filter.status) parts.push(t(`subscribers.status.${props.filter.status}`))
  if (props.filter.tagsInclude.length) parts.push(`+${props.filter.tagsInclude.join(', +')}`)
  if (props.filter.tagsExclude.length) parts.push(`-${props.filter.tagsExclude.join(', -')}`)
  return parts.join(' · ')
})

function errorCode(err: unknown): string | null {
  const data = (err as { data?: { code?: string } })?.data
  return data?.code ?? null
}

async function submit() {
  submitting.value = true
  try {
    await useApi()(`/api/v1/projects/${props.projectId}/subscribers/export`, {
      method: 'POST',
      body: { filter: toExportFilter(props.filter) },
    })
    toast.success(t('exports.triggered'))
    emit('update:open', false)
  } catch (err) {
    const code = errorCode(err)
    if (code === 'export_in_flight') toast.error(t('exports.failed.inFlight'))
    else if (code === 'export_filter_too_large') toast.error(t('exports.failed.tooLarge'))
    else toast.error(t('exports.failed.generic'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('exports.dialog.title') }}</DialogTitle>
        <DialogDescription>{{ t('exports.dialog.body') }}</DialogDescription>
      </DialogHeader>

      <p v-if="summary" data-test="export-filter-summary" class="rounded-md bg-gray-50 px-3 py-2 text-sm text-gray-700">
        {{ summary }}
      </p>

      <DialogFooter>
        <button
          type="button"
          data-test="export-cancel"
          class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
          @click="emit('update:open', false)"
        >
          {{ t('exports.dialog.cancel') }}
        </button>
        <button
          type="button"
          data-test="export-submit"
          :disabled="submitting"
          class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          @click="submit"
        >
          {{ t('exports.dialog.submit') }}
        </button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
