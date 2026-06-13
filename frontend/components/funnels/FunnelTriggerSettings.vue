<script setup lang="ts">
// Portable trigger form (Decision 11): a self-contained trigger-TYPE selector + per-type value editor.
// Authored state flows in/out via defineModel only (no store coupling beyond the lazy tag/field fetch),
// so a future canvas editor can mount this unchanged as a node form — only the list-page wrapper is
// throwaway. Phase 3 supports five entry types: on_start, keyword, tag_added, custom_field_set, event
// (UI label "api-event", Decision 4). Each type renders the value editor it needs:
//   on_start         → triggerValue text input + live deep-link preview/copy (unchanged from Phase 1)
//   keyword          → an add/remove chip list bound to the `keywords` model (does NOT use triggerValue)
//   tag_added        → tag SearchableSelect over the project's tags     (match key → triggerValue)
//   custom_field_set → field SearchableSelect over the project's fields (match key → triggerValue)
//   event            → an event-name slug input                         (match key → triggerValue)
import type { FunnelTriggerType } from '~/types/funnel'
import type { CustomFieldDefinition, Tag } from '~/types/subscriber'
import SearchableSelect from '~/components/funnels/SearchableSelect.vue'

// lockType pins the trigger-TYPE selector (disabled, no change emitted). Used by FunnelTriggersPanel for
// the on_start "main entry" slot and each event row, where the panel — not this form — owns the type.
const props = defineProps<{
  botUsername?: string | null
  deepLink?: string | null
  lockType?: boolean
}>()
const triggerType = defineModel<FunnelTriggerType>('triggerType', { default: 'on_start' })
const triggerValue = defineModel<string>('triggerValue', { default: '' })
const keywords = defineModel<string[]>('keywords', { default: () => [] })

const { t } = useI18n()
const route = useRoute()
const projectId = computed(() => String(route.params.projectId))

// The five trigger types in author-facing order. on_start first (the default + most common entry path).
const TRIGGER_TYPES: FunnelTriggerType[] = ['on_start', 'keyword', 'tag_added', 'custom_field_set', 'event']

// CANONICAL — MUST match backend normalizeTriggerValue TRIGGER_VALUE_PATTERN byte-for-byte. Empty allowed
// (bare /start); spaces/specials would break the t.me deep-link, so they are rejected client-side too.
const TRIGGER_VALUE_RE = /^[A-Za-z0-9_-]{0,64}$/
// CANONICAL event-name slug — MUST mirror the backend event_name @Pattern ^[A-Za-z0-9_-]{1,64}$. This is a
// UX mirror only (a bad value still surfaces as a server 422 on save); the client check guides the author.
const EVENT_NAME_RE = /^[A-Za-z0-9_-]{1,64}$/

const valueError = computed(() =>
  TRIGGER_VALUE_RE.test(triggerValue.value ?? '') ? null : t('funnels.trigger.valuePattern'),
)
const eventNameError = computed(() =>
  EVENT_NAME_RE.test(triggerValue.value ?? '') ? null : t('funnels.trigger.eventNamePattern'),
)

// ── Live deep-link (on_start only) ───────────────────────────────────────────────────────────────────
// Prefer composing from the bot username (updates as the user types). Fall back to the server deepLink
// (active funnel, no bot username at hand). Empty trigger_value → bare t.me/<bot> link WITHOUT ?start=.
const link = computed<string | null>(() => {
  // Never offer a copyable link for a value that fails the pattern — it would yield a broken deep-link.
  if (valueError.value) return null
  if (props.botUsername) {
    const base = `t.me/${props.botUsername}`
    return triggerValue.value ? `${base}?start=${triggerValue.value}` : base
  }
  return props.deepLink ?? null
})
// A bot is reachable for previewing when we have either its username or a server-built deepLink. Used to
// distinguish "no bot connected" from "bot connected but the current value is invalid" (link suppressed).
const hasBot = computed(() => !!(props.botUsername || props.deepLink))

const copied = ref(false)
let copyTimer: ReturnType<typeof setTimeout> | null = null
async function copy() {
  if (!link.value) return
  await navigator.clipboard.writeText(link.value)
  copied.value = true
  if (copyTimer) clearTimeout(copyTimer)
  copyTimer = setTimeout(() => {
    copied.value = false
  }, 1500)
}

