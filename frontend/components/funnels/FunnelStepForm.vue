<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'
import { CURRENT_DATE_TOKEN } from '~/types/funnel'
import type { DelayUnit, FunnelStep, StepType } from '~/types/funnel'
import type { CustomFieldDefinition, CustomFieldType, Tag } from '~/types/subscriber'
import SearchableSelect from '~/components/funnels/SearchableSelect.vue'

// Shared per-type step form used by BOTH AddStepDialog and EditStepDialog (Task 10 endorses extracting
// the common form). The picker `StepType` lives OUTSIDE the form so the computed() schema can depend on
// it (same idiom as AddCustomFieldDialog) — switching type re-runs validation against the right rules.
// All client validation MIRRORS backend FunnelService.validateSteps byte-for-byte (Decision 9/12):
// empty text, http(s) imageUrl, delayValue >= 1, tagSlug ^[a-z0-9_-]{1,32}$, customFieldKey non-empty.
// For SET_CUSTOM_FIELD the value widget + validation additionally mirror the field's TYPE (the same
// per-type inputs the subscriber custom-fields tab uses), resolved from the project's definitions.
const props = defineProps<{ initial?: FunnelStep | null; submitLabel: string }>()
const emit = defineEmits<{ submit: [step: FunnelStep]; cancel: [] }>()

const { t } = useI18n()
const route = useRoute()
const projectId = computed(() => String(route.params.projectId))

const STEP_TYPES: StepType[] = [
  'SEND_MESSAGE',
  'SEND_IMAGE',
  'DELAY',
  'ADD_TAG',
  'REMOVE_TAG',
  'SET_CUSTOM_FIELD',
]
const PARSE_MODES = ['', 'HTML', 'MarkdownV2'] as const
const DELAY_UNITS: DelayUnit[] = ['MIN', 'HOUR', 'DAY']

// CANONICAL — MUST match backend requireTagSlug @Pattern byte-for-byte (FunnelService).
const TAG_SLUG_RE = /^[a-z0-9_-]{1,32}$/
// requireImageUrl: lower-cased URL must start with http:// or https://.
const IMAGE_URL_RE = /^https?:\/\//i
// Native <input type="date"> emits 'YYYY-MM-DD'; mirror the prefix the backend DATE validator accepts.
const DATE_ONLY_RE = /^\d{4}-\d{2}-\d{2}/

const selectedType = ref<StepType>(props.initial?.stepType ?? 'SEND_MESSAGE')

// Project custom-field definitions feed the key select AND the value widget/validation. Lazy-loaded the
// first time SET_CUSTOM_FIELD is the active type (no fetch for funnels that never set a field).
const definitions = ref<CustomFieldDefinition[]>([])
const cfLoading = ref(false)
let cfRequested = false
async function ensureDefinitionsLoaded() {
  if (cfRequested) return
  cfRequested = true
  cfLoading.value = true
  try {
    definitions.value =
      (await useApi()<CustomFieldDefinition[]>(`/api/v1/projects/${projectId.value}/custom-fields`)) ?? []
  } catch {
    // Network/permission failure → empty list; the select shows its empty-state. No step blocked.
  } finally {
    cfLoading.value = false
  }
}
// Project tags feed the ADD_TAG / REMOVE_TAG slug picker — lazy-loaded the first time a tag step is the
// active type. The select is strict (existing tags only): an arbitrary slug would still attach to the
// subscriber but never increment its tag counter (addTag → incrementCounter has no upsert), i.e. an
// orphan tag — so the author picks from the project's managed tags.
const tags = ref<Tag[]>([])
const tagsLoading = ref(false)
let tagsRequested = false
async function ensureTagsLoaded() {
  if (tagsRequested) return
  tagsRequested = true
  tagsLoading.value = true
  try {
    tags.value = (await useApi()<Tag[]>(`/api/v1/projects/${projectId.value}/tags`)) ?? []
  } catch {
    // Network/permission failure → empty list; the select shows its empty-state. No step blocked.
  } finally {
    tagsLoading.value = false
  }
}
watch(
  selectedType,
  (ty) => {
    if (ty === 'SET_CUSTOM_FIELD') ensureDefinitionsLoaded()
    if (ty === 'ADD_TAG' || ty === 'REMOVE_TAG') ensureTagsLoaded()
  },
  { immediate: true },
)

