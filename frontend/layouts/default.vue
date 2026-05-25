<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { useProjectsStore } from '~/stores/projects'
import { NON_DEFAULT_LOCALES } from '~/shared/i18n-locales'

const authStore = useAuthStore()
const projectsStore = useProjectsStore()
const { t } = useI18n()
const localePath = useLocalePath()
const route = useRoute()

// Project-scoped Subscribers sub-nav bases (built only when a project is selected).
const subscribersBase = computed(() =>
  projectsStore.currentProject ? `/projects/${projectsStore.currentProject.id}/subscribers` : '',
)
const tagsBase = computed(() =>
  projectsStore.currentProject ? `/projects/${projectsStore.currentProject.id}/tags` : '',
)
const customFieldsBase = computed(() =>
  projectsStore.currentProject ? `/projects/${projectsStore.currentProject.id}/custom-fields` : '',
)

// i18n strategy="prefix_except_default": strip a known non-default locale segment before matching,
// sourced from the single locales constant (mirror SettingsSubnav). Parent + each child use
// prefix-match so the profile sub-route (/subscribers/{id}) keeps the Subscribers entry active.
const LOCALE_PREFIX_RE = new RegExp(`^/(${NON_DEFAULT_LOCALES.join('|')})(?=/|$)`)
const currentPath = computed(() => (route.path ?? '').replace(/\/$/, '').replace(LOCALE_PREFIX_RE, ''))

function isActive(base: string): boolean {
  if (!base) return false
  return currentPath.value === base || currentPath.value.startsWith(`${base}/`)
}

async function onLogout() {
  try {
    await authStore.logout()
  } catch {
    // logout() always clears local user state via finally; ignore network errors so we always redirect.
  }
  await navigateTo(localePath('/auth/login'))
}
</script>

<template>
  <div class="min-h-screen flex flex-col">
    <header class="flex items-center justify-between px-6 py-3 border-b">
      <NuxtLinkLocale to="/dashboard" class="font-semibold text-lg">{{ t('brand.name') }}</NuxtLinkLocale>
      <ProjectSelector />
      <div class="flex items-center gap-3">
        <span v-if="authStore.user" class="text-sm">
          {{ authStore.user.name ? `${authStore.user.name} (${authStore.user.email})` : authStore.user.email }}
        </span>
        <button
          type="button"
          class="px-3 py-1 text-sm border rounded hover:bg-gray-50"
          @click="onLogout"
        >
          {{ t('layout.logout') }}
        </button>
        <LangSwitcher />
      </div>
    </header>

    <div class="flex flex-1">
      <aside class="w-56 border-r p-4">
        <nav class="flex flex-col gap-2">
          <NuxtLinkLocale to="/dashboard" class="text-sm hover:underline">{{ t('layout.dashboard') }}</NuxtLinkLocale>
          <NuxtLinkLocale
            to="/projects"
            data-test="sidebar-all-projects-link"
            class="text-sm hover:underline"
          >
            {{ t('layout.sidebar.allProjects') }}
          </NuxtLinkLocale>
          <template v-if="projectsStore.currentProject">
            <NuxtLinkLocale
              :to="subscribersBase"
              data-test="sidebar-subscribers-parent"
              class="text-sm hover:underline"
              :class="isActive(subscribersBase) ? 'text-blue-600 font-medium' : ''"
              :aria-current="isActive(subscribersBase) ? 'page' : undefined"
            >
              {{ t('layout.sidebar.subscribers') }}
            </NuxtLinkLocale>
            <NuxtLinkLocale
              :to="subscribersBase"
              data-test="sidebar-subscribers-link"
              class="text-sm hover:underline pl-4"
              :class="isActive(subscribersBase) ? 'text-blue-600 font-medium' : ''"
              :aria-current="isActive(subscribersBase) ? 'page' : undefined"
            >
              {{ t('subscribers.subnav.subscribers') }}
            </NuxtLinkLocale>
            <NuxtLinkLocale
              :to="tagsBase"
              data-test="sidebar-tags-link"
              class="text-sm hover:underline pl-4"
              :class="isActive(tagsBase) ? 'text-blue-600 font-medium' : ''"
              :aria-current="isActive(tagsBase) ? 'page' : undefined"
            >
              {{ t('tags.subnav.tags') }}
            </NuxtLinkLocale>
            <NuxtLinkLocale
              :to="customFieldsBase"
              data-test="sidebar-custom-fields-link"
              class="text-sm hover:underline pl-4"
              :class="isActive(customFieldsBase) ? 'text-blue-600 font-medium' : ''"
              :aria-current="isActive(customFieldsBase) ? 'page' : undefined"
            >
              {{ t('customFields.subnav.customFields') }}
            </NuxtLinkLocale>
          </template>
          <NuxtLinkLocale
            v-if="projectsStore.currentProject"
            :to="`/projects/${projectsStore.currentProject.id}/settings`"
            class="text-sm hover:underline"
          >
            {{ t('layout.sidebar.settings') }}
          </NuxtLinkLocale>
          <NuxtLinkLocale to="/profile" class="text-sm hover:underline">{{ t('layout.profile') }}</NuxtLinkLocale>
        </nav>
      </aside>

      <main class="flex-1 p-6">
        <slot />
      </main>
    </div>
  </div>
</template>
