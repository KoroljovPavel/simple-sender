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

import RegisterPage from '../../../pages/auth/register.vue'

async function fillValidForm(wrapper: Awaited<ReturnType<typeof mountSuspended>>) {
  await wrapper.find('input[name="email"]').setValue('user@example.com')
  await wrapper.find('input[name="password"]').setValue('Password1')
  await wrapper.find('input[name="confirmPassword"]').setValue('Password1')
}

describe('register page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    navigateToMock.mockReset()
    fetchUserMock.mockReset()
    fetchUserMock.mockResolvedValue(undefined)
    useState<unknown>('auth-user').value = null
  })

  it('registerForm_passwordMismatch_showsError', async () => {
    const wrapper = await mountSuspended(RegisterPage)

    await wrapper.find('input[name="email"]').setValue('user@example.com')
    await wrapper.find('input[name="password"]').setValue('Password1')
    await wrapper.find('input[name="confirmPassword"]').setValue('Different1')
    await wrapper.find('form').trigger('submit')
    await settle()

    const error = wrapper.find('[data-test="confirm-password-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toMatch(/не співпадають/i)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('registerForm_noDigitPassword_showsError', async () => {
    const wrapper = await mountSuspended(RegisterPage)

    await wrapper.find('input[name="email"]').setValue('user@example.com')
    await wrapper.find('input[name="password"]').setValue('abcdefgh')
    await wrapper.find('input[name="confirmPassword"]').setValue('abcdefgh')
    await wrapper.find('form').trigger('submit')
    await settle()

    const error = wrapper.find('[data-test="password-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toMatch(/цифр/i)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('registerForm_validData_submitsBodyAndAutoLoginsToDashboard', async () => {
    apiMock.mockResolvedValueOnce(undefined)

    const wrapper = await mountSuspended(RegisterPage)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(apiMock).toHaveBeenCalledWith('/api/auth/register', {
      method: 'POST',
      body: {
        email: 'user@example.com',
        password: 'Password1',
      },
    })
    // Backend opens the session in the same response, so the SPA only needs to populate
    // the auth store and land on /dashboard. The pending-banner there carries the
    // verify-your-email reminder; no detour through /auth/login.
    expect(fetchUserMock).toHaveBeenCalled()
    expect(navigateToMock).toHaveBeenCalledWith('/dashboard')
  })

  it('registerForm_409Response_showsExistingEmailMessage', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 409 })

    const wrapper = await mountSuspended(RegisterPage)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await settle()

    const error = wrapper.find('[data-test="submit-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toMatch(/вже існує/i)
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