const cfOptions = computed(() =>
  definitions.value.map((d) => ({ value: d.name, label: d.label, hint: d.type })),
)
// Tag label is optional → fall back to the slug so every option is readable.
const tagOptions = computed(() =>
  tags.value.map((tg) => ({ value: tg.slug, label: tg.label ?? tg.slug })),
)
// Plain ref (NOT a computed over customFieldKey): the validation `schema` below is evaluated by useForm
// during setup, BEFORE defineField creates customFieldKey — a computed that read customFieldKey there
// would hit its temporal dead zone. A watcher keeps this in sync once the form fields exist (below).
const selectedFieldType = ref<CustomFieldType | null>(null)

// Per-field-type value rule (UX mirror of backend CustomFieldValueValidator — the funnel save endpoint
// does NOT type-check the value, so this is the only place the author is guided before execution).
function cfValueSchema(type: CustomFieldType | null): z.ZodTypeAny {
  switch (type) {
    case 'NUMBER':
      return z
        .any()
        .refine(
          (v) => v === '' || v === null || v === undefined || Number.isFinite(Number(v)),
          t('funnels.steps.validation.customFieldValueNumber'),
        )
    case 'DATE':
      return z
        .any()
        .refine(
          (v) =>
            v === '' || v === null || v === undefined || v === CURRENT_DATE_TOKEN || DATE_ONLY_RE.test(String(v)),
          t('funnels.steps.validation.customFieldValueDate'),
        )
    case 'STRING':
      return z
        .any()
        .refine(
          (v) => v === null || v === undefined || String(v).trim().length <= 1024,
          t('funnels.steps.validation.customFieldValueStringMax'),
        )
    default: // BOOLEAN (checkbox is always valid) or type not yet resolved
      return z.any()
  }
}

// One schema computed over selectedType: only the active type's fields are validated; the rest fall back
// to z.any() so a stale value from another branch never blocks submit.
function schemaFor(type: StepType): z.ZodTypeAny {
  const text =
    type === 'SEND_MESSAGE'
      ? z.string().trim().min(1, t('funnels.steps.validation.textRequired')).max(4096, t('funnels.steps.validation.textMax'))
      : z.any()
  const imageUrl =
    type === 'SEND_IMAGE'
      ? z
          .string()
          .trim()
          .min(1, t('funnels.steps.validation.imageUrlRequired'))
          .regex(IMAGE_URL_RE, t('funnels.steps.validation.imageUrlScheme'))
      : z.any()
  const delayValue =
    type === 'DELAY'
      ? z.coerce
          .number({ invalid_type_error: t('funnels.steps.validation.delayMin') })
          .int(t('funnels.steps.validation.delayMin'))
          .min(1, t('funnels.steps.validation.delayMin'))
      : z.any()
  const tagSlug =
    type === 'ADD_TAG' || type === 'REMOVE_TAG'
      ? z.string().trim().regex(TAG_SLUG_RE, t('funnels.steps.validation.tagPattern'))
      : z.any()
  const customFieldKey =
    type === 'SET_CUSTOM_FIELD'
      ? z.string().trim().min(1, t('funnels.steps.validation.customFieldKeyRequired'))
      : z.any()
  const customFieldValue =
    type === 'SET_CUSTOM_FIELD' ? cfValueSchema(selectedFieldType.value) : z.any()
  return z.object({
    text,
    parseMode: z.any(),
    imageUrl,
    caption: z.any(),
    delayValue,
    delayUnit: z.any(),
    tagSlug,
    customFieldKey,
    customFieldValue,
  })
}

const schema = computed(() => toTypedSchema(schemaFor(selectedType.value)))

