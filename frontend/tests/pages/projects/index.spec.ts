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
const pendingBannerKeyRef = ref<string | null>(null)

// reactive() wrapper so refs auto-unwrap on property access — matches the
// shape of a Pinia setup-store as seen by template/script-setup consumers.
const mockStore = reactive({
  projects: projectsRef,
  isLoaded: isLoadedRef,
  pendingBannerKey: pendingBannerKeyRef,
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
    pendingBannerKeyRef.value = null
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
    // \b\d+\b anchored so /1/ no longer matches "10"/"11" if a future off-by-one
    // regression reports the wrong count.
    expect(wrapper.find('[data-test="days-remaining-d0"]').text()).toMatch(/\b7\b/)
    expect(wrapper.find('[data-test="days-remaining-d4"]').text()).toMatch(/\b3\b/)
    expect(wrapper.find('[data-test="days-remaining-d6"]').text()).toMatch(/\b1\b/)
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

  it('index_5activeProjects_disablesCreateButtonWithVisibleLimitText', async () => {
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
    // Regression: native title is NOT used — smoke-test 2.1 finding.
    expect(btn.attributes('title')).toBeUndefined()
    // Limit text rendered visibly next to the disabled button.
    const limitText = wrapper.find('[data-test="create-project-limit-text"]')
    expect(limitText.exists()).toBe(true)
    expect(limitText.text()).toMatch(/ліміт у 5 активних проектів/i)
    expect(btn.attributes('aria-describedby')).toBe('create-project-limit-text')
  })

  it('index_under5Projects_createButtonEnabled', async () => {
    projectsRef.value = [makeProject({ id: 'a', name: 'Alpha' })]
    apiMock.mockResolvedValueOnce([makeProject({ id: 'a', name: 'Alpha' })])

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    const btn = wrapper.find('[data-test="create-project-button"]')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeUndefined()
    // Pin BOTH disabled AND aria-disabled so a regression that flips only one
    // attribute is still caught (test-reviewer R1).
    expect(btn.attributes('aria-disabled')).toBeUndefined()
    // NuxtLinkLocale renders as <a href> in the test env; pin the contract.
    expect(btn.attributes('href')).toBe('/projects/new')
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
    expect(link.attributes('href')).toBe('/projects/aaa/settings')
  })

  // ─── Round 1 follow-ups ──────────────────────────────────────────────────
  // Added after test-reviewer round 1 flagged uncovered Edge cases listed in
  // the task: concurrent restore guard, 5-active+deleted coexistence, banner
  // auto-dismiss, generic-error (non-422) path.

  it('index_concurrentRestoreClicks_callsStoreOnce', async () => {
    const row = makeProject({ id: 'd1', name: 'Beta', deletedAt: new Date(FAKE_NOW - 2 * DAY_MS).toISOString() })
    apiMock.mockResolvedValueOnce([row])
    // Capture the resolver so the promise stays pending across the second click.
    let resolveRestore: (v: Project) => void = () => {}
    restoreSpy.mockReturnValueOnce(new Promise<Project>((res) => { resolveRestore = res }))

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    const btn = wrapper.find('[data-test="restore-button-d1"]')
    await btn.trigger('click')
    // Click again before the first promise resolves — the in-flight guard
    // (restoringIds Set) must short-circuit the second invocation.
    await btn.trigger('click')
    await settle()

    expect(restoreSpy).toHaveBeenCalledTimes(1)
    expect(btn.attributes('disabled')).toBeDefined()

    resolveRestore({ ...row, deletedAt: null })
    await settle()
  })

  it('index_5activeAndDeletedRow_disablesCreateButRestoreStaysAvailable', async () => {
    // AC-30 + AC-23 coexistence: even at the active limit, the user can still
    // click Restore — the server then rejects with 422 and the row stays.
    projectsRef.value = Array.from({ length: 5 }, (_, i) =>
      makeProject({ id: `p${i}`, name: `P${i}`, createdAt: `2026-01-0${i + 1}T00:00:00Z` }),
    )
    const deletedRow = makeProject({ id: 'd1', name: 'Beta', deletedAt: new Date(FAKE_NOW - 2 * DAY_MS).toISOString() })
    apiMock.mockResolvedValueOnce([...projectsRef.value, deletedRow])
    restoreSpy.mockRejectedValueOnce({ statusCode: 422, data: { code: 'project_limit_reached' } })

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    expect(wrapper.find('[data-test="create-project-button"]').attributes('disabled')).toBeDefined()
    const restoreBtn = wrapper.find('[data-test="restore-button-d1"]')
    expect(restoreBtn.exists()).toBe(true)

    await restoreBtn.trigger('click')
    await settle()

    expect(wrapper.find('[data-test="restore-toast"]').text()).toMatch(/ліміт/i)
    expect(wrapper.find('[data-test="deleted-row-d1"]').exists()).toBe(true)
  })

  it('index_bannerAutoDismisses_after4Seconds', async () => {
    // Full fake timers for this test: setSystemTime alone (beforeEach) leaves
    // setTimeout real, but here we need to control the banner's dismiss timer.
    // Replace the partial fake state with full fakes and use a Promise-aware
    // shim of settle() that advances the queued macrotask explicitly.
    vi.useRealTimers()
    vi.useFakeTimers({ now: FAKE_NOW })
    const fakeSettle = async () => {
      await Promise.resolve()
      await vi.advanceTimersByTimeAsync(50)
      await Promise.resolve()
    }

    const row = makeProject({ id: 'd1', name: 'Beta', deletedAt: new Date(FAKE_NOW - 2 * DAY_MS).toISOString() })
    apiMock.mockResolvedValueOnce([row])
    restoreSpy.mockResolvedValueOnce({ ...row, deletedAt: null })

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await fakeSettle()
    await wrapper.find('[data-test="restore-button-d1"]').trigger('click')
    await fakeSettle()

    expect(wrapper.find('[data-test="restore-toast"]').exists()).toBe(true)

    // Past BANNER_TIMEOUT_MS (4000) → showBanner's setTimeout fires and nulls
    // the banner ref; nextTick lets the v-if re-evaluate.
    await vi.advanceTimersByTimeAsync(4001)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-test="restore-toast"]').exists()).toBe(false)
    vi.useRealTimers()
  })

  // ─── Staleness banner (smoke-test 2.6 finding) ────────────────────────
  it('index_pendingBannerKeySet_rendersPersistentStalenessBanner', async () => {
    pendingBannerKeyRef.value = 'errors.projects.unavailable'
    apiMock.mockResolvedValueOnce([])

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    const banner = wrapper.find('[data-test="staleness-banner"]')
    expect(banner.exists()).toBe(true)
    // uk default: "Цей проект більше недоступний"
    expect(banner.text()).toMatch(/недоступний/i)
    expect(banner.attributes('role')).toBe('alert')
  })

  it('index_pendingBannerKeyNull_doesNotRenderStalenessBanner', async () => {
    pendingBannerKeyRef.value = null
    apiMock.mockResolvedValueOnce([])

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    expect(wrapper.find('[data-test="staleness-banner"]').exists()).toBe(false)
  })

  it('index_dismissStalenessBanner_clearsStoreKey', async () => {
    pendingBannerKeyRef.value = 'errors.projects.unavailable'
    apiMock.mockResolvedValueOnce([])

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()

    expect(wrapper.find('[data-test="staleness-banner"]').exists()).toBe(true)

    await wrapper.find('[data-test="staleness-banner-dismiss"]').trigger('click')
    await settle()

    expect(pendingBannerKeyRef.value).toBe(null)
    expect(wrapper.find('[data-test="staleness-banner"]').exists()).toBe(false)
  })

  it('index_stalenessBanner_doesNotAutoDismiss', async () => {
    // The transient restore-toast auto-clears after BANNER_TIMEOUT_MS (4s).
    // The staleness banner must NOT — it's the "your project is gone" notice
    // and must remain visible until the user dismisses or navigates away.
    vi.useRealTimers()
    vi.useFakeTimers({ now: FAKE_NOW })
    pendingBannerKeyRef.value = 'errors.projects.unavailable'
    apiMock.mockResolvedValueOnce([])

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await Promise.resolve()

    expect(wrapper.find('[data-test="staleness-banner"]').exists()).toBe(true)

    // Advance well past any plausible auto-dismiss timer (10s ≫ 4s).
    await vi.advanceTimersByTimeAsync(10_000)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-test="staleness-banner"]').exists()).toBe(true)
    vi.useRealTimers()
  })

  it('index_restoreServer500_showsGenericErrorBanner', async () => {
    const row = makeProject({ id: 'd1', name: 'Beta', deletedAt: new Date(FAKE_NOW - 2 * DAY_MS).toISOString() })
    apiMock.mockResolvedValueOnce([row])
    restoreSpy.mockRejectedValueOnce({ statusCode: 500 })

    const wrapper = await mountSuspended(ProjectsIndexPage)
    await settle()
    await wrapper.find('[data-test="restore-button-d1"]').trigger('click')
    await settle()

    const toast = wrapper.find('[data-test="restore-toast"]')
    expect(toast.exists()).toBe(true)
    // No 500-specific key under errors.projects.restore → resolver falls
    // through to errors.projects.generic ("Не вдалося виконати дію…").
    expect(toast.text().length).toBeGreaterThan(0)
    expect(wrapper.find('[data-test="deleted-row-d1"]').exists()).toBe(true)
  })
})
