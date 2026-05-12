import { describe, it, expect, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'

// useI18n is supplied by the layer; mock it to a passthrough so the tests can
// assert raw i18n keys (matches the convention used in ProjectSelector spec).
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

import TimezonePicker from '../../components/TimezonePicker.vue'

describe('TimezonePicker', () => {
  it('renders modelValue as the input value when closed', async () => {
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    await settle()

    const input = wrapper.find('[data-test="timezone-picker-input"]').element as HTMLInputElement
    expect(input.value).toBe('Europe/Berlin')
    // Listbox closed by default — no role="listbox" element rendered.
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
    expect(input.getAttribute('aria-expanded')).toBe('false')
  })

  it('opens the listbox on focus and exposes ARIA combobox attributes', async () => {
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    await input.trigger('focus')
    await settle()

    expect(wrapper.find('[role="listbox"]').exists()).toBe(true)
    expect(input.attributes('aria-expanded')).toBe('true')
    expect(input.attributes('aria-controls')).toBeDefined()
    expect(input.attributes('aria-autocomplete')).toBe('list')
    expect(input.attributes('role')).toBe('combobox')
  })

  it('filters options via typeahead — typing "london" matches Europe/London', async () => {
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    await input.trigger('focus')
    await input.setValue('london')
    await settle()

    expect(wrapper.find('[data-test="timezone-picker-option-Europe/London"]').exists()).toBe(true)
    // A different city must not match.
    expect(wrapper.find('[data-test="timezone-picker-option-Europe/Berlin"]').exists()).toBe(false)
  })

  it('emits update:modelValue and closes on option mousedown', async () => {
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    await input.trigger('focus')
    await settle()

    await wrapper.find('[data-test="timezone-picker-option-Europe/London"]').trigger('mousedown')
    await settle()

    const emitted = wrapper.emitted('update:modelValue') as string[][] | undefined
    expect(emitted).toBeDefined()
    expect(emitted![emitted!.length - 1]).toEqual(['Europe/London'])
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
  })

  it('renders an empty-state row when no options match the query', async () => {
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    await input.trigger('focus')
    await input.setValue('definitely-not-a-real-timezone')
    await settle()

    const empty = wrapper.find('[data-test="timezone-picker-empty"]')
    expect(empty.exists()).toBe(true)
    // i18n passthrough → key visible
    expect(empty.text()).toBe('timezone.noMatches')
  })

  it('shows UTC offset next to each option (formatToParts shortOffset)', async () => {
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    await wrapper.find('[data-test="timezone-picker-input"]').trigger('focus')
    await settle()

    // Pick a stable zone present in every modern ICU build. The text contains
    // the IANA id AND an offset chunk like "GMT+1" or "GMT+2" (DST-dependent).
    const opt = wrapper.find('[data-test="timezone-picker-option-Europe/London"]')
    expect(opt.exists()).toBe(true)
    expect(opt.text()).toMatch(/GMT|UTC/)
  })

  it('Escape closes the listbox', async () => {
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    await input.trigger('focus')
    await settle()
    expect(wrapper.find('[role="listbox"]').exists()).toBe(true)

    await input.trigger('keydown', { key: 'Escape' })
    await settle()
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
  })

  it('ArrowDown + Enter selects the next option', async () => {
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    await input.trigger('focus')
    await settle()
    // Focus reopens at the current modelValue; ArrowDown advances activeIndex
    // by 1 within the filtered (== unfiltered, empty query) list.
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Enter' })
    await settle()

    const emitted = wrapper.emitted('update:modelValue') as string[][] | undefined
    expect(emitted).toBeDefined()
    expect(emitted!.length).toBeGreaterThan(0)
  })

  it('outside-click closes the listbox', async () => {
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    await wrapper.find('[data-test="timezone-picker-input"]').trigger('focus')
    await settle()
    expect(wrapper.find('[role="listbox"]').exists()).toBe(true)

    document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await settle()
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
  })
})
