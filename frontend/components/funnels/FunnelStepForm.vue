<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'
import type { DelayUnit, FunnelStep, StepType } from '~/types/funnel'

// Shared per-type step form used by BOTH AddStepDialog and EditStepDialog (Task 10 endorses extracting
// the common form). The picker `StepType` lives OUTSIDE the form so the computed() schema can depend on
// it (same idiom as AddCustomFieldDialog) — switching type re-runs validation against the right rules.
// All client validation MIRRORS backend FunnelService.validateSteps byte-for-byte (Decision 9/12):
// empty text, http(s) imageUrl, delayValue >= 1, tagSlug ^[a-z0-9_-]{1,32}$, customFieldKey non-empty.
const props = defineProps<{ initial?: FunnelStep | null; submitLabel: string }>()
const emit = defineEmits<{ submit: [step: FunnelStep]; cancel: [] }>()

const { t } = useI18n()

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

const selectedType = ref<StepType>(props.initial?.stepType ?? 'SEND_MESSAGE')

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
  return z.object({
    text,
    parseMode: z.any(),
    imageUrl,
    caption: z.any(),
    delayValue,
    delayUnit: z.any(),
    tagSlug,
    customFieldKey,
    customFieldValue: z.any(),
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
    customFieldValue: (props.initial?.customFieldValue as string | undefined) ?? '',
  },
})
const [text, textAttrs] = defineField('text')
const [parseMode, parseModeAttrs] = defineField('parseMode')
const [imageUrl, imageUrlAttrs] = defineField('imageUrl')
const [caption, captionAttrs] = defineField('caption')
const [delayValue, delayValueAttrs] = defineField('delayValue')
const [delayUnit, delayUnitAttrs] = defineField('delayUnit')
const [tagSlug, tagSlugAttrs] = defineField('tagSlug')
const [customFieldKey, customFieldKeyAttrs] = defineField('customFieldKey')
const [customFieldValue, customFieldValueAttrs] = defineField('customFieldValue')

function blankToNull(v: unknown): string | null {
  const s = typeof v === 'string' ? v.trim() : v
  return s === '' || s === null || s === undefined ? null : (s as string)
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
        customFieldValue: blankToNull(values.customFieldValue),
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
        <input
          id="step-tag"
          v-model="tagSlug"
          v-bind="tagSlugAttrs"
          data-test="step-tag-input"
          type="text"
          autocomplete="off"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.tagSlug" data-test="step-tag-error" class="mt-1 text-sm text-red-600">{{ errors.tagSlug }}</p>
      </div>
    </template>

    <!-- SET_CUSTOM_FIELD -->
    <template v-else>
      <div>
        <label for="step-cf-key" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.customFieldKey') }}</label>
        <input
          id="step-cf-key"
          v-model="customFieldKey"
          v-bind="customFieldKeyAttrs"
          data-test="step-cf-key-input"
          type="text"
          autocomplete="off"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.customFieldKey" data-test="step-cf-key-error" class="mt-1 text-sm text-red-600">{{ errors.customFieldKey }}</p>
      </div>
      <div>
        <label for="step-cf-value" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.customFieldValue') }}</label>
        <input
          id="step-cf-value"
          v-model="customFieldValue"
          v-bind="customFieldValueAttrs"
          data-test="step-cf-value-input"
          type="text"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
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
