import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'
import type { Project } from '../../../types/project'

const { projectsStoreMock, navigateToMock } = vi.hoisted(() => ({
  projectsStoreMock: {
    projects: [] as Project[],
    isLoaded: true,
    create: vi.fn(),
  },
  navigateToMock: vi.fn(),
}))

mockNuxtImport('useProjectsStore', () => () => projectsStoreMock)
mockNuxtImport('useLocalePath', () => () => (path: string) => path)
mockNuxtImport('navigateTo', () => navigateToMock)

import NewProjectPage from '../../../pages/projects/new.vue'

function makeProject(overrides: Partial<Project> = {}): Project {
  return {
    id: 'p-new',
    name: 'New Project',
    description: null,
    timezone: 'Europe/Kyiv',
    createdAt: '2026-05-12T00:00:00Z',
    updatedAt: '2026-05-12T00:00:00Z',
    deletedAt: null,
    ...overrides,
  }
}

describe('projects/new page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    projectsStoreMock.create.mockReset()
    projectsStoreMock.create.mockResolvedValue(makeProject())
    navigateToMock.mockReset()
  })

  it('new_initialValues_timezoneDefaultsToBrowser', async () => {
    const expected = Intl.DateTimeFormat().resolvedOptions().timeZone

    const wrapper = await mountSuspended(NewProjectPage)
    await settle()

    const select = wrapper.find('[data-test="project-timezone-select"]')
      .element as HTMLSelectElement
    expect(select.value).toBe(expected)
  })

  it('new_nameTooShort_showsValidationError', async () => {
    const wrapper = await mountSuspended(NewProjectPage)
    await wrapper.find('[data-test="project-name-input"]').setValue('ab')
    await wrapper.find('form').trigger('submit')
    await settle()

    const err = wrapper.find('[data-test="project-name-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text().length).toBeGreaterThan(0)
    expect(projectsStoreMock.create).not.toHaveBeenCalled()
  })

  it('new_nameTooLong_showsValidationError', async () => {
    const wrapper = await mountSuspended(NewProjectPage)
    await wrapper.find('[data-test="project-name-input"]').setValue('a'.repeat(51))
    await wrapper.find('form').trigger('submit')
    await settle()

    const err = wrapper.find('[data-test="project-name-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text().length).toBeGreaterThan(0)
    expect(projectsStoreMock.create).not.toHaveBeenCalled()
  })

  it('new_descriptionTooLong_showsValidationError', async () => {
    const wrapper = await mountSuspended(NewProjectPage)
    await wrapper.find('[data-test="project-name-input"]').setValue('Valid name')
    await wrapper.find('[data-test="project-description-input"]').setValue('x'.repeat(201))
    await wrapper.find('form').trigger('submit')
    await settle()

    const err = wrapper.find('[data-test="project-description-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text().length).toBeGreaterThan(0)
    expect(projectsStoreMock.create).not.toHaveBeenCalled()
  })

  it('new_invalidTimezone_showsValidationError', async () => {
    const wrapper = await mountSuspended(NewProjectPage)
    await wrapper.find('[data-test="project-name-input"]').setValue('Valid name')
    const select = wrapper.find('[data-test="project-timezone-select"]')
      .element as HTMLSelectElement
    // happy-dom snaps <select>.value back to '' for any value not in <option> list,
    // so the v-model field receives '' via the dispatched input event. '' isn't
    // in Intl.supportedValuesOf — refine() rejects it, which is the failure mode
    // we want to surface (any non-IANA value → validation error).
    select.value = 'Mars/Olympus_Mons'
    select.dispatchEvent(new Event('input', { bubbles: true }))
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await wrapper.find('form').trigger('submit')
    await settle()

    const err = wrapper.find('[data-test="project-timezone-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text().length).toBeGreaterThan(0)
    expect(projectsStoreMock.create).not.toHaveBeenCalled()
  })

  it('new_validSubmit_callsStoreCreateAndRedirects', async () => {
    const wrapper = await mountSuspended(NewProjectPage)
    await wrapper.find('[data-test="project-name-input"]').setValue('Acme Coffee')
    // 'Europe/Berlin' is universally present in Intl.supportedValuesOf across runtimes
    // (older ICU still ships 'Europe/Kiev' instead of 'Europe/Kyiv', so we don't rely on UA).
    await wrapper.find('[data-test="project-timezone-select"]').setValue('Europe/Berlin')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(projectsStoreMock.create).toHaveBeenCalledTimes(1)
    expect(projectsStoreMock.create).toHaveBeenCalledWith({
      name: 'Acme Coffee',
      description: null,
      timezone: 'Europe/Berlin',
    })
    expect(navigateToMock).toHaveBeenCalledWith('/dashboard')
  })

  it('new_emptyDescription_passesNullToStore', async () => {
    const wrapper = await mountSuspended(NewProjectPage)
    await wrapper.find('[data-test="project-name-input"]').setValue('Acme Coffee')
    await wrapper.find('[data-test="project-description-input"]').setValue('   ')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(projectsStoreMock.create).toHaveBeenCalledTimes(1)
    const payload = projectsStoreMock.create.mock.calls[0][0]
    expect(payload.description).toBeNull()
  })

  it('new_serverError409_showsApiErrorInline', async () => {
    projectsStoreMock.create.mockRejectedValueOnce({ statusCode: 409, data: { code: 'project_name_taken' } })

    const wrapper = await mountSuspended(NewProjectPage)
    await wrapper.find('[data-test="project-name-input"]').setValue('Acme Coffee')
    await wrapper.find('form').trigger('submit')
    await settle()

    const err = wrapper.find('[data-test="submit-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text()).toMatch(/вже існує/i)
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('new_serverError422LimitReached_showsApiErrorInline', async () => {
    projectsStoreMock.create.mockRejectedValueOnce({ statusCode: 422, data: { code: 'project_limit_reached' } })

    const wrapper = await mountSuspended(NewProjectPage)
    await wrapper.find('[data-test="project-name-input"]').setValue('Acme Coffee')
    await wrapper.find('form').trigger('submit')
    await settle()

    const err = wrapper.find('[data-test="submit-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text()).toMatch(/ліміт/i)
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
