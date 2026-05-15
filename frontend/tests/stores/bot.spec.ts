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
// might encounter, so JSON.stringify(...).not.toContain(...) is a meaningful
// invariant rather than a coincidence.
const TOKEN = '1234567890:TKN_UNIQ_MARK_abcdefghijklmnopqr'
const TOKEN_MARKER = 'TKN_UNIQ_MARK'

const fetchError = (status: number, msg = 'fetch error', extra: Record<string, unknown> = {}) =>
  Object.assign(new Error(msg), { response: { status }, ...extra })

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
    // Pre-seed current so the test can distinguish a real clear from a no-op.
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()
    await store.fetch('p1')
    expect(store.current).toEqual(BOT_A)

    apiMock.mockRejectedValueOnce(fetchError(404, 'Not Found'))
    const result = await store.fetch('p1')

    expect(result).toBeNull()
    expect(store.current).toBeNull()
  })

  it('fetch propagates non-404 errors and leaves current untouched', async () => {
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()
    await store.fetch('p1')

    apiMock.mockRejectedValueOnce(fetchError(500, 'Boom'))
    await expect(store.fetch('p1')).rejects.toThrow('Boom')
    expect(store.current).toEqual(BOT_A)
  })

  it('fetch dedups concurrent calls and resets inFlight after resolution', async () => {
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

    // After resolution, a fresh fetch must trigger a new apiMock invocation —
    // proves the `finally { inFlight = null }` reset actually fires.
    apiMock.mockResolvedValueOnce(BOT_A)
    await store.fetch('p1')
    expect(apiMock).toHaveBeenCalledTimes(2)
  })

  it('connect sends token in body only — URL contains no token substring (AC17, D17)', async () => {
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()

    await store.connect('p1', TOKEN)

    const [url, opts] = apiMock.mock.calls[0] as [string, { method: string; body: { token: string } }]
    expect(typeof url).toBe('string')
    expect(url).toBe('/api/v1/projects/p1/bot/connect')
    expect(url).not.toContain(TOKEN_MARKER)
    expect(opts.method).toBe('POST')
    expect(opts.body).toEqual({ token: TOKEN })
  })

  it('connect does not retain token in $state after resolution', async () => {
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()

    await store.connect('p1', TOKEN)

    expect(store.current).toEqual(BOT_A)
    expect(JSON.stringify(store.$state)).not.toContain(TOKEN_MARKER)
  })

  it('connect failure does not pollute current, propagates rejection, and yields a token-free $state', async () => {
    apiMock.mockRejectedValueOnce(new Error('boom'))
    const store = useBotStore()
    expect(store.current).toBeNull()

    await expect(store.connect('p1', TOKEN)).rejects.toThrow('boom')

    expect(store.current).toBeNull()
    expect(JSON.stringify(store.$state)).not.toContain(TOKEN_MARKER)
  })

  it('connect scrubs token from FetchError.options.body when body is the serialized JSON string (real ofetch shape)', async () => {
    // ofetch JSON-stringifies the body before dispatch, so by the time the
    // error fires `options.body` is the STRING `'{"token":"..."}'`. This is
    // the only realistic shape in production.
    apiMock.mockRejectedValueOnce(
      fetchError(400, 'Bad Request', { options: { body: JSON.stringify({ token: TOKEN }) } }),
    )
    const store = useBotStore()

    let caught: unknown
    try {
      await store.connect('p1', TOKEN)
    } catch (e) {
      caught = e
    }

    expect(caught).toBeDefined()
    const errOpts = (caught as { options?: { body?: unknown } }).options
    expect(errOpts?.body).toBe('[redacted]')
    // No trace of the token marker anywhere in the propagated error.
    expect(JSON.stringify(caught)).not.toContain(TOKEN_MARKER)
    // status preserved for useApiError / 404 interceptor downstream.
    expect((caught as { response?: { status?: number } }).response?.status).toBe(400)
  })

  it('connect scrubs token from FetchError.options.body when body is the original object', async () => {
    // Defensive: handle the case where a future ofetch (or a fixture) keeps
    // the original body object on the error instead of the serialized form.
    apiMock.mockRejectedValueOnce(
      fetchError(400, 'Bad Request', { options: { body: { token: TOKEN } } }),
    )
    const store = useBotStore()

    let caught: unknown
    try {
      await store.connect('p1', TOKEN)
    } catch (e) {
      caught = e
    }

    expect(caught).toBeDefined()
    expect(JSON.stringify(caught)).not.toContain(TOKEN_MARKER)
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

  it('disconnect failure leaves current intact and propagates error', async () => {
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()
    await store.fetch('p1')
    expect(store.current).toEqual(BOT_A)

    apiMock.mockRejectedValueOnce(new Error('boom'))
    await expect(store.disconnect('p1')).rejects.toThrow('boom')
    expect(store.current).toEqual(BOT_A)
  })

  it('sendTestMessage success does not mutate current', async () => {
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()
    await store.fetch('p1')

    apiMock.mockResolvedValueOnce(undefined)
    await store.sendTestMessage('p1')

    expect(store.current).toEqual(BOT_A)
    expect(apiMock).toHaveBeenLastCalledWith('/api/v1/projects/p1/bot/test-message', { method: 'POST' })
  })

  it('sendTestMessage failure does not mutate current and propagates error', async () => {
    apiMock.mockResolvedValueOnce(BOT_A)
    const store = useBotStore()
    await store.fetch('p1')

    apiMock.mockRejectedValueOnce(new Error('boom'))
    await expect(store.sendTestMessage('p1')).rejects.toThrow('boom')
    expect(store.current).toEqual(BOT_A)
  })

  it('rapid A→B→A re-selection: prior fetch resolves but does not stomp current owned by newer same-project fetch', async () => {
    const projectsStore = useProjectsStore()
    projectsStore.selectProject('a')
    const botStore = useBotStore()

    // First fetch for 'a' (slow, manually resolvable).
    let resolveA1: ((v: Bot) => void) | undefined
    apiMock.mockImplementationOnce(() => new Promise<Bot>((r) => { resolveA1 = r }))
    const a1Promise = botStore.fetch('a')

    // Switch to 'b' (watcher fires; keep 'b' pending so it cannot write).
    apiMock.mockImplementationOnce(() => new Promise(() => {}))
    projectsStore.selectProject('b')
    await nextTick()

    // Switch back to 'a' — a fresh fetch('a') becomes the active inFlight.
    apiMock.mockImplementationOnce(() => new Promise(() => {}))
    projectsStore.selectProject('a')
    await nextTick()

    // The ORIGINAL fetch('a') resolves with stale data. Even though the
    // current selection is 'a' again, this run is not the active inFlight —
    // a projectId-only guard would incorrectly accept it; the run-identity
    // guard rejects it.
    const STALE: Bot = { ...BOT_A, telegramUsername: 'StaleAlpha' }
    resolveA1!(STALE)
    await a1Promise

    expect(botStore.current).toBeNull()
  })

  it('late-arriving fetch for prior project does not stomp current after project switch', async () => {
    const projectsStore = useProjectsStore()
    projectsStore.selectProject('a')
    const botStore = useBotStore()

    // Start a slow fetch for 'a' but DO NOT await — it will resolve later.
    let resolveA: ((v: Bot) => void) | undefined
    apiMock.mockImplementationOnce(() => new Promise<Bot>((r) => { resolveA = r }))
    const aPromise = botStore.fetch('a')

    // Project switch — watcher clears `current` and starts fetch('b').
    // Keep 'b's fetch pending so it cannot also write `current`.
    apiMock.mockImplementationOnce(() => new Promise(() => {}))
    projectsStore.selectProject('b')
    await nextTick()

    // 'a' resolves late — must NOT stomp current because it is no longer
    // the active inFlight projectId.
    resolveA!(BOT_A)
    await aPromise

    expect(botStore.current).toBeNull()
  })

  it('watcher clears and refetches on project switch', async () => {
    const projectsStore = useProjectsStore()
    projectsStore.selectProject('a')
    const botStore = useBotStore()

    apiMock.mockResolvedValueOnce(BOT_A)
    await botStore.fetch('a')
    expect(botStore.current).toEqual(BOT_A)

    apiMock.mockReset()
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
