import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'
import type { Project } from '../../../types/project'

const PROJECT_ID = 'p1'
const OTHER_PROJECT_ID = 'p2'

const { projectsStoreMock, navigateToMock, routeMock, apiMock } = vi.hoisted(() => ({
  projectsStoreMock: {
    projects: [] as Project[],
    currentProjectId: null as string | null,
    currentProject: null as Project | null,
    isLoaded: true,
    pendingBannerKey: null as string | null,
    fetchAll: vi.fn(),
    update: vi.fn(),
    softDelete: vi.fn(),
  },
  navigateToMock: vi.fn(),
  routeMock: { params: { projectId: 'p1' } as Record<string, string> },
  apiMock: vi.fn(),
}))

mockNuxtImport('useProjectsStore', () => () => projectsStoreMock)
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => () => routeMock)
mockNuxtImport('useLocalePath', () => () => (path: string) => path)
mockNuxtImport('navigateTo', () => navigateToMock)

import SettingsPage from '../../../pages/projects/[projectId]/settings.vue'

function makeProject(overrides: Partial<Project> = {}): Project {
  return {
    id: PROJECT_ID,
    name: 'Acme',
    description: 'Old desc',
    // 'Europe/Berlin' is universally present in Intl.supportedValuesOf across
    // runtimes (older ICU still ships 'Europe/Kiev' instead of 'Europe/Kyiv'),
    // so the schema's refine() check stays green under Node's bundled ICU.
    timezone: 'Europe/Berlin',
    createdAt: '2026-05-12T00:00:00Z',
    updatedAt: '2026-05-12T00:00:00Z',
    deletedAt: null,
    ...overrides,
  }
}

function seedActive(...projects: Project[]) {
  projectsStoreMock.projects = projects
  projectsStoreMock.currentProjectId = projects[0]?.id ?? null
  projectsStoreMock.currentProject = projects[0] ?? null
}

