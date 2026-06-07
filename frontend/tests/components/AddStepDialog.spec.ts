// @vitest-environment nuxt
import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../helpers/settle'
import AddStepDialog from '../../components/funnels/AddStepDialog.vue'

// The SET_CUSTOM_FIELD key and ADD_TAG/REMOVE_TAG tag pickers are searchable selects: the form fetches
// the project's custom-field definitions / tags via useApi and reads projectId from the route — mock both
// so the selects have options to pick.
const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))

function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}
function maybe(sel: string): Element | null {
  return document.querySelector(sel)
}
async function mountOpen() {
  const wrapper = await mountSuspended(AddStepDialog, { props: { open: true } })
  await settle()
  return wrapper
}
async function setType(v: string) {
  await $('[data-test="step-type-select"]').setValue(v)
  await settle()
}
async function submit() {
  await $('[data-test="step-form"]').trigger('submit')
  await settle()
}

describe('AddStepDialog', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders type picker and per-type form', async () => {
    await mountOpen()
    // default SEND_MESSAGE → text + parseMode visible.
    expect(maybe('[data-test="step-text-input"]')).not.toBeNull()
    expect(maybe('[data-test="step-parsemode-select"]')).not.toBeNull()

    await setType('DELAY')
    expect(maybe('[data-test="step-delay-value-input"]')).not.toBeNull()
    expect(maybe('[data-test="step-delay-unit-select"]')).not.toBeNull()
    expect(maybe('[data-test="step-text-input"]')).toBeNull()

    await setType('ADD_TAG')
    expect(maybe('[data-test="step-tag-input"]')).not.toBeNull()
  })

  it('blocks emit on empty required field', async () => {
    const wrapper = await mountOpen()
    // SEND_MESSAGE with empty text → validation error, NO add emit.
    await submit()
    expect(maybe('[data-test="step-text-error"]')).not.toBeNull()
    expect(wrapper.emitted('add')).toBeFalsy()
  })

  it('blocks emit on delay below 1', async () => {
    const wrapper = await mountOpen()
    await setType('DELAY')
    await $('[data-test="step-delay-value-input"]').setValue('0')
    await submit()
    expect(maybe('[data-test="step-delay-error"]')).not.toBeNull()
    expect(wrapper.emitted('add')).toBeFalsy()
  })

  it('blocks emit on empty tag, emits the selected tag', async () => {
    apiMock.mockResolvedValueOnce([
      { slug: 'vip', label: 'VIP', subscriberCount: 0, createdAt: '2026-01-01T00:00:00Z' },
    ])
    const wrapper = await mountOpen()
    await setType('ADD_TAG')
    await submit()
    expect(maybe('[data-test="step-tag-error"]')).not.toBeNull()
    expect(wrapper.emitted('add')).toBeFalsy()

    // Tag is a searchable select over the project's tags — pick one rather than typing a free slug.
    await $('[data-test="step-tag-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-tag-option-vip"]').trigger('mousedown')
    await settle()
    await submit()
    expect(wrapper.emitted('add')![0][0]).toMatchObject({ stepType: 'ADD_TAG', tagSlug: 'vip' })
  })

  it('emits a valid send-message step', async () => {
    const wrapper = await mountOpen()
    await $('[data-test="step-text-input"]').setValue('Welcome!')
    await submit()
    const emitted = wrapper.emitted('add')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toMatchObject({ stepType: 'SEND_MESSAGE', text: 'Welcome!' })
  })

  it('blocks emit on empty custom-field key, emits the selected field', async () => {
    apiMock.mockResolvedValueOnce([
      { name: 'city', label: 'City', type: 'STRING', defaultValue: null, createdAt: '2026-01-01T00:00:00Z' },
    ])
    const wrapper = await mountOpen()
    await setType('SET_CUSTOM_FIELD')
    await submit()
    expect(maybe('[data-test="step-cf-key-error"]')).not.toBeNull()
    expect(wrapper.emitted('add')).toBeFalsy()

    // The key is a searchable select over the project's definitions: open it and pick the loaded field
    // rather than typing a free-text key (the backend silently skips unknown keys).
    await $('[data-test="step-cf-key-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-cf-key-option-city"]').trigger('mousedown')
    await settle()
    await $('[data-test="step-cf-value-input"]').setValue('Kyiv')
    await submit()
    const emitted = wrapper.emitted('add')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toMatchObject({ stepType: 'SET_CUSTOM_FIELD', customFieldKey: 'city', customFieldValue: 'Kyiv' })
  })

  async function pickField(name: string, type: string) {
    apiMock.mockResolvedValueOnce([
      { name, label: name, type, defaultValue: null, createdAt: '2026-01-01T00:00:00Z' },
    ])
    await setType('SET_CUSTOM_FIELD')
    await $('[data-test="step-cf-key-input"]').trigger('focus')
    await settle()
    await $(`[data-test="step-cf-key-option-${name}"]`).trigger('mousedown')
    await settle()
  }

  it('coerces a NUMBER field value to a number on submit', async () => {
    const wrapper = await mountOpen()
    await pickField('age', 'NUMBER')
    await $('[data-test="step-cf-value-input"]').setValue('30')
    await submit()
    expect(wrapper.emitted('add')![0][0]).toMatchObject({
      stepType: 'SET_CUSTOM_FIELD',
      customFieldKey: 'age',
      customFieldValue: 30,
    })
  })

  it('converts an explicit DATE value to ISO date-time on submit', async () => {
    const wrapper = await mountOpen()
    await pickField('signed_at', 'DATE')
    await $('[data-test="step-cf-value-input"]').setValue('2026-06-06')
    await submit()
    expect(wrapper.emitted('add')![0][0]).toMatchObject({
      stepType: 'SET_CUSTOM_FIELD',
      customFieldKey: 'signed_at',
      customFieldValue: '2026-06-06T00:00:00Z',
    })
  })

  it('emits the @now token for a DATE field when "current date" is chosen', async () => {
    const wrapper = await mountOpen()
    await pickField('signed_at', 'DATE')
    await $('[data-test="step-cf-current-date"]').setValue(true)
    await settle()
    // The date input is hidden once "current date" is active.
    expect(maybe('[data-test="step-cf-value-input"]')).toBeNull()
    await submit()
    expect(wrapper.emitted('add')![0][0]).toMatchObject({
      stepType: 'SET_CUSTOM_FIELD',
      customFieldKey: 'signed_at',
      customFieldValue: '@now',
    })
  })

  it('emits a valid remove-tag step', async () => {
    apiMock.mockResolvedValueOnce([
      { slug: 'vip', label: 'VIP', subscriberCount: 0, createdAt: '2026-01-01T00:00:00Z' },
    ])
    const wrapper = await mountOpen()
    await setType('REMOVE_TAG')
    await $('[data-test="step-tag-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-tag-option-vip"]').trigger('mousedown')
    await settle()
    await submit()
    const emitted = wrapper.emitted('add')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toMatchObject({ stepType: 'REMOVE_TAG', tagSlug: 'vip' })
  })

  it('rejects non-http image url', async () => {
    const wrapper = await mountOpen()
    await setType('SEND_IMAGE')
    await $('[data-test="step-imageurl-input"]').setValue('ftp://example.com/a.png')
    await submit()
    expect(maybe('[data-test="step-imageurl-error"]')).not.toBeNull()
    expect(wrapper.emitted('add')).toBeFalsy()
  })

  it('EMIT_EVENT appears in the step picker and shows the event-name field', async () => {
    await mountOpen()
    await setType('EMIT_EVENT')
    expect(maybe('[data-test="step-event-name-input"]')).not.toBeNull()
    // Non-EMIT_EVENT fields are gone.
    expect(maybe('[data-test="step-text-input"]')).toBeNull()
  })

  it('EMIT_EVENT emits a step with eventName', async () => {
    const wrapper = await mountOpen()
    await setType('EMIT_EVENT')
    // Invalid/empty name blocks submit with an inline error.
    await submit()
    expect(maybe('[data-test="step-event-name-error"]')).not.toBeNull()
    expect(wrapper.emitted('add')).toBeFalsy()

    await $('[data-test="step-event-name-input"]').setValue('order_paid')
    await submit()
    expect(wrapper.emitted('add')![0][0]).toMatchObject({ stepType: 'EMIT_EVENT', eventName: 'order_paid' })
  })
})
