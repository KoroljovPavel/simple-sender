import { defineStore } from 'pinia'
import type { Bot } from '~/types/bot'

export const useBotStore = defineStore('bot', () => {
  const current = ref<Bot | null>(null)
  // Module-scoped (never returned from the store, never on $state) — dedup
  // token + identity check for "is my fetch still the latest?". When the
  // user switches projects mid-fetch, a newer call replaces `inFlight`;
  // the older run then refuses to stomp `current` with stale data.
  let inFlight: { projectId: string; promise: Promise<Bot | null> } | null = null

  async function fetch(projectId: string): Promise<Bot | null> {
    if (inFlight && inFlight.projectId === projectId) return inFlight.promise
    const run: Promise<Bot | null> = (async (): Promise<Bot | null> => {
      try {
        const data = await useApi()<Bot>(`/api/v1/projects/${projectId}/bot`)
        if (inFlight?.projectId === projectId) current.value = data
        return data
      } catch (err: unknown) {
        const status = (err as { response?: { status?: number } })?.response?.status
        if (status === 404) {
          if (inFlight?.projectId === projectId) current.value = null
          return null
        }
        throw err
      } finally {
        if (inFlight?.projectId === projectId) inFlight = null
      }
    })()
    inFlight = { projectId, promise: run }
    return run
  }

  async function connect(projectId: string, token: string): Promise<Bot> {
    // The token argument lives only on this call stack and inside the $fetch
    // request body — never on a ref, never returned, never interpolated into
    // the URL (D17, AC17). Residual exposure: Pinia devtools action-trace
    // records action arguments in dev mode; production builds disable Vue
    // devtools, so the token is bounded to the dev session.
    try {
      const data = await useApi()<Bot>(`/api/v1/projects/${projectId}/bot/connect`, {
        method: 'POST',
        body: { token },
      })
      current.value = data
      return data
    } catch (err: unknown) {
      // ofetch FetchError attaches `options` (incl. the POST body) to the
      // rejection. Replace `body.token` before the error escapes this store,
      // so callers that log the whole error object cannot leak the secret.
      const opts = (err as { options?: { body?: unknown } })?.options
      if (opts && opts.body && typeof opts.body === 'object' && opts.body !== null) {
        opts.body = { ...(opts.body as Record<string, unknown>), token: '[redacted]' }
      }
      throw err
    }
  }

  async function disconnect(projectId: string): Promise<void> {
    await useApi()(`/api/v1/projects/${projectId}/bot/disconnect`, { method: 'POST' })
    current.value = null
  }

  async function sendTestMessage(projectId: string): Promise<void> {
    await useApi()(`/api/v1/projects/${projectId}/bot/test-message`, { method: 'POST' })
  }

  const projectsStore = useProjectsStore()
  watch(
    () => projectsStore.currentProjectId,
    (newId) => {
      // Clear synchronously so a previously-rendered "connected" view never
      // briefly persists after the user switches projects. The fetch itself
      // is client-only — server-side render relies on the page-level loader.
      current.value = null
      if (newId && import.meta.client) {
        void fetch(newId)
      }
    },
  )

  return { current, fetch, connect, disconnect, sendTestMessage }
})
