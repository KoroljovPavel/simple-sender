<script setup lang="ts">
import { toast } from 'vue-sonner'
import type { Tag } from '~/types/subscriber'

const props = defineProps<{ projectId: string; subscriberId: string; tags: string[] }>()
const emit = defineEmits<{ refresh: [] }>()

const { t } = useI18n()
const resolveError = useApiError()

const SLUG_RE = /^[a-z0-9_-]{1,32}$/

const draft = ref('')
const busy = ref(false)
const availableTags = ref<Tag[]>([])

// Normalized candidate slug — combobox is "create-or-attach", so we lowercase/space-collapse the raw
// input before the existing-tag match + POST. Backend find-or-create (TagService.findOrCreate) means a
// new slug is created implicitly; we never need a separate POST /tags call.
const candidate = computed(() => draft.value.trim().toLowerCase().replace(/\s+/g, '-'))
const canAdd = computed(
  () => !busy.value && SLUG_RE.test(candidate.value) && !props.tags.includes(candidate.value),
)

onMounted(async () => {
  try {
    availableTags.value = await useApi()<Tag[]>(`/api/v1/projects/${props.projectId}/tags`)
  } catch (err) {
    console.warn('[subscribers] failed to load project tags', err)
  }
})

async function addTag() {
  if (!canAdd.value) return
  busy.value = true
  try {
    await useApi()(`/api/v1/projects/${props.projectId}/subscribers/${props.subscriberId}/tags`, {
      method: 'POST',
      body: { slug: candidate.value },
    })
    draft.value = ''
    emit('refresh')
  } catch (err) {
    toast.error(resolveError(err, 'subscribers.tags') || t('errors.generic'))
  } finally {
    busy.value = false
  }
}

async function removeTag(slug: string) {
  if (busy.value) return
  busy.value = true
  try {
    await useApi()(
      `/api/v1/projects/${props.projectId}/subscribers/${props.subscriberId}/tags/${slug}`,
      { method: 'DELETE' },
    )
    emit('refresh')
  } catch (err) {
    toast.error(resolveError(err, 'subscribers.tags') || t('errors.generic'))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-wrap gap-2">
      <span
        v-for="slug in props.tags"
        :key="slug"
        :data-test="`subscriber-tag-chip-${slug}`"
        class="inline-flex items-center gap-1 rounded-full border border-gray-300 bg-gray-50 px-2.5 py-0.5 text-xs text-gray-700"
      >
        {{ slug }}
        <button
          type="button"
          :data-test="`subscriber-tag-remove-${slug}`"
          :aria-label="t('tags.actions.delete')"
          class="text-gray-400 hover:text-red-600"
          @click="removeTag(slug)"
        >×</button>
      </span>
      <span v-if="props.tags.length === 0" class="text-sm text-gray-500">{{ t('tags.empty.title') }}</span>
    </div>

    <form class="flex items-center gap-2" @submit.prevent="addTag">
      <input
        v-model="draft"
        data-test="subscriber-tags-combobox"
        list="subscriber-tags-suggestions"
        type="text"
        :placeholder="t('tags.createDialog.slugLabel')"
        class="rounded-md border px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
      />
      <datalist id="subscriber-tags-suggestions">
        <option v-for="tag in availableTags" :key="tag.slug" :value="tag.slug" />
      </datalist>
      <button
        type="button"
        data-test="subscriber-tag-add"
        :disabled="!canAdd"
        class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
        @click="addTag"
      >
        {{ t('tags.actions.create') }}
      </button>
    </form>
  </div>
</template>
