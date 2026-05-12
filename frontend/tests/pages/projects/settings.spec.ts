import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'
import type { Project } from '../../../types/project'

const PROJECT_ID = 'p1'
const OTHER_PROJECT_ID = 'p2'

const { projectsStoreMock, navigateToMock, routeMock } = vi.hoisted(() => ({
  projectsStoreMock: {
    projects: [] as Project[],
    currentProjectId: null as string | null,
    currentProject: null as Project | null,
    isLoaded: true,
    fetchAll: vi.fn(),
    update: vi.fn(),
    softDelete: vi.fn(),
  },
  navigateToMock: vi.fn(),
  routeMock: { params: { projectId: 'p1' } as Record<string, string> },
}))

mockNuxtImport('useProjectsStore', () => () => projectsStoreMock)
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
    navigateToMock.mockReset()
    projectsStoreMock.isLoaded = true
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

  it('renameSucceeds_storeReflectsNewName', async () => {
    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="settings-name-input"]').setValue('New Name')
    await wrapper.find('[data-test="settings-form"]').trigger('submit')
    await settle()

    expect(projectsStoreMock.currentProject?.name).toBe('New Name')
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

  it('projectNotInStore_rendersFallback', async () => {
    routeMock.params = { projectId: 'missing-id' }

    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    expect(wrapper.find('[data-test="settings-unavailable"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="settings-form"]').exists()).toBe(false)
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
