<script setup lang="ts">
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '~/components/ui/table'
import { Badge } from '~/components/ui/badge'
import type { Subscriber } from '~/types/subscriber'
import { statusBadgeClass } from '~/utils/subscriberStatus'

const props = defineProps<{ items: Subscriber[] }>()
const emit = defineEmits<{ select: [subscriberId: string] }>()

const { t } = useI18n()

function identity(s: Subscriber): string {
  const name = [s.firstName, s.lastName].filter(Boolean).join(' ').trim()
  if (name && s.username) return `${name} @${s.username}`
  if (name) return name
  if (s.username) return `@${s.username}`
  return s.id
}

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleDateString()
}
</script>

<template>
  <div>
    <div
      v-if="props.items.length === 0"
      data-test="subscribers-empty"
      class="rounded-md border border-dashed border-gray-300 bg-gray-50 px-6 py-10 text-center"
    >
      <p class="font-medium">{{ t('subscribers.empty.title') }}</p>
      <p class="mt-1 text-sm text-gray-600">{{ t('subscribers.empty.body') }}</p>
    </div>

    <Table v-else data-test="subscribers-table">
      <TableHeader>
        <TableRow>
          <TableHead>{{ t('subscribers.columns.firstName') }}</TableHead>
          <TableHead>{{ t('subscribers.columns.status') }}</TableHead>
          <TableHead>{{ t('subscribers.columns.tags') }}</TableHead>
          <TableHead>{{ t('subscribers.columns.subscribedAt') }}</TableHead>
          <TableHead>{{ t('subscribers.columns.lastSeen') }}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow
          v-for="s in props.items"
          :key="s.id"
          :data-test="`subscribers-row-${s.id}`"
          class="cursor-pointer"
          @click="emit('select', s.id)"
        >
          <TableCell class="font-medium">{{ identity(s) }}</TableCell>
          <TableCell>
            <Badge variant="outline" :class="statusBadgeClass(s.status)" :data-status="s.status">
              {{ t(`subscribers.status.${s.status}`) }}
            </Badge>
          </TableCell>
          <TableCell>
            <span
              v-for="tag in s.tags"
              :key="tag"
              class="mr-1 inline-block rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-700"
            >{{ tag }}</span>
          </TableCell>
          <TableCell>{{ formatDate(s.subscribedAt) }}</TableCell>
          <TableCell>{{ formatDate(s.lastSeenAt) }}</TableCell>
        </TableRow>
      </TableBody>
    </Table>
  </div>
</template>