const { defineField, handleSubmit, errors } = useForm({
  validationSchema: schema,
  initialValues: {
    text: props.initial?.text ?? '',
    parseMode: props.initial?.parseMode ?? '',
    imageUrl: props.initial?.imageUrl ?? '',
    caption: props.initial?.caption ?? '',
    delayValue: props.initial?.delayValue ?? 1,
    delayUnit: (props.initial?.delayUnit as DelayUnit | undefined) ?? 'MIN',
    tagSlug: props.initial?.tagSlug ?? '',
    customFieldKey: props.initial?.customFieldKey ?? '',
    customFieldValue: props.initial?.customFieldValue ?? '',
  },
})
const [text, textAttrs] = defineField('text')
const [parseMode, parseModeAttrs] = defineField('parseMode')
const [imageUrl, imageUrlAttrs] = defineField('imageUrl')
const [caption, captionAttrs] = defineField('caption')
const [delayValue, delayValueAttrs] = defineField('delayValue')
const [delayUnit, delayUnitAttrs] = defineField('delayUnit')
// tagSlug is bound to SearchableSelect via v-model (no vee-validate v-bind attrs — the combobox owns its
// input); validation runs on model update like the other fields.
const [tagSlug] = defineField('tagSlug')
// customFieldKey is bound to SearchableSelect via v-model; customFieldValue to a per-type widget.
// Neither needs the vee-validate v-bind attrs (the widgets own their input element); validation runs on
// model update like every other field.
const [customFieldKey] = defineField('customFieldKey')
const [customFieldValue] = defineField('customFieldValue')

// Resolve the selected field's type from the loaded definitions — drives the value widget + validation.
watch(
  [definitions, customFieldKey],
  () => {
    selectedFieldType.value =
      definitions.value.find((d) => d.name === customFieldKey.value)?.type ?? null
  },
  { immediate: true },
)

// DATE "current date" affordance: the toggle simply writes the @now sentinel into the value (which the
// engine resolves at execution time) and the date input hides — no separate state to keep in sync.
const useCurrentDate = computed({
  get: () => customFieldValue.value === CURRENT_DATE_TOKEN,
  set: (on: boolean) => {
    customFieldValue.value = on ? CURRENT_DATE_TOKEN : ''
  },
})

// Switching to a different field clears the old value — a date typed for a DATE field is meaningless once
// the user repoints the step at a NUMBER field, etc. (skips the very first, seeding assignment).
watch(customFieldKey, (next, prev) => {
  if (prev !== undefined && next !== prev) customFieldValue.value = ''
})

// On EDIT the stored value must be re-shaped into the widget's representation, but the type is only known
// once definitions load — reconcile exactly once when the type first resolves.
let reconciled = false
watch(
  selectedFieldType,
  (type) => {
    if (reconciled) return
    if (props.initial?.stepType !== 'SET_CUSTOM_FIELD') {
      reconciled = true
      return
    }
    if (!type) return
    reconciled = true
    const raw = props.initial?.customFieldValue
    if (raw === CURRENT_DATE_TOKEN) {
      customFieldValue.value = CURRENT_DATE_TOKEN
    } else if (raw === null || raw === undefined) {
      customFieldValue.value = type === 'BOOLEAN' ? false : ''
    } else if (type === 'DATE') {
      customFieldValue.value = isoToDateInput(raw) // ISO date-time → 'YYYY-MM-DD' for the date input
    } else if (type === 'BOOLEAN') {
      customFieldValue.value = Boolean(raw)
    } else {
      customFieldValue.value = raw
    }
  },
  { immediate: true },
)

function blankToNull(v: unknown): string | null {
  const s = typeof v === 'string' ? v.trim() : v
  return s === '' || s === null || s === undefined ? null : (s as string)
}

// Coerce the flat UI model to the persisted custom-field value by the resolved field type, so the engine's
// per-type validator accepts it at execution: number/boolean as-is, DATE → ISO (or the @now sentinel).
function customFieldValueForSubmit(raw: unknown): unknown {
  const type = selectedFieldType.value
  if (type === 'DATE' && raw === CURRENT_DATE_TOKEN) return CURRENT_DATE_TOKEN
  if (type === 'NUMBER') return raw === '' || raw === null || raw === undefined ? null : Number(raw)
  if (type === 'DATE') return dateInputToIso(raw)
  if (type === 'BOOLEAN') return Boolean(raw)
  return blankToNull(raw) // STRING or type not resolved
}

