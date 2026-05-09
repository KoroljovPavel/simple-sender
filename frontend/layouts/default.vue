<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'

const authStore = useAuthStore()
const { t } = useI18n()
const localePath = useLocalePath()

async function onLogout() {
  try {
    await authStore.logout()
  } catch {
    // logout() always clears local user state via finally; ignore network errors so we always redirect.
  }
  await navigateTo(localePath('/auth/login'))
}
</script>

<template>
  <div class="min-h-screen flex flex-col">
    <header class="flex items-center justify-between px-6 py-3 border-b">
      <NuxtLinkLocale to="/dashboard" class="font-semibold text-lg">{{ t('brand.name') }}</NuxtLinkLocale>
      <div class="flex items-center gap-3">
        <span v-if="authStore.user" class="text-sm">
          {{ authStore.user.name ? `${authStore.user.name} (${authStore.user.email})` : authStore.user.email }}
        </span>
        <button
          type="button"
          class="px-3 py-1 text-sm border rounded hover:bg-gray-50"
          @click="onLogout"
        >
          {{ t('layout.logout') }}
        </button>
        <LangSwitcher />
      </div>
    </header>

    <div class="flex flex-1">
      <aside class="w-56 border-r p-4">
        <nav class="flex flex-col gap-2">
          <NuxtLinkLocale to="/dashboard" class="text-sm hover:underline">{{ t('layout.dashboard') }}</NuxtLinkLocale>
          <NuxtLinkLocale to="/profile" class="text-sm hover:underline">{{ t('layout.profile') }}</NuxtLinkLocale>
        </nav>
      </aside>

      <main class="flex-1 p-6">
        <slot />
      </main>
    </div>
  </div>
</template>
