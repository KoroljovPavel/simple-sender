<script setup lang="ts">
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '~/components/ui/table'
import CreateTagDialog from '~/components/tags/CreateTagDialog.vue'
import RenameTagDialog from '~/components/tags/RenameTagDialog.vue'
import DeleteTagDialog from '~/components/tags/DeleteTagDialog.vue'
import type { Tag } from '~/types/subscriber'

definePageMeta({ layout: 'default' })

const { t } = useI18n()
const route = useRoute()
const localePath = useLocalePath()
const projectsStore = useProjectsStore()

const projectId = computed(() => String(route.params.projectId))
const tags = ref<Tag[]>([])

const createOpen = ref(false)
const renameOpen = ref(false)
const deleteOpen = ref(false)
const selected = ref<Tag | null>(null)

function statusOf(err: unknown): number | null {
  const e = err as { statusCode?: number; status?: number; response?: { status?: number } }
  return e?.statusCode ?? e?.status ?? e?.response?.status ?? null
}

// Project-scoped page redirect on missing resource (patterns.md:169). The list GET goes through
// requireOwned, so a 404 here means foreign/deleted/malformed project → bounce to /projects with the
// shared banner key (errors.projects.unavailable, seeded by Epic 05).
async function loadTags() {
  try {
    tags.value = await useApi()<Tag[]>(`/api/v1/projects/${projectId.value}/tags`)
  } catch (err) {
    if (statusOf(err) === 404) {
      if (!projectsStore.pendingBannerKey) projectsStore.pendingBannerKey = 'errors.projects.unavailable'
      await navigateTo(localePath('/projects'))
      return
    }
    console.warn('[tags] failed to load tags', err)
  }
}

onMounted(() => {
  if (import.meta.client) loadTags()
})

function openRename(tag: Tag) {
  selected.value = tag
  renameOpen.value = true
}
function openDelete(tag: Tag) {
  selected.value = tag
  deleteOpen.value = true
}
function formatDate(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString()
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between gap-4">
      <h1 class="text-2xl font-semibold">{{ t('tags.title') }}</h1>
      <button
        type="button"
        data-test="tags-create-trigger"
        class="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        @click="createOpen = true"
      >
        {{ t('tags.actions.create') }}
      </button>
    </div>

    <p
      v-if="tags.length === 0"
      data-test="tags-empty"
      class="rounded-md border border-dashed bg-gray-50 px-4 py-10 text-center text-sm text-gray-600"
    >
      <span class="block font-medium">{{ t('tags.empty.title') }}</span>
      <span class="mt-1 block">{{ t('tags.empty.body') }}</span>
    </p>

    <Table v-else data-test="tags-table">
      <TableHeader>
        <TableRow>
          <TableHead>{{ t('tags.columns.label') }}</TableHead>
          <TableHead>{{ t('tags.columns.slug') }}</TableHead>
          <TableHead>{{ t('tags.columns.subscriberCount') }}</TableHead>
          <TableHead>{{ t('tags.columns.created') }}</TableHead>
          <TableHead class="text-right">{{ t('tags.actions.rename') }}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow v-for="tag in tags" :key="tag.slug" :data-test="`tag-row-${tag.slug}`">
          <TableCell class="font-medium">{{ tag.label }}</TableCell>
          <TableCell><code class="rounded bg-gray-100 px-1.5 py-0.5 text-xs">{{ tag.slug }}</code></TableCell>
          <TableCell>{{ tag.subscriberCount }}</TableCell>
          <TableCell class="text-sm text-gray-600">{{ formatDate(tag.createdAt) }}</TableCell>
          <TableCell class="space-x-2 text-right">
            <button
              type="button"
              :data-test="`tag-rename-${tag.slug}`"
              class="rounded-md border px-2.5 py-1 text-sm hover:bg-gray-50"
              @click="openRename(tag)"
            >
              {{ t('tags.actions.rename') }}
            </button>
            <button
              type="button"
              :data-test="`tag-delete-${tag.slug}`"
              class="rounded-md border border-red-300 px-2.5 py-1 text-sm text-red-700 hover:bg-red-50"
              @click="openDelete(tag)"
            >
              {{ t('tags.actions.delete') }}
            </button>
          </TableCell>
        </TableRow>
      </TableBody>
    </Table>

    <CreateTagDialog v-model:open="createOpen" :project-id="projectId" @created="loadTags" />
    <RenameTagDialog
      v-if="selected"
      v-model:open="renameOpen"
      :project-id="projectId"
      :tag="selected"
      @updated="loadTags"
    />
    <DeleteTagDialog
      v-if="selected"
      v-model:open="deleteOpen"
      :project-id="projectId"
      :tag="selected"
      @deleted="loadTags"
    />
  </div>
</template>
