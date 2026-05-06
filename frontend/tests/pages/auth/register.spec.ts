import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'

const { apiMock, navigateToMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  navigateToMock: vi.fn(),
}))

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('navigateTo', () => navigateToMock)

import RegisterPage from '../../../pages/auth/register.vue'

const settle = async () => {
  await flushPromises()
  await new Promise((r) => setTimeout(r, 50))
  await flushPromises()
}

describe('register page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    navigateToMock.mockReset()
    useState<unknown>('auth-user').value = null
  })

  it('registerForm_passwordMismatch_showsError', async () => {
    const wrapper = await mountSuspended(RegisterPage)

    await wrapper.find('input[name="email"]').setValue('user@example.com')
    await wrapper.find('input[name="name"]').setValue('User')
    await wrapper.find('input[name="password"]').setValue('Password1')
    await wrapper.find('input[name="confirmPassword"]').setValue('Different1')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(wrapper.find('[data-test="confirm-password-error"]').exists()).toBe(true)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('registerForm_noDigitPassword_showsError', async () => {
    const wrapper = await mountSuspended(RegisterPage)

    await wrapper.find('input[name="email"]').setValue('user@example.com')
    await wrapper.find('input[name="name"]').setValue('User')
    await wrapper.find('input[name="password"]').setValue('abcdefgh')
    await wrapper.find('input[name="confirmPassword"]').setValue('abcdefgh')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(wrapper.find('[data-test="password-error"]').exists()).toBe(true)
    expect(apiMock).not.toHaveBeenCalled()
  })
})
