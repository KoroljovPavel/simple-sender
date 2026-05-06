import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

import { useAuthStore } from '../../stores/auth'

const ACTIVE_USER = { id: '1', email: 'a@b.com', name: 'A', status: 'active' as const }

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    useState<unknown>('auth-user').value = null
  })

  it('fetchUser_successResponse_setsUser', async () => {
    apiMock.mockResolvedValueOnce(ACTIVE_USER)

    const store = useAuthStore()
    await store.fetchUser()

    expect(store.user).toEqual(ACTIVE_USER)
    expect(store.isAuthenticated).toBe(true)
    expect(store.isPending).toBe(false)
  })

  it('fetchUser_401Response_setsUserNull', async () => {
    // Pre-arm with an authenticated user so the assertion proves the catch branch ran.
    useState<unknown>('auth-user').value = ACTIVE_USER
    apiMock.mockRejectedValueOnce({ statusCode: 401 })

    const store = useAuthStore()
    expect(store.user).toEqual(ACTIVE_USER)
    await store.fetchUser()

    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })

  it('fetchUser_500Response_propagatesErrorAndKeepsUser', async () => {
    useState<unknown>('auth-user').value = ACTIVE_USER
    apiMock.mockRejectedValueOnce({ statusCode: 500 })

    const store = useAuthStore()
    await expect(store.fetchUser()).rejects.toMatchObject({ statusCode: 500 })

    expect(store.user).toEqual(ACTIVE_USER)
  })

  it('logout_callsLogoutEndpoint_clearsUser', async () => {
    apiMock.mockResolvedValueOnce(ACTIVE_USER)
    apiMock.mockResolvedValueOnce(undefined)

    const store = useAuthStore()
    await store.fetchUser()
    expect(store.user).not.toBeNull()

    await store.logout()

    expect(apiMock).toHaveBeenLastCalledWith('/api/auth/logout', { method: 'POST' })
    expect(store.user).toBeNull()
  })

  it('logout_apiError_stillClearsUser', async () => {
    useState<unknown>('auth-user').value = ACTIVE_USER
    apiMock.mockRejectedValueOnce(new Error('network down'))

    const store = useAuthStore()
    await expect(store.logout()).rejects.toThrow('network down')

    expect(store.user).toBeNull()
  })

  it('isPending_pendingStatus_returnsTrue', async () => {
    apiMock.mockResolvedValueOnce({ id: '1', email: 'a@b.com', name: 'A', status: 'pending' })

    const store = useAuthStore()
    await store.fetchUser()

    expect(store.isPending).toBe(true)
  })
})
