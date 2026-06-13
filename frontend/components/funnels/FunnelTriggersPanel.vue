<script setup lang="ts">
// Vertical list of the funnel's entry triggers (Phase 8 — 17-funnel-multi-entry). Clones the add/delete/
// select structure of FunnelStepsList.vue. on_start is the funnel's MAIN entry (always step 1) — it is
// shown read-only at the top and edited through the existing FunnelTriggerSettings form, not the event
// list. Below it the author manages N `event` triggers: each row mounts FunnelTriggerSettings (the portable
// trigger form, Decision 11) plus a per-trigger entry-step SearchableSelect built over the funnel's OWN
// steps (passed via the `steps` prop — NOT an async fetch like FunnelStepForm.subscribeEntryOptions).
//
// Mid-entry is event-only (Decision 10): the backend REQUIRES a non-null entryStepId resolving to a step
// for `event` and rejects it for every other type. So the "from start" sentinel for an event trigger maps
// to the FIRST step's id (not null) — see entryToModel/modelToEntry. A duplicate event_name inside the
// funnel is flagged with an advisory error (the server 422 is the real backstop — Decision 13/14).
//
// The whole array is emitted via `update:triggers` on every mutation (full-replace, mirroring the PATCH
// contract). Task 7 mounts this with `v-model:triggers` and drives the autosave.
import type { FunnelStep, FunnelTrigger, FunnelTriggerType } from '~/types/funnel'
import FunnelTriggerSettings from '~/components/funnels/FunnelTriggerSettings.vue'
import SearchableSelect from '~/components/funnels/SearchableSelect.vue'

const props = defineProps<{
  triggers: FunnelTrigger[]
  steps: FunnelStep[]
  botUsername?: string | null
  deepLink?: string | null
}>()

const emit = defineEmits<{
  'update:triggers': [triggers: FunnelTrigger[]]
  add: []
  delete: [index: number]
  select: [index: number]
}>()

const { t } = useI18n()

// Sentinel = "entry at the first step". Same constant-semantics as FunnelStepForm.SUBSCRIBE_ENTRY_START.
const ENTRY_START = '__START__'

// Index of the lone on_start trigger (the main entry) and the event triggers shown in the list. on_start
// always enters at step 1 and is never deletable from this panel — it is the funnel's main entry.
const onStartIndex = computed(() => props.triggers.findIndex((tr) => tr.triggerType === 'on_start'))
const eventEntries = computed(() =>
  props.triggers
    .map((tr, index) => ({ tr, index }))
    .filter(({ tr }) => tr.triggerType === 'event'),
)

const selectedIndex = ref<number | null>(null)
const confirmIndex = ref<number | null>(null)

// Entry-step options built from the funnel's OWN steps (prop, not async). Steps without an id (not yet
// persisted) are skipped — no stable target. The "from start" sentinel is always first. Copied byte-for-
// byte from FunnelStepForm.subscribeEntryOptions except for the prop source.
const entryOptions = computed(() => {
  const stepOptions = props.steps
    .map((s, position) => ({ s, position }))
    .filter(({ s }) => !!s.id)
    .map(({ s, position }) => ({
      value: s.id as string,
      label: `${position + 1}. ${t(`funnels.steps.type.${s.stepType}`)}`,
    }))
  return [{ value: ENTRY_START, label: t('funnels.triggersPanel.entryStart') }, ...stepOptions]
})

// The id of the first step (the resolution target for the "from start" sentinel on an event trigger).
const firstStepId = computed<string | null>(() => props.steps.find((s) => !!s.id)?.id ?? null)

// Map a stored entryStepId to the SearchableSelect model: null OR the first step's id → the sentinel
// (both mean "from start" for the author); any other id is shown verbatim.
function entryToModel(entryStepId: string | null | undefined): string {
  if (!entryStepId) return ENTRY_START
  if (entryStepId === firstStepId.value) return ENTRY_START
  return entryStepId
}
// Map the SearchableSelect model back to the stored entryStepId. The sentinel → the first step's id
// (mid-entry is event-only and the backend requires a non-null entry step). If the funnel has no saved
// step yet, fall back to null — the server 422 is the backstop and the author sees the advisory.
function modelToEntry(model: string): string | null {
  return model === ENTRY_START ? firstStepId.value : model
}

