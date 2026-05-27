<script setup lang="ts">
import { Card, CardContent, CardHeader, CardTitle } from '~/components/ui/card'
import { Badge } from '~/components/ui/badge'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '~/components/ui/tooltip'
import type { Subscriber } from '~/types/subscriber'
import { statusBadgeClass } from '~/utils/subscriberStatus'

const props = defineProps<{ subscriber: Subscriber }>()
const emit = defineEmits<{ unsubscribe: []; 'send-message': [] }>()

const { t } = useI18n()

const isActive = computed(() => props.subscriber.status === 'active')
const fullName = computed(() =>
  [props.subscriber.firstName, props.subscriber.lastName].filter(Boolean).join(' ').trim(),
)

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString()
}

const lifecycle = computed(() => [
  { key: 'subscribedAt', value: props.subscriber.subscribedAt },
  { key: 'unsubscribedAt', value: props.subscriber.unsubscribedAt },
  { key: 'blockedAt', value: props.subscriber.blockedAt },
  { key: 'deletedAt', value: props.subscriber.deletedAt },
  { key: 'lastSeenAt', value: props.subscriber.lastSeenAt },
])
</script>

<template>
  <Card data-test="subscriber-profile-card">
    <CardHeader>
      <div class="flex items-start justify-between gap-4">
        <div class="min-w-0">
          <CardTitle class="truncate">
            {{ fullName || (subscriber.username ? `@${subscriber.username}` : t('subscribers.profile.identityCard.title')) }}
          </CardTitle>
          <p v-if="subscriber.username" class="text-sm text-gray-600">@{{ subscriber.username }}</p>
          <p v-if="subscriber.languageCode" class="text-xs text-gray-500">{{ subscriber.languageCode }}</p>
        </div>
        <Badge
          variant="outline"
          :class="statusBadgeClass(subscriber.status)"
          :data-status="subscriber.status"
          data-test="subscriber-status-badge"
        >
          {{ t(`subscribers.status.${subscriber.status}`) }}
        </Badge>
      </div>
    </CardHeader>

    <CardContent class="space-y-4">
      <dl class="grid grid-cols-2 gap-x-6 gap-y-1 text-sm">
        <template v-for="row in lifecycle" :key="row.key">
          <dt class="text-gray-500">{{ row.key }}</dt>
          <dd>{{ formatDate(row.value) }}</dd>
        </template>
      </dl>

      <div class="flex flex-wrap gap-2">
        <button
          type="button"
          data-test="subscriber-manual-unsubscribe"
          :disabled="!isActive"
          class="rounded-md border border-red-300 px-3 py-1.5 text-sm text-red-700 hover:bg-red-50 disabled:opacity-50 disabled:cursor-not-allowed"
          @click="emit('unsubscribe')"
        >
          {{ t('subscribers.profile.actions.unsubscribe') }}
        </button>

        <!-- Send is always visible; disabled (with explanatory tooltip) for non-active subscribers. -->
        <TooltipProvider :disable-hoverable-content="isActive">
          <Tooltip>
            <TooltipTrigger as-child>
              <button
                type="button"
                data-test="subscriber-send-message-trigger"
                :disabled="!isActive"
                class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
                @click="emit('send-message')"
              >
                {{ t('subscribers.profile.actions.sendMessage') }}
              </button>
            </TooltipTrigger>
            <TooltipContent v-if="!isActive">
              {{ t(`subscribers.status.${subscriber.status}`) }}
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
      </div>
    </CardContent>
  </Card>
</template>
