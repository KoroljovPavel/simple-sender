<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'
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
const emit = defineEmits<{ 'update:open': [value: boolean]; updated: [tag: Tag] }>()

const { t } = useI18n()
const resolveError = useApiError()

// Label-only edit (Decision 11: slug is immutable; the PATCH body excludes slug — the backend
// UpdateTagRequest schema has no slug field).
const schema = computed(() => toTypedSchema(z.object({ label: z.string().trim().min(1).max(64) })))

const { defineField, handleSubmit, errors, resetForm } = useForm({
  validationSchema: schema,
  initialValues: { label: props.tag.label ?? '' },
})
const [label, labelAttrs] = defineField('label')

// Re-seed the field when a different tag is opened for rename.
watch(
  () => props.tag,
  (tag) => resetForm({ values: { label: tag.label ?? '' } }),
)

const submitting = ref(false)
const submitError = ref<string | null>(null)

const onSubmit = handleSubmit(async (values) => {
  submitting.value = true
  submitError.value = null
  try {
    const updated = await useApi()<Tag>(
      `/api/v1/projects/${props.projectId}/tags/${encodeURIComponent(props.tag.slug)}`,
      { method: 'PATCH', body: { label: values.label } },
    )
    toast.success(t('common.save'))
    emit('updated', updated)
    emit('update:open', false)
  } catch (err) {
    submitError.value = resolveError(err, 'tags.update')
  } finally {
    submitting.value = false
  }
})
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('tags.renameDialog.title') }}</DialogTitle>
        <DialogDescription class="sr-only">{{ t('tags.renameDialog.title') }}</DialogDescription>
      </DialogHeader>

      <form data-test="tag-rename-form" class="space-y-3" novalidate @submit.prevent="onSubmit">
        <div>
          <span class="block text-sm font-medium mb-1">{{ t('tags.columns.slug') }}</span>
          <!-- slug is immutable (Decision 11): rendered read-only. The dashed/disabled treatment is the
               immutability cue — Task 2 seeded no slug-immutability tooltip copy, and borrowing the
               delete-cascade string would be misleading, so no tooltip (see decisions.md i18n gap). -->
          <span
            data-test="tag-rename-slug-readonly"
            class="inline-block rounded-md border border-dashed bg-gray-50 px-3 py-2 text-sm text-gray-500"
          >{{ props.tag.slug }}</span>
        </div>

        <div>
          <label for="tag-rename-label" class="block text-sm font-medium mb-1">{{ t('tags.renameDialog.labelLabel') }}</label>
          <input
            id="tag-rename-label"
            v-model="label"
            v-bind="labelAttrs"
            data-test="tag-rename-label-input"
            type="text"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="errors.label" data-test="tag-rename-label-error" class="mt-1 text-sm text-red-600">{{ errors.label }}</p>
        </div>

        <p v-if="submitError" data-test="submit-error" class="text-sm text-red-600">{{ submitError }}</p>

        <DialogFooter>
          <button
            type="button"
            data-test="tag-rename-cancel"
            class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
            @click="emit('update:open', false)"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="submit"
            data-test="tag-rename-submit"
            :disabled="submitting"
            class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {{ t('tags.renameDialog.submit') }}
          </button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
