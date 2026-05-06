<script setup lang="ts">
definePageMeta({ layout: 'default' })

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
        Підтвердіть email для повного доступу.
      </span>
      <NuxtLink
        to="/profile"
        class="font-medium text-amber-900 underline hover:no-underline whitespace-nowrap"
      >
        Перейти до профілю →
      </NuxtLink>
    </div>

    <h1 class="text-2xl font-semibold">
      Ласкаво просимо{{ authStore.user?.name ? `, ${authStore.user.name}` : '' }}
    </h1>
    <p class="text-sm text-gray-600">
      Це ваш дашборд. Більше функцій з'явиться у наступних епіках.
    </p>
  </div>
</template>
