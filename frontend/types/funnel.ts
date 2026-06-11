// Mirrors the Task-5 backend DTOs (com.botfunnel.funnel.dto). FunnelStatus is the lowercase enum
// (backend FunnelStatus: draft/active/paused). The list view (FunnelSummaryResponse) omits the steps
// array — the editor (Task 10) loads steps lazily via the single GET (FunnelResponse).
export type FunnelStatus = 'draft' | 'active' | 'paused'

// Status filter for the list page: 'all' is the default UI option and maps to "no ?status= param".
export type FunnelStatusFilter = 'all' | FunnelStatus

// Step discriminator — mirrors backend StepType enum (com.botfunnel.funnel.StepType) byte-for-byte.
// 15-message-composer (Decision 1): the single MESSAGE composer step carries an ordered list of
// ContentBlocks; it replaces the former flat SEND_MESSAGE / SEND_IMAGE / MENU step-kinds.
export type StepType =
  | 'MESSAGE'
  | 'DELAY'
  | 'ADD_TAG'
  | 'REMOVE_TAG'
  | 'SET_CUSTOM_FIELD'
  | 'EMIT_EVENT'
  // Cross-funnel composition (Phase 5): enrolls the subscriber into ANOTHER funnel of the project.
  | 'SUBSCRIBE_TO_FUNNEL'
  // 16-persistent-keyboard: send a mandatory text message and SET a persistent bottom reply keyboard
  // (ReplyKeyboardMarkup) / CLEAR it (ReplyKeyboardRemove). Fire-and-forget — the engine never parks.
  | 'SET_KEYBOARD'
  | 'CLEAR_KEYBOARD'

// Funnel entry trigger — mirrors backend triggerType values byte-for-byte (Phase 3). The backend value
// for the API-event path is `event`; the UI labels it "api-event" (Decision 4 — external POST /events and
// the in-funnel EMIT_EVENT step share one `event` namespace keyed by event_name). keyword does NOT use
// triggerValue — it scans the funnel's `keywords` list; tag_added/custom_field_set/event reuse triggerValue
// as the match key (tag slug / field key / event name).
export type FunnelTriggerType = 'on_start' | 'keyword' | 'tag_added' | 'custom_field_set' | 'event'

// Inline-keyboard button on a MESSAGE composer step — mirrors backend Button record
// (com.botfunnel.funnel.Button). type is 'callback' (advances the funnel to targetStepId, or End when
// null) or 'url' (opens an http(s) link, does not advance). targetStepId and url are mutually exclusive
// by type, hence optional. Buttons attach to the step (the last non-album block — Decision 2), not to a
// ContentBlock.
export interface Button {
  type: 'callback' | 'url'
  label: string
  targetStepId?: string | null
  url?: string | null
}

// One button of a SET_KEYBOARD reply keyboard — mirrors backend KeyboardButton record
// (com.botfunnel.funnel.KeyboardButton). Reply-keyboard buttons are PLAIN TEXT only (the tap arrives as a
// normal message and routes through keyword dispatch) — DISTINCT from the inline Button (callback/url).
export interface KeyboardButton {
  text: string
}

// One row of a SET_KEYBOARD reply keyboard — mirrors backend KeyboardRow record
// (com.botfunnel.funnel.KeyboardRow). 1..4 buttons per row; a keyboard has 1..10 rows.
export interface KeyboardRow {
  buttons: KeyboardButton[]
}

// Delay unit — mirrors backend requireDelay (MIN | HOUR | DAY).
export type DelayUnit = 'MIN' | 'HOUR' | 'DAY'

// Dynamic-value sentinel for a SET_CUSTOM_FIELD step on a DATE field: stored as the customFieldValue
// instead of a fixed date, the engine resolves it to the execution-time instant. MUST match backend
// StepExecutor.CURRENT_DATE_TOKEN byte-for-byte.
export const CURRENT_DATE_TOKEN = '@now'

// Block discriminator inside a MESSAGE composer step — mirrors backend BlockType enum
// (com.botfunnel.funnel.BlockType) byte-for-byte (15-message-composer / Decision 1):
//   - TEXT                    → a plain text message (optional parseMode).
//   - IMAGE / VIDEO / AUDIO / FILE → a single media message (URL or file_id) with an optional caption.
//   - ALBUM                   → a Telegram media group of 2–10 MediaItems.
export type BlockType = 'TEXT' | 'IMAGE' | 'VIDEO' | 'AUDIO' | 'FILE' | 'ALBUM'