// Narrow the flat model to ONLY the active type's persisted fields — the server ignores the rest, but
// sending a clean step keeps the array readable and avoids leaking a stale value from a switched branch.
const onSubmit = handleSubmit((values) => {
  const type = selectedType.value
  let step: FunnelStep
  switch (type) {
    case 'SEND_MESSAGE':
      step = { stepType: type, text: (values.text as string).trim(), parseMode: blankToNull(values.parseMode) }
      break
    case 'SEND_IMAGE':
      step = {
        stepType: type,
        imageUrl: (values.imageUrl as string).trim(),
        caption: blankToNull(values.caption),
        parseMode: blankToNull(values.parseMode),
      }
      break
    case 'DELAY':
      step = { stepType: type, delayValue: Number(values.delayValue), delayUnit: values.delayUnit as DelayUnit }
      break
    case 'ADD_TAG':
    case 'REMOVE_TAG':
      step = { stepType: type, tagSlug: (values.tagSlug as string).trim() }
      break
    case 'SET_CUSTOM_FIELD':
      step = {
        stepType: type,
        customFieldKey: (values.customFieldKey as string).trim(),
        customFieldValue: customFieldValueForSubmit(values.customFieldValue),
      }
      break
  }
  emit('submit', step)
})
</script>

<template>
  <form data-test="step-form" class="space-y-3" novalidate @submit.prevent="onSubmit">
    <div>
      <label for="step-type" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.typeLabel') }}</label>
      <select
        id="step-type"
        v-model="selectedType"
        data-test="step-type-select"
        class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
      >
        <option v-for="ty in STEP_TYPES" :key="ty" :value="ty">{{ t(`funnels.steps.type.${ty}`) }}</option>
      </select>
    </div>

    <!-- SEND_MESSAGE -->
    <template v-if="selectedType === 'SEND_MESSAGE'">
      <div>
        <label for="step-text" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.text') }}</label>
        <textarea
          id="step-text"
          v-model="text"
          v-bind="textAttrs"
          data-test="step-text-input"
          rows="3"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.text" data-test="step-text-error" class="mt-1 text-sm text-red-600">{{ errors.text }}</p>
      </div>
      <div>
        <label for="step-parsemode" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.parseMode') }}</label>
        <select
          id="step-parsemode"
          v-model="parseMode"
          v-bind="parseModeAttrs"
          data-test="step-parsemode-select"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          <option v-for="pm in PARSE_MODES" :key="pm" :value="pm">
            {{ pm === '' ? t('funnels.steps.form.parseModeNone') : pm }}
          </option>
        </select>
      </div>
    </template>

    <!-- SEND_IMAGE -->
    <template v-else-if="selectedType === 'SEND_IMAGE'">
      <div>
        <label for="step-imageurl" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.imageUrl') }}</label>
        <input
          id="step-imageurl"
          v-model="imageUrl"
          v-bind="imageUrlAttrs"
          data-test="step-imageurl-input"
          type="text"
          autocomplete="off"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.imageUrl" data-test="step-imageurl-error" class="mt-1 text-sm text-red-600">{{ errors.imageUrl }}</p>
      </div>
      <div>
        <label for="step-caption" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.caption') }}</label>
        <input
          id="step-caption"
          v-model="caption"
          v-bind="captionAttrs"
          data-test="step-caption-input"
          type="text"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
      </div>
    </template>

    <!-- DELAY -->
    <template v-else-if="selectedType === 'DELAY'">
      <div class="flex gap-2">
        <div class="flex-1">
          <label for="step-delay-value" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.delayValue') }}</label>
          <input
            id="step-delay-value"
            v-model="delayValue"
            v-bind="delayValueAttrs"
            data-test="step-delay-value-input"
            type="number"
            min="1"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
        </div>
        <div class="flex-1">
          <label for="step-delay-unit" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.delayUnit') }}</label>
          <select
            id="step-delay-unit"
            v-model="delayUnit"
            v-bind="delayUnitAttrs"
            data-test="step-delay-unit-select"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option v-for="u in DELAY_UNITS" :key="u" :value="u">{{ t(`funnels.steps.unit.${u}`) }}</option>
          </select>
        </div>
      </div>
      <p v-if="errors.delayValue" data-test="step-delay-error" class="mt-1 text-sm text-red-600">{{ errors.delayValue }}</p>
    </template>

    <!-- ADD_TAG / REMOVE_TAG -->
    <template v-else-if="selectedType === 'ADD_TAG' || selectedType === 'REMOVE_TAG'">
      <div>
        <label for="step-tag" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.tagSlug') }}</label>
        <SearchableSelect
          id="step-tag"
          v-model="tagSlug"
          :options="tagOptions"
          :loading="tagsLoading"
          :invalid="!!errors.tagSlug"
          test-prefix="step-tag"
          :placeholder="t('funnels.steps.form.tagPlaceholder')"
          :loading-text="t('funnels.steps.form.tagLoading')"
          :empty-text="t('funnels.steps.form.tagEmpty')"
          :no-matches-text="t('funnels.steps.form.tagNoMatches')"
        />
        <p v-if="errors.tagSlug" data-test="step-tag-error" class="mt-1 text-sm text-red-600">{{ errors.tagSlug }}</p>
      </div>
    </template>

    <!-- SET_CUSTOM_FIELD -->
    <template v-else>
      <div>
        <label for="step-cf-key" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.customFieldKey') }}</label>
        <SearchableSelect
          id="step-cf-key"
          v-model="customFieldKey"
          :options="cfOptions"
          :loading="cfLoading"
          :invalid="!!errors.customFieldKey"
          test-prefix="step-cf-key"
          :placeholder="t('funnels.steps.form.customFieldKeyPlaceholder')"
          :loading-text="t('funnels.steps.form.customFieldKeyLoading')"
          :empty-text="t('funnels.steps.form.customFieldKeyEmpty')"
          :no-matches-text="t('funnels.steps.form.customFieldKeyNoMatches')"
        />
        <p v-if="errors.customFieldKey" data-test="step-cf-key-error" class="mt-1 text-sm text-red-600">{{ errors.customFieldKey }}</p>
      </div>
      <div>
        <label for="step-cf-value" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.customFieldValue') }}</label>

        <!-- DATE: optional "current date" (resolves at execution) OR an explicit date. -->
        <template v-if="selectedFieldType === 'DATE'">
          <label class="mb-2 flex items-center gap-2 text-sm">
            <input
              v-model="useCurrentDate"
              data-test="step-cf-current-date"
              type="checkbox"
              class="h-4 w-4 rounded border-gray-300"
            />
            {{ t('funnels.steps.form.customFieldCurrentDate') }}
          </label>
          <input
            v-if="!useCurrentDate"
            id="step-cf-value"
            v-model="customFieldValue"
            data-test="step-cf-value-input"
            type="date"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
        </template>

        <!-- BOOLEAN: checkbox. -->
        <label
          v-else-if="selectedFieldType === 'BOOLEAN'"
          class="inline-flex items-center gap-2 text-sm"
        >
          <input
            v-model="customFieldValue"
            data-test="step-cf-value-input"
            type="checkbox"
            class="h-4 w-4 rounded border-gray-300"
          />
          {{ t('funnels.steps.form.customFieldValueBool') }}
        </label>

        <!-- NUMBER -->
        <input
          v-else-if="selectedFieldType === 'NUMBER'"
          id="step-cf-value"
          v-model="customFieldValue"
          data-test="step-cf-value-input"
          type="number"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />

        <!-- STRING (or type not yet resolved) -->
        <input
          v-else
          id="step-cf-value"
          v-model="customFieldValue"
          data-test="step-cf-value-input"
          type="text"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />

        <p v-if="errors.customFieldValue" data-test="step-cf-value-error" class="mt-1 text-sm text-red-600">{{ errors.customFieldValue }}</p>
      </div>
    </template>

    <div class="flex justify-end gap-2 pt-2">
      <button
        type="button"
        data-test="step-form-cancel"
        class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
        @click="emit('cancel')"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        type="submit"
        data-test="step-form-submit"
        class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
      >
        {{ props.submitLabel }}
      </button>
    </div>
  </form>
</template>
