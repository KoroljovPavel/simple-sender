import { defineStore } from 'pinia'
import type { User } from '~/types/user'

export const useAuthStore = defineStore('auth', () => {
  const userState = useState<User | null>('auth-user', () => null)

  async function fetchUser() {
    try {
      const data = await useApi()<User>('/api/auth/me')
      userState.value = data
    } catch (e: unknown) {
      const status = (e as { statusCode?: number; status?: number; response?: { status?: number } })
      const code = status?.statusCode ?? status?.status ?? status?.response?.status
      if (code === 401) {
        userState.value = null
        return
      }
      throw e
    }
  }

  async function logout() {
    try {
      await useApi()('/api/auth/logout', { method: 'POST' })
    } finally {
      userState.value = null
    }
  }

  const isAuthenticated = computed(() => userState.value !== null)
  const isPending = computed(() => userState.value?.status === 'pending')

  return {
    user: userState,
    fetchUser,
    logout,
    isAuthenticated,
    isPending,
  }
})
