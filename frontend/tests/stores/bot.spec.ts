import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { nextTick } from 'vue'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

import { useBotStore } from '../../stores/bot'
import { useProjectsStore } from '../../stores/projects'
import type { Bot } from '../../types/bot'

const BOT_A: Bot = {
  telegramBotId: 123456789,
  telegramUsername: 'AlphaBot',
  telegramFirstName: 'Alpha',
  tokenSuffix: 'abc',
  status: 'connected',
  connectedAt: '2026-05-01T00:00:00Z',
}

// Marker chosen to be clearly distinct from any other string the bot store
// might encounter, so that JSON.stringify($state).not.toContain(...) is a
// meaningful invariant rather than a coincidence.
const TOKEN = '1234567890:TKN_UNIQ_MARK_abcdefghijklmnopqr'

const fetchError = (status: number, msg = 'fetch error') =>
  Object.assign(new Error(msg), { response: { status } })

describe('bot store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    if (typeof localStorage !== 'undefined') localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('fetch GET success populates current', async () => {
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()

    const result = await store.fetch('p1')

    expect(result).toEqual(BOT_A)
    expect(store.current).toEqual(BOT_A)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/projects/p1/bot')
  })

  it('fetch 404 sets current to null and resolves to null (AC14)', async () => {
    apiMock.mockRejectedValueOnce(fetchError(404, 'Not Found'))
    const store = useBotStore()

    const result = await store.fetch('p1')

    expect(result).toBeNull()
    expect(store.current).toBeNull()
  })

  it('fetch propagates non-404 errors', async () => {
    apiMock.mockRejectedValueOnce(fetchError(500, 'Boom'))
    const store = useBotStore()

    await expect(store.fetch('p1')).rejects.toThrow('Boom')
    expect(store.current).toBeNull()
  })

  it('fetch dedups concurrent calls', async () => {
    let resolveCall: ((value: Bot) => void) | undefined
    apiMock.mockImplementationOnce(
      () =>
        new Promise<Bot>((r) => {
          resolveCall = r
        }),
    )

    const store = useBotStore()
    const a = store.fetch('p1')
    const b = store.fetch('p1')
    resolveCall!(BOT_A)
    const [resA, resB] = await Promise.all([a, b])

    expect(apiMock).toHaveBeenCalledTimes(1)
    expect(resA).toEqual(BOT_A)
    expect(resB).toEqual(BOT_A)
  })

  it('connect sends token in body only — URL contains no token substring (AC17, D17)', async () => {
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()

    await store.connect('p1', TOKEN)

    const [url, opts] = apiMock.mock.calls[0] as [string, { method: string; body: { token: string } }]
    expect(typeof url).toBe('string')
    expect(url).toBe('/api/v1/projects/p1/bot/connect')
    expect(url).not.toContain('TKN_UNIQ_MARK')
    expect(opts.method).toBe('POST')
    expect(opts.body).toEqual({ token: TOKEN })
  })

  it('connect does not retain token in $state after resolution', async () => {
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()

    await store.connect('p1', TOKEN)

    expect(store.current).toEqual(BOT_A)
    expect(JSON.stringify(store.$state)).not.toContain('TKN_UNIQ_MARK')
  })

  it('connect failure does not pollute current and propagates rejection', async () => {
    apiMock.mockRejectedValueOnce(new Error('boom'))
    const store = useBotStore()
    expect(store.current).toBeNull()

    await expect(store.connect('p1', TOKEN)).rejects.toThrow('boom')

    expect(store.current).toBeNull()
  })

  it('disconnect nulls current on success', async () => {
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()
    await store.fetch('p1')
    expect(store.current).toEqual(BOT_A)

    apiMock.mockResolvedValueOnce(undefined)
    await store.disconnect('p1')

    expect(store.current).toBeNull()
    expect(apiMock).toHaveBeenLastCalledWith('/api/v1/projects/p1/bot/disconnect', { method: 'POST' })
  })

  it('sendTestMessage does not mutate current on success or failure', async () => {
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()
    await store.fetch('p1')

    apiMock.mockResolvedValueOnce(undefined)
    await store.sendTestMessage('p1')
    expect(store.current).toEqual(BOT_A)
    expect(apiMock).toHaveBeenLastCalledWith('/api/v1/projects/p1/bot/test-message', { method: 'POST' })

    apiMock.mockRejectedValueOnce(new Error('boom'))
    await expect(store.sendTestMessage('p1')).rejects.toThrow('boom')
    expect(store.current).toEqual(BOT_A)
  })

  it('watcher clears and refetches on project switch', async () => {
    const projectsStore = useProjectsStore()
    projectsStore.selectProject('a')
    const botStore = useBotStore()

    // Pre-seed current with a value for project 'a'.
    apiMock.mockResolvedValueOnce(BOT_A)
    await botStore.fetch('a')
    expect(botStore.current).toEqual(BOT_A)

    apiMock.mockReset()
    // Never-resolving mock so the watcher's fetch stays pending — the test
    // then observes the synchronous-clear effect and the URL the watcher
    // initiated the fetch with, not a later resolved value.
    apiMock.mockImplementation(() => new Promise(() => {}))

    projectsStore.selectProject('b')
    await nextTick()

    expect(botStore.current).toBeNull()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/projects/b/bot')
  })

  it('watcher clears on currentProjectId=null without refetching', async () => {
    const projectsStore = useProjectsStore()
    projectsStore.selectProject('a')
    const botStore = useBotStore()

    apiMock.mockResolvedValueOnce(BOT_A)
    await botStore.fetch('a')
    expect(botStore.current).toEqual(BOT_A)

    apiMock.mockReset()
    projectsStore.selectProject(null)
    await nextTick()

    expect(botStore.current).toBeNull()
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('does not call localStorage.setItem or removeItem from any bot store action', async () => {
    const setSpy = vi.spyOn(localStorage, 'setItem')
    const removeSpy = vi.spyOn(localStorage, 'removeItem')

    const store = useBotStore()

    apiMock.mockResolvedValueOnce(BOT_A)
    await store.fetch('p1')

    apiMock.mockResolvedValueOnce(BOT_A)
    await store.connect('p1', TOKEN)

    apiMock.mockResolvedValueOnce(undefined)
    await store.disconnect('p1')

    apiMock.mockResolvedValueOnce(undefined)
    await store.sendTestMessage('p1')

    expect(setSpy).not.toHaveBeenCalled()
    expect(removeSpy).not.toHaveBeenCalled()
  })
})
