import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'

const { apiMock, navigateToMock, useRouteMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  navigateToMock: vi.fn(),
  useRouteMock: vi.fn(),
}))

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useRoute', () => useRouteMock)
mockNuxtImport('useLocalePath', () => () => (path: string) => path)

import ResetPasswordPage from '../../../pages/auth/reset-password.vue'

describe('reset-password page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    navigateToMock.mockReset()
    useRouteMock.mockReturnValue({ query: { token: 'valid-token' } })
    useState<unknown>('auth-user').value = null
  })

  it('resetPassword_missingToken_showsInvalidLinkBlock', async () => {
    useRouteMock.mockReturnValue({ query: {} })

    const wrapper = await mountSuspended(ResetPasswordPage)
    await settle()

    expect(wrapper.find('[data-test="invalid-link"]').exists()).toBe(true)
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('resetPassword_validData_submitsAndNavigates', async () => {
    apiMock.mockResolvedValueOnce(undefined)

    const wrapper = await mountSuspended(ResetPasswordPage)
    await wrapper.find('input[name="newPassword"]').setValue('Password1')
    await wrapper.find('input[name="confirmPassword"]').setValue('Password1')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(apiMock).toHaveBeenCalledWith('/api/auth/reset-password', {
      method: 'POST',
      body: { token: 'valid-token', newPassword: 'Password1' },
    })
    expect(navigateToMock).toHaveBeenCalledWith({
      path: '/auth/login',
      query: { reset: '1' },
    })
  })

  it('resetPassword_400Response_showsInvalidLinkBlock', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 400 })

    const wrapper = await mountSuspended(ResetPasswordPage)
    await wrapper.find('input[name="newPassword"]').setValue('Password1')
    await wrapper.find('input[name="confirmPassword"]').setValue('Password1')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(wrapper.find('[data-test="invalid-link"]').exists()).toBe(true)
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('resetPassword_passwordMismatch_showsValidationError', async () => {
    const wrapper = await mountSuspended(ResetPasswordPage)
    await wrapper.find('input[name="newPassword"]').setValue('Password1')
    await wrapper.find('input[name="confirmPassword"]').setValue('Different1')
    await wrapper.find('form').trigger('submit')
    await settle()

    const error = wrapper.find('[data-test="confirm-password-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toMatch(/не співпадають/i)
    expect(apiMock).not.toHaveBeenCalled()
  })
})
