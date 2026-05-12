import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import type { User } from '../../types/user'
import type { Project } from '../../types/project'

const { authStoreMock, projectsStoreMock } = vi.hoisted(() => ({
  authStoreMock: {
    user: null as User | null,
    fetchUser: vi.fn(),
    logout: vi.fn(),
    isAuthenticated: false,
    isPending: false,
  },
  projectsStoreMock: {
    projects: [] as Project[],
    isLoaded: false,
    fetchAll: vi.fn(),
  },
}))

mockNuxtImport('useAuthStore', () => () => authStoreMock)
mockNuxtImport('useProjectsStore', () => () => projectsStoreMock)
mockNuxtImport('useLocalePath', () => () => (path: string) => path)

import DashboardPage from '../../pages/dashboard.vue'

function setUser(user: User | null) {
  authStoreMock.user = user
  authStoreMock.isAuthenticated = user !== null
  authStoreMock.isPending = user?.status === 'pending'
}

function setProjectsState(state: { isLoaded: boolean; projects: Project[] }) {
  projectsStoreMock.isLoaded = state.isLoaded
  projectsStoreMock.projects = state.projects
}

function makeProject(overrides: Partial<Project> = {}): Project {
  return {
    id: 'p-1',
    name: 'My Project',
    description: null,
    timezone: 'Europe/Kyiv',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    deletedAt: null,
    ...overrides,
  }
}

describe('dashboard page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setUser(null)
    setProjectsState({ isLoaded: true, projects: [makeProject()] })
    projectsStoreMock.fetchAll.mockReset()
    projectsStoreMock.fetchAll.mockResolvedValue(undefined)
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

  it('dashboard_zeroProjectsLoaded_showsEmptyStateCTA', async () => {
    setUser({ id: '1', email: 'u@example.com', name: 'User', status: 'active' })
    setProjectsState({ isLoaded: true, projects: [] })

    const wrapper = await mountSuspended(DashboardPage)
    await settle()

    const empty = wrapper.find('[data-test="dashboard-empty-state"]')
    expect(empty.exists()).toBe(true)
    const cta = wrapper.find('[data-test="dashboard-empty-state-cta"]')
    expect(cta.exists()).toBe(true)
    expect(cta.attributes('href')).toBe('/projects/new')
    // welcome content must not render in zero-projects branch
    expect(wrapper.text()).not.toMatch(/Ласкаво просимо/i)
  })

  it('dashboard_hasProjects_showsWelcomeNotEmpty', async () => {
    setUser({ id: '1', email: 'u@example.com', name: 'User', status: 'active' })
    setProjectsState({ isLoaded: true, projects: [makeProject()] })

    const wrapper = await mountSuspended(DashboardPage)
    await settle()

    expect(wrapper.find('[data-test="dashboard-empty-state"]').exists()).toBe(false)
    expect(wrapper.text()).toMatch(/Ласкаво просимо/i)
  })

  it('dashboard_notLoaded_showsNeitherEmptyNorWelcome', async () => {
    setUser({ id: '1', email: 'u@example.com', name: 'User', status: 'active' })
    setProjectsState({ isLoaded: false, projects: [] })

    const wrapper = await mountSuspended(DashboardPage)
    await settle()

    expect(wrapper.find('[data-test="dashboard-empty-state"]').exists()).toBe(false)
    expect(wrapper.text()).not.toMatch(/Ласкаво просимо/i)
  })

  it('dashboard_mount_callsFetchAll', async () => {
    setUser({ id: '1', email: 'u@example.com', name: 'User', status: 'active' })

    await mountSuspended(DashboardPage)
    await settle()

    expect(projectsStoreMock.fetchAll).toHaveBeenCalledTimes(1)
    expect(projectsStoreMock.fetchAll).toHaveBeenCalledWith()
  })
})