// Advisory duplicate-event-name guard: an event row is flagged when an EARLIER event row carries the same
// (case-preserving, trimmed) triggerValue — mirrors the backend's case-preserving duplicate rule, but is
// advisory only (does not gate save). The first occurrence is never flagged.
const duplicateIndices = computed(() => {
  const seen = new Set<string>()
  const dups = new Set<number>()
  for (const { tr, index } of eventEntries.value) {
    const key = (tr.triggerValue ?? '').trim()
    if (!key) continue
    if (seen.has(key)) dups.add(index)
    else seen.add(key)
  }
  return dups
})

// All mutations rebuild the whole array and emit it (full-replace). Pinia/parent owns the source of truth.
function replaceAt(index: number, next: FunnelTrigger): void {
  const copy = props.triggers.map((tr, i) => (i === index ? next : tr))
  emit('update:triggers', copy)
}

function addEvent(): void {
  const fresh: FunnelTrigger = {
    triggerType: 'event',
    triggerValue: '',
    keywords: null,
    // Default mid-entry to the first step (event requires a non-null entry step).
    entryStepId: firstStepId.value,
  }
  emit('update:triggers', [...props.triggers, fresh])
  emit('add')
  selectedIndex.value = props.triggers.length // the new row's index
}

function askDelete(index: number): void {
  confirmIndex.value = index
}
function confirmDelete(index: number): void {
  confirmIndex.value = null
  if (selectedIndex.value === index) selectedIndex.value = null
  emit('update:triggers', props.triggers.filter((_, i) => i !== index))
  emit('delete', index)
}

function selectRow(index: number): void {
  selectedIndex.value = index
  emit('select', index)
}

// FunnelTriggerSettings exposes triggerValue/keywords via v-model. The trigger TYPE is panel-owned and
// pinned (on_start for the main entry, event for each list row — both mounted with `lock-type`), so only
// value/keywords/entry changes flow back here, each replacing the whole trigger.
function onValueChange(index: number, value: string): void {
  replaceAt(index, { ...props.triggers[index], triggerValue: value })
}
function onKeywordsChange(index: number, value: string[]): void {
  replaceAt(index, { ...props.triggers[index], keywords: value })
}
function onEntryChange(index: number, model: string): void {
  replaceAt(index, { ...props.triggers[index], entryStepId: modelToEntry(model) })
}
</script>

