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
    // Mirror new.vue's own resolve-or-fallback logic — older ICU may resolve
    // a browser TZ that supportedValuesOf doesn't list (e.g. legacy Kiev/Kyiv
    // mismatch), in which case the page falls back to 'UTC'.
    const browser = Intl.DateTimeFormat().resolvedOptions().timeZone
    const expected =
      typeof Intl.supportedValuesOf === 'function' && Intl.supportedValuesOf('timeZone').includes(browser)
        ? browser
        : 'UTC'

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

  it('new_pickerTypeWithoutSelect_doesNotCommitInvalidTimezone', async () => {
    // Positive UI-contract test: TimezonePicker only commits a value via
    // option click / Enter. Typing into the input updates the internal
    // `query` (filter) but does NOT emit update:modelValue. So a user that
    // types garbage and submits should NOT see a validation error from the
    // schema — the picker prevents reaching that state in the first place.
    const wrapper = await mountSuspended(NewProjectPage)
    await wrapper.find('[data-test="project-name-input"]').setValue('Valid name')
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    const originalValue = (input.element as HTMLInputElement).value
    await input.trigger('focus')
    await input.setValue('Mars/Olympus_Mons')
    await settle()

    // After typing, the picker has not committed — modelValue is still the
    // initial valid default. Closing the dropdown via Escape resets query.
    await input.trigger('keydown', { key: 'Escape' })
    await settle()
    expect((input.element as HTMLInputElement).value).toBe(originalValue)

    await wrapper.find('form').trigger('submit')
    await settle()

    // Submit succeeds with the default (valid) timezone — no error, no
    // unreachable-state validation message.
    expect(wrapper.find('[data-test="project-timezone-error"]').exists()).toBe(false)
    expect(projectsStoreMock.create).toHaveBeenCalledTimes(1)
  })

  it('new_invalidTimezoneCommittedSynthetically_refineRejects', async () => {
    // Defense-in-depth: if some external code path mutates timezone to a
    // non-IANA value (custom integration, dev tools, schema-bypassing test
    // harness), the form's zod refine() must reject it. We can't reach this
    // state via the picker UI, so we synthetically emit on the component.
    // This test guards the schema, not the UI.
    const wrapper = await mountSuspended(NewProjectPage)
    await wrapper.find('[data-test="project-name-input"]').setValue('Valid name')
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
