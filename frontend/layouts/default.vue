<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'

const authStore = useAuthStore()

async function onLogout() {
  try {
    await authStore.logout()
  } catch {
    // logout() always clears local user state via finally; ignore network errors so we always redirect.
  }
  await navigateTo('/auth/login')
}
</script>

<template>
  <div class="min-h-screen flex flex-col">
    <header class="flex items-center justify-between px-6 py-3 border-b">
      <NuxtLink to="/dashboard" class="font-semibold text-lg">Bot Funnel</NuxtLink>
      <div class="flex items-center gap-3">
        <span v-if="authStore.user" class="text-sm">
          {{ authStore.user.name }} ({{ authStore.user.email }})
        </span>
        <button
          type="button"
          class="px-3 py-1 text-sm border rounded hover:bg-gray-50"
          @click="onLogout"
        >
          Logout
        </button>
      </div>
    </header>

    <div class="flex flex-1">
      <aside class="w-56 border-r p-4">
        <nav class="flex flex-col gap-2">
          <NuxtLink to="/dashboard" class="text-sm hover:underline">Dashboard</NuxtLink>
          <NuxtLink to="/profile" class="text-sm hover:underline">Profile</NuxtLink>
        </nav>
      </aside>

      <main class="flex-1 p-6">
        <slot />
      </main>
    </div>
  </div>
</template>
