// @vitest-environment nuxt
import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import SearchableSelect from '../../components/funnels/SearchableSelect.vue'

const OPTIONS = [
  { value: 'city', label: 'City', hint: 'STRING' },
  { value: 'age', label: 'Age', hint: 'NUMBER' },
]

const TEXTS = {
  testPrefix: 'sel',
  placeholder: 'search',
  loadingText: 'loading',
  emptyText: 'empty',
  noMatchesText: 'no-matches',
}

async function mountOpen(modelValue = '', options = OPTIONS, extra = {}) {
  const wrapper = await mountSuspended(SearchableSelect, {
    props: { modelValue, options, ...TEXTS, ...extra },
  })
  await settle()
  await wrapper.find('[data-test="sel-input"]').trigger('focus')
  await settle()
  return wrapper
}

describe('SearchableSelect', () => {
  it('lists the provided options when opened', async () => {
    const wrapper = await mountOpen()
    expect(wrapper.find('[data-test="sel-option-city"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="sel-option-age"]').exists()).toBe(true)
  })

  it('filters options by query (value or label)', async () => {
    const wrapper = await mountOpen()
    await wrapper.find('[data-test="sel-input"]').setValue('age')
    await settle()
    expect(wrapper.find('[data-test="sel-option-age"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="sel-option-city"]').exists()).toBe(false)
  })

  it('emits the option value, not the label, on select', async () => {
    const wrapper = await mountOpen()
    await wrapper.find('[data-test="sel-option-city"]').trigger('mousedown')
    await settle()
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['city'])
  })

  it('shows the selected option label in the closed input', async () => {
    const wrapper = await mountSuspended(SearchableSelect, {
      props: { modelValue: 'city', options: OPTIONS, ...TEXTS },
    })
    await settle()
    expect((wrapper.find('[data-test="sel-input"]').element as HTMLInputElement).value).toBe('City')
  })

  it('falls back to the raw value when the option no longer exists', async () => {
    const wrapper = await mountSuspended(SearchableSelect, {
      props: { modelValue: 'removed', options: OPTIONS, ...TEXTS },
    })
    await settle()
    expect((wrapper.find('[data-test="sel-input"]').element as HTMLInputElement).value).toBe('removed')
  })

  it('shows the value as a grey suffix by default (tag/field pickers need the key visible)', async () => {
    const wrapper = await mountOpen()
    // Default showValue=true → label differs from value → suffix "· city" is rendered.
    expect(wrapper.find('[data-test="sel-option-city"]').text()).toContain('· city')
  })

  it('hides the value suffix when showValue is false (MENU target picker)', async () => {
    const wrapper = await mountOpen('', OPTIONS, { showValue: false })
    const opt = wrapper.find('[data-test="sel-option-city"]')
    expect(opt.text()).toContain('City') // label still shown
    expect(opt.text()).not.toContain('· city') // internal value suffix suppressed
  })

  it('renders an empty-state when there are no options', async () => {
    const wrapper = await mountOpen('', [])
    expect(wrapper.find('[data-test="sel-empty"]').exists()).toBe(true)
  })

  it('renders a loading state while loading', async () => {
    const wrapper = await mountOpen('', [], { loading: true })
    expect(wrapper.find('[data-test="sel-loading"]').exists()).toBe(true)
  })
})
