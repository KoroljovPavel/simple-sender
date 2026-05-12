<script setup lang="ts">
import type { Project } from '~/types/project'

definePageMeta({ layout: 'default' })

const { t } = useI18n()
const localePath = useLocalePath()
const apiError = useApiError()
const projectsStore = useProjectsStore()

const ACTIVE_LIMIT = 5
const RETENTION_DAYS = 7
const DAY_MS = 24 * 60 * 60 * 1000
const BANNER_TIMEOUT_MS = 4000

const softDeletedProjects = ref<Project[]>([])
const restoringIds = ref<Set<string>>(new Set())
const banner = ref<{ kind: 'success' | 'error' | 'info'; message: string } | null>(null)
let bannerTimer: ReturnType<typeof setTimeout> | null = null

function showBanner(next: { kind: 'success' | 'error' | 'info'; message: string }) {
  banner.value = next
  if (bannerTimer) clearTimeout(bannerTimer)
  bannerTimer = setTimeout(() => {
    banner.value = null
    bannerTimer = null
  }, BANNER_TIMEOUT_MS)
}

onBeforeUnmount(() => {
  if (bannerTimer) clearTimeout(bannerTimer)
})

async function refreshSoftDeleted() {
  try {
    const all = await useApi()<Project[]>('/api/v1/projects', { query: { include_deleted: true } })
    softDeletedProjects.value = (all ?? [])
      .filter((p) => p.deletedAt !== null)
      // Most recently deleted first — UX nicety, backend does not order this slice.
      .sort((a, b) => ((a.deletedAt ?? '') < (b.deletedAt ?? '') ? 1 : -1))
  } catch {
    // Soft-deleted listing is a non-fatal companion fetch; surfacing an error
    // here would shadow the (working) active list. Keep the section empty if
    // the call fails — user can refresh.
    softDeletedProjects.value = []
  }
}

if (import.meta.client) {
  if (!projectsStore.isLoaded) projectsStore.fetchAll()
  refreshSoftDeleted()
}

const activeProjects = computed(() => projectsStore.projects.filter((p) => p.deletedAt === null))
const atLimit = computed(() => activeProjects.value.length >= ACTIVE_LIMIT)

function daysRemaining(deletedAt: string | null): number {
  if (!deletedAt) return 0
  const expiresAt = new Date(deletedAt).getTime() + RETENTION_DAYS * DAY_MS
  return Math.max(0, Math.ceil((expiresAt - Date.now()) / DAY_MS))
}

async function onRestore(project: Project) {
  if (restoringIds.value.has(project.id)) return
  const preRequestName = project.name
  restoringIds.value.add(project.id)
  try {
    const restored = await projectsStore.restore(project.id)
    softDeletedProjects.value = softDeletedProjects.value.filter((p) => p.id !== project.id)
    if (restored.name !== preRequestName) {
      showBanner({ kind: 'info', message: t('errors.projects.restore.renamedDueToConflict') })
    } else {
      showBanner({ kind: 'success', message: t('projects.restore.success') })
    }
  } catch (err: unknown) {
    const msg = apiError(err, 'projects.restore')
    if (msg) showBanner({ kind: 'error', message: msg })
  } finally {
    restoringIds.value.delete(project.id)
  }
}

const bannerClass = computed(() => {
  switch (banner.value?.kind) {
    case 'success': return 'bg-green-50 border-green-300 text-green-800'
    case 'error': return 'bg-red-50 border-red-300 text-red-800'
    case 'info': return 'bg-blue-50 border-blue-300 text-blue-800'
    default: return ''
  }
})
</script>

<template>
  <div class="space-y-8">
    <div class="flex items-center justify-between gap-4">
      <h1 class="text-2xl font-semibold">{{ t('projects.title') }}</h1>

      <NuxtLinkLocale
        v-if="!atLimit"
        :to="localePath('/projects/new')"
        data-test="create-project-button"
        class="inline-block rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
      >
        {{ t('layout.projectSelector.createNew') }}
      </NuxtLinkLocale>
      <button
        v-else
        type="button"
        disabled
        aria-disabled="true"
        data-test="create-project-button"
        :title="t('projects.create.limitReachedTooltip')"
        class="inline-block rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white opacity-50 cursor-not-allowed"
      >
        {{ t('layout.projectSelector.createNew') }}
      </button>
    </div>

    <!--
      Restore banner lives at page level (not nested inside the Recently
      deleted section): a successful restore removes the last soft-deleted
      row and the section is conditionally hidden, but the success message
      still needs to be visible to the user.
    -->
    <div
      v-if="banner"
      data-test="restore-toast"
      role="status"
      :class="['rounded-md border px-4 py-2 text-sm', bannerClass]"
    >
      {{ banner.message }}
    </div>

    <section data-test="active-section" class="space-y-3">
      <h2 class="text-lg font-medium">{{ t('projects.activeSection') }}</h2>

      <p
        v-if="activeProjects.length === 0"
        data-test="active-empty"
        class="text-sm text-gray-600"
      >
        {{ t('layout.projectSelector.noActive') }}
      </p>

      <ul v-else class="space-y-2">
        <li
          v-for="project in activeProjects"
          :key="project.id"
          :data-test="`active-row-${project.id}`"
          class="flex items-center justify-between rounded-md border border-gray-200 bg-white px-4 py-3"
        >
          <div class="min-w-0">
            <p class="font-medium">{{ project.name }}</p>
            <p v-if="project.description" class="text-sm text-gray-600 truncate">
              {{ project.description }}
            </p>
            <p class="text-xs text-gray-500">{{ project.timezone }}</p>
          </div>
          <NuxtLinkLocale
            :to="localePath(`/projects/${project.id}/settings`)"
            :data-test="`active-settings-link-${project.id}`"
            class="text-sm text-blue-600 hover:underline whitespace-nowrap"
          >
            {{ t('layout.projectSelector.settingsLink') }}
          </NuxtLinkLocale>
        </li>
      </ul>
    </section>

    <section
      v-if="softDeletedProjects.length > 0"
      data-test="recently-deleted-section"
      class="space-y-3"
    >
      <h2 class="text-lg font-medium">{{ t('projects.recentlyDeletedSection') }}</h2>

      <ul class="space-y-2">
        <li
          v-for="project in softDeletedProjects"
          :key="project.id"
          :data-test="`deleted-row-${project.id}`"
          class="flex items-center justify-between rounded-md border border-gray-200 bg-white px-4 py-3"
        >
          <div class="min-w-0">
            <p class="font-medium">{{ project.name }}</p>
            <p
              :data-test="`days-remaining-${project.id}`"
              class="text-xs text-gray-500"
            >
              {{ t('projects.daysRemaining', { count: daysRemaining(project.deletedAt) }) }}
            </p>
          </div>
          <button
            type="button"
            :data-test="`restore-button-${project.id}`"
            :disabled="restoringIds.has(project.id)"
            class="rounded-md border border-blue-600 px-3 py-1 text-sm text-blue-700 hover:bg-blue-50 disabled:opacity-50"
            @click="onRestore(project)"
          >
            {{ t('projects.restore.action') }}
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>
