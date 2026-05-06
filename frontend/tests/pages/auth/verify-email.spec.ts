import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'

const { apiMock, useRouteMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  useRouteMock: vi.fn(),
}))

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => useRouteMock)

import VerifyEmailPage from '../../../pages/auth/verify-email.vue'

describe('verify-email page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    useRouteMock.mockReturnValue({ query: { token: 'sometoken' } })
    useState<unknown>('auth-user').value = null
  })

  it('verifyEmail_validToken_showsSuccessState', async () => {
    apiMock.mockResolvedValueOnce(undefined)

    const wrapper = await mountSuspended(VerifyEmailPage)
    await settle()

    expect(wrapper.find('[data-test="success"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="invalid"]').exists()).toBe(false)
  })

  it('verifyEmail_genericError_showsInvalidState', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 400 })

    const wrapper = await mountSuspended(VerifyEmailPage)
    await settle()

    expect(wrapper.find('[data-test="invalid"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="expired"]').exists()).toBe(false)
  })

  it('verifyEmail_missingToken_showsInvalidImmediately', async () => {
    useRouteMock.mockReturnValue({ query: {} })

    const wrapper = await mountSuspended(VerifyEmailPage)
    await settle()

    expect(wrapper.find('[data-test="invalid"]').exists()).toBe(true)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('verifyEmail_tokenExpired_showsResendButton', async () => {
    apiMock.mockRejectedValueOnce({
      statusCode: 400,
      data: { code: 'TOKEN_EXPIRED' },
      response: { status: 400, _data: { code: 'TOKEN_EXPIRED' } },
    })

    const wrapper = await mountSuspended(VerifyEmailPage)
    await settle()

    expect(wrapper.find('[data-test="expired"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="resend-button"]').exists()).toBe(true)
  })

  it('verifyEmail_tokenExpiredAndResend_callsResendEndpoint', async () => {
    apiMock.mockRejectedValueOnce({
      statusCode: 400,
      data: { code: 'TOKEN_EXPIRED' },
      response: { status: 400, _data: { code: 'TOKEN_EXPIRED' } },
    })
    apiMock.mockResolvedValueOnce(undefined)

    const wrapper = await mountSuspended(VerifyEmailPage)
    await settle()

    await wrapper.find('input[name="email"]').setValue('user@example.com')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(apiMock).toHaveBeenLastCalledWith('/api/auth/resend-verification', {
      method: 'POST',
      body: { email: 'user@example.com' },
    })
    expect(wrapper.find('[data-test="resend-sent"]').exists()).toBe(true)
  })
})
