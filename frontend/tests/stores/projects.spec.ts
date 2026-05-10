import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

import { useProjectsStore, LOCAL_STORAGE_KEY, __setClientGuardForTests } from '../../stores/projects'
import type { Project } from '../../types/project'

const P1: Project = {
  id: 'a1',
  name: 'Alpha',
  description: null,
  timezone: 'Europe/Kyiv',
  createdAt: '2026-01-02T00:00:00Z',
  updatedAt: '2026-01-02T00:00:00Z',
  deletedAt: null,
}
const P2: Project = {
  id: 'b2',
  name: 'Beta',
  description: 'desc',
  timezone: 'UTC',
  createdAt: '2026-01-03T00:00:00Z',
  updatedAt: '2026-01-03T00:00:00Z',
  deletedAt: null,
}
// Backend-sorted createdAt desc — newest first
const ACTIVE_LIST: Project[] = [P2, P1]

describe('projects store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    if (typeof localStorage !== 'undefined') localStorage.clear()
  })

  afterEach(() => {
    __setClientGuardForTests(() => import.meta.client)
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('fetchAll auto-selects per AC-20 when persisted ID is present in active list', async () => {
    localStorage.setItem(LOCAL_STORAGE_KEY, P1.id)
    apiMock.mockResolvedValueOnce(ACTIVE_LIST)

    const store = useProjectsStore()
    await store.fetchAll()

    expect(store.projects).toEqual(ACTIVE_LIST)
    expect(store.currentProjectId).toBe(P1.id)
    expect(store.isLoaded).toBe(true)
    expect(localStorage.getItem(LOCAL_STORAGE_KEY)).toBe(P1.id)
  })

  it('fetchAll falls back to projects[0] when persisted ID missing', async () => {
    apiMock.mockResolvedValueOnce(ACTIVE_LIST)

    const store = useProjectsStore()
    await store.fetchAll()

    // sorted createdAt desc → P2 is first
    expect(store.currentProjectId).toBe(P2.id)
    expect(localStorage.getItem(LOCAL_STORAGE_KEY)).toBe(P2.id)
  })

  it('fetchAll falls back to projects[0] when persisted ID is stale (not in fetched list)', async () => {
    localStorage.setItem(LOCAL_STORAGE_KEY, 'stale-id')
    apiMock.mockResolvedValueOnce(ACTIVE_LIST)

    const store = useProjectsStore()
    await store.fetchAll()

    expect(store.currentProjectId).toBe(P2.id)
    expect(localStorage.getItem(LOCAL_STORAGE_KEY)).toBe(P2.id)
  })

  it('fetchAll with empty list clears currentProjectId and removes localStorage key', async () => {
    localStorage.setItem(LOCAL_STORAGE_KEY, 'some-id')
    apiMock.mockResolvedValueOnce([])

    const store = useProjectsStore()
    await store.fetchAll()

    expect(store.projects).toEqual([])
    expect(store.currentProjectId).toBeNull()
    expect(store.isLoaded).toBe(true)
    expect(localStorage.getItem(LOCAL_STORAGE_KEY)).toBeNull()
  })

  it('fetchAll passes include_deleted query when requested', async () => {
    apiMock.mockResolvedValueOnce(ACTIVE_LIST)

    const store = useProjectsStore()
    await store.fetchAll(true)

    expect(apiMock).toHaveBeenCalledWith('/api/v1/projects', { query: { include_deleted: true } })
  })

  it('fetchAll dedupes concurrent in-flight calls', async () => {
    let resolveCall: ((value: Project[]) => void) | undefined
    apiMock.mockImplementationOnce(
      () => new Promise<Project[]>((r) => {
        resolveCall = r
      }),
    )

    const store = useProjectsStore()
    const a = store.fetchAll()
    const b = store.fetchAll()
    resolveCall!(ACTIVE_LIST)
    await Promise.all([a, b])

    expect(apiMock).toHaveBeenCalledTimes(1)
  })

  it('create appends and selectProject writes localStorage', async () => {
    apiMock.mockResolvedValueOnce(P1)

    const store = useProjectsStore()
    const created = await store.create({ name: 'Alpha', description: null, timezone: 'Europe/Kyiv' })

    expect(created).toEqual(P1)
    expect(store.projects).toContainEqual(P1)
    expect(store.currentProjectId).toBe(P1.id)
    expect(localStorage.getItem(LOCAL_STORAGE_KEY)).toBe(P1.id)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/projects', {
      method: 'POST',
      body: { name: 'Alpha', description: null, timezone: 'Europe/Kyiv' },
    })
  })

  it('update replaces item preserving order', async () => {
    apiMock.mockResolvedValueOnce(ACTIVE_LIST)
    const store = useProjectsStore()
    await store.fetchAll()

    const renamed: Project = { ...P1, name: 'Alpha2', updatedAt: '2026-02-01T00:00:00Z' }
    apiMock.mockResolvedValueOnce(renamed)

    const result = await store.update(P1.id, { name: 'Alpha2' })

    expect(result).toEqual(renamed)
    expect(store.projects).toEqual([P2, renamed])
    expect(apiMock).toHaveBeenLastCalledWith(`/api/v1/projects/${P1.id}`, {
      method: 'PATCH',
      body: { name: 'Alpha2' },
    })
  })

  it('softDelete removes item; if current is deleted, falls back per AC-20', async () => {
    apiMock.mockResolvedValueOnce(ACTIVE_LIST)
    const store = useProjectsStore()
    await store.fetchAll()
    expect(store.currentProjectId).toBe(P2.id)

    apiMock.mockResolvedValueOnce(undefined)
    await store.softDelete(P2.id)

    expect(store.projects).toEqual([P1])
    expect(store.currentProjectId).toBe(P1.id)
    expect(localStorage.getItem(LOCAL_STORAGE_KEY)).toBe(P1.id)
    expect(apiMock).toHaveBeenLastCalledWith(`/api/v1/projects/${P2.id}`, { method: 'DELETE' })
  })

  it('softDelete of last project clears currentProjectId and removes localStorage', async () => {
    apiMock.mockResolvedValueOnce([P1])
    const store = useProjectsStore()
    await store.fetchAll()
    expect(store.currentProjectId).toBe(P1.id)

    apiMock.mockResolvedValueOnce(undefined)
    await store.softDelete(P1.id)

    expect(store.projects).toEqual([])
    expect(store.currentProjectId).toBeNull()
    expect(localStorage.getItem(LOCAL_STORAGE_KEY)).toBeNull()
  })

  it('restore returns response for AC-14b rename detection', async () => {
    apiMock.mockResolvedValueOnce([P1])
    const store = useProjectsStore()
    await store.fetchAll()

    const restored: Project = { ...P1, name: 'Alpha (restored)', deletedAt: null }
    apiMock.mockResolvedValueOnce(restored)

    const result = await store.restore(P1.id)

    expect(result).toEqual(restored)
    expect(store.projects).toContainEqual(restored)
    expect(apiMock).toHaveBeenLastCalledWith(`/api/v1/projects/${P1.id}/restore`, { method: 'POST' })
  })

  it('restore inserts the project when not currently in the list', async () => {
    const store = useProjectsStore()
    const restored: Project = { ...P1, name: 'Alpha (restored)' }
    apiMock.mockResolvedValueOnce(restored)

    await store.restore(P1.id)

    expect(store.projects).toContainEqual(restored)
  })

  it('selectProject(null) removes localStorage key', () => {
    localStorage.setItem(LOCAL_STORAGE_KEY, 'x')
    const store = useProjectsStore()
    store.selectProject(null)

    expect(store.currentProjectId).toBeNull()
    expect(localStorage.getItem(LOCAL_STORAGE_KEY)).toBeNull()
  })

  it('currentProject computed returns matching project or null', async () => {
    apiMock.mockResolvedValueOnce(ACTIVE_LIST)
    const store = useProjectsStore()
    await store.fetchAll()

    expect(store.currentProject).toEqual(P2)
    store.selectProject(null)
    expect(store.currentProject).toBeNull()
  })

  it('handleStaleCurrent clears currentProjectId, removes localStorage key, refetches, auto-selects', async () => {
    apiMock.mockResolvedValueOnce([P1])
    const store = useProjectsStore()
    await store.fetchAll()
    expect(store.currentProjectId).toBe(P1.id)

    apiMock.mockResolvedValueOnce(ACTIVE_LIST)
    await store.handleStaleCurrent()

    // After stale handling: in-memory cleared, refetched list returned, AC-20 picks projects[0]
    expect(store.projects).toEqual(ACTIVE_LIST)
    expect(store.currentProjectId).toBe(P2.id)
    expect(localStorage.getItem(LOCAL_STORAGE_KEY)).toBe(P2.id)
  })

  // Risk R6: simulate SSR by flipping the client-guard. Spies on the real localStorage
  // prove the early-return branch is taken (not just the defensive try/catch fallback).
  it('SSR context: store init does not read localStorage', () => {
    const getSpy = vi.spyOn(localStorage, 'getItem')
    __setClientGuardForTests(() => false)

    const store = useProjectsStore()

    expect(getSpy).not.toHaveBeenCalled()
    expect(store.currentProjectId).toBeNull()
  })

  it('SSR context: selectProject does not write localStorage', () => {
    const setSpy = vi.spyOn(localStorage, 'setItem')
    const removeSpy = vi.spyOn(localStorage, 'removeItem')
    __setClientGuardForTests(() => false)

    const store = useProjectsStore()
    store.selectProject('abc')
    store.selectProject(null)

    expect(setSpy).not.toHaveBeenCalled()
    expect(removeSpy).not.toHaveBeenCalled()
    expect(store.currentProjectId).toBeNull()
  })

  it('selectProject swallows QuotaExceededError and keeps in-memory state', () => {
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
      throw new DOMException('quota exceeded', 'QuotaExceededError')
    })
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const store = useProjectsStore()
    expect(() => store.selectProject('id-1')).not.toThrow()

    expect(store.currentProjectId).toBe('id-1')
    expect(warnSpy).toHaveBeenCalled()
  })

  it('fetchAll re-sorts defensively when API returns out-of-order list', async () => {
    // Backend would return desc, but the store re-sorts to be defensive.
    apiMock.mockResolvedValueOnce([P1, P2]) // ascending order on createdAt

    const store = useProjectsStore()
    await store.fetchAll()

    // P2 has the newest createdAt (2026-01-03) — wins after defensive re-sort.
    expect(store.currentProjectId).toBe(P2.id)
  })
})
