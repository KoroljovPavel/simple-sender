import { useAuthStore } from '~/stores/auth'

function isPublicAuthRoute(path: string): boolean {
  return path.startsWith('/auth/')
}

function requiresAuth(to: { meta?: Record<string, unknown> }): boolean {
  if (to.meta?.requiresAuth === false) return false
  return true
}

export default defineNuxtRouteMiddleware(async (to) => {
  const authStore = useAuthStore()
  const publicAuth = isPublicAuthRoute(to.path)

  if (authStore.user === null && !publicAuth) {
    await authStore.fetchUser()
  }

  if (publicAuth && authStore.user !== null) {
    return navigateTo('/dashboard')
  }

  if (!publicAuth && authStore.user === null && requiresAuth(to)) {
    return navigateTo('/auth/login')
  }
})
