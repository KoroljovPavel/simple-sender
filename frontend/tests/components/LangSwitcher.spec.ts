import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'

// Hoist ONLY vi.fn() spies — vi.hoisted runs BEFORE imports, so vue's `ref`
// is not yet available inside the factory.
const { setLocaleSpy } = vi.hoisted(() => ({
  setLocaleSpy: vi.fn(),
}))

// Module-level refs (created AFTER `import { ref } from 'vue'`).
// mockNuxtImport factories execute lazily when the auto-import is resolved
// during component mount — by then these refs exist.
const localeRef = ref('uk')
const cookieRef = ref('uk')
const localesRef = ref([
  { code: 'uk', name: 'Українська' },
  { code: 'en', name: 'English' },
])

mockNuxtImport('useI18n', () => () => ({
  locale: localeRef,
  locales: localesRef,
  setLocale: setLocaleSpy,
}))
mockNuxtImport('useCookie', () => (key: string) =>
  key === 'i18n_lang' ? cookieRef : ref(null),
)

import LangSwitcher from '../../components/LangSwitcher.vue'

describe('LangSwitcher', () => {
  beforeEach(() => {
    // Mutate .value, do NOT recreate the refs — spy mockImplementation closes
    // over the original ref identity.
    localeRef.value = 'uk'
    cookieRef.value = 'uk'
    setLocaleSpy.mockReset()
    setLocaleSpy.mockImplementation(async (code: string) => {
      localeRef.value = code
      cookieRef.value = code
    })
  })

  it('renders both UK and EN', async () => {
    const wrapper = await mountSuspended(LangSwitcher)
    expect(wrapper.find('[data-test="lang-uk"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="lang-en"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="lang-uk"]').text()).toBe('UK')
    expect(wrapper.find('[data-test="lang-en"]').text()).toBe('EN')
  })

  it('active locale has highlight class', async () => {
    localeRef.value = 'uk'
    const wrapper = await mountSuspended(LangSwitcher)
    const ukBtn = wrapper.find('[data-test="lang-uk"]')
    const enBtn = wrapper.find('[data-test="lang-en"]')
    expect(ukBtn.classes()).toContain('font-semibold')
    expect(enBtn.classes()).not.toContain('font-semibold')
  })

  it('click EN calls setLocale with en', async () => {
    const wrapper = await mountSuspended(LangSwitcher)
    await wrapper.find('[data-test="lang-en"]').trigger('click')
    await settle()
    expect(setLocaleSpy).toHaveBeenCalledTimes(1)
    expect(setLocaleSpy).toHaveBeenCalledWith('en')
  })

  it('click EN updates cookie i18n_lang to en', async () => {
    const wrapper = await mountSuspended(LangSwitcher)
    await wrapper.find('[data-test="lang-en"]').trigger('click')
    await settle()
    expect(cookieRef.value).toBe('en')
  })

  it('click on currently-active locale is idempotent', async () => {
    localeRef.value = 'uk'
    const wrapper = await mountSuspended(LangSwitcher)
    await wrapper.find('[data-test="lang-uk"]').trigger('click')
    await settle()
    expect(setLocaleSpy).not.toHaveBeenCalled()
  })
})