// One element of a BlockType.ALBUM media group — mirrors backend MediaItemDto
// (com.botfunnel.funnel.dto.MediaItemDto) one-to-one. `type` is the per-item media kind (the
// type-mixing rule is enforced server-side in FunnelService); `mediaUrl` is an http(s) URL or an opaque
// Telegram file_id; `caption` is meaningful only on the FIRST element of the album (Decision 5).
export interface MediaItem {
  type: BlockType
  mediaUrl: string
  caption?: string | null
}

// One content block of a MESSAGE composer step — mirrors backend ContentBlockDto
// (com.botfunnel.funnel.dto.ContentBlockDto) one-to-one. Flat shape (no discriminated narrowing,
// Decision 1): `type` is the BlockType discriminator and the remaining fields are type-specific and
// nullable, populated only for their own `type` so a save round-trip never drops a field:
//   - TEXT                    → text (+ optional parseMode)
//   - IMAGE / VIDEO / AUDIO / FILE → mediaUrl (+ optional caption, parseMode)
//   - ALBUM                   → items (2–10 MediaItems; caption meaningful only on the first)
// Per-type required-field / album-size / type-mixing checks live server-side (→ 422), mirrored as a UX
// hint client-side — they are NOT encoded in this type.
export interface ContentBlock {
  type: BlockType
  text?: string | null
  parseMode?: string | null
  mediaUrl?: string | null
  caption?: string | null
  items?: MediaItem[] | null
}

// One ordered step. Flat shape mirroring backend FunnelStepDto (Decision 12 — no _class discriminator).
// Position in the steps array IS the order; the server rewrites FunnelStep.order from the index, so the
// editor never sends `order` explicitly. Per-type fields are optional and only the type's required ones
// are populated/validated client-side (mirroring FunnelService.validateSteps).
// 15-message-composer (Decision 1): the former flat msg fields (text/parseMode/imageUrl/caption) are
// replaced by `blocks` — an ordered ContentBlock[] populated only for stepType 'MESSAGE'.
export interface FunnelStep {
  stepType: StepType
  // MESSAGE composer step only — the ordered content blocks this step sends as N Telegram messages.
  blocks?: ContentBlock[] | null
  delayValue?: number | null
  delayUnit?: DelayUnit | null
  tagSlug?: string | null
  customFieldKey?: string | null
  customFieldValue?: unknown
  // EMIT_EVENT only — the event_name this step dispatches into the shared `event` namespace (Decision 4).
  eventName?: string | null
  // SUBSCRIBE_TO_FUNNEL only (Phase 5) — mirrors backend FunnelStep cross-funnel fields. targetFunnelId =
  // the funnel to enroll into; targetEntryStepId = the step to start at (null = from the start). The entry
  // field is `targetEntryStepId` (NOT `targetStepId` — Decision 5) so it is never confused with the MENU
  // Button.targetStepId. endParentAfter = end THIS funnel right after enrolling (recommended for transfer).
  targetFunnelId?: string | null
  targetEntryStepId?: string | null
  endParentAfter?: boolean | null
  // 16-persistent-keyboard — mirrors backend FunnelStep keyboard fields (all nullable). SET_KEYBOARD and
  // CLEAR_KEYBOARD share the mandatory text pair (keyboardText + keyboardParseMode, ≤4096, variables +
  // parse mode). The remaining three are SET_KEYBOARD only: keyboardRows (1..10 rows × 1..4 buttons,
  // button text non-blank ≤64, no duplicate trimmed texts), isPersistent (Telegram is_persistent, form
  // default true) and oneTimeKeyboard (one_time_keyboard, form default false). resize_keyboard is NOT
  // modeled here — the backend StepExecutor hardcodes it true. CLEAR_KEYBOARD must NOT carry the three
  // SET-only fields — the backend strictly rejects them on that type (Decision 6).
  keyboardText?: string | null
  keyboardParseMode?: string | null
  keyboardRows?: KeyboardRow[] | null
  isPersistent?: boolean | null
  oneTimeKeyboard?: boolean | null
  // Graph model (Phase 2) — mirrors backend FunnelStep graph fields. id is server-minted; next is the
  // default outgoing edge (null = next step in list). buttons/timeout* apply to the MESSAGE composer step
  // (attached to the last non-album block — Decision 2).
  id?: string | null
  next?: string | null
  buttons?: Button[] | null
  timeoutValue?: number | null
  // Canonical timeout units mirror DelayUnit / backend DelayUnit (MIN | HOUR | DAY) — NOT the stale
  // minutes/hours/days from the tech-spec Data Models text. The engine only parses MIN/HOUR/DAY.
  timeoutUnit?: DelayUnit | null
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
  // Only populated for triggerType=keyword; null/empty for every other trigger type (Decision 3).
  keywords?: string[] | null
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
  // Only populated for triggerType=keyword; null/empty for every other trigger type (Decision 3).
  keywords?: string[] | null
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
  // Only sent for triggerType=keyword (the active type owns its value — Edge cases); omit otherwise.
  keywords?: string[] | null
  allowReEnter?: boolean
  steps?: FunnelStep[]
}

