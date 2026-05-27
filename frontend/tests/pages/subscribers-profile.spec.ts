import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import type { Subscriber, SubscriberStatus } from '../../types/subscriber'

vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))

const { apiMock, navigateToMock, routeMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  navigateToMock: vi.fn(),
  routeMock: { params: { projectId: 'p1', subscriberId: 's1' }, hash: '' },
}))

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => () => routeMock)
mockNuxtImport('useLocalePath', () => () => (path: string) => path)
mockNuxtImport('navigateTo', () => navigateToMock)

import SubscriberProfile from '../../pages/projects/[projectId]/subscribers/[subscriberId].vue'

function sub(status: SubscriberStatus): Subscriber {
  return {
    id: 's1', telegramUserId: 1, telegramChatId: 1, telegramBotId: 1,
    firstName: 'Ann', lastName: 'Lee', username: 'ann', languageCode: 'en',
    status, tags: ['vip'], customFields: {},
    subscribedAt: '2026-01-01T00:00:00Z', unsubscribedAt: null, blockedAt: null,
    deletedAt: null, lastSeenAt: '2026-01-01T00:00:00Z',
  }
}

// Route GET calls by URL: the profile GET resolves the subscriber; tab fetches (tags/custom-fields/
// events) resolve empty so the active tab's on-mount fetch does not blow up.
function routeApi(subscriber: Subscriber | { __reject: number }) {
  apiMock.mockImplementation((url: string) => {
    if (url === '/api/v1/projects/p1/subscribers/s1') {
      if ('__reject' in subscriber) return Promise.reject({ statusCode: subscriber.__reject })
      return Promise.resolve(subscriber)
    }
    return Promise.resolve([])
  })
}

describe('subscribers profile page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    routeMock.params = { projectId: 'p1', subscriberId: 's1' }
    routeMock.hash = ''
    apiMock.mockReset()
    navigateToMock.mockReset()
  })

  it('onMount_404_redirectsToList', async () => {
    routeApi({ __reject: 404 })
    await mountSuspended(SubscriberProfile)
    await settle()
    expect(navigateToMock).toHaveBeenCalledWith('/projects/p1/subscribers')
  })

  it('statusBadge_reflectsCurrentStatus', async () => {
    routeApi(sub('blocked'))
    const wrapper = await mountSuspended(SubscriberProfile)
    await settle()
    const badge = wrapper.find('[data-test="subscriber-status-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.attributes('data-status')).toBe('blocked')
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('activeSubscriber_unsubscribeEnabled', async () => {
    routeApi(sub('active'))
    const wrapper = await mountSuspended(SubscriberProfile)
    await settle()
    const btn = wrapper.find('[data-test="subscriber-manual-unsubscribe"]').element as HTMLButtonElement
    expect(btn.disabled).toBe(false)
  })

  it('blockedSubscriber_unsubscribeDisabled', async () => {
    routeApi(sub('blocked'))
    const wrapper = await mountSuspended(SubscriberProfile)
    await settle()
    const btn = wrapper.find('[data-test="subscriber-manual-unsubscribe"]').element as HTMLButtonElement
    expect(btn.disabled).toBe(true)
  })

  it('unsubscribeClick_postsUnsubscribe', async () => {
    // GET resolves active; the unsubscribe POST resolves an updated (unsubscribed) row.
    apiMock.mockImplementation((url: string, opts?: { method?: string }) => {
      if (url === '/api/v1/projects/p1/subscribers/s1/unsubscribe' && opts?.method === 'POST') {
        return Promise.resolve(sub('unsubscribed'))
      }
      if (url === '/api/v1/projects/p1/subscribers/s1') return Promise.resolve(sub('active'))
      return Promise.resolve([])
    })
    const wrapper = await mountSuspended(SubscriberProfile)
    await settle()
    await wrapper.find('[data-test="subscriber-manual-unsubscribe"]').trigger('click')
    await settle()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/projects/p1/subscribers/s1/unsubscribe', { method: 'POST' })
    expect(wrapper.find('[data-test="subscriber-status-badge"]').attributes('data-status')).toBe('unsubscribed')
  })
})
