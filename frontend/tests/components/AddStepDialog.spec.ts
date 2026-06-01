// @vitest-environment nuxt
import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../helpers/settle'
import AddStepDialog from '../../components/funnels/AddStepDialog.vue'

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

  it('blocks emit on invalid tag slug', async () => {
    const wrapper = await mountOpen()
    await setType('ADD_TAG')
    await $('[data-test="step-tag-input"]').setValue('Not Valid!')
    await submit()
    expect(maybe('[data-test="step-tag-error"]')).not.toBeNull()
    expect(wrapper.emitted('add')).toBeFalsy()
  })

  it('emits a valid send-message step', async () => {
    const wrapper = await mountOpen()
    await $('[data-test="step-text-input"]').setValue('Welcome!')
    await submit()
    const emitted = wrapper.emitted('add')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toMatchObject({ stepType: 'SEND_MESSAGE', text: 'Welcome!' })
  })

  it('blocks emit on empty custom-field key, emits when filled', async () => {
    const wrapper = await mountOpen()
    await setType('SET_CUSTOM_FIELD')
    await submit()
    expect(maybe('[data-test="step-cf-key-error"]')).not.toBeNull()
    expect(wrapper.emitted('add')).toBeFalsy()

    await $('[data-test="step-cf-key-input"]').setValue('city')
    await $('[data-test="step-cf-value-input"]').setValue('Kyiv')
    await submit()
    const emitted = wrapper.emitted('add')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toMatchObject({ stepType: 'SET_CUSTOM_FIELD', customFieldKey: 'city', customFieldValue: 'Kyiv' })
  })

  it('emits a valid remove-tag step', async () => {
    const wrapper = await mountOpen()
    await setType('REMOVE_TAG')
    await $('[data-test="step-tag-input"]').setValue('vip')
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
})
