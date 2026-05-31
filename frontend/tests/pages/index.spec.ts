import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref, reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import type { User } from '../../types/user'

const { navigateToSpy } = vi.hoisted(() => ({ navigateToSpy: vi.fn() }))

const userRef = ref<User | null>(null)
// reactive() so `store.user` auto-unwraps the ref on access — same shape the real Pinia setup-store
// exposes (a bare ref is always truthy, which would break the unauthenticated branch).
const mockAuthStore = reactive({ user: userRef })

mockNuxtImport('useAuthStore', () => () => mockAuthStore)
mockNuxtImport('useLocalePath', () => () => (p: string) => p)
mockNuxtImport('navigateTo', () => navigateToSpy)

import IndexPage from '../../pages/index.vue'

describe('pages/index — root redirect', () => {
  beforeEach(() => {
    navigateToSpy.mockReset()
    userRef.value = null
  })

  it('redirects an authenticated user to the dashboard', async () => {
    userRef.value = { id: 'u1', email: 'a@b.c', name: null } as User
    await mountSuspended(IndexPage)
    expect(navigateToSpy).toHaveBeenCalledWith('/dashboard', { replace: true })
  })

  it('redirects an unauthenticated user to login', async () => {
    userRef.value = null
    await mountSuspended(IndexPage)
    expect(navigateToSpy).toHaveBeenCalledWith('/auth/login', { replace: true })
  })
})
