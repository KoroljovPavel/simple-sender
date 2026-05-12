import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ref, reactive } from 'vue'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'
import type { Project } from '../../../types/project'

// vi.hoisted only holds the plain vi.fn() spies — vue's reactivity is not
// available inside the factory because it runs before the import phase.
const { apiMock, fetchAllSpy, restoreSpy } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  fetchAllSpy: vi.fn(),
  restoreSpy: vi.fn(),
}))

// Module-level refs created AFTER vue imports are wired.
const projectsRef = ref<Project[]>([])
const isLoadedRef = ref(true)

// reactive() wrapper so refs auto-unwrap on property access — matches the
// shape of a Pinia setup-store as seen by template/script-setup consumers.
const mockStore = reactive({
  projects: projectsRef,
  isLoaded: isLoadedRef,
  fetchAll: fetchAllSpy,
  restore: restoreSpy,
})

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useProjectsStore', () => () => mockStore)
mockNuxtImport('useLocalePath', () => () => (path: string) => path)

import ProjectsIndexPage from '../../../pages/projects/index.vue'

function makeProject(overrides: Partial<Project> = {}): Project {
  return {
    id: 'p-1',
    name: 'Alpha',
    description: null,
    timezone: 'Europe/Kyiv',
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    deletedAt: null,
    ...overrides,
  }
}

const FAKE_NOW = new Date('2026-05-09T12:00:00Z').getTime()
const DAY_MS = 24 * 60 * 60 * 1000

