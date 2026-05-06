import type { RouteLocationNormalized } from 'vue-router'
import { useAuthStore } from '~/stores/auth'

function isPublicAuthRoute(path: string): boolean {
  return path.startsWith('/auth/')
}

export default defineNuxtRouteMiddleware(async (to: RouteLocationNormalized) => {
  const authStore = useAuthStore()

  if (authStore.user === null) {
    try {
      await authStore.fetchUser()
    } catch {
      // Backend unreachable or non-401 error — treat as unauthenticated.
      // The auth store only clears state on 401; transient errors leave it null.
    }
  }

  const publicAuth = isPublicAuthRoute(to.path)

  if (publicAuth && authStore.user !== null) {
    return navigateTo('/dashboard')
  }

  if (!publicAuth && authStore.user === null) {
    return navigateTo('/auth/login')
  }
})
