<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'
import type { Project } from '~/types/project'

definePageMeta({ layout: 'default' })

const { t } = useI18n()
const route = useRoute()
const localePath = useLocalePath()
const apiError = useApiError()
const projectsStore = useProjectsStore()

const projectId = computed(() => String(route.params.projectId ?? ''))

const project = computed(() =>
  projectsStore.projects.find((p) => p.id === projectId.value && p.deletedAt === null) ?? null,
)

// Intl.supportedValuesOf may be missing on very old Safari; degrade to the
// resolved browser zone so validation still admits at least one value.
const timezones = (() => {
  if (typeof Intl.supportedValuesOf === 'function') return Intl.supportedValuesOf('timeZone')
  return [Intl.DateTimeFormat().resolvedOptions().timeZone]
})()

// Hydration + staleness guard. Same-tab back-after-delete and cross-tab edits
// both land here on routes for projects no longer in the active list. The
// explicit GET surfaces the cross-tab case (where the store is stale-true);
// for any 404 we surface the persistent banner via pendingBannerKey and bail
// to /projects so the user sees a useful page instead of an empty form.
onMounted(async () => {
  if (!import.meta.client) return

  if (!projectsStore.isLoaded) {
    try {
      await projectsStore.fetchAll()
    } catch (err) {
      console.warn('[projects/settings] fetchAll failed', err)
    }
  }

  // No happy-path short-circuit here: the cross-tab smoke-test (2.6) is
  // precisely the case where `projectsStore.isLoaded && project.value !== null`
  // is misleading — this tab's store still has the row marked active, but the
  // server already deleted it. Skipping the GET would let the user interact
  // with a doomed form until their first PATCH/DELETE triggers the 404. One
  // extra GET per settings mount is the agreed price for "redirect on entry".
  try {
    await useApi()<Project>(`/api/v1/projects/${projectId.value}`)
  } catch (e: unknown) {
    if ((e as { statusCode?: number })?.statusCode === 404) {
      // For currentProjectId case, useApi 404 interceptor already set the
      // banner key and cleared the store. For other cases (foreign id, deleted
      // non-current), set it explicitly so /projects renders the same banner.
      if (!projectsStore.pendingBannerKey) {
        projectsStore.pendingBannerKey = 'errors.projects.unavailable'
      }
      await navigateTo(localePath('/projects'))
      return
    }
    console.warn('[projects/settings] project GET failed', e)
  }

  if (!project.value) {
    if (!projectsStore.pendingBannerKey) {
      projectsStore.pendingBannerKey = 'errors.projects.unavailable'
    }
    await navigateTo(localePath('/projects'))
  }
})

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
          (v) => v === '' || timezones.includes(v),
          t('validation.timezoneInvalid'),
        ),
    }),
  ),
)

const { defineField, handleSubmit, isSubmitting, errors, resetForm } = useForm({
  validationSchema: schemaComputed,
  initialValues: {
    name: project.value?.name ?? '',
    description: project.value?.description ?? '',
    timezone: project.value?.timezone ?? '',
  },
})

// Lazy hydration (Decision 9): if fetchAll resolves AFTER the form is wired
// up, repopulate fields from the now-available project. Skip later runs to
// avoid stomping on a partially-edited form.
let formHydrated = project.value !== null
watch(project, (next) => {
  if (formHydrated || !next) return
  resetForm({
    values: { name: next.name, description: next.description ?? '', timezone: next.timezone },
  })
  formHydrated = true
})

const [nameField, nameAttrs] = defineField('name')
const [descriptionField, descriptionAttrs] = defineField('description')
// TimezonePicker accepts only v-model — same rationale as new.vue.
const [timezoneField] = defineField('timezone')

const saveStatus = ref<'idle' | 'saved'>('idle')
const saveError = ref<string | null>(null)

