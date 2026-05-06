import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import type { User } from '../../types/user'

const { authStoreMock } = vi.hoisted(() => ({
  authStoreMock: {
    user: null as User | null,
    fetchUser: vi.fn(),
    logout: vi.fn(),
    isAuthenticated: false,
    isPending: false,
  },
}))

mockNuxtImport('useAuthStore', () => () => authStoreMock)

import DashboardPage from '../../pages/dashboard.vue'

function setUser(user: User | null) {
  authStoreMock.user = user
  authStoreMock.isAuthenticated = user !== null
  authStoreMock.isPending = user?.status === 'pending'
}

describe('dashboard page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setUser(null)
    useState<unknown>('auth-user').value = null
  })

  it('dashboard_pendingUser_showsBanner', async () => {
    setUser({ id: '1', email: 'u@example.com', name: 'User', status: 'pending' })

    const wrapper = await mountSuspended(DashboardPage)
    await settle()

    const banner = wrapper.find('[data-test="pending-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toMatch(/Підтвердіть email/i)
    const link = banner.find('a[href="/profile"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toMatch(/Перейти до профілю/i)
  })

  it('dashboard_activeUser_noBanner', async () => {
    setUser({ id: '1', email: 'u@example.com', name: 'Active User', status: 'active' })

    const wrapper = await mountSuspended(DashboardPage)
    await settle()

    expect(wrapper.find('[data-test="pending-banner"]').exists()).toBe(false)
    expect(wrapper.text()).toMatch(/Active User/)
  })
})
