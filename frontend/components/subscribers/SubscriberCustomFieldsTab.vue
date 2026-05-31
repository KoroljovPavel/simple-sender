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
// Server truth snapshot, kept in lock-step with editValues on every (re)sync. isDirty() diffs the two
// so each field's Save button only enables — and a "not saved" marker only shows — when actually edited.
const baseline = reactive<Record<string, any>>({})
const savingField = ref<string | null>(null)

function initialValue(def: CustomFieldDefinition): unknown {
  const current = props.customFields?.[def.name]
  const source = current !== undefined && current !== null
    ? current
    : def.defaultValue !== undefined && def.defaultValue !== null
      ? def.defaultValue
      : null
  if (source === null) return def.type === 'BOOLEAN' ? false : ''
  // DATE is stored as ISO-8601 date-time; the date input needs 'YYYY-MM-DD'.
  return def.type === 'DATE' ? isoToDateInput(source) : source
}

// Re-seed both the edit buffer and the baseline from server truth.
function syncFromServer() {
  for (const def of definitions.value) {
    const v = initialValue(def)
    editValues[def.name] = v
    baseline[def.name] = v
  }
}

// String-normalized compare tolerates the type drift native inputs introduce (a number field's model
// becomes a string after the first keystroke, etc.); empty/null collapse to ''.
function norm(v: unknown): string {
  return v === null || v === undefined ? '' : String(v)
}
function isDirty(def: CustomFieldDefinition): boolean {
  return norm(editValues[def.name]) !== norm(baseline[def.name])
}

onMounted(async () => {
  try {
    definitions.value = await useApi()<CustomFieldDefinition[]>(
      `/api/v1/projects/${props.projectId}/custom-fields`,
    )
    syncFromServer()
  } catch (err) {
    console.warn('[subscribers] failed to load custom-field definitions', err)
  }
})

// Re-sync to server truth whenever the parent re-fetches the subscriber (e.g. after a save emits
// refresh) so a stale local value never lingers and the field flips back to a clean (saved) state.
watch(() => props.customFields, syncFromServer)

function typedValue(def: CustomFieldDefinition, raw: unknown): unknown {
  switch (def.type) {
    case 'NUMBER':
      return raw === '' || raw === null || raw === undefined ? null : Number(raw)
    case 'BOOLEAN':
      return Boolean(raw)
    case 'DATE':
      return dateInputToIso(raw) // 'YYYY-MM-DD' -> ISO-8601 date-time the backend accepts
    default: // STRING
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
    toast.success(t('subscribers.profile.customFields.saved'))
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
      class="flex flex-wrap items-end gap-3 rounded-md border p-3"
      :class="isDirty(def) ? 'border-amber-400 bg-amber-50' : 'border-gray-200'"
    >
      <div class="flex-1 min-w-[180px]">
        <label class="block text-xs font-medium text-gray-600 mb-1">
          {{ def.label }} <span class="text-gray-400">({{ t(`customFields.type.${def.type.toLowerCase()}`) }})</span>
          <span
            v-if="isDirty(def)"
            :data-test="`subscriber-custom-field-${def.name}-dirty`"
            class="ml-1 font-medium text-amber-600"
          >● {{ t('subscribers.profile.customFields.unsaved') }}</span>
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
          class="w-full rounded-md border px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
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

      <!-- Disabled until the field is actually edited, so a saved field reads as "nothing to do"
           and only dirty fields invite a click. -->
      <button
        type="button"
        :data-test="`subscriber-custom-field-${def.name}-save`"
        :disabled="savingField === def.name || !isDirty(def)"
        class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
        @click="save(def)"
      >
        {{ t('common.save') }}
      </button>
    </div>
  </div>
</template>
