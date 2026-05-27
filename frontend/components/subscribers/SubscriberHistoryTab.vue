<script setup lang="ts">
import type { SubscriberEvent } from '~/types/subscriber'

const props = defineProps<{ projectId: string; subscriberId: string }>()

const { t } = useI18n()

const events = ref<SubscriberEvent[]>([])
const loaded = ref(false)

// Capped at 50, no scroll pagination (Risk R13). Sorted desc by createdAt defensively even though the
// backend already returns DESC.
onMounted(async () => {
  try {
    const rows = await useApi()<SubscriberEvent[]>(
      `/api/v1/projects/${props.projectId}/subscribers/${props.subscriberId}/events`,
      { query: { limit: 50 } },
    )
    events.value = [...rows].sort((a, b) => (a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0))
  } catch (err) {
    console.warn('[subscribers] failed to load history', err)
  } finally {
    loaded.value = true
  }
})

const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })
const UNITS: { unit: Intl.RelativeTimeFormatUnit; ms: number }[] = [
  { unit: 'year', ms: 365 * 24 * 3600 * 1000 },
  { unit: 'day', ms: 24 * 3600 * 1000 },
  { unit: 'hour', ms: 3600 * 1000 },
  { unit: 'minute', ms: 60 * 1000 },
  { unit: 'second', ms: 1000 },
]

function relative(iso: string): string {
  const diff = new Date(iso).getTime() - Date.now()
  if (Number.isNaN(diff)) return ''
  for (const { unit, ms } of UNITS) {
    if (Math.abs(diff) >= ms || unit === 'second') {
      return rtf.format(Math.round(diff / ms), unit)
    }
  }
  return ''
}
</script>

<template>
  <div>
    <p
      v-if="loaded && events.length === 0"
      data-test="subscriber-history-empty"
      class="px-4 py-6 text-center text-sm text-gray-500"
    >
      {{ t('subscribers.profile.history.empty') }}
    </p>

    <ul v-else class="divide-y divide-gray-100">
      <li
        v-for="event in events"
        :key="event.id"
        data-test="subscriber-history-row"
        class="flex items-center justify-between gap-4 py-2 text-sm"
      >
        <span class="font-medium text-gray-800">{{ event.eventType }}</span>
        <time :datetime="event.createdAt" class="text-xs text-gray-500">{{ relative(event.createdAt) }}</time>
      </li>
    </ul>
  </div>
</template>
