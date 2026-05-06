import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'

const { apiMock, navigateToMock, fetchUserMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  navigateToMock: vi.fn(),
  fetchUserMock: vi.fn(),
}))

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useAuthStore', () => () => ({
  user: null,
  fetchUser: fetchUserMock,
  logout: vi.fn(),
  isAuthenticated: false,
  isPending: false,
}))

import LoginPage from '../../../pages/auth/login.vue'

const settle = async () => {
  await flushPromises()
  await new Promise((r) => setTimeout(r, 50))
  await flushPromises()
}

describe('login page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    navigateToMock.mockReset()
    fetchUserMock.mockReset()
    fetchUserMock.mockResolvedValue(undefined)
    useState<unknown>('auth-user').value = null
  })

  it('loginForm_invalidEmail_showsInlineError', async () => {
    const wrapper = await mountSuspended(LoginPage)

    await wrapper.find('input[type="email"]').setValue('not-an-email')
    await wrapper.find('input[type="password"]').setValue('password1')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(wrapper.find('[data-test="email-error"]').exists()).toBe(true)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('loginForm_shortPassword_showsInlineError', async () => {
    const wrapper = await mountSuspended(LoginPage)

    await wrapper.find('input[type="email"]').setValue('user@example.com')
    await wrapper.find('input[type="password"]').setValue('abc')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(wrapper.find('[data-test="password-error"]').exists()).toBe(true)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('loginForm_validData_submitsAndRedirects', async () => {
    apiMock.mockResolvedValueOnce(undefined)

    const wrapper = await mountSuspended(LoginPage)

    await wrapper.find('input[type="email"]').setValue('user@example.com')
    await wrapper.find('input[type="password"]').setValue('password1')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(apiMock).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({
      method: 'POST',
      body: expect.objectContaining({
        email: 'user@example.com',
        password: 'password1',
      }),
    }))
    expect(navigateToMock).toHaveBeenCalledWith('/dashboard')
  })
})
