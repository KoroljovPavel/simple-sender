<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'

// Root has no landing page yet, so a bare "/" must not 404. auth.global.ts has already run
// fetchUser() (and redirects unauthenticated visitors to /auth/login before this resolves), so by
// here authStore.user is populated: authenticated users land on their dashboard. layout:false keeps
// the default layout (and its project hydration) from mounting for a view that only redirects.
definePageMeta({ layout: false })

const authStore = useAuthStore()
const localePath = useLocalePath()

await navigateTo(localePath(authStore.user ? '/dashboard' : '/auth/login'), { replace: true })
</script>

<template>
  <div />
</template>
