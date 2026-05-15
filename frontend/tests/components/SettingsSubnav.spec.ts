import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'

const { tSpy, localePathSpy } = vi.hoisted(() => ({
  tSpy: vi.fn((key: string) => key),
  // Identity mock so the rendered href matches the user-visible path. We
  // verify useLocalePath was actually invoked via the spy below — a refactor
  // that drops the localePath() wrap fails the spy assertion, not the href.
  // (A non-identity transform here double-prefixes because NuxtLinkLocale's
  // own internals re-apply locale resolution to the already-prefixed path.)
  localePathSpy: vi.fn((p: string) => p),
}))

// mockNuxtImport('useRoute') only swaps the auto-imported helper, not the
// underlying Vue Router instance — so RouterLink's built-in active/exact-active
// class behaviour would still see the real router (currentRoute = '/'). The
// component therefore computes active state from useRoute() and applies the
// signal itself (aria-current + class). The mock here drives that computed.
const routeRef = ref({ path: '/projects/p1/settings', fullPath: '/projects/p1/settings' })

mockNuxtImport('useI18n', () => () => ({ t: tSpy }))
mockNuxtImport('useLocalePath', () => () => localePathSpy)
mockNuxtImport('useRoute', () => () => routeRef.value)

import SettingsSubnav from '../../components/SettingsSubnav.vue'

describe('SettingsSubnav', () => {
  beforeEach(() => {
    tSpy.mockReset()
    tSpy.mockImplementation((key: string) => key)
    localePathSpy.mockReset()
    localePathSpy.mockImplementation((p: string) => p)
    routeRef.value = { path: '/projects/p1/settings', fullPath: '/projects/p1/settings' }
  })

  it('renders two tabs with locale-aware hrefs and i18n labels', async () => {
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
    // useLocalePath must wrap each target path — the spy assertion makes the
    // dependency explicit. A refactor that drops the localePath() wrap fails
    // here even though the identity mock leaves hrefs unchanged.
    expect(localePathSpy).toHaveBeenCalledWith('/projects/p1/settings')
    expect(localePathSpy).toHaveBeenCalledWith('/projects/p1/settings/bot')

    // i18n keys must be the contract — pin the exact keys so a hardcoded
    // literal regression breaks here.
    expect(general.text()).toBe('bot.subnav.general')
    expect(bot.text()).toBe('bot.subnav.bot')
  })

  it('active state on /settings/bot — Bot active, General not active', async () => {
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

    // Primary contract: aria-current="page" — accessibility-first and
    // independent of the Tailwind class string.
    expect(bot.attributes('aria-current')).toBe('page')
    expect(general.attributes('aria-current')).toBeUndefined()

    // Secondary signal: visible class still pinned so a CSS refactor that
    // drops the highlight is caught.
    expect(bot.classes()).toContain('subnav-link-active')
    // Regression guard: Vue Router's default prefix match would light up
    // /projects/p1/settings as "active" on /projects/p1/settings/bot. The
    // exact-match logic in the component must prevent that.
    expect(general.classes()).not.toContain('subnav-link-active')
  })

  it('General active on /settings root', async () => {
    routeRef.value = { path: '/projects/p1/settings', fullPath: '/projects/p1/settings' }

    const wrapper = await mountSuspended(SettingsSubnav, {
      props: { projectId: 'p1' },
    })
    await settle()

    const general = wrapper.find('[data-test="settings-subnav-general"]')
    const bot = wrapper.find('[data-test="settings-subnav-bot"]')

    expect(general.attributes('aria-current')).toBe('page')
    expect(bot.attributes('aria-current')).toBeUndefined()
    expect(general.classes()).toContain('subnav-link-active')
    expect(bot.classes()).not.toContain('subnav-link-active')
  })

  it('locale-prefixed Bot route (/en/...) still resolves active state', async () => {
    // Nuxt i18n strategy="prefix_except_default" prepends /en to non-default
    // routes. useRoute().path includes that prefix, so the matcher must strip
    // a leading locale segment from NON_DEFAULT_LOCALES before comparing.
    routeRef.value = {
      path: '/en/projects/p1/settings/bot',
      fullPath: '/en/projects/p1/settings/bot',
    }

    const wrapper = await mountSuspended(SettingsSubnav, {
      props: { projectId: 'p1' },
    })
    await settle()

    expect(wrapper.find('[data-test="settings-subnav-bot"]').attributes('aria-current')).toBe('page')
    expect(wrapper.find('[data-test="settings-subnav-general"]').attributes('aria-current')).toBeUndefined()
  })

  it('locale-prefixed General route (/en/...) still resolves active state', async () => {
    // Symmetric coverage to the Bot-under-/en case above — catches a regex
    // regression that breaks only on the General-active branch.
    routeRef.value = {
      path: '/en/projects/p1/settings',
      fullPath: '/en/projects/p1/settings',
    }

    const wrapper = await mountSuspended(SettingsSubnav, {
      props: { projectId: 'p1' },
    })
    await settle()

    expect(wrapper.find('[data-test="settings-subnav-general"]').attributes('aria-current')).toBe('page')
    expect(wrapper.find('[data-test="settings-subnav-bot"]').attributes('aria-current')).toBeUndefined()
  })
})
