<script setup lang="ts">
import SubscribersFilterBar from '~/components/subscribers/SubscribersFilterBar.vue'
import SubscribersTable from '~/components/subscribers/SubscribersTable.vue'
import ExportCsvDialog from '~/components/subscribers/ExportCsvDialog.vue'
import RecentExportsDialog from '~/components/subscribers/RecentExportsDialog.vue'
import type { SegmentFilter } from '~/types/subscriber'
import { defaultFilter } from '~/types/subscriber'

definePageMeta({ layout: 'default' })

const { t } = useI18n()
const route = useRoute()
const localePath = useLocalePath()
const store = useSubscribersStore()

const projectId = computed(() => String(route.params.projectId))
const exportOpen = ref(false)
const recentExportsOpen = ref(false)

// Client-only initial fetch — mirrors pages/projects/index.vue lifecycle. The store holds no SSR state
// (no persistence), so SSR would just throw away the result.
if (import.meta.client) {
  store.loadFirstPage(defaultFilter()).catch((err) => console.warn('[subscribers] initial load failed', err))
}

function onFilter(filter: SegmentFilter) {
  store.loadFirstPage(filter).catch((err) => console.warn('[subscribers] filter load failed', err))
}
function onSelect(subscriberId: string) {
  navigateTo(localePath(`/projects/${projectId.value}/subscribers/${subscriberId}`))
}
function onLoadMore() {
  store.loadMore().catch((err) => console.warn('[subscribers] load more failed', err))
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between gap-4">
      <h1 class="text-2xl font-semibold">{{ t('subscribers.title') }}</h1>
      <div class="flex items-center gap-2">
        <button
          type="button"
          data-test="subscribers-export-trigger"
          class="rounded-md border border-blue-600 px-4 py-2 text-sm font-medium text-blue-700 hover:bg-blue-50"
          @click="exportOpen = true"
        >
          {{ t('subscribers.actions.export') }}
        </button>
        <!-- TASK-11 RecentExportsDialog mount -->
        <button
          type="button"
          data-test="subscribers-recent-exports-trigger"
          class="rounded-md border px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
          @click="recentExportsOpen = true"
        >
          {{ t('exports.recentDialog.title') }}
        </button>
        <!-- END TASK-11 RecentExportsDialog mount -->
      </div>
    </div>

    <SubscribersFilterBar :project-id="projectId" @update:filter="onFilter" />

    <SubscribersTable :items="store.items" @select="onSelect" />

    <div v-if="store.nextCursor" class="flex justify-center">
      <button
        type="button"
        data-test="subscribers-load-more"
        :disabled="store.loading"
        class="rounded-md border px-4 py-2 text-sm hover:bg-gray-50 disabled:opacity-50"
        @click="onLoadMore"
      >
        {{ t('subscribers.loadMore') }}
      </button>
    </div>

    <ExportCsvDialog v-model:open="exportOpen" :project-id="projectId" :filter="store.filter" />
    <!-- TASK-11 RecentExportsDialog mount -->
    <RecentExportsDialog v-model:open="recentExportsOpen" :project-id="projectId" />
    <!-- END TASK-11 RecentExportsDialog mount -->
  </div>
</template>
