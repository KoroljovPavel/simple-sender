// @vitest-environment nuxt
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import FunnelTriggerSettings from '../../components/funnels/FunnelTriggerSettings.vue'

// navigator.clipboard is a BROWSER global (not a Nuxt composable). Define ONLY clipboard on the existing
// jsdom navigator — replacing the whole navigator object breaks the Nuxt test mount.
const writeText = vi.fn().mockResolvedValue(undefined)
beforeEach(() => {
  writeText.mockClear()
  Object.defineProperty(globalThis.navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
    writable: true,
  })
})

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
})