const onSubmit = handleSubmit(async (values) => {
  const current = project.value
  if (!current) return
  saveError.value = null

  const partial: Partial<{ name: string; description: string | null; timezone: string }> = {}
  const trimmedName = values.name.trim()
  if (trimmedName !== '' && trimmedName !== current.name) {
    partial.name = trimmedName
  }
  // PATCH-vs-POST description-blank asymmetry: ship the literal empty string
  // and let the backend normalize to null (see decisions.md). POST in
  // pages/projects/new.vue normalizes on the client because there's no delta
  // semantics — here we want to signal "clear the description" explicitly.
  const currentDescription = current.description ?? ''
  if (values.description !== currentDescription) {
    partial.description = values.description
  }
  if (values.timezone !== '' && values.timezone !== current.timezone) {
    partial.timezone = values.timezone
  }

  if (Object.keys(partial).length === 0) {
    saveStatus.value = 'saved'
    return
  }

  try {
    await projectsStore.update(current.id, partial)
    saveStatus.value = 'saved'
  } catch (e: unknown) {
    saveStatus.value = 'idle'
    saveError.value = apiError(e, 'projects.update')
  }
})

const isDeleteModalOpen = ref(false)
const isDeleting = ref(false)
const deleteError = ref<string | null>(null)
const deleteTypedName = ref('')

const isDeleteEnabled = computed(() => {
  const current = project.value
  if (!current) return false
  return deleteTypedName.value.trim() === current.name
})

function openDeleteModal() {
  deleteError.value = null
  deleteTypedName.value = ''
  isDeleteModalOpen.value = true
}

function closeDeleteModal() {
  if (isDeleting.value) return
  isDeleteModalOpen.value = false
}

async function confirmDelete() {
  const current = project.value
  if (!current || !isDeleteEnabled.value || isDeleting.value) return
  isDeleting.value = true
  deleteError.value = null
  try {
    await projectsStore.softDelete(current.id)
    const activeRemaining = projectsStore.projects.filter((p) => p.deletedAt === null).length
    const redirectPath = activeRemaining === 0 ? '/dashboard' : '/projects'
    await navigateTo(localePath(redirectPath))
  } catch (e: unknown) {
    isDeleting.value = false
    deleteError.value = apiError(e, 'projects.delete')
  }
}
</script>