// ── Keyword chip list ────────────────────────────────────────────────────────────────────────────────
// Local draft for the add-input; on add it is lowercase-normalized (mirrors backend keyword normalization)
// and de-duplicated, then pushed to the `keywords` model. Empty/blank input is a no-op.
const keywordDraft = ref('')
function addKeyword() {
  const word = keywordDraft.value.trim().toLowerCase()
  if (!word) return
  if ((keywords.value ?? []).includes(word)) {
    keywordDraft.value = ''
    return
  }
  keywords.value = [...(keywords.value ?? []), word]
  keywordDraft.value = ''
}
function removeKeyword(index: number) {
  keywords.value = (keywords.value ?? []).filter((_, i) => i !== index)
}

// ── Tag / field pickers (lazy-fetched the same way FunnelStepForm does) ───────────────────────────────
// Strict pick over existing project entities; network/permission failure → empty list (the select shows
// its empty-state), never blocks the editor (mirrors the step form's error-swallow idiom).
const tags = ref<Tag[]>([])
const tagsLoading = ref(false)
let tagsRequested = false
async function ensureTagsLoaded() {
  if (tagsRequested) return
  tagsRequested = true
  tagsLoading.value = true
  try {
    tags.value = (await useApi()<Tag[]>(`/api/v1/projects/${projectId.value}/tags`)) ?? []
  } catch {
    // swallow — empty list, no block
  } finally {
    tagsLoading.value = false
  }
}

const definitions = ref<CustomFieldDefinition[]>([])
const cfLoading = ref(false)
let cfRequested = false
async function ensureDefinitionsLoaded() {
  if (cfRequested) return
  cfRequested = true
  cfLoading.value = true
  try {
    definitions.value =
      (await useApi()<CustomFieldDefinition[]>(`/api/v1/projects/${projectId.value}/custom-fields`)) ?? []
  } catch {
    // swallow — empty list, no block
  } finally {
    cfLoading.value = false
  }
}

watch(
  triggerType,
  (ty) => {
    if (ty === 'tag_added') ensureTagsLoaded()
    if (ty === 'custom_field_set') ensureDefinitionsLoaded()
  },
  { immediate: true },
)

const tagOptions = computed(() => tags.value.map((tg) => ({ value: tg.slug, label: tg.label ?? tg.slug })))
const cfOptions = computed(() =>
  definitions.value.map((d) => ({ value: d.name, label: d.label, hint: d.type })),
)

onBeforeUnmount(() => {
  if (copyTimer) clearTimeout(copyTimer)
})
</script>

