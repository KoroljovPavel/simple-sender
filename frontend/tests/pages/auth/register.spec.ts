import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'

const { apiMock, navigateToMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  navigateToMock: vi.fn(),
}))

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('navigateTo', () => navigateToMock)

import RegisterPage from '../../../pages/auth/register.vue'

async function fillValidForm(wrapper: Awaited<ReturnType<typeof mountSuspended>>) {
  await wrapper.find('input[name="email"]').setValue('user@example.com')
  await wrapper.find('input[name="name"]').setValue('User')
  await wrapper.find('input[name="password"]').setValue('Password1')
  await wrapper.find('input[name="confirmPassword"]').setValue('Password1')
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

    const error = wrapper.find('[data-test="confirm-password-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toMatch(/не співпадають/i)
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

    const error = wrapper.find('[data-test="password-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toMatch(/цифр/i)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('registerForm_validData_submitsBodyAndNavigatesToLogin', async () => {
    apiMock.mockResolvedValueOnce(undefined)

    const wrapper = await mountSuspended(RegisterPage)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(apiMock).toHaveBeenCalledWith('/api/auth/register', {
      method: 'POST',
      body: {
        email: 'user@example.com',
        name: 'User',
        password: 'Password1',
      },
    })
    expect(navigateToMock).toHaveBeenCalledWith({
      path: '/auth/login',
      query: { registered: '1' },
    })
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