<template>
  <div data-test="funnel-triggers" class="space-y-4">
    <!-- on_start: the funnel's main entry (always step 1). Edited via the portable trigger form. -->
    <section data-test="funnel-trigger-on-start" class="rounded-md border border-blue-200 bg-blue-50/40 px-3 py-2">
      <div class="mb-2 flex items-center justify-between">
        <span class="text-sm font-semibold">{{ t('funnels.triggersPanel.mainEntry') }}</span>
        <span class="text-xs text-gray-500">{{ t('funnels.triggersPanel.mainEntryHint') }}</span>
      </div>
      <FunnelTriggerSettings
        v-if="onStartIndex >= 0"
        lock-type
        :trigger-type="(props.triggers[onStartIndex].triggerType as FunnelTriggerType)"
        :trigger-value="props.triggers[onStartIndex].triggerValue ?? ''"
        :keywords="props.triggers[onStartIndex].keywords ?? []"
        :bot-username="props.botUsername"
        :deep-link="props.deepLink"
        @update:trigger-value="onValueChange(onStartIndex, $event)"
        @update:keywords="onKeywordsChange(onStartIndex, $event)"
      />
    </section>

    <!-- event triggers: the multi-entry list. -->
    <div>
      <div class="mb-2 flex items-center justify-between">
        <span class="text-sm font-semibold">{{ t('funnels.triggersPanel.eventTriggers') }}</span>
        <button
          type="button"
          data-test="funnel-trigger-add"
          class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
          @click="addEvent"
        >
          {{ t('funnels.triggersPanel.add') }}
        </button>
      </div>

      <div
        v-if="eventEntries.length === 0"
        data-test="funnel-triggers-empty"
        class="rounded-md border border-dashed bg-gray-50 px-4 py-8 text-center text-sm text-gray-600"
      >
        <span class="block font-medium">{{ t('funnels.triggersPanel.empty.title') }}</span>
        <span class="mt-1 block">{{ t('funnels.triggersPanel.empty.body') }}</span>
      </div>

      <ul v-else data-test="funnel-triggers-list" class="space-y-3">
        <li
          v-for="{ tr, index } in eventEntries"
          :key="index"
          :data-test="`funnel-trigger-row-${index}`"
          class="rounded-md border px-3 py-3"
          :class="selectedIndex === index ? 'ring-2 ring-blue-400 border-blue-400' : ''"
        >
          <div class="mb-2 flex items-center justify-between gap-3">
            <button
              type="button"
              :data-test="`funnel-trigger-select-${index}`"
              class="min-w-0 flex-1 text-left text-sm font-medium"
              @click="selectRow(index)"
            >
              {{ tr.triggerValue || t('funnels.triggersPanel.unnamedEvent') }}
            </button>

            <template v-if="confirmIndex === index">
              <span class="text-sm text-gray-600">{{ t('funnels.triggersPanel.confirmDelete') }}</span>
              <button
                type="button"
                :data-test="`funnel-trigger-delete-confirm-${index}`"
                class="rounded-md bg-red-600 px-2.5 py-1 text-sm text-white hover:bg-red-700"
                @click="confirmDelete(index)"
              >
                {{ t('funnels.triggersPanel.delete') }}
              </button>
              <button
                type="button"
                :data-test="`funnel-trigger-delete-cancel-${index}`"
                class="rounded-md border px-2.5 py-1 text-sm hover:bg-gray-50"
                @click="confirmIndex = null"
              >
                {{ t('common.cancel') }}
              </button>
            </template>
            <template v-else>
              <button
                type="button"
                :data-test="`funnel-trigger-delete-${index}`"
                class="rounded-md border border-red-300 px-2.5 py-1 text-sm text-red-700 hover:bg-red-50"
                @click="askDelete(index)"
              >
                {{ t('funnels.triggersPanel.delete') }}
              </button>
            </template>
          </div>

          <FunnelTriggerSettings
            lock-type
            :trigger-type="(tr.triggerType as FunnelTriggerType)"
            :trigger-value="tr.triggerValue ?? ''"
            :keywords="tr.keywords ?? []"
            :bot-username="props.botUsername"
            :deep-link="props.deepLink"
            @update:trigger-value="onValueChange(index, $event)"
            @update:keywords="onKeywordsChange(index, $event)"
          />

          <!-- per-trigger entry step (event-only mid-entry). -->
          <div class="mt-3">
            <label class="mb-1 block text-sm font-medium">{{ t('funnels.triggersPanel.entryLabel') }}</label>
            <SearchableSelect
              :model-value="entryToModel(tr.entryStepId)"
              :options="entryOptions"
              :test-prefix="`funnel-trigger-entry-${index}`"
              :show-value="false"
              :placeholder="t('funnels.triggersPanel.entryPlaceholder')"
              :loading-text="t('funnels.triggersPanel.entryLoading')"
              :empty-text="t('funnels.triggersPanel.entryEmpty')"
              :no-matches-text="t('funnels.triggersPanel.entryNoMatches')"
              @update:model-value="onEntryChange(index, $event)"
            />
            <p class="mt-1 text-xs text-gray-500">{{ t('funnels.triggersPanel.entryHint') }}</p>
          </div>

          <!-- advisory duplicate-event-name guard (server 422 is the real backstop). -->
          <p
            v-if="duplicateIndices.has(index)"
            :data-test="`funnel-trigger-duplicate-${index}`"
            class="mt-2 text-sm text-amber-600"
          >
            {{ t('funnels.triggersPanel.duplicateEventName') }}
          </p>
        </li>
      </ul>
    </div>
  </div>
</template>
