import type { RouteLocationNormalized } from 'vue-router'
import { useAuthStore } from '~/stores/auth'

const PUBLIC_ROUTE_NAMES = [
  'auth-login',
  'auth-register',
  'auth-forgot-password',
  'auth-reset-password',
  'auth-verify-email',
] as const

export default defineNuxtRouteMiddleware(async (to: RouteLocationNormalized) => {
  const authStore = useAuthStore()
  const getBaseName = useRouteBaseName()
  const localePath = useLocalePath()

  if (authStore.user === null) {
    try {
      await authStore.fetchUser()
    } catch {
      // Backend unreachable or non-401 error — treat as unauthenticated.
      // The auth store only clears state on 401; transient errors leave it null.
    }
  }

  const baseName = getBaseName(to)
  const isPublicAuth =
    baseName !== undefined &&
    (PUBLIC_ROUTE_NAMES as readonly string[]).includes(baseName)

  if (isPublicAuth && authStore.user !== null) {
    return navigateTo(localePath('/dashboard'))
  }

  // Fail-closed: if baseName could not be resolved (catch-all / unknown route)
  // and the user is unauthenticated, redirect to login. Authenticated users
  // pass through so they can land on whatever the unknown route resolves to.
  if (!isPublicAuth && authStore.user === null) {
    return navigateTo(localePath('/auth/login'))
  }
})
