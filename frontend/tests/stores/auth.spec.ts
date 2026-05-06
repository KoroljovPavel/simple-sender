import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

import { useAuthStore } from '../../stores/auth'

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    useState<unknown>('auth-user').value = null
  })

  it('fetchUser_successResponse_setsUser', async () => {
    const user = { id: '1', email: 'a@b.com', name: 'A', status: 'active' as const }
    apiMock.mockResolvedValueOnce(user)

    const store = useAuthStore()
    await store.fetchUser()

    expect(store.user).toEqual(user)
    expect(store.isAuthenticated).toBe(true)
    expect(store.isPending).toBe(false)
  })

  it('fetchUser_401Response_setsUserNull', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 401 })

    const store = useAuthStore()
    await store.fetchUser()

    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })

  it('logout_callsLogoutEndpoint_clearsUser', async () => {
    const user = { id: '1', email: 'a@b.com', name: 'A', status: 'active' as const }
    apiMock.mockResolvedValueOnce(user)
    apiMock.mockResolvedValueOnce(undefined)

    const store = useAuthStore()
    await store.fetchUser()
    expect(store.user).not.toBeNull()

    await store.logout()

    expect(apiMock).toHaveBeenLastCalledWith('/api/auth/logout', { method: 'POST' })
    expect(store.user).toBeNull()
  })

  it('isPending_pendingStatus_returnsTrue', async () => {
    apiMock.mockResolvedValueOnce({ id: '1', email: 'a@b.com', name: 'A', status: 'pending' })

    const store = useAuthStore()
    await store.fetchUser()

    expect(store.isPending).toBe(true)
  })
})
