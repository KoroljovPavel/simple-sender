import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { toast } from 'vue-sonner'
import { settle } from '../helpers/settle'
import AddCustomFieldDialog from '../../components/customFields/AddCustomFieldDialog.vue'

vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

const URL = '/api/v1/projects/p1/custom-fields'

function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}
function maybe(sel: string): Element | null {
  return document.querySelector(sel)
}
async function mountOpen() {
  const wrapper = await mountSuspended(AddCustomFieldDialog, { props: { open: true, projectId: 'p1' } })
  await settle()
  return wrapper
}
async function setName(v: string) {
  await $('[data-test="custom-field-name-input"]').setValue(v)
}
async function setLabel(v: string) {
  await $('[data-test="custom-field-label-input"]').setValue(v)
}
async function setType(v: string) {
  await $('[data-test="custom-field-type-select"]').setValue(v)
  await settle()
}
async function submit() {
  await $('[data-test="custom-field-add-form"]').trigger('submit')
  await settle()
}

describe('AddCustomFieldDialog', () => {
  beforeEach(() => {
    apiMock.mockReset()
    vi.mocked(toast.success).mockReset()
    vi.mocked(toast.error).mockReset()
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('AddCustomFieldDialog_canonicalSlugRegex_matchesBackend', async () => {
    // name field zod regex MUST be canonical ^[a-z0-9_-]{1,32}$ (matches backend @Pattern byte-for-byte).
    const accepted = ['course_buyer', 'paid-2024', '2024_vip']
    const rejected = ['Foo Bar', 'abc!', 'a'.repeat(33)]

    for (const name of accepted) {
      const wrapper = await mountOpen()
      apiMock.mockResolvedValueOnce({ name, label: 'X', type: 'STRING' })
      await setName(name)
      await setLabel('X')
      await submit()
      expect(maybe('[data-test="custom-field-name-error"]')).toBeNull()
      expect(apiMock).toHaveBeenCalledTimes(1)
      wrapper.unmount()
      document.body.innerHTML = ''
      apiMock.mockReset()
    }

    for (const name of rejected) {
      const wrapper = await mountOpen()
      await setName(name)
      await setLabel('X')
      await submit()
      expect(maybe('[data-test="custom-field-name-error"]')).not.toBeNull()
      expect(apiMock).not.toHaveBeenCalled()
      wrapper.unmount()
      document.body.innerHTML = ''
      apiMock.mockReset()
    }
  })

  it('AddCustomFieldDialog_typeSwitch_swapsDefaultValueInput', async () => {
    await mountOpen()
    // default type STRING → text input.
    expect($('[data-test="custom-field-default-input"]').attributes('type')).toBe('text')
    await setType('NUMBER')
    expect($('[data-test="custom-field-default-input"]').attributes('type')).toBe('number')
    await setType('DATE')
    expect($('[data-test="custom-field-default-input"]').attributes('type')).toBe('date')
    // BOOLEAN renders the tri-state <select> (not an <input>) — the only branch on a different element.
    await setType('BOOLEAN')
    expect($('[data-test="custom-field-default-input"]').element.tagName).toBe('SELECT')
  })

  it('AddCustomFieldDialog_numberType_rejectsNonNumeric', async () => {
    await mountOpen()
    await setName('age')
    await setLabel('Age')
    // Enter "abc" while STRING (text input keeps non-numeric); a type=number input would sanitize it
    // away in the DOM, but the vee-validate MODEL value survives the switch. Switching type to NUMBER
    // re-runs the computed() zod schema against the retained "abc" — the numeric refine must fail.
    await $('[data-test="custom-field-default-input"]').setValue('abc')
    await settle()
    await setType('NUMBER')
    await submit()
    expect(maybe('[data-test="custom-field-default-error"]')).not.toBeNull()
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('AddCustomFieldDialog_validSubmit_emitsCreated', async () => {
    apiMock.mockResolvedValueOnce({ name: 'city', label: 'City', type: 'STRING' })
    const wrapper = await mountOpen()
    await setName('city')
    await setLabel('City')
    await submit()

    expect(apiMock).toHaveBeenCalledTimes(1)
    const [url, opts] = apiMock.mock.calls[0]
    expect(url).toBe(URL)
    expect(opts.method).toBe('POST')
    const body = opts.body as Record<string, unknown>
    expect(body.name).toBe('city')
    expect(body.label).toBe('City')
    expect(body.type).toBe('STRING')
    expect(wrapper.emitted('created')).toBeTruthy()
    expect(toast.success).toHaveBeenCalled()
  })

  it('AddCustomFieldDialog_duplicateName_rendersSubmitError', async () => {
    // 409 custom_field_name_taken; Task 2 did not seed errors.customFields.create.codes.*, so
    // useApiError walks to errors.generic — a real, non-key string in the submit-error slot.
    apiMock.mockRejectedValueOnce({ statusCode: 409, data: { code: 'custom_field_name_taken' } })
    const wrapper = await mountOpen()
    await setName('city')
    await setLabel('City')
    await submit()

    const err = maybe('[data-test="submit-error"]')
    expect(err).not.toBeNull()
    expect(err!.textContent?.trim().length).toBeGreaterThan(0)
    expect(wrapper.emitted('created')).toBeFalsy()
  })
})
