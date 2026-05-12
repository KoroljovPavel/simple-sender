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

    // TimezonePicker renders an <input> showing the current modelValue when
    // closed; assert against that rather than a native <select>.
    const input = wrapper.find('[data-test="timezone-picker-input"]')
      .element as HTMLInputElement
    expect(input.value).toBe(expected)
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
    // TimezonePicker filters as the user types but only commits a value via
    // option-click or Enter. Typing a non-IANA query AND blurring without
    // selecting leaves the field with the previously valid default (browser TZ)
    // — that scenario can't reach validation failure through the UI.
    // To exercise the refine() guard we directly write a non-IANA value into
    // the underlying input as v-model sees it, then submit. The picker's
    // computed setter writes into `query` (open=true gate), so we open first
    // and then set value. Submit triggers vee-validate → refine rejects.
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    await input.trigger('focus')
    await settle()
    await input.setValue('Mars/Olympus_Mons')
    // Close the dropdown without selecting (Escape), then force commit by
    // emitting a synthetic update on the picker. Direct v-model write keeps
    // this test honest about the validation surface even if UI flow disallows.
    const picker = wrapper.findComponent({ name: 'TimezonePicker' })
    picker.vm.$emit('update:modelValue', 'Mars/Olympus_Mons')
    await settle()
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
    // 'Europe/Berlin' is universally present in Intl.supportedValuesOf across runtimes.
    // TimezonePicker: open via focus, click option (mousedown event commits).
    await wrapper.find('[data-test="timezone-picker-input"]').trigger('focus')
    await settle()
    await wrapper.find('[data-test="timezone-picker-option-Europe/Berlin"]').trigger('mousedown')
    await settle()
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
