import { defineStore } from 'pinia'
import type { PageResponse, SegmentFilter, Subscriber } from '~/types/subscriber'
import { defaultFilter, toListQuery } from '~/types/subscriber'

// Cursor-paginated subscriber-list store (Decision 12). Mirrors the setup-store SHAPE of
// stores/projects.ts but deliberately persists NOTHING to the browser — the opaque-ID-only rule
// (patterns.md:165) forbids caching list data client-side. Project scope comes from the route param,
// so the page does not have to thread projectId through every action.
const PAGE_SIZE = 50

export const useSubscribersStore = defineStore('subscribers', () => {
  const items = ref<Subscriber[]>([])
  const nextCursor = ref<string | null>(null)
  const loading = ref(false)
  const filter = ref<SegmentFilter>(defaultFilter())

  function listUrl(): string {
    // Route param is authoritative for project scope (it can differ from projectsStore.currentProjectId
    // when the user deep-links a project they are not "currently" on).
    const projectId = useRoute().params.projectId
    return `/api/v1/projects/${projectId}/subscribers`
  }

  // First page / filter change. Clears items + cursor BEFORE fetching so a filter switch never shows
  // stale rows, and so concurrent calls are last-call-wins (the later fetch overwrites items/cursor;
  // no abort needed — a brief flash of an in-flight earlier result is acceptable for a list view).
  async function loadFirstPage(next: SegmentFilter): Promise<void> {
    filter.value = next
    items.value = []
    nextCursor.value = null
    loading.value = true
    try {
      const page = await useApi()<PageResponse<Subscriber>>(listUrl(), {
        query: toListQuery(next, { limit: PAGE_SIZE }),
      })
      items.value = page.items
      nextCursor.value = page.nextCursor
    } finally {
      loading.value = false
    }
  }

  // Append the next page. No-op when there is nothing more to fetch or a request is already in flight
  // (a plain boolean guard suffices — append, unlike a list-replace, has no last-call-wins hazard).
  async function loadMore(): Promise<void> {
    if (loading.value || nextCursor.value == null) return
    loading.value = true
    try {
      const page = await useApi()<PageResponse<Subscriber>>(listUrl(), {
        query: toListQuery(filter.value, { limit: PAGE_SIZE, cursor: nextCursor.value }),
      })
      items.value = [...items.value, ...page.items]
      nextCursor.value = page.nextCursor
    } finally {
      loading.value = false
    }
  }

  return { items, nextCursor, loading, filter, loadFirstPage, loadMore }
})