<template>
  <section data-test="funnel-trigger" class="space-y-3 rounded-md border px-4 py-3">
    <h2 class="text-sm font-semibold">{{ t('funnels.trigger.title') }}</h2>

    <div>
      <label for="funnel-trigger-type" class="block text-sm font-medium mb-1">{{ t('funnels.trigger.typeLabel') }}</label>
      <select
        id="funnel-trigger-type"
        v-model="triggerType"
        data-test="funnel-trigger-type-select"
        :disabled="props.lockType"
        class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:cursor-not-allowed disabled:bg-gray-100"
      >
        <option v-for="ty in TRIGGER_TYPES" :key="ty" :value="ty">{{ t(`funnels.trigger.type.${ty}`) }}</option>
      </select>
    </div>

    <!-- on_start: trigger value + live deep-link preview/copy (Phase 1 behavior, unchanged). -->
    <template v-if="triggerType === 'on_start'">
      <div>
        <label for="funnel-trigger-value" class="block text-sm font-medium mb-1">{{ t('funnels.trigger.valueLabel') }}</label>
        <input
          id="funnel-trigger-value"
          v-model="triggerValue"
          data-test="funnel-trigger-value-input"
          type="text"
          autocomplete="off"
          :placeholder="t('funnels.trigger.valuePlaceholder')"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p class="mt-1 text-xs text-gray-500">{{ t('funnels.trigger.valueHint') }}</p>
        <p v-if="valueError" data-test="funnel-trigger-value-error" class="mt-1 text-sm text-red-600">{{ valueError }}</p>
      </div>

      <div v-if="link">
        <label class="block text-sm font-medium mb-1">{{ t('funnels.trigger.deepLinkLabel') }}</label>
        <div class="flex items-center gap-2">
          <code data-test="funnel-trigger-deeplink" class="min-w-0 flex-1 truncate rounded bg-gray-100 px-2 py-1 text-sm">{{ link }}</code>
          <button
            type="button"
            data-test="funnel-trigger-copy"
            class="rounded-md border px-2.5 py-1 text-sm hover:bg-gray-50"
            @click="copy"
          >
            {{ copied ? t('funnels.trigger.copied') : t('funnels.trigger.copy') }}
          </button>
        </div>
      </div>
      <!-- Only when genuinely no bot is connected — NOT when a bot is connected but the value is invalid
           (the link is suppressed then, and the value-pattern error above already explains why). -->
      <p v-else-if="!hasBot" data-test="funnel-trigger-no-bot" class="text-sm text-gray-500">{{ t('funnels.trigger.noBot') }}</p>
    </template>

    <!-- keyword: an add/remove chip list. Owns the `keywords` model — does NOT use triggerValue. -->
    <template v-else-if="triggerType === 'keyword'">
      <div data-test="funnel-trigger-keyword">
        <label for="funnel-trigger-keyword" class="block text-sm font-medium mb-1">{{ t('funnels.trigger.keywordLabel') }}</label>
        <div class="flex gap-2">
          <input
            id="funnel-trigger-keyword"
            v-model="keywordDraft"
            data-test="funnel-trigger-keyword-input"
            type="text"
            autocomplete="off"
            :placeholder="t('funnels.trigger.keywordPlaceholder')"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            @keydown.enter.prevent="addKeyword"
          />
          <button
            type="button"
            data-test="funnel-trigger-keyword-add"
            class="rounded-md border px-3 py-2 text-sm hover:bg-gray-50"
            @click="addKeyword"
          >
            {{ t('funnels.trigger.keywordAdd') }}
          </button>
        </div>
        <p class="mt-1 text-xs text-gray-500">{{ t('funnels.trigger.keywordHint') }}</p>
        <ul v-if="keywords.length" data-test="funnel-trigger-keyword-list" class="mt-2 flex flex-wrap gap-2">
          <li
            v-for="(word, index) in keywords"
            :key="word"
            :data-test="`funnel-trigger-keyword-chip-${index}`"
            class="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2.5 py-1 text-sm"
          >
            <span>{{ word }}</span>
            <button
              type="button"
              :data-test="`funnel-trigger-keyword-remove-${index}`"
              :aria-label="t('funnels.trigger.keywordRemove')"
              class="text-gray-500 hover:text-red-600"
              @click="removeKeyword(index)"
            >
              ×
            </button>
          </li>
        </ul>
      </div>
    </template>

    <!-- tag_added: strict pick over the project's tags (match key → triggerValue). -->
    <template v-else-if="triggerType === 'tag_added'">
      <div>
        <label for="funnel-trigger-tag" class="block text-sm font-medium mb-1">{{ t('funnels.trigger.tagLabel') }}</label>
        <SearchableSelect
          id="funnel-trigger-tag"
          v-model="triggerValue"
          :options="tagOptions"
          :loading="tagsLoading"
          test-prefix="funnel-trigger-tag"
          :placeholder="t('funnels.trigger.tagPlaceholder')"
          :loading-text="t('funnels.steps.form.tagLoading')"
          :empty-text="t('funnels.steps.form.tagEmpty')"
          :no-matches-text="t('funnels.steps.form.tagNoMatches')"
        />
        <p class="mt-1 text-xs text-gray-500">{{ t('funnels.trigger.tagHint') }}</p>
      </div>
    </template>

    <!-- custom_field_set: strict pick over the project's custom fields (match key → triggerValue). -->
    <template v-else-if="triggerType === 'custom_field_set'">
      <div>
        <label for="funnel-trigger-field" class="block text-sm font-medium mb-1">{{ t('funnels.trigger.fieldLabel') }}</label>
        <SearchableSelect
          id="funnel-trigger-field"
          v-model="triggerValue"
          :options="cfOptions"
          :loading="cfLoading"
          test-prefix="funnel-trigger-field"
          :placeholder="t('funnels.trigger.fieldPlaceholder')"
          :loading-text="t('funnels.steps.form.customFieldKeyLoading')"
          :empty-text="t('funnels.steps.form.customFieldKeyEmpty')"
          :no-matches-text="t('funnels.steps.form.customFieldKeyNoMatches')"
        />
        <p class="mt-1 text-xs text-gray-500">{{ t('funnels.trigger.fieldHint') }}</p>
      </div>
    </template>

    <!-- event (UI "api-event"): an event-name slug input (match key → triggerValue). -->
    <template v-else-if="triggerType === 'event'">
      <div>
        <label for="funnel-trigger-event" class="block text-sm font-medium mb-1">{{ t('funnels.trigger.eventNameLabel') }}</label>
        <input
          id="funnel-trigger-event"
          v-model="triggerValue"
          data-test="funnel-trigger-event-input"
          type="text"
          autocomplete="off"
          :placeholder="t('funnels.trigger.eventNamePlaceholder')"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p class="mt-1 text-xs text-gray-500">{{ t('funnels.trigger.eventNameHint') }}</p>
        <p v-if="eventNameError" data-test="funnel-trigger-event-error" class="mt-1 text-sm text-red-600">{{ eventNameError }}</p>
      </div>
    </template>
  </section>
</template>
