<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { useProjectsStore } from '~/stores/projects'
import { NON_DEFAULT_LOCALES } from '~/shared/i18n-locales'
// vue-sonner's stylesheet positions the toaster container (position:fixed; bottom/right
// offsets) and carries its animations. Without it the toaster has no positioning CSS and
// falls into normal document flow — toasts surfaced bottom-LEFT instead of bottom-right.
// shadcn-vue's Sonner wrapper only adds Tailwind classes for the toast body, not the
// container, so this import is required in the layout that mounts <Toaster />.
import 'vue-sonner/style.css'
import { Toaster } from '~/components/ui/sonner'

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
const funnelsBase = computed(() =>
  projectsStore.currentProject ? `/projects/${projectsStore.currentProject.id}/funnels` : '',
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

// Deep-link hydration. Project-scoped pages (/projects/{id}/...) read projectId
// straight from the route for their own API calls, but the sidebar nav group and
// the ProjectSelector depend on the projects store being populated (currentProject
// = projects.find(id === currentProjectId)). On a HARD REFRESH of a deep CRM route
// nothing else calls fetchAll() — only the dashboard and /projects pages do — so
// projects stays empty, currentProject resolves to null, and the Subscribers nav
// group silently disappears. The layout is the common ancestor of every
// project-scoped page, so hydrate here and treat the URL as the source of truth
// for which project is current.
function syncProjectFromRoute() {
  const id = route.params.projectId
  // Only act on project-scoped routes; leaving for /dashboard (no projectId) keeps
  // the last selection intact.
  if (typeof id === 'string' && id && id !== projectsStore.currentProjectId) {
    projectsStore.selectProject(id)
  }
}

onMounted(async () => {
  // Set selection from the URL BEFORE fetchAll so its runAutoSelect keeps the
  // route's project (when valid/active) instead of falling back to active[0].
  syncProjectFromRoute()
  if (!projectsStore.isLoaded) {
    await projectsStore.fetchAll()
  }
})

// Client-side navigation between projects (sidebar links, browser back/forward,
// ProjectSelector) must re-sync the store to the new URL segment.
watch(() => route.params.projectId, syncProjectFromRoute)
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
            :to="funnelsBase"
            data-test="sidebar-funnels-link"
            class="text-sm hover:underline"
            :class="isActive(funnelsBase) ? 'text-blue-600 font-medium' : ''"
            :aria-current="isActive(funnelsBase) ? 'page' : undefined"
          >
            {{ t('layout.sidebar.funnels') }}
          </NuxtLinkLocale>
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

    <!-- Toast surface (vue-sonner). Mounted once here so every page's toast.*() calls render;
         Task 10 is the first consumer of the sonner block Task 2 scaffolded. -->
    <!-- rich-colors: type-tinted toasts (success=green, error=red, warning=amber). The
         richColors selectors [data-rich-colors][data-type=*] out-specify the shadcn wrapper's
         bg-background utility, so the green wins without touching the generated Sonner.vue.
         position made explicit (matches vue-sonner's own default) — clears the top header. -->
    <Toaster rich-colors position="bottom-right" />
  </div>
</template>
