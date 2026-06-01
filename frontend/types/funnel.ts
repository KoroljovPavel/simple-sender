// Mirrors the Task-5 backend DTOs (com.botfunnel.funnel.dto). FunnelStatus is the lowercase enum
// (backend FunnelStatus: draft/active/paused). The list view (FunnelSummaryResponse) omits the steps
// array — the editor (Task 10) loads steps lazily via the single GET (FunnelResponse).
export type FunnelStatus = 'draft' | 'active' | 'paused'

// Status filter for the list page: 'all' is the default UI option and maps to "no ?status= param".
export type FunnelStatusFilter = 'all' | FunnelStatus

// GET .../funnels?status= → FunnelSummaryResponse[] (metadata without steps; stepCount is a cheap hint).
export interface FunnelSummaryResponse {
  id: string
  projectId: string
  name: string
  description: string | null
  status: FunnelStatus
  triggerType: string | null
  triggerValue: string | null
  allowReEnter: boolean
  stepCount: number
  createdAt: string
  updatedAt: string
}

// POST/GET/{id}/PUT/PATCH → FunnelResponse (metadata + full ordered steps + deepLink). Task 9 only
// needs `id` (for navigation into the editor); the steps shape is owned by Task 10.
export interface FunnelResponse {
  id: string
  projectId: string
  name: string
  description: string | null
  status: FunnelStatus
  triggerType: string | null
  triggerValue: string | null
  allowReEnter: boolean
  steps: unknown[]
  deepLink: string | null
  createdAt: string
  updatedAt: string
}

// POST .../funnels body — name required (@NotBlank @Size max 128), description optional (@Size max 1024).
export interface CreateFunnelRequest {
  name: string
  description?: string | null
}

// PATCH/PUT .../funnels/{id} body — full-replace metadata + trigger + steps (all nullable server-side).
// Task 9 includes update() in the store per contract but has no UI for it (Task 10 reuses it).
export interface UpdateFunnelRequest {
  name?: string
  description?: string | null
  triggerType?: string | null
  triggerValue?: string | null
  allowReEnter?: boolean
  steps?: unknown[]
}