// POST .../funnels/{id}/steps/{stepId}/preview body — mirrors backend PreviewStepRequest
// (com.botfunnel.funnel.dto.PreviewStepRequest). Decision 8 / 16-persistent-keyboard Decision 7: preview
// renders the CURRENT (possibly unsaved) content of the step, NOT the persisted step — so the editor sends
// stepType + the live form state on the fly. parseMode now lives per-block inside ContentBlock (not
// top-level). For a non-message step `blocks` is null/empty → the server renders an empty array. The
// backend drops unknown/absent fields (@JsonIgnoreProperties), so a request only carries the fields its
// stepType needs:
//   - MESSAGE                  → `blocks` (the ordered composer blocks).
//   - SET_KEYBOARD/CLEAR_KEYBOARD → `keyboardText` + `keyboardParseMode`; SET_KEYBOARD also `keyboardRows`
//     (labels echoed verbatim, never variable-rendered). `blocks` is irrelevant for keyboard steps.
export interface PreviewStepRequest {
  stepType: StepType
  blocks?: ContentBlock[]
  // SET_KEYBOARD / CLEAR_KEYBOARD — the mandatory text pair rendered server-side (variables + parse mode).
  keyboardText?: string | null
  keyboardParseMode?: string | null
  // SET_KEYBOARD only — the button rows; labels are echoed verbatim (the keyword link), never rendered.
  keyboardRows?: KeyboardRow[] | null
}

// One rendered album element in a preview response — mirrors backend
// PreviewStepResponse.RenderedMediaItem. mediaUrl is passed through VERBATIM (never dereferenced by the
// backend — anti-SSRF, Decision 6); caption is already escaped per the album block's parseMode
// (meaningful only on the first element — Decision 5).
export interface RenderedMediaItem {
  mediaUrl: string
  caption?: string | null
}

// One rendered block in a preview response — mirrors backend PreviewStepResponse.RenderedBlock. text/
// caption are already escaped per parseMode server-side (the frontend renders them text-only, never
// v-html); mediaUrl/items are passed through verbatim (:src). Fields are nullable by block type:
//   - TEXT                    → text (parseMode); media/caption/items null
//   - IMAGE / VIDEO / AUDIO / FILE → mediaUrl + caption (parseMode); text/items null
//   - ALBUM                   → items (each rendered caption escaped); text/mediaUrl/caption null
export interface RenderedBlock {
  type: BlockType
  text?: string | null
  parseMode?: string | null
  mediaUrl?: string | null
  caption?: string | null
  items?: RenderedMediaItem[] | null
}

// POST .../steps/{stepId}/preview → PreviewStepResponse (com.botfunnel.funnel.dto.PreviewStepResponse).
// Exactly these four fields — no ownerChatId/identity leakage (Decision 8). `renderedBlocks` is the
// ordered, rendered blocks (one per composer block; an empty array for a non-message step; exactly one
// rendered TEXT block for a keyboard step). `kind` is 'message' for a renderable MESSAGE composer step,
// 'non_message' for an action step, or 'keyboard' for both SET_KEYBOARD and CLEAR_KEYBOARD (16-persistent-
// keyboard Decision 7 — the discriminator between them is `keyboardRows` non-null vs null). `sampleData`
// is true when sample placeholders were substituted (e.g. bot owner not linked). `keyboardRows` are the
// raw button-label rows for the bottom-keyboard mock — VERBATIM (labels are the keyword link, never
// variable-rendered) but CLAMPED server-side to the validation caps (≤10 rows × ≤4 buttons, label ≤64);
// non-null ONLY for a SET_KEYBOARD preview, null for every other kind (CLEAR_KEYBOARD, message, non_message).
export interface PreviewStepResponse {
  renderedBlocks: RenderedBlock[]
  sampleData: boolean
  kind: 'message' | 'non_message' | 'keyboard'
  keyboardRows?: string[][] | null
}

// POST .../funnels/{id}/executions/stop → StopAllResponse (com.botfunnel.funnel.dto.StopAllResponse,
// Decision 7). `cancelled` is the bulk-cancel `modifiedCount` (backend field is a `long`).
export interface StopAllResponse {
  cancelled: number
}
