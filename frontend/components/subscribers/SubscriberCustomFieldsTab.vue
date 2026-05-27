<script setup lang="ts">
import { toast } from 'vue-sonner'
import type { CustomFieldDefinition } from '~/types/subscriber'

const props = defineProps<{
  projectId: string
  subscriberId: string
  customFields: Record<string, unknown>
}>()
const emit = defineEmits<{ refresh: [] }>()

const { t } = useI18n()
const resolveError = useApiError()

const definitions = ref<CustomFieldDefinition[]>([])
// Per-field edit buffer. `any` is deliberate: each field's runtime type varies (string/number/boolean/
// date) and is bound directly to native inputs via v-model; typedValue() re-narrows on save.
const editValues = reactive<Record<string, any>>({})
const savingField = ref<string | null>(null)

function initialValue(def: CustomFieldDefinition): unknown {
  const current = props.customFields?.[def.name]
  if (current !== undefined && current !== null) return current
  if (def.defaultValue !== undefined && def.defaultValue !== null) return def.defaultValue
  return def.type === 'BOOLEAN' ? false : ''
}

onMounted(async () => {
  try {
    definitions.value = await useApi()<CustomFieldDefinition[]>(
      `/api/v1/projects/${props.projectId}/custom-fields`,
    )
    for (const def of definitions.value) editValues[def.name] = initialValue(def)
  } catch (err) {
    console.warn('[subscribers] failed to load custom-field definitions', err)
  }
})

// Re-sync the edit buffer to server truth whenever the parent re-fetches the subscriber (e.g. after a
// save emits refresh) so a stale local value never lingers.
watch(
  () => props.customFields,
  () => {
    for (const def of definitions.value) editValues[def.name] = initialValue(def)
  },
)

function typedValue(def: CustomFieldDefinition, raw: unknown): unknown {
  switch (def.type) {
    case 'NUMBER':
      return raw === '' || raw === null || raw === undefined ? null : Number(raw)
    case 'BOOLEAN':
      return Boolean(raw)
    default: // STRING, DATE
      return raw === '' ? null : raw
  }
}

async function save(def: CustomFieldDefinition) {
  savingField.value = def.name
  try {
    // Mass-assignment safety (patterns.md:161): the PATCH body carries EXACTLY the edited field's key.
    await useApi()(
      `/api/v1/projects/${props.projectId}/subscribers/${props.subscriberId}/custom-fields`,
      { method: 'PATCH', body: { values: { [def.name]: typedValue(def, editValues[def.name]) } } },
    )
    // No customFields-specific success key was seeded by Task 2; common.save is the closest seeded,
    // non-misleading confirmation (flagged for a Task 2 i18n follow-up). Do NOT add keys here.
    toast.success(t('common.save'))
    emit('refresh')
  } catch (err) {
    toast.error(resolveError(err, 'subscribers.customFields') || t('errors.generic'))
  } finally {
    savingField.value = null
  }
}
</script>

<template>
  <div class="space-y-3">
    <div
      v-if="definitions.length === 0"
      data-test="subscriber-custom-fields-empty"
      class="rounded-md border border-dashed border-gray-300 bg-gray-50 px-4 py-6 text-center text-sm text-gray-600"
    >
      <p class="font-medium">{{ t('customFields.empty.title') }}</p>
      <p class="mt-1">{{ t('customFields.empty.body') }}</p>
    </div>

    <div
      v-for="def in definitions"
      :key="def.name"
      class="flex flex-wrap items-end gap-3 rounded-md border border-gray-200 p-3"
    >
      <div class="flex-1 min-w-[180px]">
        <label class="block text-xs font-medium text-gray-600 mb-1">
          {{ def.label }} <span class="text-gray-400">({{ t(`customFields.type.${def.type.toLowerCase()}`) }})</span>
        </label>

        <input
          v-if="def.type === 'STRING'"
          v-model="editValues[def.name]"
          :data-test="`subscriber-custom-field-${def.name}-input`"
          type="text"
          class="w-full rounded-md border px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <input
          v-else-if="def.type === 'NUMBER'"
          v-model="editValues[def.name]"
          :data-test="`subscriber-custom-field-${def.name}-input`"
          type="number"
          class="w-full rounded-md border px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <input
          v-else-if="def.type === 'DATE'"
          v-model="editValues[def.name]"
          :data-test="`subscriber-custom-field-${def.name}-input`"
          type="date"
          class="rounded-md border px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <label v-else class="inline-flex items-center gap-2 text-sm">
          <input
            v-model="editValues[def.name]"
            :data-test="`subscriber-custom-field-${def.name}-input`"
            type="checkbox"
            class="h-4 w-4 rounded border-gray-300"
          />
          {{ def.label }}
        </label>
      </div>

      <button
        type="button"
        :data-test="`subscriber-custom-field-${def.name}-save`"
        :disabled="savingField === def.name"
        class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
        @click="save(def)"
      >
        {{ t('common.save') }}
      </button>
    </div>
  </div>
</template>
