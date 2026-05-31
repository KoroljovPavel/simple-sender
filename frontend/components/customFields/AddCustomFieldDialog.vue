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

const props = defineProps<{ open: boolean; projectId: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; created: [field: CustomFieldDefinition] }>()

const { t } = useI18n()
const resolveError = useApiError()

// CANONICAL slug regex — MUST match backend CreateCustomFieldRequest @Pattern byte-for-byte (Decision 11).
const SLUG_RE = /^[a-z0-9_-]{1,32}$/
const TYPES: CustomFieldType[] = ['STRING', 'NUMBER', 'BOOLEAN', 'DATE']

// Type lives OUTSIDE the form so the schema computed() can depend on it. Native <select> (not shadcn
// Select) for testability + parity with SubscribersFilterBar.
const selectedType = ref<CustomFieldType>('STRING')

// defaultValue validation is type-dependent. Wrapping the WHOLE schema in one computed() whose body
// reads selectedType.value means switching type re-runs validation against an already-entered value
// (patterns.md:145 + Task 11 edge case). NUMBER refine rejects non-numeric; null/empty always allowed.
function defaultValueSchema(type: CustomFieldType): z.ZodTypeAny {
  if (type === 'NUMBER') {
    return z.any().refine(
      (v) => {
        if (v === '' || v === null || v === undefined) return true
        if (typeof v === 'number') return Number.isFinite(v)
        if (typeof v === 'string') return v.trim() !== '' && !Number.isNaN(Number(v))
        return false
      },
      { message: t('validation.customFieldNumberDefault') },
    )
  }
  // STRING / DATE / BOOLEAN: any value accepted; null/unset is a valid persisted default.
  return z.any()
}

const schema = computed(() =>
  toTypedSchema(
    z.object({
      name: z.string().trim().regex(SLUG_RE, t('validation.customFieldNamePattern')),
      label: z.string().trim().min(1, t('validation.customFieldLabelRequired')).max(64, t('validation.customFieldLabelMax')),
      defaultValue: defaultValueSchema(selectedType.value),
    }),
  ),
)

const { defineField, handleSubmit, errors, resetForm } = useForm({
  validationSchema: schema,
  initialValues: { name: '', label: '', defaultValue: '' },
})
const [name, nameAttrs] = defineField('name')
const [label, labelAttrs] = defineField('label')
const [defaultValue, defaultValueAttrs] = defineField('defaultValue')

const submitting = ref(false)
const submitError = ref<string | null>(null)

// Narrow the raw model to the persisted shape. Empty/unset → null (valid for every type).
function typedDefault(): unknown {
  const raw = defaultValue.value as unknown
  if (raw === '' || raw === null || raw === undefined) return null
  switch (selectedType.value) {
    case 'NUMBER':
      return Number(raw)
    case 'BOOLEAN':
      return raw === 'true' || raw === true
    case 'DATE':
      return dateInputToIso(raw) // 'YYYY-MM-DD' -> ISO-8601 date-time the backend accepts
    default:
      return raw // STRING
  }
}

function reset() {
  resetForm({ values: { name: '', label: '', defaultValue: '' } })
  selectedType.value = 'STRING'
}