describe('projects/[projectId]/settings page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    routeMock.params = { projectId: PROJECT_ID }
    projectsStoreMock.fetchAll.mockReset()
    projectsStoreMock.update.mockReset()
    projectsStoreMock.softDelete.mockReset()
    apiMock.mockReset()
    // Default: explicit project GET on mount succeeds (project exists on server).
    // Stale/missing scenarios override with mockRejectedValueOnce({statusCode: 404}).
    apiMock.mockResolvedValue(makeProject())
    navigateToMock.mockReset()
    projectsStoreMock.isLoaded = true
    projectsStoreMock.pendingBannerKey = null
    seedActive(makeProject())

    // Default: update resolves with mutated row + reflects into store mock.
    projectsStoreMock.update.mockImplementation(async (id: string, partial: Partial<Project>) => {
      const idx = projectsStoreMock.projects.findIndex((p) => p.id === id)
      if (idx === -1) throw new Error('not in store')
      const next = { ...projectsStoreMock.projects[idx], ...partial }
      projectsStoreMock.projects = [
        ...projectsStoreMock.projects.slice(0, idx),
        next,
        ...projectsStoreMock.projects.slice(idx + 1),
      ]
      if (projectsStoreMock.currentProjectId === id) projectsStoreMock.currentProject = next
      return next
    })

    // Default: softDelete drops the row from the active list.
    projectsStoreMock.softDelete.mockImplementation(async (id: string) => {
      projectsStoreMock.projects = projectsStoreMock.projects.filter((p) => p.id !== id)
      if (projectsStoreMock.currentProjectId === id) {
        projectsStoreMock.currentProjectId = projectsStoreMock.projects[0]?.id ?? null
        projectsStoreMock.currentProject = projectsStoreMock.projects[0] ?? null
      }
    })
  })

  it('renameSubmit_callsStoreUpdate_withPartial', async () => {
    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="settings-name-input"]').setValue('New Name')
    await wrapper.find('[data-test="settings-form"]').trigger('submit')
    await settle()

    expect(projectsStoreMock.update).toHaveBeenCalledTimes(1)
    expect(projectsStoreMock.update).toHaveBeenCalledWith(PROJECT_ID, { name: 'New Name' })
  })

  it('descriptionEmptySubmit_sendsBlankString', async () => {
    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="settings-description-input"]').setValue('')
    await wrapper.find('[data-test="settings-form"]').trigger('submit')
    await settle()

    expect(projectsStoreMock.update).toHaveBeenCalledTimes(1)
    const [, partial] = projectsStoreMock.update.mock.calls[0]
    expect(partial).toEqual({ description: '' })
  })

  it('timezoneUntouched_omitsFieldFromPartial', async () => {
    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="settings-name-input"]').setValue('Renamed')
    await wrapper.find('[data-test="settings-form"]').trigger('submit')
    await settle()

    expect(projectsStoreMock.update).toHaveBeenCalledTimes(1)
    const [, partial] = projectsStoreMock.update.mock.calls[0]
    expect(partial).not.toHaveProperty('timezone')
  })

  it('descriptionUnchanged_omitsFieldFromPartial', async () => {
    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="settings-name-input"]').setValue('Renamed')
    await wrapper.find('[data-test="settings-form"]').trigger('submit')
    await settle()

    expect(projectsStoreMock.update).toHaveBeenCalledTimes(1)
    const [, partial] = projectsStoreMock.update.mock.calls[0]
    expect(partial).not.toHaveProperty('description')
  })

  it('timezoneChanged_includesFieldInPartial', async () => {
    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    // Both 'Europe/Berlin' (initial) and 'Europe/London' (target) are
    // universally present in Intl.supportedValuesOf across ICU builds.
    // TimezonePicker: open via focus, click the option to commit selection.
    await wrapper.find('[data-test="timezone-picker-input"]').trigger('focus')
    await settle()
    await wrapper.find('[data-test="timezone-picker-option-Europe/London"]').trigger('mousedown')
    await settle()
    await wrapper.find('[data-test="settings-form"]').trigger('submit')
    await settle()

    expect(projectsStoreMock.update).toHaveBeenCalledTimes(1)
    const [, partial] = projectsStoreMock.update.mock.calls[0]
    expect(partial).toEqual({ timezone: 'Europe/London' })
  })

  it('submitWithoutChanges_doesNotCallUpdate', async () => {
    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="settings-form"]').trigger('submit')
    await settle()

    expect(projectsStoreMock.update).not.toHaveBeenCalled()
  })

  it('renameSucceeds_showsSavedIndicator', async () => {
    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="settings-name-input"]').setValue('New Name')
    await wrapper.find('[data-test="settings-form"]').trigger('submit')
    await settle()

    expect(wrapper.find('[data-test="settings-saved"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="settings-error"]').exists()).toBe(false)
  })

  it('deleteModal_buttonDisabledUntilExactNameMatch', async () => {
    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="delete-project-open"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="delete-project-modal"]').exists()).toBe(true)
    const confirmBtn = () =>
      wrapper.find('[data-test="delete-project-confirm"]').element as HTMLButtonElement
    const input = wrapper.find('[data-test="delete-project-name-input"]')

    await input.setValue('Acm')
    await settle()
    expect(confirmBtn().disabled).toBe(true)

    await input.setValue('Acme ')
    await settle()
    expect(confirmBtn().disabled).toBe(false)

    await input.setValue('acme')
    await settle()
    expect(confirmBtn().disabled).toBe(true)

    await input.setValue('Acme')
    await settle()
    expect(confirmBtn().disabled).toBe(false)
  })

  it('deleteConfirm_redirects_toDashboard_whenNoOtherActive', async () => {
    // Seed a soft-deleted leftover so the page's defensive
    // `p.deletedAt === null` filter is load-bearing: after softDelete
    // removes the active row, projects[] still has a deletedAt entry,
    // and only the filter (not just length) keeps us on /dashboard.
    seedActive(
      makeProject(),
      makeProject({ id: OTHER_PROJECT_ID, name: 'Old', deletedAt: '2026-05-01T00:00:00Z' }),
    )

    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="delete-project-open"]').trigger('click')
    await settle()
    await wrapper.find('[data-test="delete-project-name-input"]').setValue('Acme')
    await settle()
    await wrapper.find('[data-test="delete-project-confirm"]').trigger('click')
    await settle()

    expect(projectsStoreMock.softDelete).toHaveBeenCalledWith(PROJECT_ID)
    expect(navigateToMock).toHaveBeenCalledWith('/dashboard')
  })

  it('deleteConfirm_redirects_toProjects_whenOthersActive', async () => {
    seedActive(makeProject(), makeProject({ id: OTHER_PROJECT_ID, name: 'Other' }))

    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="delete-project-open"]').trigger('click')
    await settle()
    await wrapper.find('[data-test="delete-project-name-input"]').setValue('Acme')
    await settle()
    await wrapper.find('[data-test="delete-project-confirm"]').trigger('click')
    await settle()

    expect(projectsStoreMock.softDelete).toHaveBeenCalledWith(PROJECT_ID)
    expect(navigateToMock).toHaveBeenCalledWith('/projects')
  })

  it('deleteConfirm_apiError_showsInlineErrorAndStays', async () => {
    projectsStoreMock.softDelete.mockReset()
    projectsStoreMock.softDelete.mockRejectedValueOnce({ statusCode: 404 })

    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="delete-project-open"]').trigger('click')
    await settle()
    await wrapper.find('[data-test="delete-project-name-input"]').setValue('Acme')
    await settle()
    await wrapper.find('[data-test="delete-project-confirm"]').trigger('click')
    await settle()

    const err = wrapper.find('[data-test="delete-project-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text().length).toBeGreaterThan(0)
    expect(navigateToMock).not.toHaveBeenCalled()
    // Modal stays open so the user can retry / cancel.
    expect(wrapper.find('[data-test="delete-project-modal"]').exists()).toBe(true)
  })

  it('renameTo409_showsErrorViaUseApiError', async () => {
    projectsStoreMock.update.mockReset()
    projectsStoreMock.update.mockRejectedValueOnce({ statusCode: 409 })

    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="settings-name-input"]').setValue('Renamed')
    await wrapper.find('[data-test="settings-form"]').trigger('submit')
    await settle()

    const err = wrapper.find('[data-test="settings-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text()).toMatch(/вже існує/i)
  })

  // ─── Stale-state redirect (smoke-test 2.6 finding) ────────────────────
  it('mount_apiReturns404_whileStoreShowsProjectActive_setsBannerAndRedirects', async () => {
    // Cross-tab scenario: store still thinks the project is active, but the
    // explicit GET returns 404 first. The 404 branch fires and bails before
    // the second `!project.value` redirect — so toHaveBeenCalledTimes(1)
    // guards against double-navigate from a future regression.
    routeMock.params = { projectId: 'p-deleted' }
    seedActive(makeProject({ id: 'p-deleted', name: 'Stale' }))
    apiMock.mockReset()
    apiMock.mockRejectedValueOnce({ statusCode: 404 })

    await mountSuspended(SettingsPage)
    await settle()

    expect(projectsStoreMock.pendingBannerKey).toBe('errors.projects.unavailable')
    expect(navigateToMock).toHaveBeenCalledWith('/projects')
    expect(navigateToMock).toHaveBeenCalledTimes(1)
  })

  it('mount_apiReturns404_doesNotOverwriteExistingBannerKey', async () => {
    // The `!pendingBannerKey` guard exists so the useApi 404 interceptor's
    // more specific banner key survives. If a future regression drops the
    // guard, the page would clobber the interceptor-set key — this catches it.
    routeMock.params = { projectId: 'p-deleted' }
    projectsStoreMock.pendingBannerKey = 'errors.projects.staleSpecific'
    apiMock.mockReset()
    apiMock.mockRejectedValueOnce({ statusCode: 404 })

    await mountSuspended(SettingsPage)
    await settle()

    expect(projectsStoreMock.pendingBannerKey).toBe('errors.projects.staleSpecific')
    expect(navigateToMock).toHaveBeenCalledWith('/projects')
  })

  it('mount_projectNotInStoreAfterFetch_redirectsToProjects', async () => {
    // Same-tab back-after-delete: store has no record, explicit GET 404s.
    routeMock.params = { projectId: 'p-gone' }
    projectsStoreMock.projects = []
    projectsStoreMock.currentProject = null
    projectsStoreMock.currentProjectId = null
    apiMock.mockReset()
    apiMock.mockRejectedValueOnce({ statusCode: 404 })

    await mountSuspended(SettingsPage)
    await settle()

    expect(navigateToMock).toHaveBeenCalledWith('/projects')
  })

  it('mount_apiReturns500_andProjectInStore_doesNotRedirect_keepsForm', async () => {
    // Non-404 errors fall through to the !project.value check. With the
    // project present in the store, no redirect happens; form stays.
    apiMock.mockReset()
    apiMock.mockRejectedValueOnce({ statusCode: 500 })
    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    expect(projectsStoreMock.pendingBannerKey).toBe(null)
    expect(navigateToMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="settings-form"]').exists()).toBe(true)
  })

  it('mount_apiReturns500_andProjectMissing_stillRedirects', async () => {
    // The asymmetry the previous test silently relied on: when the API
    // fails with 500 AND the project is absent from the store, the
    // `!project.value` fallback STILL redirects. Exposes the real branch.
    routeMock.params = { projectId: 'p-vanished' }
    projectsStoreMock.projects = []
    projectsStoreMock.currentProject = null
    projectsStoreMock.currentProjectId = null
    apiMock.mockReset()
    apiMock.mockRejectedValueOnce({ statusCode: 500 })

    await mountSuspended(SettingsPage)
    await settle()

    expect(projectsStoreMock.pendingBannerKey).toBe('errors.projects.unavailable')
    expect(navigateToMock).toHaveBeenCalledWith('/projects')
  })

  it('mount_apiResolves_doesNotRedirect_rendersForm', async () => {
    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    expect(navigateToMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="settings-form"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="settings-danger-zone"]').exists()).toBe(true)
  })
})
