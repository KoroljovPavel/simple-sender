<script setup lang="ts">
import type { SegmentFilter, SortKey, SubscriberStatus, Tag } from '~/types/subscriber'

const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ 'update:filter': [filter: SegmentFilter] }>()

const { t } = useI18n()

const SEARCH_DEBOUNCE_MS = 300
const MIN_SEARCH_CHARS = 2
const STATUSES: SubscriberStatus[] = ['active', 'unsubscribed', 'blocked', 'deleted']

const search = ref('')
const status = ref<SubscriberStatus | ''>('')
const tagsInclude = ref<string[]>([])
const tagsExclude = ref<string[]>([])
const dateFrom = ref('') // yyyy-mm-dd
const dateTo = ref('')
const sort = ref<SortKey>('created_desc')

const availableTags = ref<Tag[]>([])

// Populate tag combobox sets from the project tag list. Non-fatal: a failure leaves the tag selectors
// empty rather than breaking the whole bar.
onMounted(async () => {
  try {
    availableTags.value = await useApi()<Tag[]>(`/api/v1/projects/${props.projectId}/tags`)
  } catch (err) {
    console.warn('[subscribers] failed to load tags for filter bar', err)
  }
})

function buildFilter(): SegmentFilter {
  const s = search.value.trim()
  return {
    // <2 chars (incl. empty) → search omitted so the backend @Size(min=2) guard is never tripped.
    search: s.length >= MIN_SEARCH_CHARS ? s : undefined,
    status: status.value === '' ? null : status.value,
    tagsInclude: [...tagsInclude.value],
    tagsExclude: [...tagsExclude.value],
    subscribedFrom: dateFrom.value ? `${dateFrom.value}T00:00:00Z` : null,
    subscribedTo: dateTo.value ? `${dateTo.value}T23:59:59Z` : null,
    sort: sort.value,
  }
}

function emitFilter() {
  emit('update:filter', buildFilter())
}

// Search is debounced; a single trailing char is intentionally NOT emitted (too short to query, but
// not a "clear" either). Empty (0 chars) and ≥2 chars both emit.
let searchTimer: ReturnType<typeof setTimeout> | null = null
function onSearchInput() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    searchTimer = null
    if (search.value.trim().length === 1) return
    emitFilter()
  }, SEARCH_DEBOUNCE_MS)
}
onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
})

function toggleInclude(slug: string) {
  tagsInclude.value = tagsInclude.value.includes(slug)
    ? tagsInclude.value.filter((s) => s !== slug)
    : [...tagsInclude.value, slug]
  emitFilter()
}
function toggleExclude(slug: string) {
  tagsExclude.value = tagsExclude.value.includes(slug)
    ? tagsExclude.value.filter((s) => s !== slug)
    : [...tagsExclude.value, slug]
  emitFilter()
}
</script>

<template>
  <div data-test="subscribers-filter-bar" class="space-y-3 rounded-md border border-gray-200 bg-white p-4">
    <div class="flex flex-wrap items-end gap-3">
      <div class="flex-1 min-w-[200px]">
        <label class="block text-xs font-medium text-gray-600 mb-1">{{ t('subscribers.title') }}</label>
        <input
          v-model="search"
          data-test="subscribers-filter-search"
          type="text"
          :placeholder="t('subscribers.searchPlaceholder')"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          @input="onSearchInput"
        />
      </div>

      <div>
        <label class="block text-xs font-medium text-gray-600 mb-1">{{ t('subscribers.filters.status') }}</label>
        <select
          v-model="status"
          data-test="subscribers-filter-status"
          class="rounded-md border px-3 py-2 text-sm"
          @change="emitFilter"
        >
          <option value="">{{ t('subscribers.title') }}</option>
          <option v-for="st in STATUSES" :key="st" :value="st">{{ t(`subscribers.status.${st}`) }}</option>
        </select>
      </div>

      <div>
        <label class="block text-xs font-medium text-gray-600 mb-1">{{ t('subscribers.columns.lastSeen') }}</label>
        <select
          v-model="sort"
          data-test="subscribers-filter-sort"
          class="rounded-md border px-3 py-2 text-sm"
          @change="emitFilter"
        >
          <option value="created_desc">{{ t('subscribers.sort.createdDesc') }}</option>
          <option value="last_seen_desc">{{ t('subscribers.sort.lastSeenDesc') }}</option>
        </select>
      </div>

      <div>
        <label class="block text-xs font-medium text-gray-600 mb-1">{{ t('subscribers.filters.dateRange.from') }}</label>
        <input
          v-model="dateFrom"
          data-test="subscribers-filter-from"
          type="date"
          class="rounded-md border px-3 py-2 text-sm"
          @change="emitFilter"
        />
      </div>
      <div>
        <label class="block text-xs font-medium text-gray-600 mb-1">{{ t('subscribers.filters.dateRange.to') }}</label>
        <input
          v-model="dateTo"
          data-test="subscribers-filter-to"
          type="date"
          class="rounded-md border px-3 py-2 text-sm"
          @change="emitFilter"
        />
      </div>
    </div>

    <div v-if="availableTags.length" class="flex flex-wrap gap-x-6 gap-y-2">
      <div>
        <p class="text-xs font-medium text-gray-600 mb-1">{{ t('subscribers.filters.tags.include') }}</p>
        <div class="flex flex-wrap gap-1">
          <button
            v-for="tag in availableTags"
            :key="`inc-${tag.slug}`"
            type="button"
            :data-test="`subscribers-filter-tag-include-${tag.slug}`"
            :aria-pressed="tagsInclude.includes(tag.slug)"
            :class="[
              'rounded-full border px-2.5 py-0.5 text-xs',
              tagsInclude.includes(tag.slug)
                ? 'border-blue-500 bg-blue-50 text-blue-700'
                : 'border-gray-300 text-gray-600 hover:bg-gray-50',
            ]"
            @click="toggleInclude(tag.slug)"
          >
            {{ tag.label || tag.slug }}
          </button>
        </div>
      </div>

      <div>
        <p class="text-xs font-medium text-gray-600 mb-1">{{ t('subscribers.filters.tags.exclude') }}</p>
        <div class="flex flex-wrap gap-1">
          <button
            v-for="tag in availableTags"
            :key="`exc-${tag.slug}`"
            type="button"
            :data-test="`subscribers-filter-tag-exclude-${tag.slug}`"
            :aria-pressed="tagsExclude.includes(tag.slug)"
            :class="[
              'rounded-full border px-2.5 py-0.5 text-xs',
              tagsExclude.includes(tag.slug)
                ? 'border-red-500 bg-red-50 text-red-700'
                : 'border-gray-300 text-gray-600 hover:bg-gray-50',
            ]"
            @click="toggleExclude(tag.slug)"
          >
            {{ tag.label || tag.slug }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
