import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type {
  FunnelSummaryResponse,
  FunnelResponse,
  PreviewStepRequest,
  PreviewStepResponse,
} from '../../types/funnel'

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

  it('fetchOne gets the single funnel detail', async () => {
    apiMock.mockResolvedValueOnce(full('a'))
    const store = useFunnelsStore()
    const res = await store.fetchOne('a')

    expect(res.id).toBe('a')
    expect(apiMock).toHaveBeenCalledWith(`${URL}/a`)
  })

  it('activate posts and syncs the matching list row', async () => {
    apiMock.mockResolvedValueOnce([summary('a', 'draft')])
    const store = useFunnelsStore()
    await store.fetch()

    apiMock.mockResolvedValueOnce({ ...full('a'), status: 'active', steps: [{ stepType: 'MESSAGE', blocks: [{ type: 'TEXT', text: 'Hi' }] }] })
    const res = await store.activate('a')

    expect(res.status).toBe('active')
    expect(apiMock).toHaveBeenLastCalledWith(`${URL}/a/activate`, { method: 'POST' })
    // List row reflects the new status + stepCount.
    expect(store.funnels[0].status).toBe('active')
    expect(store.funnels[0].stepCount).toBe(1)
  })

  it('pause posts and syncs the matching list row', async () => {
    apiMock.mockResolvedValueOnce([summary('a', 'active')])
    const store = useFunnelsStore()
    await store.fetch()

    apiMock.mockResolvedValueOnce({ ...full('a'), status: 'paused' })
    await store.pause('a')

    expect(apiMock).toHaveBeenLastCalledWith(`${URL}/a/pause`, { method: 'POST' })
    expect(store.funnels[0].status).toBe('paused')
  })

  it('activate re-throws and flags error on 422', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 422, data: { code: 'funnel_no_steps' } })
    const store = useFunnelsStore()

    await expect(store.activate('a')).rejects.toMatchObject({ statusCode: 422 })
    expect(store.error).toBe(true)
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

  // --- Task 3: author-tooling actions (duplicate / stopAllExecutions / testRun / preview) ---

  it('duplicate posts and returns the new draft funnel', async () => {
    // Source 'a' is in the loaded list; the returned copy 'a-copy' is NOT — syncRow is a no-op
    // for the new id (must not crash). The list page surfaces the new row separately.
    apiMock.mockResolvedValueOnce([summary('a')])
    const store = useFunnelsStore()
    await store.fetch()

    apiMock.mockResolvedValueOnce(full('a-copy'))
    const created = await store.duplicate('a')

    expect(created.id).toBe('a-copy')
    expect(apiMock).toHaveBeenLastCalledWith(`${URL}/a/duplicate`, { method: 'POST' })
    // No synthetic row fabricated for the new copy.
    expect(store.funnels.map((f) => f.id)).toEqual(['a'])
  })

  it('duplicate re-throws and flags error on api failure', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 500 })
    const store = useFunnelsStore()

    await expect(store.duplicate('a')).rejects.toMatchObject({ statusCode: 500 })
    expect(store.error).toBe(true)
  })

  it('stopAllExecutions posts and returns cancelled count', async () => {
    apiMock.mockResolvedValueOnce({ cancelled: 3 })
    const store = useFunnelsStore()
    const res = await store.stopAllExecutions('a')

    expect(res).toEqual({ cancelled: 3 })
    expect(apiMock).toHaveBeenLastCalledWith(`${URL}/a/executions/stop`, { method: 'POST' })
  })

  it('stopAllExecutions re-throws and flags error', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 500 })
    const store = useFunnelsStore()

    await expect(store.stopAllExecutions('a')).rejects.toMatchObject({ statusCode: 500 })
    expect(store.error).toBe(true)
  })

  it('testRun posts to test-run endpoint', async () => {
    apiMock.mockResolvedValueOnce(undefined)
    const store = useFunnelsStore()
    await expect(store.testRun('a')).resolves.toBeUndefined()

    expect(apiMock).toHaveBeenLastCalledWith(`${URL}/a/test-run`, { method: 'POST' })
  })

  it('testRun re-throws 422 funnel_owner_not_linked', async () => {
    // Store does NOT map the code — it only flips error and re-throws; the component maps inline.
    apiMock.mockRejectedValueOnce({ statusCode: 422, data: { code: 'funnel_owner_not_linked' } })
    const store = useFunnelsStore()

    await expect(store.testRun('a')).rejects.toMatchObject({ statusCode: 422 })
    expect(store.error).toBe(true)
  })

  it('preview action sends the blocks request and returns renderedBlocks', async () => {
    // New multiblock DTO shape (Task 6/8): the body is { stepType, blocks: ContentBlock[] }, the
    // response is { renderedBlocks: RenderedBlock[], sampleData, kind } — NOT the old flat
    // { stepType, text, parseMode } / { rendered }.
    const payload: PreviewStepRequest = {
      stepType: 'MESSAGE',
      blocks: [
        { type: 'TEXT', text: 'Hi {{first_name}}', parseMode: 'HTML' },
        { type: 'IMAGE', mediaUrl: 'https://cdn.example/pic.png', caption: 'Look' },
      ],
    }
    const response: PreviewStepResponse = {
      renderedBlocks: [
        { type: 'TEXT', text: 'Hi John', parseMode: 'HTML' },
        { type: 'IMAGE', mediaUrl: 'https://cdn.example/pic.png', caption: 'Look' },
      ],
      sampleData: true,
      kind: 'message',
    }
    apiMock.mockResolvedValueOnce(response)
    const store = useFunnelsStore()
    const res = await store.preview('a', 's1', payload)

    // Store returns the payload verbatim (no branching on kind/sampleData).
    expect(res).toEqual(response)
    // Body is passed through unchanged — the blocks array reaches the backend as-is.
    expect(apiMock).toHaveBeenLastCalledWith(`${URL}/a/steps/s1/preview`, {
      method: 'POST',
      body: payload,
    })
  })

  it('preview re-throws on failure (store contract: flip error, no useApiError)', async () => {
    const store = useFunnelsStore()
    apiMock.mockRejectedValueOnce({ statusCode: 500 })

    await expect(
      store.preview('a', 's1', { stepType: 'DELAY', blocks: [] }),
    ).rejects.toMatchObject({ statusCode: 500 })
    expect(store.error).toBe(true)
  })
})
