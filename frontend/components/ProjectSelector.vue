<script setup lang="ts">
import type { Project } from '~/types/project'

const projectsStore = useProjectsStore()
const { t } = useI18n()
const localePath = useLocalePath()

const open = ref(false)
const triggerRef = ref<HTMLElement | null>(null)
const panelRef = ref<HTMLElement | null>(null)

const ACTIVE_LIMIT = 5

// Active-only sorted copy — the store may contain soft-deleted entries when
// the /projects list page hydrated it via fetchAll(true). AC-19/AC-30 are about
// active projects. Never mutate the store array.
const activeProjects = computed<Project[]>(() =>
  projectsStore.projects.filter((p) => p.deletedAt === null),
)

const sortedProjects = computed<Project[]>(() =>
  [...activeProjects.value].sort((a, b) =>
    a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0,
  ),
)

const atLimit = computed(() => activeProjects.value.length >= ACTIVE_LIMIT)

function toggle() {
  open.value = !open.value
}

function close() {
  open.value = false
}

function onSelect(id: string) {
  projectsStore.selectProject(id)
  close()
  // APG menu-button pattern: return focus to the invoking trigger when the
  // menu closes via item activation (mirrors the Esc-close path below).
  triggerRef.value?.focus()
}

function onOutsideClick(event: MouseEvent) {
  if (!open.value) return
  const target = event.target as Node | null
  if (!target) return
  if (panelRef.value?.contains(target)) return
  if (triggerRef.value?.contains(target)) return
  close()
}

function onKey(event: KeyboardEvent) {
  if (!open.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    triggerRef.value?.focus()
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onOutsideClick)
  document.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onOutsideClick)
  document.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div class="relative inline-block text-sm">
    <button
      ref="triggerRef"
      data-test="project-selector-trigger"
      type="button"
      class="px-3 py-1 border rounded hover:bg-gray-50 flex items-center gap-2"
      aria-haspopup="menu"
      :aria-expanded="open ? 'true' : 'false'"
      @click="toggle"
    >
      <span class="truncate max-w-[14rem]">
        {{ projectsStore.currentProject?.name ?? t('layout.projectSelector.placeholder') }}
      </span>
      <span aria-hidden="true" class="text-muted-foreground">▾</span>
    </button>

    <div
      v-if="open"
      ref="panelRef"
      role="menu"
      class="absolute right-0 mt-1 w-64 rounded-md border bg-white shadow-md py-1 z-10"
    >
      <template v-if="sortedProjects.length > 0">
        <button
          v-for="p in sortedProjects"
          :key="p.id"
          :data-test="`project-selector-item-${p.id}`"
          type="button"
          role="menuitem"
          class="w-full text-left px-3 py-2 hover:bg-gray-50 truncate"
          :class="p.id === projectsStore.currentProjectId ? 'font-semibold' : ''"
          @click="onSelect(p.id)"
        >
          {{ p.name }}
        </button>
      </template>
      <div
        v-else
        class="px-3 py-2 text-muted-foreground"
      >
        {{ t('layout.projectSelector.noActive') }}
      </div>

      <div class="border-t my-1" aria-hidden="true" />

      <NuxtLinkLocale
        v-if="!atLimit"
        data-test="project-selector-create"
        :to="localePath('/projects/new')"
        role="menuitem"
        class="block px-3 py-2 hover:bg-gray-50"
        @click="close"
      >
        {{ t('layout.projectSelector.createNew') }}
      </NuxtLinkLocale>
      <button
        v-else
        data-test="project-selector-create"
        type="button"
        role="menuitem"
        disabled
        aria-disabled="true"
        :title="t('projects.create.limitReachedTooltip')"
        class="block w-full text-left px-3 py-2 text-muted-foreground cursor-not-allowed select-none disabled:opacity-100"
      >
        {{ t('layout.projectSelector.createNew') }}
      </button>

      <NuxtLinkLocale
        v-if="projectsStore.currentProject"
        data-test="project-selector-settings"
        :to="localePath(`/projects/${projectsStore.currentProject.id}/settings`)"
        role="menuitem"
        class="block px-3 py-2 hover:bg-gray-50"
        @click="close"
      >
        {{ t('layout.projectSelector.settingsLink') }}
      </NuxtLinkLocale>
    </div>
  </div>
</template>
