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
import type { CustomFieldDefinition } from '~/types/subscriber'

const props = defineProps<{ open: boolean; projectId: string; field: CustomFieldDefinition }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; deleted: [field: CustomFieldDefinition] }>()

const { t } = useI18n()
const resolveError = useApiError()

const confirmText = ref('')
const submitting = ref(false)
const submitError = ref<string | null>(null)

// Typed-confirm gate: operator echoes the field name to enable Delete.
const canConfirm = computed(() => !submitting.value && confirmText.value.trim() === props.field.name)

watch(
  () => props.open,
  (open) => {
    if (open) {
      confirmText.value = ''
      submitError.value = null
    }
  },
)

async function confirm() {
  if (!canConfirm.value) return
  submitting.value = true
  submitError.value = null
  try {
    await useApi()(
      `/api/v1/projects/${props.projectId}/custom-fields/${encodeURIComponent(props.field.name)}`,
      { method: 'DELETE' },
    )
    toast.success(t('customFields.actions.delete'))
    emit('deleted', props.field)
    emit('update:open', false)
  } catch (err) {
    submitError.value = resolveError(err, 'customFields.delete')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('customFields.deleteDialog.title') }}</DialogTitle>
        <!-- The DELETE endpoint returns 204 (no affected-subscriber count), and no per-field GET exists,
             so the cascade impact is conveyed by the generic copy rather than a live number. See
             decisions.md deviation note. -->
        <DialogDescription>{{ t('customFields.deleteDialog.confirmBody') }}</DialogDescription>
      </DialogHeader>

      <div>
        <label for="cf-delete-confirm" class="block text-sm font-medium mb-1">{{ props.field.name }}</label>
        <input
          id="cf-delete-confirm"
          v-model="confirmText"
          data-test="custom-field-delete-confirm-input"
          type="text"
          autocomplete="off"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-red-500"
        />
      </div>

      <p v-if="submitError" data-test="submit-error" class="text-sm text-red-600">{{ submitError }}</p>

      <DialogFooter>
        <button
          type="button"
          data-test="custom-field-delete-cancel"
          class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
          @click="emit('update:open', false)"
        >
          {{ t('common.cancel') }}
        </button>
        <button
          type="button"
          data-test="custom-field-delete-confirm"
          :disabled="!canConfirm"
          class="rounded-md bg-red-600 px-3 py-1.5 text-sm text-white hover:bg-red-700 disabled:opacity-50"
          @click="confirm"
        >
          {{ t('customFields.deleteDialog.submit') }}
        </button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
