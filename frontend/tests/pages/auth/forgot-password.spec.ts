import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))

mockNuxtImport('useApi', () => () => apiMock)

import ForgotPasswordPage from '../../../pages/auth/forgot-password.vue'

describe('forgot-password page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    useState<unknown>('auth-user').value = null
  })

  it('forgotPassword_validEmail_showsSuccessMessage', async () => {
    apiMock.mockResolvedValueOnce(undefined)

    const wrapper = await mountSuspended(ForgotPasswordPage)
    await wrapper.find('input[name="email"]').setValue('user@example.com')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(apiMock).toHaveBeenCalledWith('/api/auth/forgot-password', {
      method: 'POST',
      body: { email: 'user@example.com' },
    })
    expect(wrapper.find('[data-test="success-message"]').exists()).toBe(true)
  })

  it('forgotPassword_backendError_stillShowsSuccessMessage', async () => {
    // Critical anti-enumeration invariant: regardless of backend response (404, 500, network),
    // the UI must show the same success message. Removing the catch block would leak existence.
    apiMock.mockRejectedValueOnce({ statusCode: 500 })

    const wrapper = await mountSuspended(ForgotPasswordPage)
    await wrapper.find('input[name="email"]').setValue('user@example.com')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(wrapper.find('[data-test="success-message"]').exists()).toBe(true)
  })

  it('forgotPassword_invalidEmail_showsValidationError', async () => {
    const wrapper = await mountSuspended(ForgotPasswordPage)
    await wrapper.find('input[name="email"]').setValue('not-an-email')
    await wrapper.find('form').trigger('submit')
    await settle()

    const error = wrapper.find('[data-test="email-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toMatch(/коректний email/i)
    expect(wrapper.find('[data-test="success-message"]').exists()).toBe(false)
    expect(apiMock).not.toHaveBeenCalled()
  })
})