describe('projects index page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    projectsRef.value = []
    isLoadedRef.value = true
    apiMock.mockReset()
    fetchAllSpy.mockReset()
    restoreSpy.mockReset()
    fetchAllSpy.mockResolvedValue(undefined)
    // Default: the include_deleted=true fetch returns whatever the test puts
    // in projectsRef plus zero soft-deleted rows. Tests that need deleted rows
    // override this via apiMock.mockResolvedValueOnce(...).
    apiMock.mockResolvedValue([])
    // Only freeze the wall clock so the days-remaining math is deterministic.
    // Do NOT fake setTimeout: the `settle()` helper relies on a real macrotask,
    // and the page's banner-dismiss setTimeout is observed via its initial
    // appearance (not its scheduled clear).
    vi.setSystemTime(FAKE_NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('index_emptyDeleted_hidesRecentlyDeletedSection', async () => {
    projectsRef.value = [makeProject({ id: 'a', name: 'Alpha' })]
    apiMock.mockResolvedValueOnce([makeProject({ id: 'a', name: 'Alpha' })])

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    expect(wrapper.find('[data-test="active-section"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="recently-deleted-section"]').exists()).toBe(false)
  })

  it('index_hasSoftDeleted_rendersSectionWithCountdown', async () => {
    projectsRef.value = []
    const at = (offsetDays: number) => new Date(FAKE_NOW - offsetDays * DAY_MS).toISOString()
    const deletedRows: Project[] = [
      makeProject({ id: 'd0', name: 'Fresh', deletedAt: at(0) }),
      makeProject({ id: 'd4', name: 'Mid', deletedAt: at(4) }),
      makeProject({ id: 'd6', name: 'Soon', deletedAt: at(6) }),
    ]
    apiMock.mockResolvedValueOnce(deletedRows)

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    expect(wrapper.find('[data-test="recently-deleted-section"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="days-remaining-d0"]').text()).toMatch(/7/)
    expect(wrapper.find('[data-test="days-remaining-d4"]').text()).toMatch(/3/)
    expect(wrapper.find('[data-test="days-remaining-d6"]').text()).toMatch(/1/)
  })

  it('index_restoreSuccess_callsStoreAndShowsSuccessToast', async () => {
    const row = makeProject({ id: 'd1', name: 'Beta', deletedAt: new Date(FAKE_NOW - 2 * DAY_MS).toISOString() })
    apiMock.mockResolvedValueOnce([row])
    // Server returns the same name → success branch.
    restoreSpy.mockResolvedValueOnce({ ...row, deletedAt: null })

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    await wrapper.find('[data-test="restore-button-d1"]').trigger('click')
    await settle()

    expect(restoreSpy).toHaveBeenCalledTimes(1)
    expect(restoreSpy).toHaveBeenCalledWith('d1')

    const toast = wrapper.find('[data-test="restore-toast"]')
    expect(toast.exists()).toBe(true)
    // Default uk locale: "Проект відновлено"
    expect(toast.text()).toMatch(/Проект відновлено/)
  })

  it('index_restoreReturnsRenamed_showsRenamedDueToConflictToast', async () => {
    const row = makeProject({ id: 'd1', name: 'Beta', deletedAt: new Date(FAKE_NOW - 2 * DAY_MS).toISOString() })
    apiMock.mockResolvedValueOnce([row])
    restoreSpy.mockResolvedValueOnce({ ...row, name: 'Beta (restored)', deletedAt: null })

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    await wrapper.find('[data-test="restore-button-d1"]').trigger('click')
    await settle()

    const toast = wrapper.find('[data-test="restore-toast"]')
    expect(toast.exists()).toBe(true)
    // errors.projects.restore.renamedDueToConflict (uk)
    expect(toast.text()).toMatch(/Назву було змінено/)
  })

  it('index_restore422Limit_showsLimitToast', async () => {
    const row = makeProject({ id: 'd1', name: 'Beta', deletedAt: new Date(FAKE_NOW - 2 * DAY_MS).toISOString() })
    apiMock.mockResolvedValueOnce([row])
    restoreSpy.mockRejectedValueOnce({ statusCode: 422, data: { code: 'project_limit_reached' } })

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    await wrapper.find('[data-test="restore-button-d1"]').trigger('click')
    await settle()

    const toast = wrapper.find('[data-test="restore-toast"]')
    expect(toast.exists()).toBe(true)
    // errors.projects.restore.422 (uk): "Досягнуто ліміт активних проектів"
    expect(toast.text()).toMatch(/ліміт/i)
    // Row must stay in "Recently deleted".
    expect(wrapper.find('[data-test="deleted-row-d1"]').exists()).toBe(true)
  })

  it('index_5activeProjects_disablesCreateButtonWithTooltip', async () => {
    projectsRef.value = Array.from({ length: 5 }, (_, i) =>
      makeProject({ id: `p${i}`, name: `P${i}`, createdAt: `2026-01-0${i + 1}T00:00:00Z` }),
    )
    apiMock.mockResolvedValueOnce([...projectsRef.value])

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    const btn = wrapper.find('[data-test="create-project-button"]')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeDefined()
    expect(btn.attributes('aria-disabled')).toBe('true')
    // uk: "Досягнуто ліміт у 5 активних проектів. Видаліть один, щоб створити новий."
    expect(btn.attributes('title')).toMatch(/ліміт у 5 активних проектів/i)
  })

  it('index_under5Projects_createButtonEnabled', async () => {
    projectsRef.value = [makeProject({ id: 'a', name: 'Alpha' })]
    apiMock.mockResolvedValueOnce([makeProject({ id: 'a', name: 'Alpha' })])

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    const btn = wrapper.find('[data-test="create-project-button"]')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeUndefined()
    // Rendered as a NuxtLinkLocale → resolves to an <a> with href set via localePath
    // identity-passthrough mock above. Anchor href should target /projects/new.
    const href = btn.attributes('href') ?? btn.attributes('to')
    expect(href).toBe('/projects/new')
  })

  it('index_activeRowSettingsLink_pointsToProjectSettings', async () => {
    const a = makeProject({ id: 'aaa', name: 'Alpha' })
    projectsRef.value = [a]
    apiMock.mockResolvedValueOnce([a])

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    const row = wrapper.find('[data-test="active-row-aaa"]')
    expect(row.exists()).toBe(true)
    const link = row.find('[data-test="active-settings-link-aaa"]')
    expect(link.exists()).toBe(true)
    const href = link.attributes('href') ?? link.attributes('to')
    expect(href).toBe('/projects/aaa/settings')
  })
})
