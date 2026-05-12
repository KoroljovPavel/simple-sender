<script setup lang="ts">
definePageMeta({ layout: 'default' })

const { t } = useI18n()
const authStore = useAuthStore()
const projectsStore = useProjectsStore()

if (import.meta.client) {
  projectsStore.fetchAll()
}

const showEmpty = computed(
  () => projectsStore.isLoaded && projectsStore.projects.length === 0,
)
const showWelcome = computed(
  () => projectsStore.isLoaded && projectsStore.projects.length >= 1,
)
</script>

<template>
  <div class="space-y-6">
    <div
      v-if="authStore.isPending"
      data-test="pending-banner"
      class="rounded-md border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 flex items-center gap-3"
    >
      <span class="flex-1">
        {{ t('dashboard.pendingBanner') }}
      </span>
      <NuxtLinkLocale
        to="/profile"
        class="font-medium text-amber-900 underline hover:no-underline whitespace-nowrap"
      >
        {{ t('dashboard.goToProfile') }}
      </NuxtLinkLocale>
    </div>

    <div
      v-if="showEmpty"
      data-test="dashboard-empty-state"
      class="rounded-lg border border-gray-200 bg-white px-6 py-12 text-center"
    >
      <h1 class="text-2xl font-semibold mb-2">
        {{ t('projects.emptyState.title') }}
      </h1>
      <p class="text-sm text-gray-600 mb-6 max-w-xl mx-auto">
        {{ t('projects.emptyState.description') }}
      </p>
      <NuxtLinkLocale
        to="/projects/new"
        data-test="dashboard-empty-state-cta"
        class="inline-block bg-blue-600 text-white rounded-md px-4 py-2 font-medium hover:bg-blue-700"
      >
        {{ t('projects.emptyState.cta') }}
      </NuxtLinkLocale>
    </div>

    <template v-else-if="showWelcome">
      <h1 class="text-2xl font-semibold">
        {{ authStore.user?.name
          ? t('dashboard.welcomeWithName', { name: authStore.user.name })
          : t('dashboard.welcomeAnon') }}
      </h1>
      <p class="text-sm text-gray-600">
        {{ t('dashboard.description') }}
      </p>
    </template>
  </div>
</template>
