// @vitest-environment nuxt
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import FunnelTriggerSettings from '../../components/funnels/FunnelTriggerSettings.vue'

// tag_added / custom_field_set reuse SearchableSelect, which lazily fetches /tags and /custom-fields via
// useApi and reads projectId from the route — mock both so the pickers have a project context. on_start /
// keyword / event paths never call useApi (the mock just returns an empty list when touched).
const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))

// navigator.clipboard is a BROWSER global (not a Nuxt composable). Define ONLY clipboard on the existing
// jsdom navigator — replacing the whole navigator object breaks the Nuxt test mount.
const writeText = vi.fn().mockResolvedValue(undefined)
beforeEach(() => {
  writeText.mockClear()
  apiMock.mockReset()
  apiMock.mockResolvedValue([])
  Object.defineProperty(globalThis.navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
    writable: true,
  })
})

async function setTriggerType(wrapper: Awaited<ReturnType<typeof mountSuspended>>, v: string) {
  await wrapper.get('[data-test="funnel-trigger-type-select"]').setValue(v)
  await settle()
}

describe('FunnelTriggerSettings', () => {
  it('renders deep link with trigger value', async () => {
    const wrapper = await mountSuspended(FunnelTriggerSettings, {
      props: { triggerValue: 'ref_x', botUsername: 'my_bot' },
    })
    await settle()
    expect(wrapper.get('[data-test="funnel-trigger-deeplink"]').text()).toBe('t.me/my_bot?start=ref_x')
  })

  it('renders bare /start link for empty trigger value', async () => {
    const wrapper = await mountSuspended(FunnelTriggerSettings, {
      props: { triggerValue: '', botUsername: 'my_bot' },
    })
    await settle()
    const link = wrapper.get('[data-test="funnel-trigger-deeplink"]').text()
    expect(link).toBe('t.me/my_bot')
    expect(link).not.toContain('?start=')
  })

  it('copy writes full link and shows Copied feedback', async () => {
    const wrapper = await mountSuspended(FunnelTriggerSettings, {
      props: { triggerValue: 'ref_x', botUsername: 'my_bot' },
    })
    await settle()
    const before = wrapper.get('[data-test="funnel-trigger-copy"]').text()
    await wrapper.get('[data-test="funnel-trigger-copy"]').trigger('click')
    await settle()
    expect(writeText).toHaveBeenCalledWith('t.me/my_bot?start=ref_x')
    // Temporary "Copied" feedback is visible — the button label toggles away from its idle text.
    expect(wrapper.get('[data-test="funnel-trigger-copy"]').text()).not.toBe(before)
  })

  it('shows no-bot hint when no username and no deepLink', async () => {
    const wrapper = await mountSuspended(FunnelTriggerSettings, {
      props: { triggerValue: '', botUsername: null, deepLink: null },
    })
    await settle()
    expect(wrapper.find('[data-test="funnel-trigger-no-bot"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-trigger-deeplink"]').exists()).toBe(false)
  })

  it('on_start keeps the deep-link preview', async () => {
    // The default trigger type is on_start; switching away and back must restore the deep-link block.
    const wrapper = await mountSuspended(FunnelTriggerSettings, {
      props: { triggerType: 'on_start', triggerValue: 'ref_x', botUsername: 'my_bot' },
    })
    await settle()
    expect(wrapper.get('[data-test="funnel-trigger-deeplink"]').text()).toBe('t.me/my_bot?start=ref_x')
    expect(wrapper.find('[data-test="funnel-trigger-value-input"]').exists()).toBe(true)
  })

  it('selecting keyword renders the keyword list editor', async () => {
    const wrapper = await mountSuspended(FunnelTriggerSettings, {
      props: { triggerType: 'on_start', triggerValue: 'ref_x', botUsername: 'my_bot' },
    })
    await settle()
    await setTriggerType(wrapper, 'keyword')
    expect(wrapper.find('[data-test="funnel-trigger-keyword-input"]').exists()).toBe(true)
    // The on_start deep-link block is hidden for non-on_start types.
    expect(wrapper.find('[data-test="funnel-trigger-deeplink"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-trigger-value-input"]').exists()).toBe(false)
  })

  it('selecting tag_added renders the tag SearchableSelect', async () => {
    const wrapper = await mountSuspended(FunnelTriggerSettings, {
      props: { triggerType: 'on_start', botUsername: 'my_bot' },
    })
    await settle()
    await setTriggerType(wrapper, 'tag_added')
    expect(wrapper.find('[data-test="funnel-trigger-tag-select"]').exists()).toBe(true)

    await setTriggerType(wrapper, 'custom_field_set')
    expect(wrapper.find('[data-test="funnel-trigger-field-select"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-trigger-tag-select"]').exists()).toBe(false)
  })

  it('selecting api-event renders the event-name input and validates the slug', async () => {
    const wrapper = await mountSuspended(FunnelTriggerSettings, {
      props: { triggerType: 'on_start', botUsername: 'my_bot' },
    })
    await settle()
    await setTriggerType(wrapper, 'event')
    const input = wrapper.get('[data-test="funnel-trigger-event-input"]')
    await input.setValue('bad name')
    await settle()
    expect(wrapper.find('[data-test="funnel-trigger-event-error"]').exists()).toBe(true)

    await input.setValue('order_paid')
    await settle()
    expect(wrapper.find('[data-test="funnel-trigger-event-error"]').exists()).toBe(false)
  })

  it('keyword list add and remove', async () => {
    const keywords: string[] = []
    const wrapper = await mountSuspended(FunnelTriggerSettings, {
      props: {
        triggerType: 'keyword',
        keywords,
        'onUpdate:keywords': (v: string[]) => keywords.splice(0, keywords.length, ...v),
      },
    })
    await settle()
    const input = wrapper.get('[data-test="funnel-trigger-keyword-input"]')
    await input.setValue('bonus')
    await wrapper.get('[data-test="funnel-trigger-keyword-add"]').trigger('click')
    await settle()
    await input.setValue('sale')
    await wrapper.get('[data-test="funnel-trigger-keyword-add"]').trigger('click')
    await settle()
    expect(keywords).toEqual(['bonus', 'sale'])

    await wrapper.get('[data-test="funnel-trigger-keyword-remove-0"]').trigger('click')
    await settle()
    expect(keywords).toEqual(['sale'])
  })
})
