import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import type { User } from '../../types/user'

// NOTE: setUser must be called BEFORE mountSuspended — authStoreMock is a plain
// object, not a reactive store, so post-mount mutations of isPending/isAuthenticated
// would not trigger re-render. The fully-mocked useAuthStore is sufficient because
// every test seeds the user state up-front.
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
mockNuxtImport('useLocalePath', () => () => (path: string) => path)

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
  })

  // ─── Section 1: Profile info ──────────────────────────────────────────
  it('profile_emailField_isReadOnly', async () => {
    setUser({ id: '1', email: 'me@example.com', name: 'User', status: 'active' })

    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    const emailInput = wrapper.find('[data-test="profile-email"]')
    expect(emailInput.exists()).toBe(true)
    expect(emailInput.attributes('readonly')).toBeDefined()
    expect((emailInput.element as HTMLInputElement).value).toBe('me@example.com')
  })

  it('profileName_editAndSave_callsPatchAndUpdatesStore', async () => {
    apiMock.mockResolvedValueOnce(undefined)
    setUser({ id: '1', email: 'u@example.com', name: 'Old', status: 'active' })

    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    await wrapper.find('[data-test="edit-name-button"]').trigger('click')
    await settle()

    await wrapper.find('[data-test="name-input"]').setValue('New Name')
    await wrapper.find('[data-test="name-edit-form"]').trigger('submit')
    await settle()

    expect(apiMock).toHaveBeenCalledWith('/api/profile', expect.objectContaining({
      method: 'PATCH',
      body: { name: 'New Name' },
    }))
    expect(authStoreMock.user?.name).toBe('New Name')
    expect(wrapper.find('[data-test="name-saved"]').exists()).toBe(true)
  })

  // ─── Section 2: Resend verification ───────────────────────────────────
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

  // ─── Section 3: Change password ───────────────────────────────────────
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

  it('changePasswordForm_emptyCurrent_showsError', async () => {
    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    await wrapper.find('input[name="newPassword"]').setValue('NewPassword1')
    await wrapper.find('input[name="confirmPassword"]').setValue('NewPassword1')
    await wrapper.find('[data-test="change-password-form"]').trigger('submit')
    await settle()

    const error = wrapper.find('[data-test="current-password-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toMatch(/введіть поточний пароль/i)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('changePasswordForm_weakNew_showsError', async () => {
    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    await wrapper.find('input[name="currentPassword"]').setValue('OldPassword1')
    await wrapper.find('input[name="newPassword"]').setValue('abcdefgh')
    await wrapper.find('input[name="confirmPassword"]').setValue('abcdefgh')
    await wrapper.find('[data-test="change-password-form"]').trigger('submit')
    await settle()

    const error = wrapper.find('[data-test="new-password-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toMatch(/цифр/i)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('changePasswordForm_backend400_showsWrongCurrentPasswordError', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 400 })

    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    await wrapper.find('input[name="currentPassword"]').setValue('OldPassword1')
    await wrapper.find('input[name="newPassword"]').setValue('NewPassword1')
    await wrapper.find('input[name="confirmPassword"]').setValue('NewPassword1')
    await wrapper.find('[data-test="change-password-form"]').trigger('submit')
    await settle()

    expect(apiMock).toHaveBeenCalledWith('/api/profile/change-password', expect.objectContaining({
      method: 'POST',
      body: {
        currentPassword: 'OldPassword1',
        newPassword: 'NewPassword1',
      },
    }))
    const error = wrapper.find('[data-test="change-password-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toMatch(/невірний поточний пароль/i)
  })

  // ─── Section 4: Terminate all sessions ────────────────────────────────
  it('terminateAllSessions_clickButton_callsApiAndNavigates', async () => {
    apiMock.mockResolvedValueOnce(undefined)

    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    await wrapper.find('[data-test="terminate-all-button"]').trigger('click')
    await settle()

    expect(apiMock).toHaveBeenCalledWith('/api/profile/terminate-all-sessions', expect.objectContaining({
      method: 'POST',
    }))
    expect(authStoreMock.user).toBeNull()
    expect(navigateToMock).toHaveBeenCalledWith('/auth/login')
  })

  it('terminateAllSessions_apiError_showsError', async () => {
    apiMock.mockRejectedValueOnce(new Error('network'))

    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    await wrapper.find('[data-test="terminate-all-button"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="terminate-error"]').exists()).toBe(true)
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  // ─── Section 5: Delete account ────────────────────────────────────────
  it('deleteAccount_modalShowsConsequencesList', async () => {
    const wrapper = await mountSuspended(ProfilePage)
    await settle()

    await wrapper.find('[data-test="delete-account-button"]').trigger('click')
    await settle()

    const modal = wrapper.find('[data-test="delete-account-modal"]')
    expect(modal.exists()).toBe(true)
    expect(modal.text()).toMatch(/проект/i)
    expect(modal.text()).toMatch(/бот/i)
    expect(modal.text()).toMatch(/підписник/i)
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
    expect(authStoreMock.user).toBeNull()
    expect(navigateToMock).toHaveBeenCalledWith('/auth/login')
  })
})
