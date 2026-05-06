import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'

const { apiMock, useRouteMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  useRouteMock: vi.fn(),
}))

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => useRouteMock)

import VerifyEmailPage from '../../../pages/auth/verify-email.vue'

const settle = async () => {
  await flushPromises()
  await new Promise((r) => setTimeout(r, 50))
  await flushPromises()
}

describe('verify-email page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    useRouteMock.mockReturnValue({ query: { token: 'sometoken' } })
    useState<unknown>('auth-user').value = null
  })

  it('verifyEmail_tokenExpired_showsResendButton', async () => {
    apiMock.mockRejectedValueOnce({
      statusCode: 400,
      data: { code: 'TOKEN_EXPIRED' },
      response: { status: 400, _data: { code: 'TOKEN_EXPIRED' } },
    })

    const wrapper = await mountSuspended(VerifyEmailPage)
    await settle()

    expect(wrapper.find('[data-test="resend-button"]').exists()).toBe(true)
  })
})
