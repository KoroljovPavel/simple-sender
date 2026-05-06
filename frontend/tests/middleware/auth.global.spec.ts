import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

const { navigateToMock, apiMock } = vi.hoisted(() => ({
  navigateToMock: vi.fn(),
  apiMock: vi.fn(),
}))

mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useApi', () => () => apiMock)

import middleware from '../../middleware/auth.global'
import { useAuthStore } from '../../stores/auth'

const ACTIVE_USER = { id: '1', email: 'a@b.com', name: 'A', status: 'active' as const }

function makeRoute(path: string, meta: Record<string, unknown> = {}) {
  return { path, fullPath: path, meta } as never
}

describe('auth.global middleware', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    navigateToMock.mockReset()
    apiMock.mockReset()
    useState<unknown>('auth-user').value = null
  })

  it('unauthenticated_protectedRoute_redirectsToLogin', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 401 })

    await middleware(makeRoute('/dashboard'), makeRoute('/'))

    expect(apiMock).toHaveBeenCalledWith('/api/auth/me')
    expect(navigateToMock).toHaveBeenCalledWith('/auth/login')
  })

  it('authenticated_authRoute_redirectsToDashboard', async () => {
    useState<unknown>('auth-user').value = ACTIVE_USER

    await middleware(makeRoute('/auth/login'), makeRoute('/'))

    expect(navigateToMock).toHaveBeenCalledWith('/dashboard')
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('unauthenticatedWithValidSession_authRoute_hydratesAndRedirectsToDashboard', async () => {
    // User lands on /auth/login with a valid session cookie — middleware must hydrate
    // and redirect to /dashboard, not pass through.
    apiMock.mockResolvedValueOnce(ACTIVE_USER)

    await middleware(makeRoute('/auth/login'), makeRoute('/'))

    expect(apiMock).toHaveBeenCalledWith('/api/auth/me')
    expect(navigateToMock).toHaveBeenCalledWith('/dashboard')
  })

  it('authenticated_protectedRoute_passesThrough', async () => {
    useState<unknown>('auth-user').value = ACTIVE_USER

    await middleware(makeRoute('/dashboard'), makeRoute('/'))

    expect(navigateToMock).not.toHaveBeenCalled()
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('unauthenticated_publicAuthRoute_passesThrough', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 401 })

    await middleware(makeRoute('/auth/login'), makeRoute('/'))

    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('backendError_protectedRoute_redirectsToLogin', async () => {
    // Non-401 error during fetchUser must not crash the middleware.
    apiMock.mockRejectedValueOnce({ statusCode: 500 })

    await middleware(makeRoute('/dashboard'), makeRoute('/'))

    expect(navigateToMock).toHaveBeenCalledWith('/auth/login')
  })
})
