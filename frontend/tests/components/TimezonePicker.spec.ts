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

  it('ArrowDown + Enter selects the option AFTER the current selection', async () => {
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    await input.trigger('focus')
    await settle()
    // openDropdown() seeds activeIndex to the currently-selected option's index.
    // ArrowDown advances by 1; Enter commits. The emitted value must equal
    // the option that comes right after Berlin in the unfiltered IANA list —
    // not Berlin itself (which would mean ArrowDown didn't move).
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Enter' })
    await settle()

    const emitted = wrapper.emitted('update:modelValue') as string[][] | undefined
    expect(emitted).toBeDefined()
    const payload = emitted![emitted!.length - 1][0]
    const tzList = Intl.supportedValuesOf('timeZone')
    const berlinIdx = tzList.indexOf('Europe/Berlin')
    expect(berlinIdx).toBeGreaterThanOrEqual(0)
    expect(payload).toBe(tzList[berlinIdx + 1])
    expect(payload).not.toBe('Europe/Berlin')
  })

  it('emits empty string when the user clears the input', async () => {
    // Task 15 contract (item 4): "Якщо юзер очистив поле — emit empty string".
    // Without this, downstream schemas that tolerate '' (settings.vue:80
    // refine `v === '' || timezones.includes(v)`) cannot see the cleared state.
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    await input.trigger('focus')
    await input.setValue('')
    await settle()

    const emitted = wrapper.emitted('update:modelValue') as string[][] | undefined
    expect(emitted).toBeDefined()
    expect(emitted![emitted!.length - 1]).toEqual([''])
  })

  it('does NOT emit when the input is cleared but modelValue is already empty', async () => {
    // Guards the `&& props.modelValue !== ''` clause in the inputValue setter
    // (TimezonePicker.vue:65). Dropping that guard would cause redundant ''
    // emits on every keystroke in an already-empty field — wasted reactivity
    // and a spurious dirty-state in vee-validate.
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: '' },
    })
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    await input.trigger('focus')
    await input.setValue('')
    await settle()

    const emitted = wrapper.emitted('update:modelValue') as string[][] | undefined
    expect(emitted).toBeUndefined()
  })

  it('opens with the full IANA list when query is empty', async () => {
    // Regression guard: a future refactor that mistakenly filters by
    // props.modelValue (instead of returning allTimezones when query is empty)
    // would slip past the typeahead and empty-state tests. ~400+ zones is a
    // safe lower bound across ICU versions.
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    await wrapper.find('[data-test="timezone-picker-input"]').trigger('focus')
    await settle()
    const options = wrapper.findAll('[data-test^="timezone-picker-option-"]')
    expect(options.length).toBeGreaterThan(50)
    expect(wrapper.find('[data-test="timezone-picker-option-Europe/Berlin"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="timezone-picker-option-Europe/London"]').exists()).toBe(true)
  })

  it('updates aria-activedescendant when navigating with ArrowDown', async () => {
    // aria-activedescendant is the load-bearing ARIA contract for screen
    // readers in the combobox pattern — a regression that breaks the binding
    // or stops activeIndex updates would silently lose accessibility.
    const wrapper = await mountSuspended(TimezonePicker, {
      props: { modelValue: 'Europe/Berlin' },
    })
    const input = wrapper.find('[data-test="timezone-picker-input"]')
    await input.trigger('focus')
    await settle()
    const before = input.attributes('aria-activedescendant')
    expect(before).toBeDefined()
    await input.trigger('keydown', { key: 'ArrowDown' })
    await settle()
    const after = input.attributes('aria-activedescendant')
    expect(after).toBeDefined()
    expect(after).not.toBe(before)
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
