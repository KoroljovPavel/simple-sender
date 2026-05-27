// Subscriber CRM domain types + pure filter mappers (Epic 09, Task 10). The list endpoint and the
// export endpoint consume the SAME segment intent through two different wire shapes, so the mapping
// lives here as pure functions both the store and ExportCsvDialog import + unit-test.

// Backend returns status lowercased (SubscriberController.toResponse → status().name().toLowerCase()).
export type SubscriberStatus = 'active' | 'unsubscribed' | 'blocked' | 'deleted'

// Frontend-canonical sort tokens. The list query takes them verbatim (backend parses leniently);
// the export body needs the Java enum NAME, mapped in toExportFilter().
export type SortKey = 'created_desc' | 'last_seen_desc'

export interface Subscriber {
  id: string
  telegramUserId: number | null
  telegramChatId: number | null
  telegramBotId: number | null
  firstName: string | null
  lastName: string | null
  username: string | null
  languageCode: string | null
  status: SubscriberStatus
  tags: string[]
  customFields: Record<string, unknown>
  subscribedAt: string | null
  unsubscribedAt: string | null
  blockedAt: string | null
  deletedAt: string | null
  lastSeenAt: string | null
}

export interface PageResponse<T> {
  items: T[]
  nextCursor: string | null
}

export interface SubscriberEvent {
  id: string
  eventType: string
  metadata: Record<string, unknown> | null
  createdAt: string
}

export interface Tag {
  slug: string
  label: string | null
  subscriberCount: number
  createdAt: string
}

// CustomFieldType enum mirrors backend com.botfunnel.project.CustomFieldType — only these four exist
// (no ENUM/SELECT type, despite the task's "enum → select" wording; see decisions.md deviation note).
export type CustomFieldType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'DATE'

export interface CustomFieldDefinition {
  name: string
  label: string
  type: CustomFieldType
  defaultValue: unknown
  createdAt: string
}

export interface SendMessageResponse {
  status: 'sent' | 'blocked' | 'deleted'
  message: string
}

// The segment the list page filters on. NOT persisted to localStorage (Decision 12 / patterns.md:165).
export interface SegmentFilter {
  search?: string
  status: SubscriberStatus | null
  tagsInclude: string[]
  tagsExclude: string[]
  subscribedFrom: string | null
  subscribedTo: string | null
  sort: SortKey
}

export function defaultFilter(): SegmentFilter {
  return {
    status: null,
    tagsInclude: [],
    tagsExclude: [],
    subscribedFrom: null,
    subscribedTo: null,
    sort: 'created_desc',
  }
}

// Search shorter than 2 trimmed chars is dropped so the backend's @Size(min=2) bean-validation on
// SubscriberListQuery.search is never tripped (user-spec ≥2-char rule).
const MIN_SEARCH_CHARS = 2

function effectiveSearch(filter: SegmentFilter): string | null {
  const s = filter.search?.trim() ?? ''
  return s.length >= MIN_SEARCH_CHARS ? s : null
}

// → query params for GET /subscribers. Array params (tagsInclude/Exclude) serialize as repeated keys,
// which Spring's @ModelAttribute List binding consumes. Omitted keys keep the URL clean.
export function toListQuery(
  filter: SegmentFilter,
  opts: { cursor?: string | null; limit: number },
): Record<string, unknown> {
  const q: Record<string, unknown> = { limit: opts.limit, sort: filter.sort }
  const search = effectiveSearch(filter)
  if (search) q.search = search
  if (filter.status) q.status = filter.status
  if (filter.tagsInclude.length) q.tagsInclude = filter.tagsInclude
  if (filter.tagsExclude.length) q.tagsExclude = filter.tagsExclude
  if (filter.subscribedFrom) q.subscribedFrom = filter.subscribedFrom
  if (filter.subscribedTo) q.subscribedTo = filter.subscribedTo
  if (opts.cursor) q.cursor = opts.cursor
  return q
}

// → CreateExportRequest.filter JSON. This deserializes into the Java SegmentFilter record, whose
// `status` (SubscriberStatus) and `sort` (SortKey) are case-sensitive enums — hence the uppercase map.
export function toExportFilter(filter: SegmentFilter): Record<string, unknown> {
  return {
    search: effectiveSearch(filter),
    status: filter.status ? filter.status.toUpperCase() : null,
    tagsInclude: filter.tagsInclude,
    tagsExclude: filter.tagsExclude,
    subscribedFrom: filter.subscribedFrom,
    subscribedTo: filter.subscribedTo,
    sort: filter.sort === 'last_seen_desc' ? 'LAST_SEEN_DESC' : 'CREATED_DESC',
    cursor: null,
    limit: 0,
  }
}
