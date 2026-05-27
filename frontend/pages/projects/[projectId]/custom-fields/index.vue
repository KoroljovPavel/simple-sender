<script setup lang="ts">
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '~/components/ui/table'
import AddCustomFieldDialog from '~/components/customFields/AddCustomFieldDialog.vue'
import EditCustomFieldDialog from '~/components/customFields/EditCustomFieldDialog.vue'
import DeleteCustomFieldDialog from '~/components/customFields/DeleteCustomFieldDialog.vue'
import type { CustomFieldDefinition } from '~/types/subscriber'

definePageMeta({ layout: 'default' })

const { t } = useI18n()
const route = useRoute()
const localePath = useLocalePath()
const projectsStore = useProjectsStore()

const projectId = computed(() => String(route.params.projectId))
const fields = ref<CustomFieldDefinition[]>([])

const addOpen = ref(false)
const editOpen = ref(false)
const deleteOpen = ref(false)
const selected = ref<CustomFieldDefinition | null>(null)

function statusOf(err: unknown): number | null {
  const e = err as { statusCode?: number; status?: number; response?: { status?: number } }
  return e?.statusCode ?? e?.status ?? e?.response?.status ?? null
}

// Project-scoped page redirect on missing resource (patterns.md:169).
async function loadFields() {
  try {
    fields.value = await useApi()<CustomFieldDefinition[]>(
      `/api/v1/projects/${projectId.value}/custom-fields`,
    )
  } catch (err) {
    if (statusOf(err) === 404) {
      if (!projectsStore.pendingBannerKey) projectsStore.pendingBannerKey = 'errors.projects.unavailable'
      await navigateTo(localePath('/projects'))
      return
    }
    console.warn('[custom-fields] failed to load definitions', err)
  }
}

onMounted(() => {
  if (import.meta.client) loadFields()
})

function openEdit(field: CustomFieldDefinition) {
  selected.value = field
  editOpen.value = true
}
function openDelete(field: CustomFieldDefinition) {
  selected.value = field
  deleteOpen.value = true
}
// NOTE: the task lists a "Created" column for /custom-fields, but Task 2 seeded no
// `customFields.columns.created` i18n key and this task must not edit locales — column omitted
// (see decisions.md i18n gap).
function formatDefault(field: CustomFieldDefinition): string {
  const dv = field.defaultValue
  if (dv === null || dv === undefined || dv === '') return '—'
  if (field.type === 'BOOLEAN') return dv ? '✓' : '✕'
  return String(dv)
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between gap-4">
      <h1 class="text-2xl font-semibold">{{ t('customFields.title') }}</h1>
      <button
        type="button"
        data-test="custom-fields-add-trigger"
        class="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        @click="addOpen = true"
      >
        {{ t('customFields.actions.add') }}
      </button>
    </div>

    <p
      v-if="fields.length === 0"
      data-test="custom-fields-empty"
      class="rounded-md border border-dashed bg-gray-50 px-4 py-10 text-center text-sm text-gray-600"
    >
      <span class="block font-medium">{{ t('customFields.empty.title') }}</span>
      <span class="mt-1 block">{{ t('customFields.empty.body') }}</span>
    </p>

    <Table v-else data-test="custom-fields-table">
      <TableHeader>
        <TableRow>
          <TableHead>{{ t('customFields.columns.label') }}</TableHead>
          <TableHead>{{ t('customFields.columns.name') }}</TableHead>
          <TableHead>{{ t('customFields.columns.type') }}</TableHead>
          <TableHead>{{ t('customFields.columns.defaultValue') }}</TableHead>
          <TableHead class="text-right">{{ t('customFields.actions.edit') }}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow v-for="field in fields" :key="field.name" :data-test="`custom-field-row-${field.name}`">
          <TableCell class="font-medium">{{ field.label }}</TableCell>
          <TableCell><code class="rounded bg-gray-100 px-1.5 py-0.5 text-xs">{{ field.name }}</code></TableCell>
          <TableCell>{{ t(`customFields.type.${field.type.toLowerCase()}`) }}</TableCell>
          <TableCell>{{ formatDefault(field) }}</TableCell>
          <TableCell class="space-x-2 text-right">
            <button
              type="button"
              :data-test="`custom-field-edit-${field.name}`"
              class="rounded-md border px-2.5 py-1 text-sm hover:bg-gray-50"
              @click="openEdit(field)"
            >
              {{ t('customFields.actions.edit') }}
            </button>
            <button
              type="button"
              :data-test="`custom-field-delete-${field.name}`"
              class="rounded-md border border-red-300 px-2.5 py-1 text-sm text-red-700 hover:bg-red-50"
              @click="openDelete(field)"
            >
              {{ t('customFields.actions.delete') }}
            </button>
          </TableCell>
        </TableRow>
      </TableBody>
    </Table>

    <AddCustomFieldDialog v-model:open="addOpen" :project-id="projectId" @created="loadFields" />
    <EditCustomFieldDialog
      v-if="selected"
      v-model:open="editOpen"
      :project-id="projectId"
      :field="selected"
      @updated="loadFields"
    />
    <DeleteCustomFieldDialog
      v-if="selected"
      v-model:open="deleteOpen"
      :project-id="projectId"
      :field="selected"
      @deleted="loadFields"
    />
  </div>
</template>
