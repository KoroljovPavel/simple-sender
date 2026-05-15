import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'

const { tSpy } = vi.hoisted(() => ({
  tSpy: vi.fn((key: string) => key),
}))

// mockNuxtImport('useRoute') only swaps the auto-imported helper, not the
// underlying Vue Router instance — so RouterLink's built-in active/exact-active
// class behaviour would still see the real router (currentRoute = '/'). To make
// the contract "General is NOT active on /settings/bot" actually testable, the
// component computes active state from useRoute() and applies the class
// itself. The mock here drives that computed.
const routeRef = ref({ path: '/projects/p1/settings', fullPath: '/projects/p1/settings' })

mockNuxtImport('useI18n', () => () => ({ t: tSpy }))
mockNuxtImport('useLocalePath', () => () => (p: string) => p)
mockNuxtImport('useRoute', () => () => routeRef.value)

import SettingsSubnav from '../../components/SettingsSubnav.vue'

describe('SettingsSubnav', () => {
  beforeEach(() => {
    tSpy.mockReset()
    tSpy.mockImplementation((key: string) => key)
    routeRef.value = { path: '/projects/p1/settings', fullPath: '/projects/p1/settings' }
  })

  it('renders two tabs with locale-aware hrefs', async () => {
    const wrapper = await mountSuspended(SettingsSubnav, {
      props: { projectId: 'p1' },
    })
    await settle()

    expect(wrapper.find('[data-test="settings-subnav"]').exists()).toBe(true)

    const general = wrapper.find('[data-test="settings-subnav-general"]')
    const bot = wrapper.find('[data-test="settings-subnav-bot"]')
    expect(general.exists()).toBe(true)
    expect(bot.exists()).toBe(true)
    expect(general.attributes('href')).toBe('/projects/p1/settings')
    expect(bot.attributes('href')).toBe('/projects/p1/settings/bot')
  })

  it('active class on current route — Bot active, General not active when on /settings/bot', async () => {
    routeRef.value = {
      path: '/projects/p1/settings/bot',
      fullPath: '/projects/p1/settings/bot',
    }

    const wrapper = await mountSuspended(SettingsSubnav, {
      props: { projectId: 'p1' },
    })
    await settle()

    const general = wrapper.find('[data-test="settings-subnav-general"]')
    const bot = wrapper.find('[data-test="settings-subnav-bot"]')

    // Regression guard: Vue Router default prefix match would light up
    // /projects/p1/settings as "active" on /projects/p1/settings/bot. The
    // exact-match logic in the component must prevent that.
    expect(general.classes()).not.toContain('subnav-link-active')
    expect(bot.classes()).toContain('subnav-link-active')
  })

  it('General active on /settings root', async () => {
    routeRef.value = { path: '/projects/p1/settings', fullPath: '/projects/p1/settings' }

    const wrapper = await mountSuspended(SettingsSubnav, {
      props: { projectId: 'p1' },
    })
    await settle()

    const general = wrapper.find('[data-test="settings-subnav-general"]')
    const bot = wrapper.find('[data-test="settings-subnav-bot"]')

    expect(general.classes()).toContain('subnav-link-active')
    expect(bot.classes()).not.toContain('subnav-link-active')
  })

  it('locale-prefixed route (/uk/...) still resolves active state', async () => {
    // Nuxt i18n strategy="prefix_except_default" prepends /uk to non-default
    // routes. useRoute().path includes that prefix, so the matcher must strip
    // a leading locale segment before comparing.
    routeRef.value = {
      path: '/uk/projects/p1/settings/bot',
      fullPath: '/uk/projects/p1/settings/bot',
    }

    const wrapper = await mountSuspended(SettingsSubnav, {
      props: { projectId: 'p1' },
    })
    await settle()

    expect(wrapper.find('[data-test="settings-subnav-bot"]').classes()).toContain('subnav-link-active')
    expect(wrapper.find('[data-test="settings-subnav-general"]').classes()).not.toContain('subnav-link-active')
  })
})
