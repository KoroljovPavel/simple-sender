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
import type { Tag } from '~/types/subscriber'

const props = defineProps<{ open: boolean; projectId: string; tag: Tag }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; deleted: [tag: Tag] }>()

const { t } = useI18n()
const resolveError = useApiError()

const confirmText = ref('')
const submitting = ref(false)
const submitError = ref<string | null>(null)

// Typed-confirm gate: operator must echo the slug to enable Delete (mirrors the project delete modal).
const canConfirm = computed(() => !submitting.value && confirmText.value.trim() === props.tag.slug)

// Affected-subscriber count comes from the already-loaded list row (subscriberCount). There is no
// GET /tags/{slug} endpoint (TagController exposes only list/create/patch/delete) — see decisions.md.
// The count line is always shown, including "0", so the cascade impact is never silently hidden.
const affectedCount = computed(() => props.tag.subscriberCount)

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
      `/api/v1/projects/${props.projectId}/tags/${encodeURIComponent(props.tag.slug)}`,
      { method: 'DELETE' },
    )
    toast.success(t('tags.actions.delete'))
    emit('deleted', props.tag)
    emit('update:open', false)
  } catch (err) {
    submitError.value = resolveError(err, 'tags.delete')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('tags.deleteDialog.title') }}</DialogTitle>
        <DialogDescription>{{ t('tags.deleteDialog.confirmBody') }}</DialogDescription>
      </DialogHeader>

      <p data-test="tag-delete-affected" class="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800">
        {{ t('tags.columns.subscriberCount') }}: {{ affectedCount }}
      </p>

      <div>
        <label for="tag-delete-confirm" class="block text-sm font-medium mb-1">{{ props.tag.slug }}</label>
        <input
          id="tag-delete-confirm"
          v-model="confirmText"
          data-test="tag-delete-confirm-input"
          type="text"
          autocomplete="off"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-red-500"
        />
      </div>

      <p v-if="submitError" data-test="submit-error" class="text-sm text-red-600">{{ submitError }}</p>

      <DialogFooter>
        <button
          type="button"
          data-test="tag-delete-cancel"
          class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
          @click="emit('update:open', false)"
        >
          {{ t('common.cancel') }}
        </button>
        <button
          type="button"
          data-test="tag-delete-confirm"
          :disabled="!canConfirm"
          class="rounded-md bg-red-600 px-3 py-1.5 text-sm text-white hover:bg-red-700 disabled:opacity-50"
          @click="confirm"
        >
          {{ t('tags.deleteDialog.submit') }}
        </button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
