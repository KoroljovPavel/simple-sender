<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'
import {
  Dialog,
  DialogScrollContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '~/components/ui/dialog'
import type { FunnelResponse } from '~/types/funnel'

const props = defineProps<{ open: boolean; projectId: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; created: [funnel: FunnelResponse] }>()

const { t } = useI18n()
const localePath = useLocalePath()
const funnelsStore = useFunnelsStore()
// useApiError is called HERE, in the component setup (never in the store) — it pulls useI18n().
const resolveError = useApiError()

// name required (backend @NotBlank @Size max 128); description optional (@Size max 1024).
const schema = computed(() =>
  toTypedSchema(
    z.object({
      name: z.string().trim().min(1, t('validation.funnelNameRequired')).max(128, t('validation.funnelNameMax')),
      description: z.string().trim().max(1024, t('validation.funnelDescriptionMax')).optional(),
    }),
  ),
)

const { handleSubmit, errors, defineField, resetForm } = useForm({
  validationSchema: schema,
  initialValues: { name: '', description: '' },
})
const [name, nameAttrs] = defineField('name')
const [description, descriptionAttrs] = defineField('description')

const submitting = ref(false)
const submitError = ref<string | null>(null)

function reset() {
  resetForm({ values: { name: '', description: '' } })
  submitError.value = null
}

const onSubmit = handleSubmit(async (values) => {
  submitting.value = true
  submitError.value = null
  try {
    const description = values.description?.trim() ? values.description.trim() : undefined
    const funnel = await funnelsStore.create({ name: values.name.trim(), description })
    emit('created', funnel)
    reset()
    emit('update:open', false)
    // Navigate into the editor of the freshly-created draft funnel (Task 10 page).
    await navigateTo(localePath(`/projects/${props.projectId}/funnels/${funnel.id}`))
  } catch (err) {
    // Store re-threw the raw error; the component maps it to a localized message.
    submitError.value = resolveError(err, 'funnels.create')
  } finally {
    submitting.value = false
  }
})
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogScrollContent>
      <DialogHeader>
        <DialogTitle>{{ t('funnels.createDialog.title') }}</DialogTitle>
        <DialogDescription class="sr-only">{{ t('funnels.createDialog.title') }}</DialogDescription>
      </DialogHeader>

      <form data-test="funnel-create-form" class="space-y-3" novalidate @submit.prevent="onSubmit">
        <div>
          <label for="funnel-name" class="block text-sm font-medium mb-1">{{ t('funnels.form.name') }}</label>
          <input
            id="funnel-name"
            v-model="name"
            v-bind="nameAttrs"
            data-test="funnel-name-input"
            type="text"
            autocomplete="off"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="errors.name" data-test="funnel-name-error" class="mt-1 text-sm text-red-600">{{ errors.name }}</p>
        </div>

        <div>
          <label for="funnel-description" class="block text-sm font-medium mb-1">{{ t('funnels.form.description') }}</label>
          <textarea
            id="funnel-description"
            v-model="description"
            v-bind="descriptionAttrs"
            data-test="funnel-description-input"
            rows="3"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="errors.description" data-test="funnel-description-error" class="mt-1 text-sm text-red-600">{{ errors.description }}</p>
        </div>

        <p v-if="submitError" data-test="funnel-submit-error" class="text-sm text-red-600">{{ submitError }}</p>

        <DialogFooter>
          <button
            type="button"
            data-test="funnel-create-cancel"
            class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
            @click="emit('update:open', false)"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="submit"
            data-test="funnel-create-submit"
            :disabled="submitting"
            class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {{ t('funnels.createDialog.submit') }}
          </button>
        </DialogFooter>
      </form>
    </DialogScrollContent>
  </Dialog>
</template>
