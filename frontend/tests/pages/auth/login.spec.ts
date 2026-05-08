import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'

const { apiMock, navigateToMock, fetchUserMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  navigateToMock: vi.fn(),
  fetchUserMock: vi.fn(),
}))

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useLocalePath', () => () => (path: string) => path)
mockNuxtImport('useAuthStore', () => () => ({
  user: null,
  fetchUser: fetchUserMock,
  logout: vi.fn(),
  isAuthenticated: false,
  isPending: false,
}))

import LoginPage from '../../../pages/auth/login.vue'

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

    const emailError = wrapper.find('[data-test="email-error"]')
    expect(emailError.exists()).toBe(true)
    expect(emailError.text()).toMatch(/коректний email/i)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('loginForm_shortPassword_showsInlineError', async () => {
    const wrapper = await mountSuspended(LoginPage)

    await wrapper.find('input[type="email"]').setValue('user@example.com')
    await wrapper.find('input[type="password"]').setValue('abc')
    await wrapper.find('form').trigger('submit')
    await settle()

    const pwdError = wrapper.find('[data-test="password-error"]')
    expect(pwdError.exists()).toBe(true)
    expect(pwdError.text()).toMatch(/8/)
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
      body: {
        email: 'user@example.com',
        password: 'password1',
        rememberMe: false,
      },
    }))
    expect(fetchUserMock).toHaveBeenCalled()
    expect(navigateToMock).toHaveBeenCalledWith('/dashboard')
  })

  it('loginForm_429Response_showsCooldownMessage', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 429 })

    const wrapper = await mountSuspended(LoginPage)

    await wrapper.find('input[type="email"]').setValue('user@example.com')
    await wrapper.find('input[type="password"]').setValue('password1')
    await wrapper.find('form').trigger('submit')
    await settle()

    const submitError = wrapper.find('[data-test="submit-error"]')
    expect(submitError.exists()).toBe(true)
    expect(submitError.text()).toMatch(/15 хв/)
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('loginForm_401Response_showsInvalidCredentialsMessage', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 401 })

    const wrapper = await mountSuspended(LoginPage)

    await wrapper.find('input[type="email"]').setValue('user@example.com')
    await wrapper.find('input[type="password"]').setValue('password1')
    await wrapper.find('form').trigger('submit')
    await settle()

    const submitError = wrapper.find('[data-test="submit-error"]')
    expect(submitError.exists()).toBe(true)
    expect(submitError.text()).toMatch(/невірний/i)
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
