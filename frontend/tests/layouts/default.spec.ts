import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref, computed, reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import type { Project } from '../../types/project'

// Hoist ONLY vi.fn() spies — vi.hoisted runs before imports.
const { selectProjectSpy, fetchAllSpy, logoutSpy, tSpy, navigateToSpy } = vi.hoisted(() => ({
  selectProjectSpy: vi.fn(),
  fetchAllSpy: vi.fn().mockResolvedValue(undefined),
  logoutSpy: vi.fn().mockResolvedValue(undefined),
  tSpy: vi.fn((key: string) => key),
  navigateToSpy: vi.fn(),
}))

const projectsRef = ref<Project[]>([])
const currentProjectIdRef = ref<string | null>(null)
const isLoadedRef = ref(false)
const currentProjectComputed = computed<Project | null>(
  () => projectsRef.value.find((p) => p.id === currentProjectIdRef.value) ?? null,
)

const routeStub = reactive<{ path: string; params: Record<string, string | string[]> }>({
  path: '/dashboard',
  params: {},
})

const mockProjectsStore = reactive({
  projects: projectsRef,
  currentProjectId: currentProjectIdRef,
  currentProject: currentProjectComputed,
  isLoaded: isLoadedRef,
  selectProject: selectProjectSpy,
  fetchAll: fetchAllSpy,
})

const mockAuthStore = reactive({
  user: ref<{ email: string; name: string | null } | null>({ email: 'a@b.c', name: null }),
  logout: logoutSpy,
})

mockNuxtImport('useProjectsStore', () => () => mockProjectsStore)
mockNuxtImport('useAuthStore', () => () => mockAuthStore)
mockNuxtImport('useI18n', () => () => ({ t: tSpy }))
mockNuxtImport('useLocalePath', () => () => (p: string) => p)
mockNuxtImport('useRoute', () => () => routeStub)
mockNuxtImport('navigateTo', () => navigateToSpy)

import DefaultLayout from '../../layouts/default.vue'

// Stub heavy children — they each pull their own stores/composables and are not
// under test here. inheritAttrs keeps data-test/class on NuxtLinkLocale stubs so
// sidebar visibility can be asserted by data-test.
const NuxtLinkLocaleStub = { inheritAttrs: true, template: '<a><slot /></a>' }
const mountOpts = {
  global: {
    stubs: {
      NuxtLinkLocale: NuxtLinkLocaleStub,
      ProjectSelector: true,
      LangSwitcher: true,
      Toaster: true,
    },
  },
}

function makeProject(id: string): Project {
  return {
    id,
    name: `Project ${id}`,
    description: null,
    timezone: 'Europe/Kyiv',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    deletedAt: null,
  }
}

describe('layouts/default — deep-link project hydration', () => {
  beforeEach(() => {
    projectsRef.value = []
    currentProjectIdRef.value = null
    isLoadedRef.value = false
    routeStub.path = '/dashboard'
    routeStub.params = {}
    selectProjectSpy.mockReset()
    fetchAllSpy.mockReset().mockResolvedValue(undefined)
    navigateToSpy.mockReset()
  })

  it('hard refresh on a deep CRM route selects the route project and hydrates projects', async () => {
    routeStub.path = '/projects/p1/subscribers/s9'
    routeStub.params = { projectId: 'p1', subscriberId: 's9' }

    await mountSuspended(DefaultLayout, mountOpts)
    await settle()

    expect(selectProjectSpy).toHaveBeenCalledWith('p1')
    expect(fetchAllSpy).toHaveBeenCalledTimes(1)
  })

  it('does not refetch when the store is already loaded (still syncs selection)', async () => {
    routeStub.path = '/projects/p1/tags'
    routeStub.params = { projectId: 'p1' }
    isLoadedRef.value = true

    await mountSuspended(DefaultLayout, mountOpts)
    await settle()

    expect(selectProjectSpy).toHaveBeenCalledWith('p1')
    expect(fetchAllSpy).not.toHaveBeenCalled()
  })

  it('does not select a project on a non-scoped route', async () => {
    routeStub.path = '/dashboard'
    routeStub.params = {}

    await mountSuspended(DefaultLayout, mountOpts)
    await settle()

    expect(selectProjectSpy).not.toHaveBeenCalled()
  })

  it('renders the Subscribers nav group when a project is current', async () => {
    projectsRef.value = [makeProject('p1')]
    currentProjectIdRef.value = 'p1'
    isLoadedRef.value = true
    routeStub.path = '/projects/p1/subscribers/s9'
    routeStub.params = { projectId: 'p1', subscriberId: 's9' }

    const wrapper = await mountSuspended(DefaultLayout, mountOpts)
    await settle()

    expect(wrapper.find('[data-test="sidebar-subscribers-parent"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="sidebar-subscribers-link"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="sidebar-tags-link"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="sidebar-custom-fields-link"]').exists()).toBe(true)
  })

  it('hides the Subscribers nav group when no project is current (pre-hydration state)', async () => {
    // currentProject stays null (projects empty) — this is the exact state that
    // caused the group to disappear on hard refresh before the fix.
    routeStub.path = '/projects/p1/subscribers/s9'
    routeStub.params = { projectId: 'p1', subscriberId: 's9' }

    const wrapper = await mountSuspended(DefaultLayout, mountOpts)
    await settle()

    expect(wrapper.find('[data-test="sidebar-subscribers-parent"]').exists()).toBe(false)
  })

  it('re-syncs the store when navigating to a different project URL', async () => {
    routeStub.path = '/projects/p1/subscribers'
    routeStub.params = { projectId: 'p1' }
    isLoadedRef.value = true

    await mountSuspended(DefaultLayout, mountOpts)
    await settle()
    selectProjectSpy.mockClear()

    routeStub.params = { projectId: 'p2' }
    await settle()

    expect(selectProjectSpy).toHaveBeenCalledWith('p2')
  })
})
