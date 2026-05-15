import { defineStore } from 'pinia'
import type { Bot } from '~/types/bot'

export const useBotStore = defineStore('bot', () => {
  const current = ref<Bot | null>(null)
  // Module-scoped (never returned from the store) — dedup token for concurrent
  // fetches. Mirrors the pattern in stores/projects.ts. Holding this on the
  // store would expose it via $state, which is unnecessary and would survive
  // hot-reload state snapshots.
  let inFlight: Promise<Bot | null> | null = null

  async function fetch(projectId: string): Promise<Bot | null> {
    if (inFlight) return inFlight
    const run = (async (): Promise<Bot | null> => {
      try {
        const data = await useApi()<Bot>(`/api/v1/projects/${projectId}/bot`)
        current.value = data
        return data
      } catch (err: unknown) {
        const status = (err as { response?: { status?: number } })?.response?.status
        if (status === 404) {
          current.value = null
          return null
        }
        throw err
      } finally {
        inFlight = null
      }
    })()
    inFlight = run
    return run
  }

  async function connect(projectId: string, token: string): Promise<Bot> {
    // The token argument lives only on this call stack and inside the $fetch
    // request body. It is never assigned to a ref, returned, re-thrown in an
    // error, or interpolated into the URL (D17, AC17).
    const data = await useApi()<Bot>(`/api/v1/projects/${projectId}/bot/connect`, {
      method: 'POST',
      body: { token },
    })
    current.value = data
    return data
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
      // briefly persists after the user switches projects.
      current.value = null
      if (newId && import.meta.client) {
        void fetch(newId)
      }
    },
  )

  return { current, fetch, connect, disconnect, sendTestMessage }
})
