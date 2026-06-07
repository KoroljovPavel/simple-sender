import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'
import type { Project } from '../../../types/project'

const PROJECT_ID = 'p1'

const { projectsStoreMock, navigateToMock, routeMock, apiMock } = vi.hoisted(() => ({
  projectsStoreMock: {
    projects: [] as Project[],
    currentProjectId: null as string | null,
    currentProject: null as Project | null,
    isLoaded: true,
    pendingBannerKey: null as string | null,
    fetchAll: vi.fn(),
    update: vi.fn(),
    softDelete: vi.fn(),
  },
  navigateToMock: vi.fn(),
  routeMock: { params: { projectId: 'p1' } as Record<string, string> },
  apiMock: vi.fn(),
}))

mockNuxtImport('useProjectsStore', () => () => projectsStoreMock)
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => () => routeMock)
mockNuxtImport('useLocalePath', () => () => (path: string) => path)
mockNuxtImport('navigateTo', () => navigateToMock)

import SettingsPage from '../../../pages/projects/[projectId]/settings/index.vue'

const API_KEY_PATH = `/api/v1/projects/${PROJECT_ID}/api-key`

function makeProject(overrides: Partial<Project> = {}): Project {
  return {
    id: PROJECT_ID,
    name: 'Acme',
    description: 'Old desc',
    timezone: 'Europe/Berlin',
    createdAt: '2026-05-12T00:00:00Z',
    updatedAt: '2026-05-12T00:00:00Z',
    deletedAt: null,
    ...overrides,
  }
}

function seedActive(...projects: Project[]) {
  projectsStoreMock.projects = projects
  projectsStoreMock.currentProjectId = projects[0]?.id ?? null
  projectsStoreMock.currentProject = projects[0] ?? null
}

// The page makes its own on-mount project GET (`/api/v1/projects/{id}`) AND the
// API-key card makes a GET then POST against `/api/v1/projects/{id}/api-key`.
// `apiMock` is the single shared `useApi()` fn, so route by URL + method.
type ApiResponders = {
  maskGet?: () => unknown
  generatePost?: () => unknown
}

function installApiResponders(responders: ApiResponders) {
  apiMock.mockImplementation((url: unknown, opts?: { method?: string }) => {
    const path = String(url)
    const method = String(opts?.method ?? 'GET').toUpperCase()
    if (path === API_KEY_PATH) {
      if (method === 'POST') {
        return Promise.resolve(responders.generatePost?.())
      }
      return Promise.resolve(responders.maskGet?.() ?? { present: false, mask: null })
    }
    // The page's own on-mount project GET.
    return Promise.resolve(makeProject())
  })
}

describe('projects/[projectId]/settings — API-key card', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    routeMock.params = { projectId: PROJECT_ID }
    projectsStoreMock.fetchAll.mockReset()
    projectsStoreMock.update.mockReset()
    projectsStoreMock.softDelete.mockReset()
    apiMock.mockReset()
    navigateToMock.mockReset()
    projectsStoreMock.isLoaded = true
    projectsStoreMock.pendingBannerKey = null
    seedActive(makeProject())
    // Default: no key yet.
    installApiResponders({ maskGet: () => ({ present: false, mask: null }) })
  })

  it('renders Generate when no key', async () => {
    installApiResponders({ maskGet: () => ({ present: false, mask: null }) })

    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    expect(wrapper.find('[data-test="api-key-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="api-key-generate"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="api-key-mask"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="api-key-regenerate"]').exists()).toBe(false)
  })

  it('renders mask + Regenerate when key present', async () => {
    installApiResponders({ maskGet: () => ({ present: true, mask: 'sk_live•••' }) })

    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    const mask = wrapper.find('[data-test="api-key-mask"]')
    expect(mask.exists()).toBe(true)
    expect(mask.text()).toContain('sk_live•••')
    expect(wrapper.find('[data-test="api-key-regenerate"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="api-key-generate"]').exists()).toBe(false)
    // Masked state must never contain a full key.
    expect(wrapper.find('[data-test="api-key-card"]').html()).not.toContain('PLAINTEXT')
  })

  it('Generate shows plaintext once in modal', async () => {
    installApiResponders({
      maskGet: () => ({ present: false, mask: null }),
      generatePost: () => ({ apiKey: 'sk_live_PLAINTEXT_SECRET_123', mask: 'sk_live•••' }),
    })

    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="api-key-generate"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="api-key-modal"]').exists()).toBe(true)
    const plaintext = wrapper.find('[data-test="api-key-plaintext"]')
    expect(plaintext.exists()).toBe(true)
    expect(plaintext.text()).toContain('sk_live_PLAINTEXT_SECRET_123')
    expect(wrapper.find('[data-test="api-key-copy"]').exists()).toBe(true)

    // POST was issued against the api-key endpoint.
    expect(
      apiMock.mock.calls.some(
        ([url, opts]) =>
          String(url) === API_KEY_PATH &&
          String((opts as { method?: string } | undefined)?.method ?? '').toUpperCase() === 'POST',
      ),
    ).toBe(true)
  })

  it('dismissing modal returns to masked state', async () => {
    installApiResponders({
      maskGet: () => ({ present: false, mask: null }),
      generatePost: () => ({ apiKey: 'sk_live_PLAINTEXT_SECRET_123', mask: 'sk_live•••' }),
    })

    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="api-key-generate"]').trigger('click')
    await settle()
    expect(wrapper.find('[data-test="api-key-modal"]').exists()).toBe(true)

    await wrapper.find('[data-test="api-key-modal-close"]').trigger('click')
    await settle()

    // Modal gone, masked value + Regenerate shown, plaintext NOT lingering anywhere.
    expect(wrapper.find('[data-test="api-key-modal"]').exists()).toBe(false)
    const mask = wrapper.find('[data-test="api-key-mask"]')
    expect(mask.exists()).toBe(true)
    expect(mask.text()).toContain('sk_live•••')
    expect(wrapper.find('[data-test="api-key-regenerate"]').exists()).toBe(true)
    expect(wrapper.html()).not.toContain('sk_live_PLAINTEXT_SECRET_123')
  })

  it('request failure surfaces inline error', async () => {
    apiMock.mockImplementation((url: unknown, opts?: { method?: string }) => {
      const path = String(url)
      const method = String(opts?.method ?? 'GET').toUpperCase()
      if (path === API_KEY_PATH) {
        if (method === 'POST') return Promise.reject({ statusCode: 500 })
        return Promise.resolve({ present: false, mask: null })
      }
      return Promise.resolve(makeProject())
    })

    const wrapper = await mountSuspended(SettingsPage)
    await settle()

    await wrapper.find('[data-test="api-key-generate"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="api-key-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="api-key-error"]').text().length).toBeGreaterThan(0)
    expect(wrapper.find('[data-test="api-key-modal"]').exists()).toBe(false)
  })
})