const onSubmit = handleSubmit(async (values) => {
  submitting.value = true
  submitError.value = null
  try {
    const field = await useApi()<CustomFieldDefinition>(
      `/api/v1/projects/${props.projectId}/custom-fields`,
      {
        method: 'POST',
        body: { name: values.name, label: values.label, type: selectedType.value, defaultValue: typedDefault() },
      },
    )
    toast.success(t('customFields.addDialog.success'))
    emit('created', field)
    reset()
    emit('update:open', false)
  } catch (err) {
    submitError.value = resolveError(err, 'customFields.create')
  } finally {
    submitting.value = false
  }
})
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogScrollContent>
      <DialogHeader>
        <DialogTitle>{{ t('customFields.addDialog.title') }}</DialogTitle>
        <DialogDescription class="sr-only">{{ t('customFields.addDialog.title') }}</DialogDescription>
      </DialogHeader>

      <form data-test="custom-field-add-form" class="space-y-3" novalidate @submit.prevent="onSubmit">
        <div>
          <label for="cf-name" class="block text-sm font-medium mb-1">{{ t('customFields.addDialog.nameLabel') }}</label>
          <input
            id="cf-name"
            v-model="name"
            v-bind="nameAttrs"
            data-test="custom-field-name-input"
            type="text"
            autocomplete="off"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="errors.name" data-test="custom-field-name-error" class="mt-1 text-sm text-red-600">{{ errors.name }}</p>
        </div>

        <div>
          <label for="cf-label" class="block text-sm font-medium mb-1">{{ t('customFields.addDialog.labelLabel') }}</label>
          <input
            id="cf-label"
            v-model="label"
            v-bind="labelAttrs"
            data-test="custom-field-label-input"
            type="text"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="errors.label" data-test="custom-field-label-error" class="mt-1 text-sm text-red-600">{{ errors.label }}</p>
        </div>

        <div>
          <label for="cf-type" class="block text-sm font-medium mb-1">{{ t('customFields.addDialog.typeLabel') }}</label>
          <select
            id="cf-type"
            v-model="selectedType"
            data-test="custom-field-type-select"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option v-for="ty in TYPES" :key="ty" :value="ty">{{ t(`customFields.type.${ty.toLowerCase()}`) }}</option>
          </select>
        </div>

        <div>
          <label for="cf-default" class="block text-sm font-medium mb-1">{{ t('customFields.addDialog.defaultValueLabel') }}</label>
          <!-- Per-type defaultValue renderer. data-test is stable across branches so unit + E2E can
               select it regardless of the active type. -->
          <input
            v-if="selectedType === 'STRING'"
            id="cf-default"
            key="cf-default-string"
            v-model="defaultValue"
            v-bind="defaultValueAttrs"
            data-test="custom-field-default-input"
            type="text"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <input
            v-else-if="selectedType === 'NUMBER'"
            id="cf-default"
            key="cf-default-number"
            v-model="defaultValue"
            v-bind="defaultValueAttrs"
            data-test="custom-field-default-input"
            type="number"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <input
            v-else-if="selectedType === 'DATE'"
            id="cf-default"
            key="cf-default-date"
            v-model="defaultValue"
            v-bind="defaultValueAttrs"
            data-test="custom-field-default-input"
            type="date"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <!-- BOOLEAN: tri-state (unset / true / false). defaultValue=null is a valid persisted state
               (Task 11 edge case), so a plain checkbox cannot represent it — a 3-option select can.
               No v-bind="defaultValueAttrs": vee-validate only needs v-model on a native select, and the
               BOOLEAN branch has no per-type refine (schema is z.any()) — mirrors the new.vue rationale. -->
          <select
            v-else
            id="cf-default"
            key="cf-default-boolean"
            v-model="defaultValue"
            data-test="custom-field-default-input"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option value="">—</option>
            <option value="true">{{ t('customFields.type.boolean') }}: ✓</option>
            <option value="false">{{ t('customFields.type.boolean') }}: ✕</option>
          </select>
          <p v-if="errors.defaultValue" data-test="custom-field-default-error" class="mt-1 text-sm text-red-600">{{ errors.defaultValue }}</p>
        </div>

        <p v-if="submitError" data-test="submit-error" class="text-sm text-red-600">{{ submitError }}</p>

        <DialogFooter>
          <button
            type="button"
            data-test="custom-field-add-cancel"
            class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
            @click="emit('update:open', false)"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="submit"
            data-test="custom-field-add-submit"
            :disabled="submitting"
            class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {{ t('customFields.addDialog.submit') }}
          </button>
        </DialogFooter>
      </form>
    </DialogScrollContent>
  </Dialog>
</template>
