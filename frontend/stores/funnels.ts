import { defineStore } from 'pinia'
import type {
  CreateFunnelRequest,
  FunnelResponse,
  FunnelStatusFilter,
  FunnelSummaryResponse,
  PreviewStepRequest,
  PreviewStepResponse,
  StopAllResponse,
  UpdateFunnelRequest,
} from '~/types/funnel'

// Simple project-scoped CRUD store over useApi (structural template: stores/projects.ts / stores/bot.ts).
// Deliberately NOT cursor-paginated (unlike stores/subscribers.ts) — the funnels list is a small,
// fully-loaded metadata list. The ONE idiom borrowed from subscribers.ts is reading projectId from the
// route in a listUrl() helper, so the page does not thread projectId through every action.
//
// CRITICAL (task spec): this store NEVER calls useApiError. useApiError → useI18n() requires a Vue
// component setup context and crashes at runtime inside a Pinia action. Actions catch ONLY to flip the
// loading/error flags and then RE-THROW, so the component (CreateFunnelDialog.vue / funnels/index.vue)
// maps the error to a localized message via useApiError in its own setup.
export const useFunnelsStore = defineStore('funnels', () => {
  const funnels = ref<FunnelSummaryResponse[]>([])
  const loading = ref(false)
  const error = ref(false)

  function listUrl(): string {
    // Route param is authoritative for project scope (it can differ from projectsStore.currentProjectId
    // when the user deep-links a project they are not "currently" on). Idiom from stores/subscribers.ts.
    const projectId = useRoute().params.projectId
    return `/api/v1/projects/${projectId}/funnels`
  }

  // 'all' (default) → omit the ?status= param entirely; a concrete status is passed through.
  async function fetch(status: FunnelStatusFilter = 'all'): Promise<void> {
    loading.value = true
    error.value = false
    try {
      funnels.value = await useApi()<FunnelSummaryResponse[]>(listUrl(), {
        query: status === 'all' ? undefined : { status },
      })
    } catch (err) {
      error.value = true
      throw err
    } finally {
      loading.value = false
    }
  }

  // Creates a draft funnel and returns it (caller navigates into the editor via the returned id).
  async function create(payload: CreateFunnelRequest): Promise<FunnelResponse> {
    error.value = false
    try {
      return await useApi()<FunnelResponse>(listUrl(), {
        method: 'POST',
        body: { name: payload.name, description: payload.description },
      })
    } catch (err) {
      error.value = true
      throw err
    }
  }

  // Full-replace update (Task 10 reuses this — no UI in Task 9). Refreshes the matching list row.
  async function update(funnelId: string, payload: UpdateFunnelRequest): Promise<FunnelResponse> {
    error.value = false
    try {
      const updated = await useApi()<FunnelResponse>(`${listUrl()}/${funnelId}`, {
        method: 'PATCH',
        body: payload,
      })
      funnels.value = funnels.value.map((f) =>
        f.id === funnelId
          ? {
              ...f,
              name: updated.name,
              description: updated.description,
              status: updated.status,
              // Phase 8 (17-funnel-multi-entry): the summary row carries the whole trigger array (the flat
              // triggerType/triggerValue trio is gone). The store stays a thin pass-through — it projects
              // the array verbatim, never reasons about its shape.
              triggers: updated.triggers,
              allowReEnter: updated.allowReEnter,
              stepCount: updated.steps.length,
              updatedAt: updated.updatedAt,
            }
          : f,
      )
      return updated
    } catch (err) {
      error.value = true
      throw err
    }
  }

  // Single-funnel GET for the editor (Task 10): returns the full FunnelResponse (steps + deepLink).
  // Not cached in `funnels` (that list holds summaries without steps); the editor owns the detail.
  async function fetchOne(funnelId: string): Promise<FunnelResponse> {
    error.value = false
    try {
      return await useApi()<FunnelResponse>(`${listUrl()}/${funnelId}`)
    } catch (err) {
      error.value = true
      throw err
    }
  }

  // Re-sync the matching list row from a fresh detail response (after update/activate/pause), so the
  // list page reflects status/stepCount changes without a separate refetch.
  function syncRow(updated: FunnelResponse): void {
    funnels.value = funnels.value.map((f) =>
      f.id === updated.id
        ? {
            ...f,
            name: updated.name,
            description: updated.description,
            status: updated.status,
            // Phase 8 (17-funnel-multi-entry): project the whole trigger array (flat trio removed).
            triggers: updated.triggers,
            allowReEnter: updated.allowReEnter,
            stepCount: updated.steps.length,
            updatedAt: updated.updatedAt,
          }
        : f,
    )
  }

  // POST .../activate → 200 active FunnelResponse, or 422 (no steps / invalid step / trigger conflict).
  // Mirrors the store contract: catch only to flip the error flag and RE-THROW (the editor page maps the
  // 422 code to a localized inline message via useApiError in its own setup).
  async function activate(funnelId: string): Promise<FunnelResponse> {
    error.value = false
    try {
      const updated = await useApi()<FunnelResponse>(`${listUrl()}/${funnelId}/activate`, {
        method: 'POST',
      })
      syncRow(updated)
      return updated
    } catch (err) {
      error.value = true
      throw err
    }
  }

  // POST .../pause → 200 paused FunnelResponse, or 422 (funnel_invalid_state when not active).
  async function pause(funnelId: string): Promise<FunnelResponse> {
    error.value = false
    try {
      const updated = await useApi()<FunnelResponse>(`${listUrl()}/${funnelId}/pause`, {
        method: 'POST',
      })
      syncRow(updated)
      return updated
    } catch (err) {
      error.value = true
      throw err
    }
  }

  // Server cascades cancellation of active executions (Task 5); the front-end only DELETEs + confirms.
  async function delete_(funnelId: string): Promise<void> {
    error.value = false
    try {
      await useApi()<void>(`${listUrl()}/${funnelId}`, { method: 'DELETE' })
      funnels.value = funnels.value.filter((f) => f.id !== funnelId)
    } catch (err) {
      error.value = true
      throw err
    }
  }

  // POST .../duplicate → 201 verbatim graph copy as a fresh draft FunnelResponse (Decision: Task 1).
  // syncRow is called for contract symmetry with activate/pause, but the copy's id is NOT yet in the
  // loaded list, so it is a no-op here — the list page (Task 5) surfaces the new row (refetch/navigate).
  // Do NOT fabricate a FunnelSummaryResponse from the detail response.
  async function duplicate(funnelId: string): Promise<FunnelResponse> {
    error.value = false
    try {
      const created = await useApi()<FunnelResponse>(`${listUrl()}/${funnelId}/duplicate`, {
        method: 'POST',
      })
      syncRow(created)
      return created
    } catch (err) {
      error.value = true
      throw err
    }
  }

  // POST .../executions/stop → 200 { cancelled } bulk-cancel count (Decision 7). The store returns the
  // payload verbatim; the component decides how to surface the count.
  async function stopAllExecutions(funnelId: string): Promise<StopAllResponse> {
    error.value = false
    try {
      return await useApi()<StopAllResponse>(`${listUrl()}/${funnelId}/executions/stop`, {
        method: 'POST',
      })
    } catch (err) {
      error.value = true
      throw err
    }
  }

  // POST .../test-run → 2xx enroll-registered (void). 422 (funnel_owner_not_linked / validation codes)
  // and network errors both re-throw unmapped — the component maps inline-vs-toast via useApiError.
  async function testRun(funnelId: string): Promise<void> {
    error.value = false
    try {
      await useApi()<void>(`${listUrl()}/${funnelId}/test-run`, { method: 'POST' })
    } catch (err) {
      error.value = true
      throw err
    }
  }

  // POST .../steps/{stepId}/preview → 200 PreviewStepResponse. Sends the CURRENT (possibly unsaved) step
  // content as the body (Decision 8); returns the rendered payload verbatim (kind/sampleData drive no
  // branching in the store).
  async function preview(
    funnelId: string,
    stepId: string,
    payload: PreviewStepRequest,
  ): Promise<PreviewStepResponse> {
    error.value = false
    try {
      return await useApi()<PreviewStepResponse>(`${listUrl()}/${funnelId}/steps/${stepId}/preview`, {
        method: 'POST',
        body: payload,
      })
    } catch (err) {
      error.value = true
      throw err
    }
  }

  return {
    funnels,
    loading,
    error,
    fetch,
    fetchOne,
    create,
    update,
    activate,
    pause,
    duplicate,
    stopAllExecutions,
    testRun,
    preview,
    // `delete` is a reserved word for a method name in object shorthand; expose under the spec'd name.
    delete: delete_,
  }
})
