import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import type { Subscriber } from '../../types/subscriber'
import { defaultFilter } from '../../types/subscriber'

const { storeMock, navigateToMock, apiMock } = vi.hoisted(() => ({
  storeMock: {
    items: [] as Subscriber[],
    nextCursor: null as string | null,
    loading: false,
    filter: { status: null, tagsInclude: [], tagsExclude: [], subscribedFrom: null, subscribedTo: null, sort: 'created_desc' },
    loadFirstPage: vi.fn(),
    loadMore: vi.fn(),
  },
  navigateToMock: vi.fn(),
  apiMock: vi.fn(),
}))

mockNuxtImport('useSubscribersStore', () => () => storeMock)
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))
mockNuxtImport('useLocalePath', () => () => (path: string) => path)
mockNuxtImport('navigateTo', () => navigateToMock)

import SubscribersIndex from '../../pages/projects/[projectId]/subscribers/index.vue'

function sub(id: string): Subscriber {
  return {
    id, telegramUserId: 1, telegramChatId: 1, telegramBotId: 1,
    firstName: 'Ann', lastName: null, username: 'ann', languageCode: 'en',
    status: 'active', tags: [], customFields: {},
    subscribedAt: '2026-01-01T00:00:00Z', unsubscribedAt: null, blockedAt: null,
    deletedAt: null, lastSeenAt: '2026-01-01T00:00:00Z',
  }
}

describe('subscribers index page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    storeMock.items = []
    storeMock.nextCursor = null
    storeMock.loading = false
    storeMock.loadFirstPage.mockReset().mockResolvedValue(undefined)
    storeMock.loadMore.mockReset().mockResolvedValue(undefined)
    navigateToMock.mockReset()
    apiMock.mockReset().mockResolvedValue([]) // FilterBar tag-list fetch
  })

  it('onMount_callsLoadFirstPage', async () => {
    await mountSuspended(SubscribersIndex)
    await settle()
    expect(storeMock.loadFirstPage).toHaveBeenCalledTimes(1)
    expect(storeMock.loadFirstPage).toHaveBeenCalledWith(defaultFilter())
  })

  it('loadMoreButton_hiddenWhenNoCursor', async () => {
    storeMock.nextCursor = null
    const wrapper = await mountSuspended(SubscribersIndex)
    await settle()
    expect(wrapper.find('[data-test="subscribers-load-more"]').exists()).toBe(false)
  })

  it('loadMoreButton_shownWhenCursor', async () => {
    storeMock.nextCursor = 'cursor-1'
    const wrapper = await mountSuspended(SubscribersIndex)
    await settle()
    expect(wrapper.find('[data-test="subscribers-load-more"]').exists()).toBe(true)
  })

  it('rowClick_navigatesToProfile', async () => {
    storeMock.items = [sub('abc')]
    const wrapper = await mountSuspended(SubscribersIndex)
    await settle()
    await wrapper.find('[data-test="subscribers-row-abc"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/projects/p1/subscribers/abc')
  })
})
