<script setup lang="ts">
definePageMeta({ layout: 'default' })

const { t } = useI18n()
const authStore = useAuthStore()
</script>

<template>
  <div class="space-y-6">
    <div
      v-if="authStore.isPending"
      data-test="pending-banner"
      class="rounded-md border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 flex items-center gap-3"
    >
      <span class="flex-1">
        {{ t('dashboard.pendingBanner') }}
      </span>
      <NuxtLinkLocale
        to="/profile"
        class="font-medium text-amber-900 underline hover:no-underline whitespace-nowrap"
      >
        {{ t('dashboard.goToProfile') }}
      </NuxtLinkLocale>
    </div>

    <h1 class="text-2xl font-semibold">
      {{ authStore.user?.name
        ? t('dashboard.welcomeWithName', { name: authStore.user.name })
        : t('dashboard.welcomeAnon') }}
    </h1>
    <p class="text-sm text-gray-600">
      {{ t('dashboard.description') }}
    </p>
  </div>
</template>
