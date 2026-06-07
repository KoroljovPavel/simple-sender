<script setup lang="ts">
import type { FunnelStep } from '~/types/funnel'

// Vertical ordered list of steps. Position = order, so reordering is just emitting a new (from,to) pair
// the parent applies to its local array (then PATCHes the full array). Delete uses an inline two-click
// confirm (no extra modal) so a misclick never silently drops a step.
const props = defineProps<{ steps: FunnelStep[] }>()
const emit = defineEmits<{
  move: [from: number, to: number]
  edit: [index: number]
  delete: [index: number]
  add: []
}>()

const { t } = useI18n()

const confirmIndex = ref<number | null>(null)

function summary(step: FunnelStep): string {
  switch (step.stepType) {
    case 'SEND_MESSAGE':
      return truncate(step.text ?? '')
    case 'SEND_IMAGE':
      return step.caption ? truncate(step.caption) : truncate(step.imageUrl ?? '')
    case 'DELAY':
      return t('funnels.steps.summary.delay', {
        value: step.delayValue ?? '',
        unit: t(`funnels.steps.unit.${step.delayUnit ?? 'MIN'}`),
      })
    case 'ADD_TAG':
      return t('funnels.steps.summary.addTag', { tag: step.tagSlug ?? '' })
    case 'REMOVE_TAG':
      return t('funnels.steps.summary.removeTag', { tag: step.tagSlug ?? '' })
    case 'SET_CUSTOM_FIELD':
      return t('funnels.steps.summary.setCustomField', { key: step.customFieldKey ?? '' })
    case 'MENU':
      return t('funnels.steps.summary.menu', { count: step.buttons?.length ?? 0 })
  }
}

function truncate(s: string): string {
  return s.length > 60 ? `${s.slice(0, 60)}…` : s
}

function askDelete(index: number) {
  confirmIndex.value = index
}
function confirmDelete(index: number) {
  confirmIndex.value = null
  emit('delete', index)
}
</script>

<template>
  <div data-test="funnel-steps">
    <div
      v-if="props.steps.length === 0"
      data-test="funnel-steps-empty"
      class="rounded-md border border-dashed bg-gray-50 px-4 py-10 text-center text-sm text-gray-600"
    >
      <span class="block font-medium">{{ t('funnels.steps.empty.title') }}</span>
      <span class="mt-1 block">{{ t('funnels.steps.empty.body') }}</span>
      <button
        type="button"
        data-test="funnel-steps-empty-add"
        class="mt-4 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        @click="emit('add')"
      >
        {{ t('funnels.steps.add') }}
      </button>
    </div>

    <ol v-else data-test="funnel-steps-list" class="space-y-2">
      <li
        v-for="(step, index) in props.steps"
        :key="index"
        :data-test="`funnel-step-row-${index}`"
        class="flex items-center gap-3 rounded-md border px-3 py-2"
      >
        <span class="text-xs font-medium text-gray-400 w-5 text-right">{{ index + 1 }}</span>
        <span class="min-w-0 flex-1">
          <span class="block text-sm font-medium">{{ t(`funnels.steps.type.${step.stepType}`) }}</span>
          <span v-if="summary(step)" class="block truncate text-sm text-gray-500">{{ summary(step) }}</span>
        </span>

        <template v-if="confirmIndex === index">
          <span class="text-sm text-gray-600">{{ t('funnels.steps.confirmDelete') }}</span>
          <button
            type="button"
            :data-test="`funnel-step-delete-confirm-${index}`"
            class="rounded-md bg-red-600 px-2.5 py-1 text-sm text-white hover:bg-red-700"
            @click="confirmDelete(index)"
          >
            {{ t('funnels.steps.delete') }}
          </button>
          <button
            type="button"
            :data-test="`funnel-step-delete-cancel-${index}`"
            class="rounded-md border px-2.5 py-1 text-sm hover:bg-gray-50"
            @click="confirmIndex = null"
          >
            {{ t('common.cancel') }}
          </button>
        </template>

        <template v-else>
          <button
            type="button"
            :data-test="`funnel-step-move-up-${index}`"
            :disabled="index === 0"
            class="rounded-md border px-2 py-1 text-sm hover:bg-gray-50 disabled:opacity-40"
            :aria-label="t('funnels.steps.moveUp')"
            @click="emit('move', index, index - 1)"
          >
            ↑
          </button>
          <button
            type="button"
            :data-test="`funnel-step-move-down-${index}`"
            :disabled="index === props.steps.length - 1"
            class="rounded-md border px-2 py-1 text-sm hover:bg-gray-50 disabled:opacity-40"
            :aria-label="t('funnels.steps.moveDown')"
            @click="emit('move', index, index + 1)"
          >
            ↓
          </button>
          <button
            type="button"
            :data-test="`funnel-step-edit-${index}`"
            class="rounded-md border px-2.5 py-1 text-sm hover:bg-gray-50"
            @click="emit('edit', index)"
          >
            {{ t('funnels.steps.edit') }}
          </button>
          <button
            type="button"
            :data-test="`funnel-step-delete-${index}`"
            class="rounded-md border border-red-300 px-2.5 py-1 text-sm text-red-700 hover:bg-red-50"
            @click="askDelete(index)"
          >
            {{ t('funnels.steps.delete') }}
          </button>
        </template>
      </li>
    </ol>
  </div>
</template>
