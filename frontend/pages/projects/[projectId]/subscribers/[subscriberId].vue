<script setup lang="ts">
import { toast } from 'vue-sonner'
import SubscriberProfileCard from '~/components/subscribers/SubscriberProfileCard.vue'
import SubscriberTagsTab from '~/components/subscribers/SubscriberTagsTab.vue'
import SubscriberCustomFieldsTab from '~/components/subscribers/SubscriberCustomFieldsTab.vue'
import SubscriberHistoryTab from '~/components/subscribers/SubscriberHistoryTab.vue'
import SendPersonalMessageDialog from '~/components/subscribers/SendPersonalMessageDialog.vue'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '~/components/ui/tabs'
import type { Subscriber } from '~/types/subscriber'

definePageMeta({ layout: 'default' })

const { t } = useI18n()
const route = useRoute()
const localePath = useLocalePath()
const resolveError = useApiError()

const projectId = computed(() => String(route.params.projectId))
const subscriberId = computed(() => String(route.params.subscriberId))

const subscriber = ref<Subscriber | null>(null)
const sendOpen = ref(false)
// Deep-linkable active tab via URL hash (#tags / #custom-fields / #history).
const activeTab = ref((route.hash || '#tags').replace('#', ''))
watch(activeTab, (v) => {
  if (import.meta.client) history.replaceState(history.state, '', `#${v}`)
})

function statusOf(err: unknown): number | null {
  const e = err as { statusCode?: number; status?: number; response?: { status?: number } }
  return e?.statusCode ?? e?.status ?? e?.response?.status ?? null
}

function listPath(): string {
  return localePath(`/projects/${projectId.value}/subscribers`)
}

async function load() {
  try {
    subscriber.value = await useApi()<Subscriber>(
      `/api/v1/projects/${projectId.value}/subscribers/${subscriberId.value}`,
    )
  } catch (err) {
    // 404 → the subscriber (or project) is gone; mirror the project-scoped redirect convention
    // (patterns.md:169). The errors.subscribers.unavailable banner key was NOT seeded by Task 2, so we
    // redirect without writing pendingBannerKey (do not invent the key here).
    if (statusOf(err) === 404) {
      await navigateTo(listPath())
      return
    }
    console.warn('[subscribers] failed to load profile', err)
  }
}
if (import.meta.client) load()

async function onUnsubscribe() {
  try {
    subscriber.value = await useApi()<Subscriber>(
      `/api/v1/projects/${projectId.value}/subscribers/${subscriberId.value}/unsubscribe`,
      { method: 'POST' },
    )
  } catch (err) {
    toast.error(resolveError(err, 'subscribers.unsubscribe') || t('errors.generic'))
  }
}
</script>

<template>
  <div v-if="subscriber" class="space-y-6">
    <NuxtLinkLocale :to="listPath()" class="text-sm text-blue-600 hover:underline">
      ← {{ t('subscribers.title') }}
    </NuxtLinkLocale>

    <SubscriberProfileCard
      :subscriber="subscriber"
      @unsubscribe="onUnsubscribe"
      @send-message="sendOpen = true"
    />

    <Tabs v-model="activeTab">
      <TabsList>
        <TabsTrigger value="tags" data-test="subscriber-tab-tags">
          {{ t('subscribers.profile.tabs.tags') }}
        </TabsTrigger>
        <TabsTrigger value="custom-fields" data-test="subscriber-tab-custom-fields">
          {{ t('subscribers.profile.tabs.customFields') }}
        </TabsTrigger>
        <TabsTrigger value="history" data-test="subscriber-tab-history">
          {{ t('subscribers.profile.tabs.history') }}
        </TabsTrigger>
      </TabsList>

      <TabsContent value="tags" class="pt-4">
        <SubscriberTagsTab
          :project-id="projectId"
          :subscriber-id="subscriberId"
          :tags="subscriber.tags"
          @refresh="load"
        />
      </TabsContent>
      <TabsContent value="custom-fields" class="pt-4">
        <SubscriberCustomFieldsTab
          :project-id="projectId"
          :subscriber-id="subscriberId"
          :custom-fields="subscriber.customFields"
          @refresh="load"
        />
      </TabsContent>
      <TabsContent value="history" class="pt-4">
        <SubscriberHistoryTab :project-id="projectId" :subscriber-id="subscriberId" />
      </TabsContent>
    </Tabs>

    <SendPersonalMessageDialog
      v-model:open="sendOpen"
      :project-id="projectId"
      :subscriber-id="subscriberId"
      @refresh="load"
    />
  </div>
</template>
