import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { FunnelSummaryResponse, FunnelResponse } from '../../types/funnel'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))

import { useFunnelsStore } from '../../stores/funnels'

const URL = '/api/v1/projects/p1/funnels'

function summary(id: string, status: FunnelSummaryResponse['status'] = 'draft'): FunnelSummaryResponse {
  return {
    id,
    projectId: 'p1',
    name: `Funnel ${id}`,
    description: null,
    status,
    triggerType: 'on_start',
    triggerValue: '',
    allowReEnter: false,
    stepCount: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

function full(id: string): FunnelResponse {
  return {
    id,
    projectId: 'p1',
    name: `Funnel ${id}`,
    description: null,
    status: 'draft',
    triggerType: 'on_start',
    triggerValue: '',
    allowReEnter: false,
    steps: [],
    deepLink: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

describe('funnels store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
  })

  it('fetch loads summary list', async () => {
    apiMock.mockResolvedValueOnce([summary('a'), summary('b')])
    const store = useFunnelsStore()

    const loadingDuring: boolean[] = []
    const promise = store.fetch()
    loadingDuring.push(store.loading) // true synchronously before the fetch resolves
    await promise

    expect(loadingDuring[0]).toBe(true)
    expect(store.loading).toBe(false)
    expect(store.funnels.map((f) => f.id)).toEqual(['a', 'b'])
    // 'all' (default) → no status param.
    expect(apiMock).toHaveBeenCalledWith(URL, { query: undefined })
  })

  it('fetch with status filter', async () => {
    apiMock.mockResolvedValueOnce([summary('a', 'active')])
    const store = useFunnelsStore()
    await store.fetch('active')

    expect(apiMock).toHaveBeenCalledWith(URL, { query: { status: 'active' } })
  })

  it('create posts and returns funnel', async () => {
    apiMock.mockResolvedValueOnce(full('new1'))
    const store = useFunnelsStore()
    const created = await store.create({ name: 'My funnel' })

    expect(created.id).toBe('new1')
    expect(apiMock).toHaveBeenCalledWith(URL, {
      method: 'POST',
      body: { name: 'My funnel', description: undefined },
    })
  })

  it('action re-throws on api error', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 422, data: { code: 'funnel_trigger_conflict' } })
    const store = useFunnelsStore()

    await expect(store.fetch()).rejects.toMatchObject({ statusCode: 422 })
    // Store sets the error flag but does NOT swallow the error (component maps it via useApiError).
    expect(store.error).toBe(true)
    expect(store.loading).toBe(false)
  })

  it('update patches funnel', async () => {
    apiMock.mockResolvedValueOnce([summary('a')])
    const store = useFunnelsStore()
    await store.fetch()

    apiMock.mockResolvedValueOnce(full('a'))
    await store.update('a', { name: 'Renamed' })

    expect(apiMock).toHaveBeenLastCalledWith(`${URL}/a`, {
      method: 'PATCH',
      body: { name: 'Renamed' },
    })
  })

  it('delete removes from list', async () => {
    apiMock.mockResolvedValueOnce([summary('a'), summary('b')])
    const store = useFunnelsStore()
    await store.fetch()
    expect(store.funnels).toHaveLength(2)

    apiMock.mockResolvedValueOnce(undefined)
    await store.delete('a')

    expect(apiMock).toHaveBeenLastCalledWith(`${URL}/a`, { method: 'DELETE' })
    expect(store.funnels.map((f) => f.id)).toEqual(['b'])
  })
})
