import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

const { navigateToMock, apiMock, getBaseNameMock, localePathMock } = vi.hoisted(() => ({
  navigateToMock: vi.fn(),
  apiMock: vi.fn(),
  getBaseNameMock: vi.fn(),
  localePathMock: vi.fn((path: string) => path),
}))

mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRouteBaseName', () => () => getBaseNameMock)
mockNuxtImport('useLocalePath', () => () => localePathMock)

import middleware from '../../middleware/auth.global'

const ACTIVE_USER = { id: '1', email: 'a@b.com', name: 'A', status: 'active' as const }

function makeRoute(path: string, meta: Record<string, unknown> = {}) {
  return { path, fullPath: path, meta } as never
}

describe('auth.global middleware (locale-aware)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    navigateToMock.mockReset()
    apiMock.mockReset()
    getBaseNameMock.mockReset()
    localePathMock.mockReset()
    localePathMock.mockImplementation((path: string) => path)
    useState<unknown>('auth-user').value = null
  })

  it('unauthenticated_publicAuthRouteByName_passesThrough', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 401 })
    getBaseNameMock.mockReturnValue('auth-login')

    await middleware(makeRoute('/auth/login'), makeRoute('/'))

    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('unauthenticated_protectedRouteByName_redirectsToLocaleLogin_uk', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 401 })
    getBaseNameMock.mockReturnValue('dashboard')
    localePathMock.mockImplementation((path: string) => path)

    await middleware(makeRoute('/dashboard'), makeRoute('/'))

    expect(localePathMock).toHaveBeenCalledWith('/auth/login')
    expect(navigateToMock).toHaveBeenCalledWith('/auth/login')
  })

  it('unauthenticated_protectedRouteByName_redirectsToLocaleLogin_en', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 401 })
    getBaseNameMock.mockReturnValue('dashboard')
    localePathMock.mockImplementation((path: string) =>
      path === '/auth/login' ? '/en/auth/login' : path,
    )

    await middleware(makeRoute('/en/dashboard'), makeRoute('/'))

    expect(localePathMock).toHaveBeenCalledWith('/auth/login')
    expect(navigateToMock).toHaveBeenCalledWith('/en/auth/login')
  })

  it('authenticated_publicAuthRouteByName_redirectsToLocaleDashboard_uk', async () => {
    useState<unknown>('auth-user').value = ACTIVE_USER
    getBaseNameMock.mockReturnValue('auth-login')
    localePathMock.mockImplementation((path: string) => path)

    await middleware(makeRoute('/auth/login'), makeRoute('/'))

    expect(localePathMock).toHaveBeenCalledWith('/dashboard')
    expect(navigateToMock).toHaveBeenCalledWith('/dashboard')
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('authenticated_publicAuthRouteByName_redirectsToLocaleDashboard_en', async () => {
    useState<unknown>('auth-user').value = ACTIVE_USER
    getBaseNameMock.mockReturnValue('auth-login')
    localePathMock.mockImplementation((path: string) =>
      path === '/dashboard' ? '/en/dashboard' : path,
    )

    await middleware(makeRoute('/en/auth/login'), makeRoute('/'))

    expect(navigateToMock).toHaveBeenCalledWith('/en/dashboard')
  })

  it('authenticated_protectedRouteByName_passesThrough', async () => {
    useState<unknown>('auth-user').value = ACTIVE_USER
    getBaseNameMock.mockReturnValue('dashboard')

    await middleware(makeRoute('/dashboard'), makeRoute('/'))

    expect(navigateToMock).not.toHaveBeenCalled()
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('failClosed_undefinedBaseName_unauthenticated_redirectsToLogin', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 401 })
    getBaseNameMock.mockReturnValue(undefined)

    await middleware(makeRoute('/some-unknown-route'), makeRoute('/'))

    expect(localePathMock).toHaveBeenCalledWith('/auth/login')
    expect(navigateToMock).toHaveBeenCalledWith('/auth/login')
  })

  it('failClosed_undefinedBaseName_authenticated_passesThrough', async () => {
    useState<unknown>('auth-user').value = ACTIVE_USER
    getBaseNameMock.mockReturnValue(undefined)

    await middleware(makeRoute('/some-unknown-route'), makeRoute('/'))

    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('backendError_protectedRouteByName_redirectsToLogin', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 500 })
    getBaseNameMock.mockReturnValue('dashboard')

    await middleware(makeRoute('/dashboard'), makeRoute('/'))

    expect(navigateToMock).toHaveBeenCalledWith('/auth/login')
  })

  it('unauthenticatedWithValidSession_publicAuthRouteByName_hydratesAndRedirectsToDashboard', async () => {
    apiMock.mockResolvedValueOnce(ACTIVE_USER)
    getBaseNameMock.mockReturnValue('auth-login')

    await middleware(makeRoute('/auth/login'), makeRoute('/'))

    expect(apiMock).toHaveBeenCalledWith('/api/auth/me')
    expect(navigateToMock).toHaveBeenCalledWith('/dashboard')
  })
})
