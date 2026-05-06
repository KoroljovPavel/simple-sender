import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import type { User } from '../../types/user'

const { apiMock, navigateToMock, authStoreMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  navigateToMock: vi.fn(),
  authStoreMock: {
    user: null as User | null,
    fetchUser: vi.fn(),
    logout: vi.fn(),
    isAuthenticated: false,
    isPending: false,
  },
}))

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useAuthStore', () => () => authStoreMock)

import ProfilePage from '../../pages/profile.vue'

function setUser(user: User | null) {
  authStoreMock.user = user
  authStoreMock.isAuthenticated = user !== null
  authStoreMock.isPending = user?.status === 'pending'
}

describe('profile page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset()
    navigateToMock.mockReset()
    setUser({ id: '1', email: 'u@example.com', name: 'User', status: 'active' })
    useState<unknown>('auth-user').value = null
  })

  it('profile_pendingUser_showsResendButton', async () => {
    setUser({ id: '1', email: 'u@example.com', name: 'User', status: 'pending' })

    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    expect(wrapper.find('[data-test="resend-section"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="resend-button"]').exists()).toBe(true)
  })

  it('profile_activeUser_hidesResendSection', async () => {
    setUser({ id: '1', email: 'u@example.com', name: 'User', status: 'active' })

    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    expect(wrapper.find('[data-test="resend-section"]').exists()).toBe(false)
  })

  it('changePasswordForm_wrongConfirm_showsInlineError', async () => {
    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    await wrapper.find('input[name="currentPassword"]').setValue('OldPassword1')
    await wrapper.find('input[name="newPassword"]').setValue('NewPassword1')
    await wrapper.find('input[name="confirmPassword"]').setValue('Different1')
    await wrapper.find('[data-test="change-password-form"]').trigger('submit')
    await settle()

    const error = wrapper.find('[data-test="confirm-password-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toMatch(/не співпадають/i)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('deleteAccount_clickConfirm_callsDeleteProfile', async () => {
    apiMock.mockResolvedValueOnce(undefined)

    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    await wrapper.find('[data-test="delete-account-button"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="delete-account-modal"]').exists()).toBe(true)

    await wrapper.find('[data-test="delete-account-confirm"]').trigger('click')
    await settle()

    expect(apiMock).toHaveBeenCalledWith('/api/profile', expect.objectContaining({
      method: 'DELETE',
    }))
    expect(navigateToMock).toHaveBeenCalledWith('/auth/login')
  })
})
