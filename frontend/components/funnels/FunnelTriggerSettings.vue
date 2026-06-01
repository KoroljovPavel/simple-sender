<script setup lang="ts">
// Trigger settings. Phase 1 fixes triggerType to on_start (the bot's /start command), so only the
// optional trigger_value is editable. It builds the deep-link preview live as the user types and offers a
// Copy button. For an ACTIVE funnel the canonical link comes from the server (`deepLink` prop, persists
// across reloads); for a draft we compose a preview from the project's bot username.
const props = defineProps<{ botUsername?: string | null; deepLink?: string | null }>()
const triggerValue = defineModel<string>('triggerValue', { default: '' })

const { t } = useI18n()

// CANONICAL — MUST match backend normalizeTriggerValue TRIGGER_VALUE_PATTERN byte-for-byte. Empty allowed
// (bare /start); spaces/specials would break the t.me deep-link, so they are rejected client-side too.
const TRIGGER_VALUE_RE = /^[A-Za-z0-9_-]{0,64}$/
const valueError = computed(() =>
  TRIGGER_VALUE_RE.test(triggerValue.value ?? '') ? null : t('funnels.trigger.valuePattern'),
)

// Live link: prefer composing from the bot username (updates as the user types). Fall back to the
// server deepLink (active funnel, no bot username at hand). Empty trigger_value → bare t.me/<bot> link
// WITHOUT ?start= (mirrors backend: a blank value yields plain /start).
const link = computed<string | null>(() => {
  // Never offer a copyable link for a value that fails the pattern — it would yield a broken deep-link.
  if (valueError.value) return null
  if (props.botUsername) {
    const base = `t.me/${props.botUsername}`
    return triggerValue.value ? `${base}?start=${triggerValue.value}` : base
  }
  return props.deepLink ?? null
})

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

onBeforeUnmount(() => {
  if (copyTimer) clearTimeout(copyTimer)
})
</script>

<template>
  <section data-test="funnel-trigger" class="space-y-3 rounded-md border px-4 py-3">
    <h2 class="text-sm font-semibold">{{ t('funnels.trigger.title') }}</h2>

    <div>
      <label class="block text-sm font-medium mb-1">{{ t('funnels.trigger.typeLabel') }}</label>
      <span data-test="funnel-trigger-type" class="inline-block rounded bg-gray-100 px-2 py-1 text-sm font-mono">
        {{ t('funnels.trigger.onStart') }}
      </span>
    </div>

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
    <p v-else data-test="funnel-trigger-no-bot" class="text-sm text-gray-500">{{ t('funnels.trigger.noBot') }}</p>
  </section>
</template>
