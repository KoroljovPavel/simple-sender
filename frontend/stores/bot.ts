import { defineStore } from 'pinia'
import type { Bot } from '~/types/bot'

export const useBotStore = defineStore('bot', () => {
  const current = ref<Bot | null>(null)
  // Module-scoped (never returned, never on $state). Dedup is keyed by
  // `projectId` (concurrent calls for the same project share one request);
  // stale-write protection is keyed by `promise` identity (an A→B→A
  // re-selection produces a new run whose promise is distinct from the
  // earlier run for the same projectId).
  let inFlight: { projectId: string; promise: Promise<Bot | null> } | null = null

  function fetch(projectId: string): Promise<Bot | null> {
    if (inFlight && inFlight.projectId === projectId) return inFlight.promise
    // `run` is captured by the IIFE's closure. All references inside the
    // async body happen AFTER the first `await`, by which time the binding
    // has been assigned by the outer statement below — no TDZ at runtime.
    let run!: Promise<Bot | null>
    run = (async (): Promise<Bot | null> => {
      try {
        const data = await useApi()<Bot>(`/api/v1/projects/${projectId}/bot`)
        if (inFlight?.promise === run) current.value = data
        return data
      } catch (err: unknown) {
        const status = (err as { response?: { status?: number } })?.response?.status
        if (status === 404) {
          if (inFlight?.promise === run) current.value = null
          return null
        }
        throw err
      } finally {
        if (inFlight?.promise === run) inFlight = null
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
      // ofetch attaches request `options` (incl. the POST body) to the
      // rejection. By the time the error fires, ofetch has already
      // JSON-stringified the JSON body — `err.options.body` is the string
      // `'{"token":"<raw>"}'`. Unconditionally replace it so callers that
      // log the whole error object cannot leak the secret. `response.status`
      // sits on a separate property and is preserved.
      const opts = (err as { options?: { body?: unknown } })?.options
      if (opts) opts.body = '[redacted]'
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