<template>
  <div class="max-w-2xl space-y-8">
    <SettingsSubnav :project-id="projectId" />
    <h1 class="text-2xl font-semibold">{{ t('projects.settings.title') }}</h1>

    <!--
      No "project missing" inline fallback: settings/index.vue redirects to /projects
      in onMounted when the project is not in the active list (same-tab delete,
      cross-tab stale, foreign / non-existent id). The /projects page renders
      a persistent banner keyed off projectsStore.pendingBannerKey.
    -->
    <template v-if="project">
      <!-- General section -->
      <section data-test="settings-general" class="rounded-md border p-4 space-y-3">
        <h2 class="text-lg font-medium">{{ t('projects.settings.general') }}</h2>

        <form
          novalidate
          data-test="settings-form"
          class="space-y-4"
          @submit.prevent="onSubmit"
        >
          <div>
            <label for="settings-name" class="block text-sm font-medium mb-1">
              {{ t('projects.settings.rename') }}
            </label>
            <input
              id="settings-name"
              v-model="nameField"
              v-bind="nameAttrs"
              data-test="settings-name-input"
              name="name"
              type="text"
              class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <p v-if="errors.name" data-test="settings-name-error" class="text-sm text-red-600 mt-1">
              {{ errors.name }}
            </p>
          </div>

          <div>
            <label for="settings-description" class="block text-sm font-medium mb-1">
              {{ t('projects.settings.descriptionLabel') }}
            </label>
            <textarea
              id="settings-description"
              v-model="descriptionField"
              v-bind="descriptionAttrs"
              data-test="settings-description-input"
              name="description"
              rows="3"
              class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <p v-if="errors.description" data-test="settings-description-error" class="text-sm text-red-600 mt-1">
              {{ errors.description }}
            </p>
          </div>

          <div>
            <label for="settings-timezone" class="block text-sm font-medium mb-1">
              {{ t('projects.settings.timezoneLabel') }}
            </label>
            <!--
              See comment in new.vue: omit v-bind="timezoneAttrs" so the field
              attrs don't fall through to TimezonePicker's root <div>.
            -->
            <TimezonePicker
              id="settings-timezone"
              v-model="timezoneField"
              :invalid="!!errors.timezone"
              data-test="settings-timezone"
            />
            <p v-if="errors.timezone" data-test="settings-timezone-error" class="text-sm text-red-600 mt-1">
              {{ errors.timezone }}
            </p>
          </div>

          <p v-if="saveStatus === 'saved'" data-test="settings-saved" class="text-sm text-green-700">
            {{ t('projects.settings.saved') }}
          </p>
          <p v-if="saveError" data-test="settings-error" class="text-sm text-red-600">
            {{ saveError }}
          </p>

          <button
            type="submit"
            data-test="settings-submit"
            :disabled="isSubmitting"
            class="px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
          >
            {{ isSubmitting ? t('projects.settings.saving') : t('projects.settings.save') }}
          </button>
        </form>
      </section>

      <!-- Danger zone -->
      <section
        data-test="settings-danger-zone"
        class="rounded-md border border-red-200 p-4 space-y-3"
      >
        <h2 class="text-lg font-medium text-red-700">{{ t('projects.settings.dangerZone') }}</h2>
        <button
          type="button"
          data-test="delete-project-open"
          class="px-3 py-1 text-sm bg-red-600 text-white rounded hover:bg-red-700"
          @click="openDeleteModal"
        >
          {{ t('projects.settings.delete.open') }}
        </button>
      </section>

      <!-- Delete confirmation modal -->
      <div
        v-if="isDeleteModalOpen"
        data-test="delete-project-modal"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-project-modal-title"
      >
        <div class="w-full max-w-md rounded-md bg-white p-5 shadow-lg space-y-4">
          <h3 id="delete-project-modal-title" class="text-lg font-semibold">
            {{ t('projects.settings.delete.modalTitle') }}
          </h3>
          <p class="text-sm text-gray-700">
            {{ t('projects.settings.delete.modalIntro') }}
          </p>
          <div class="space-y-1">
            <label for="delete-project-name" class="block text-sm text-gray-700">
              {{ t('errors.projects.delete.confirmTypeName') }}
            </label>
            <!--
              The shipped locale string has no {name} placeholder, so we render
              the project name explicitly as a separate emphasized element.
              Avoids editing locales (task AC) and keeps the name visible.
            -->
            <p class="text-sm">
              <strong data-test="delete-project-expected-name">{{ project.name }}</strong>
            </p>
            <input
              id="delete-project-name"
              v-model="deleteTypedName"
              data-test="delete-project-name-input"
              type="text"
              autocomplete="off"
              autofocus
              class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-red-500"
            />
          </div>

          <p v-if="deleteError" data-test="delete-project-error" class="text-sm text-red-600">
            {{ deleteError }}
          </p>

          <div class="flex justify-end gap-2 pt-2">
            <button
              type="button"
              data-test="delete-project-cancel"
              :disabled="isDeleting"
              class="px-3 py-1 text-sm border rounded hover:bg-gray-50 disabled:opacity-50"
              @click="closeDeleteModal"
            >
              {{ t('projects.settings.delete.cancel') }}
            </button>
            <button
              type="button"
              data-test="delete-project-confirm"
              :disabled="!isDeleteEnabled || isDeleting"
              class="px-3 py-1 text-sm bg-red-600 text-white rounded hover:bg-red-700 disabled:opacity-50"
              @click="confirmDelete"
            >
              {{ isDeleting ? t('projects.settings.delete.deleting') : t('projects.settings.delete.confirm') }}
            </button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
