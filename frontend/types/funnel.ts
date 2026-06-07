// Mirrors the Task-5 backend DTOs (com.botfunnel.funnel.dto). FunnelStatus is the lowercase enum
// (backend FunnelStatus: draft/active/paused). The list view (FunnelSummaryResponse) omits the steps
// array — the editor (Task 10) loads steps lazily via the single GET (FunnelResponse).
export type FunnelStatus = 'draft' | 'active' | 'paused'

// Status filter for the list page: 'all' is the default UI option and maps to "no ?status= param".
export type FunnelStatusFilter = 'all' | FunnelStatus

// Step discriminator — mirrors backend StepType enum (com.botfunnel.funnel.StepType) byte-for-byte.
export type StepType =
  | 'SEND_MESSAGE'
  | 'SEND_IMAGE'
  | 'DELAY'
  | 'ADD_TAG'
  | 'REMOVE_TAG'
  | 'SET_CUSTOM_FIELD'
  | 'MENU'

// Inline-keyboard button on a MENU step — mirrors backend Button record (com.botfunnel.funnel.Button).
// type is 'callback' (advances the funnel to targetStepId, or End when null) or 'url' (opens an
// http(s) link, does not advance). targetStepId and url are mutually exclusive by type, hence optional.
export interface Button {
  type: 'callback' | 'url'
  label: string
  targetStepId?: string | null
  url?: string | null
}

// Delay unit — mirrors backend requireDelay (MIN | HOUR | DAY).
export type DelayUnit = 'MIN' | 'HOUR' | 'DAY'

// Dynamic-value sentinel for a SET_CUSTOM_FIELD step on a DATE field: stored as the customFieldValue
// instead of a fixed date, the engine resolves it to the execution-time instant. MUST match backend
// StepExecutor.CURRENT_DATE_TOKEN byte-for-byte.
export const CURRENT_DATE_TOKEN = '@now'

// One ordered step. Flat shape mirroring backend FunnelStepDto (Decision 12 — no _class discriminator).
// Position in the steps array IS the order; the server rewrites FunnelStep.order from the index, so the
// editor never sends `order` explicitly. Per-type fields are optional and only the type's required ones
// are populated/validated client-side (mirroring FunnelService.validateSteps).
export interface FunnelStep {
  stepType: StepType
  text?: string | null
  parseMode?: string | null
  imageUrl?: string | null
  caption?: string | null
  delayValue?: number | null
  delayUnit?: DelayUnit | null
  tagSlug?: string | null
  customFieldKey?: string | null
  customFieldValue?: unknown
  // Graph model (Phase 2) — mirrors backend FunnelStep graph fields. id is server-minted; next is the
  // default outgoing edge (null = next step in list). buttons/timeout* apply to MENU steps only.
  id?: string | null
  next?: string | null
  buttons?: Button[] | null
  timeoutValue?: number | null
  timeoutUnit?: string | null
  timeoutTargetStepId?: string | null
}

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
  steps: FunnelStep[]
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
  steps?: FunnelStep[]
}
