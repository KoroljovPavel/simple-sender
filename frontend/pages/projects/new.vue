<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'

definePageMeta({ layout: 'default' })

const { t } = useI18n()
const localePath = useLocalePath()
const apiError = useApiError()
const projectsStore = useProjectsStore()

const timezones = Intl.supportedValuesOf('timeZone')
const browserTz = Intl.DateTimeFormat().resolvedOptions().timeZone
const defaultTz = timezones.includes(browserTz) ? browserTz : 'UTC'

const schemaComputed = computed(() =>
  toTypedSchema(
    z.object({
      name: z
        .string()
        .trim()
        .min(3, t('validation.projectNameMin'))
        .max(50, t('validation.projectNameMax')),
      description: z.string().max(200, t('validation.projectDescriptionMax')),
      timezone: z
        .string()
        .refine(
          (v) => Intl.supportedValuesOf('timeZone').includes(v),
          t('validation.timezoneInvalid'),
        ),
    }),
  ),
)

const { defineField, handleSubmit, isSubmitting, errors } = useForm({
  validationSchema: schemaComputed,
  initialValues: { name: '', description: '', timezone: defaultTz },
})

const [name, nameAttrs] = defineField('name')
const [description, descriptionAttrs] = defineField('description')
const [timezone, timezoneAttrs] = defineField('timezone')

const submitError = ref<string | null>(null)

const onSubmit = handleSubmit(async (values) => {
  submitError.value = null
  const trimmedDescription = (values.description ?? '').trim()
  try {
    await projectsStore.create({
      name: values.name.trim(),
      description: trimmedDescription === '' ? null : trimmedDescription,
      timezone: values.timezone,
    })
    await navigateTo(localePath('/dashboard'))
  } catch (e: unknown) {
    submitError.value = apiError(e, 'projects.create')
  }
})
</script>

<template>
  <div class="max-w-xl mx-auto">
    <h1 class="text-2xl font-semibold mb-6">{{ t('projects.create.title') }}</h1>

    <form novalidate class="space-y-4" @submit.prevent="onSubmit">
      <div>
        <label for="name" class="block text-sm font-medium mb-1">{{ t('projects.create.nameLabel') }}</label>
        <input
          id="name"
          v-model="name"
          v-bind="nameAttrs"
          data-test="project-name-input"
          name="name"
          type="text"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.name" data-test="project-name-error" class="text-sm text-red-600 mt-1">
          {{ errors.name }}
        </p>
      </div>

      <div>
        <label for="description" class="block text-sm font-medium mb-1">{{ t('projects.create.descriptionLabel') }}</label>
        <textarea
          id="description"
          v-model="description"
          v-bind="descriptionAttrs"
          data-test="project-description-input"
          name="description"
          rows="3"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.description" data-test="project-description-error" class="text-sm text-red-600 mt-1">
          {{ errors.description }}
        </p>
      </div>

      <div>
        <label for="timezone" class="block text-sm font-medium mb-1">{{ t('projects.create.timezoneLabel') }}</label>
        <select
          id="timezone"
          v-model="timezone"
          v-bind="timezoneAttrs"
          data-test="project-timezone-select"
          name="timezone"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          <option v-for="tz in timezones" :key="tz" :value="tz">{{ tz }}</option>
        </select>
        <p v-if="errors.timezone" data-test="project-timezone-error" class="text-sm text-red-600 mt-1">
          {{ errors.timezone }}
        </p>
      </div>

      <p v-if="submitError" data-test="submit-error" class="text-sm text-red-600">
        {{ submitError }}
      </p>

      <button
        type="submit"
        data-test="project-submit"
        :disabled="isSubmitting"
        class="w-full bg-blue-600 text-white rounded-md py-2 font-medium disabled:opacity-50 hover:bg-blue-700"
      >
        {{ isSubmitting ? t('projects.create.submitting') : t('projects.create.submit') }}
      </button>
    </form>
  </div>
</template>
