import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'

const { tSpy } = vi.hoisted(() => ({
  tSpy: vi.fn((key: string) => key),
}))

const routeRef = ref({
  params: { projectId: 'p1' } as Record<string, string>,
  path: '/projects/p1/settings/bot',
  fullPath: '/projects/p1/settings/bot',
})

mockNuxtImport('useI18n', () => () => ({ t: tSpy }))
mockNuxtImport('useLocalePath', () => () => (p: string) => p)
mockNuxtImport('useRoute', () => () => routeRef.value)

import BotPage from '../../../pages/projects/[projectId]/settings/bot.vue'

describe('projects/[projectId]/settings/bot placeholder page', () => {
  beforeEach(() => {
    tSpy.mockReset()
    tSpy.mockImplementation((key: string) => key)
    routeRef.value = {
      params: { projectId: 'p1' },
      path: '/projects/p1/settings/bot',
      fullPath: '/projects/p1/settings/bot',
    }
  })

  it('renders SettingsSubnav and placeholder text', async () => {
    const wrapper = await mountSuspended(BotPage)
    await settle()

    expect(wrapper.find('[data-test="settings-subnav"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="bot-placeholder"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="bot-placeholder"]').text()).toBe('bot.placeholder')
  })
})
