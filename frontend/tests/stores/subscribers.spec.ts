import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { PageResponse, SegmentFilter, Subscriber } from '../../types/subscriber'
import { defaultFilter } from '../../types/subscriber'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))

import { useSubscribersStore } from '../../stores/subscribers'

function sub(id: string): Subscriber {
  return {
    id,
    telegramUserId: 1,
    telegramChatId: 1,
    telegramBotId: 1,
    firstName: 'A',
    lastName: null,
    username: null,
    languageCode: 'en',
    status: 'active',
    tags: [],
    customFields: {},
    subscribedAt: '2026-01-01T00:00:00Z',
    unsubscribedAt: null,
    blockedAt: null,
    deletedAt: null,
    lastSeenAt: '2026-01-01T00:00:00Z',
  }
}
function page(ids: string[], nextCursor: string | null): PageResponse<Subscriber> {
  return { items: ids.map(sub), nextCursor }
}
const altFilter: SegmentFilter = { ...defaultFilter(), status: 'blocked', sort: 'last_seen_desc' }

describe('subscribers store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
  })

  it('loadFirstPage_clearsItems_setsNextCursorAndLoadingFlag', async () => {
    apiMock.mockResolvedValueOnce(page(['a', 'b'], 'cursor-1'))
    const store = useSubscribersStore()

    const loadingDuring: boolean[] = []
    const promise = store.loadFirstPage(defaultFilter())
    loadingDuring.push(store.loading) // true synchronously before the fetch resolves
    await promise

    expect(loadingDuring[0]).toBe(true)
    expect(store.loading).toBe(false)
    expect(store.items.map((s) => s.id)).toEqual(['a', 'b'])
    expect(store.nextCursor).toBe('cursor-1')
    expect(apiMock).toHaveBeenCalledWith('/api/v1/projects/p1/subscribers', {
      query: { limit: 50, sort: 'created_desc' },
    })
  })

  it('loadMore_appendsNotReplaces_andRespectsNoCursorOrLoading', async () => {
    apiMock.mockResolvedValueOnce(page(['a', 'b'], 'cursor-1'))
    const store = useSubscribersStore()
    await store.loadFirstPage(defaultFilter())

    apiMock.mockResolvedValueOnce(page(['c', 'd'], null))
    await store.loadMore()

    expect(store.items.map((s) => s.id)).toEqual(['a', 'b', 'c', 'd'])
    expect(store.nextCursor).toBeNull()
    expect(apiMock).toHaveBeenLastCalledWith('/api/v1/projects/p1/subscribers', {
      query: { limit: 50, sort: 'created_desc', cursor: 'cursor-1' },
    })

    // nextCursor == null → no-op (no further fetch).
    apiMock.mockClear()
    await store.loadMore()
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('loadMore_noOps_whenLoadingInFlight', async () => {
    apiMock.mockResolvedValueOnce(page(['a'], 'cursor-1'))
    const store = useSubscribersStore()
    await store.loadFirstPage(defaultFilter())
    apiMock.mockClear() // forget the loadFirstPage call so the count below isolates loadMore

    // Hold the first loadMore open so loading == true while a second call arrives.
    let resolveFirst!: (v: PageResponse<Subscriber>) => void
    apiMock.mockImplementationOnce(() => new Promise((r) => (resolveFirst = r)))
    const first = store.loadMore()
    const second = store.loadMore() // should bail immediately: loading is true
    resolveFirst(page(['b'], null))
    await Promise.all([first, second])

    expect(apiMock).toHaveBeenCalledTimes(1) // only the first loadMore fired a request
    expect(store.items.map((s) => s.id)).toEqual(['a', 'b'])
  })

  it('loadFirstPage_filterChange_resetsItemsAndCursor', async () => {
    apiMock.mockResolvedValueOnce(page(['a', 'b'], 'cursor-1'))
    const store = useSubscribersStore()
    await store.loadFirstPage(defaultFilter())
    expect(store.items).toHaveLength(2)

    apiMock.mockResolvedValueOnce(page(['z'], null))
    await store.loadFirstPage(altFilter)

    expect(store.items.map((s) => s.id)).toEqual(['z'])
    expect(store.nextCursor).toBeNull()
    expect(store.filter).toEqual(altFilter)
    expect(apiMock).toHaveBeenLastCalledWith('/api/v1/projects/p1/subscribers', {
      query: { limit: 50, sort: 'last_seen_desc', status: 'blocked' },
    })
  })
})
