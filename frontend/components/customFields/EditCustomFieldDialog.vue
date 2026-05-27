<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'
import { toast } from 'vue-sonner'
import {
  Dialog,
  DialogScrollContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '~/components/ui/dialog'
import type { CustomFieldDefinition, CustomFieldType } from '~/types/subscriber'

const props = defineProps<{ open: boolean; projectId: string; field: CustomFieldDefinition }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; updated: [field: CustomFieldDefinition] }>()

const { t } = useI18n()
const resolveError = useApiError()

// defaultValue validation is re-derived from the EXISTING type (props.field.type, read-only), NOT a
// form-driven type field (Task 11 edge case + Decision 11: name/type are immutable).
function defaultValueSchema(type: CustomFieldType): z.ZodTypeAny {
  if (type === 'NUMBER') {
    return z.any().refine((v) => {
      if (v === '' || v === null || v === undefined) return true
      if (typeof v === 'number') return Number.isFinite(v)
      if (typeof v === 'string') return v.trim() !== '' && !Number.isNaN(Number(v))
      return false
    })
  }
  return z.any()
}

// Map the persisted defaultValue to the string model the native inputs/select bind to.
function toModel(field: CustomFieldDefinition): string {
  const dv = field.defaultValue
  if (dv === null || dv === undefined) return ''
  return String(dv)
}

const schema = computed(() =>
  toTypedSchema(
    z.object({
      label: z.string().trim().min(1).max(64),
      defaultValue: defaultValueSchema(props.field.type),
    }),
  ),
)

const { defineField, handleSubmit, errors, resetForm } = useForm({
  validationSchema: schema,
  initialValues: { label: props.field.label, defaultValue: toModel(props.field) },
})
const [label, labelAttrs] = defineField('label')
const [defaultValue, defaultValueAttrs] = defineField('defaultValue')

watch(
  () => props.field,
  (field) => resetForm({ values: { label: field.label, defaultValue: toModel(field) } }),
)

const submitting = ref(false)
const submitError = ref<string | null>(null)

function typedDefault(): unknown {
  const raw = defaultValue.value as unknown
  if (raw === '' || raw === null || raw === undefined) return null
  switch (props.field.type) {
    case 'NUMBER':
      return Number(raw)
    case 'BOOLEAN':
      return raw === 'true' || raw === true
    default:
      return raw
  }
}

const onSubmit = handleSubmit(async (values) => {
  submitting.value = true
  submitError.value = null
  try {
    // PATCH body carries ONLY editable fields (label, defaultValue) — name/type are immutable and
    // absent from UpdateCustomFieldRequest (Decision 11).
    const field = await useApi()<CustomFieldDefinition>(
      `/api/v1/projects/${props.projectId}/custom-fields/${encodeURIComponent(props.field.name)}`,
      { method: 'PATCH', body: { label: values.label, defaultValue: typedDefault() } },
    )
    toast.success(t('common.save'))
    emit('updated', field)
    emit('update:open', false)
  } catch (err) {
    submitError.value = resolveError(err, 'customFields.update')
  } finally {
    submitting.value = false
  }
})
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogScrollContent>
      <DialogHeader>
        <DialogTitle>{{ t('customFields.editDialog.title') }}</DialogTitle>
        <DialogDescription class="sr-only">{{ t('customFields.editDialog.title') }}</DialogDescription>
      </DialogHeader>

      <form data-test="custom-field-edit-form" class="space-y-3" novalidate @submit.prevent="onSubmit">
        <!-- name + type are immutable (Decision 11). Rendered as disabled fields so the immutability is
             visible. Task 2 did not seed a dedicated customFields.editImmutableWarning copy — the
             disabled controls communicate it (see decisions.md i18n gap note). -->
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-xs font-medium text-gray-500 mb-1">{{ t('customFields.columns.name') }}</label>
            <input
              data-test="custom-field-edit-name-readonly"
              :value="props.field.name"
              type="text"
              disabled
              class="w-full rounded-md border border-dashed bg-gray-50 px-3 py-2 text-sm text-gray-500"
            />
          </div>
          <div>
            <label class="block text-xs font-medium text-gray-500 mb-1">{{ t('customFields.columns.type') }}</label>
            <input
              data-test="custom-field-edit-type-readonly"
              :value="t(`customFields.type.${props.field.type.toLowerCase()}`)"
              type="text"
              disabled
              class="w-full rounded-md border border-dashed bg-gray-50 px-3 py-2 text-sm text-gray-500"
            />
          </div>
        </div>

        <div>
          <label for="cf-edit-label" class="block text-sm font-medium mb-1">{{ t('customFields.editDialog.labelLabel') }}</label>
          <input
            id="cf-edit-label"
            v-model="label"
            v-bind="labelAttrs"
            data-test="custom-field-edit-label-input"
            type="text"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="errors.label" data-test="custom-field-edit-label-error" class="mt-1 text-sm text-red-600">{{ errors.label }}</p>
        </div>

        <div>
          <label for="cf-edit-default" class="block text-sm font-medium mb-1">{{ t('customFields.editDialog.defaultValueLabel') }}</label>
          <input
            v-if="props.field.type === 'STRING'"
            id="cf-edit-default"
            v-model="defaultValue"
            v-bind="defaultValueAttrs"
            data-test="custom-field-edit-default-input"
            type="text"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <input
            v-else-if="props.field.type === 'NUMBER'"
            id="cf-edit-default"
            v-model="defaultValue"
            v-bind="defaultValueAttrs"
            data-test="custom-field-edit-default-input"
            type="number"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <input
            v-else-if="props.field.type === 'DATE'"
            id="cf-edit-default"
            v-model="defaultValue"
            v-bind="defaultValueAttrs"
            data-test="custom-field-edit-default-input"
            type="date"
            class="rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <!-- BOOLEAN tri-state select; no v-bind="defaultValueAttrs" — vee-validate only needs v-model
               on a native select and BOOLEAN has no per-type refine (mirrors AddCustomFieldDialog). -->
          <select
            v-else
            id="cf-edit-default"
            v-model="defaultValue"
            data-test="custom-field-edit-default-input"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option value="">—</option>
            <option value="true">{{ t('customFields.type.boolean') }}: ✓</option>
            <option value="false">{{ t('customFields.type.boolean') }}: ✕</option>
          </select>
          <p v-if="errors.defaultValue" data-test="custom-field-edit-default-error" class="mt-1 text-sm text-red-600">{{ errors.defaultValue }}</p>
        </div>

        <p v-if="submitError" data-test="submit-error" class="text-sm text-red-600">{{ submitError }}</p>

        <DialogFooter>
          <button
            type="button"
            data-test="custom-field-edit-cancel"
            class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
            @click="emit('update:open', false)"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="submit"
            data-test="custom-field-edit-submit"
            :disabled="submitting"
            class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {{ t('customFields.editDialog.submit') }}
          </button>
        </DialogFooter>
      </form>
    </DialogScrollContent>
  </Dialog>
</template>
