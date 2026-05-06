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

    const to = makeRoute('/dashboard')
    const from = makeRoute('/')
    await middleware(to, from)

    expect(navigateToMock).toHaveBeenCalledWith('/auth/login')
  })

  it('authenticated_authRoute_redirectsToDashboard', async () => {
    const store = useAuthStore()
    store.user = { id: '1', email: 'a@b.com', name: 'A', status: 'active' }

    const to = makeRoute('/auth/login')
    const from = makeRoute('/')
    await middleware(to, from)

    expect(navigateToMock).toHaveBeenCalledWith('/dashboard')
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('authenticated_protectedRoute_passesThrough', async () => {
    const store = useAuthStore()
    store.user = { id: '1', email: 'a@b.com', name: 'A', status: 'active' }

    const to = makeRoute('/dashboard')
    const from = makeRoute('/')
    await middleware(to, from)

    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('unauthenticated_publicAuthRoute_passesThrough', async () => {
    const to = makeRoute('/auth/login')
    const from = makeRoute('/')
    await middleware(to, from)

    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
