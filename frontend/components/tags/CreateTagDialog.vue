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

const props = defineProps<{ open: boolean; projectId: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; created: [tag: Tag] }>()

const { t } = useI18n()
const resolveError = useApiError()

// CANONICAL slug regex — MUST match backend CreateTagRequest @Pattern byte-for-byte (Decision 11).
// Allows hyphens, digit-leading slugs (2024_vip) and caps length at 32.
const SLUG_RE = /^[a-z0-9_-]{1,32}$/

// Schema wrapped in computed() (patterns.md:145) so a live locale switch re-runs validation. label is
// required on the frontend for a usable display name even though the backend treats it as optional —
// stricter-than-backend is safe (backend never rejects a provided label). Messages localized via the
// validation.* namespace (mirrors the auth/projects forms).
const schema = computed(() =>
  toTypedSchema(
    z.object({
      slug: z.string().trim().regex(SLUG_RE, t('validation.tagSlugPattern')),
      label: z.string().trim().min(1, t('validation.tagLabelRequired')).max(64, t('validation.tagLabelMax')),
    }),
  ),
)

const { defineField, handleSubmit, errors, resetForm } = useForm({
  validationSchema: schema,
  initialValues: { slug: '', label: '' },
})
const [slug, slugAttrs] = defineField('slug')
const [label, labelAttrs] = defineField('label')

const submitting = ref(false)
const submitError = ref<string | null>(null)

const onSubmit = handleSubmit(async (values) => {
  submitting.value = true
  submitError.value = null
  try {
    const tag = await useApi()<Tag>(`/api/v1/projects/${props.projectId}/tags`, {
      method: 'POST',
      body: { slug: values.slug, label: values.label },
    })
    toast.success(t('tags.createDialog.success'))
    emit('created', tag)
    resetForm()
    emit('update:open', false)
  } catch (err) {
    // 409 tag_name_taken / 400 → useApiError walks to errors.generic (per-code keys not seeded).
    submitError.value = resolveError(err, 'tags.create')
  } finally {
    submitting.value = false
  }
})
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('tags.createDialog.title') }}</DialogTitle>
        <DialogDescription class="sr-only">{{ t('tags.createDialog.title') }}</DialogDescription>
      </DialogHeader>

      <form data-test="tag-create-form" class="space-y-3" novalidate @submit.prevent="onSubmit">
        <div>
          <label for="tag-slug" class="block text-sm font-medium mb-1">{{ t('tags.createDialog.slugLabel') }}</label>
          <input
            id="tag-slug"
            v-model="slug"
            v-bind="slugAttrs"
            data-test="tag-slug-input"
            type="text"
            autocomplete="off"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="errors.slug" data-test="tag-slug-error" class="mt-1 text-sm text-red-600">{{ errors.slug }}</p>
        </div>

        <div>
          <label for="tag-label" class="block text-sm font-medium mb-1">{{ t('tags.createDialog.labelLabel') }}</label>
          <input
            id="tag-label"
            v-model="label"
            v-bind="labelAttrs"
            data-test="tag-label-input"
            type="text"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="errors.label" data-test="tag-label-error" class="mt-1 text-sm text-red-600">{{ errors.label }}</p>
        </div>

        <p v-if="submitError" data-test="submit-error" class="text-sm text-red-600">{{ submitError }}</p>

        <DialogFooter>
          <button
            type="button"
            data-test="tag-create-cancel"
            class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
            @click="emit('update:open', false)"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="submit"
            data-test="tag-create-submit"
            :disabled="submitting"
            class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {{ t('tags.createDialog.submit') }}
          </button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
