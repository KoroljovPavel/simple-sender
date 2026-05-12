import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref, computed, reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import type { Project } from '../../types/project'

// Hoist ONLY vi.fn() spies — vi.hoisted runs before imports, so vue's
// reactivity primitives are not yet available inside the factory.
const { selectProjectSpy, handleStaleCurrentSpy, tSpy } = vi.hoisted(() => ({
  selectProjectSpy: vi.fn(),
  handleStaleCurrentSpy: vi.fn(),
  tSpy: vi.fn((key: string) => key),
}))

// Module-level refs (created AFTER `import { ref } from 'vue'`).
const projectsRef = ref<Project[]>([])
const currentProjectIdRef = ref<string | null>(null)
const pendingBannerKeyRef = ref<string | null>(null)
const currentProjectComputed = computed<Project | null>(
  () => projectsRef.value.find((p) => p.id === currentProjectIdRef.value) ?? null,
)

// Wrap with reactive() so refs auto-unwrap on property access — same shape
// the real Pinia setup-store exposes.
const mockStore = reactive({
  projects: projectsRef,
  currentProjectId: currentProjectIdRef,
  currentProject: currentProjectComputed,
  pendingBannerKey: pendingBannerKeyRef,
  selectProject: selectProjectSpy,
  handleStaleCurrent: handleStaleCurrentSpy,
})

mockNuxtImport('useProjectsStore', () => () => mockStore)
mockNuxtImport('useI18n', () => () => ({ t: tSpy }))
mockNuxtImport('useLocalePath', () => () => (p: string) => p)

import ProjectSelector from '../../components/ProjectSelector.vue'

function makeProject(id: string, name: string, createdAt: string): Project {
  return {
    id,
    name,
    description: null,
    timezone: 'Europe/Kyiv',
    createdAt,
    updatedAt: createdAt,
    deletedAt: null,
  }
}

const P_OLD = makeProject('a1', 'Alpha', '2026-01-01T00:00:00Z')
const P_MID = makeProject('b2', 'Beta', '2026-02-01T00:00:00Z')
const P_NEW = makeProject('c3', 'Gamma', '2026-03-01T00:00:00Z')

describe('ProjectSelector', () => {
  beforeEach(() => {
    projectsRef.value = []
    currentProjectIdRef.value = null
    pendingBannerKeyRef.value = null
    selectProjectSpy.mockReset()
    handleStaleCurrentSpy.mockReset()
    tSpy.mockReset()
    tSpy.mockImplementation((key: string) => key)
  })

  it('renders projects sorted by createdAt desc', async () => {
    // Intentionally unsorted input
    projectsRef.value = [P_OLD, P_NEW, P_MID]
    currentProjectIdRef.value = P_NEW.id

    const wrapper = await mountSuspended(ProjectSelector)
    // open the dropdown
    await wrapper.find('[data-test="project-selector-trigger"]').trigger('click')
    await settle()

    const items = wrapper.findAll('[data-test^="project-selector-item-"]')
    expect(items).toHaveLength(3)
    expect(items[0].attributes('data-test')).toBe(`project-selector-item-${P_NEW.id}`)
    expect(items[1].attributes('data-test')).toBe(`project-selector-item-${P_MID.id}`)
    expect(items[2].attributes('data-test')).toBe(`project-selector-item-${P_OLD.id}`)
  })

  it('clicking item calls selectProject and closes dropdown', async () => {
    projectsRef.value = [P_NEW, P_MID]
    currentProjectIdRef.value = P_NEW.id

    const wrapper = await mountSuspended(ProjectSelector)
    await wrapper.find('[data-test="project-selector-trigger"]').trigger('click')
    await settle()

    expect(wrapper.find('[role="menu"]').exists()).toBe(true)

    await wrapper.find(`[data-test="project-selector-item-${P_MID.id}"]`).trigger('click')
    await settle()

    expect(selectProjectSpy).toHaveBeenCalledTimes(1)
    expect(selectProjectSpy).toHaveBeenCalledWith(P_MID.id)
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
  })

  it('create button disabled with tooltip when projects.length >= 5', async () => {
    projectsRef.value = [
      makeProject('p1', 'P1', '2026-01-01T00:00:00Z'),
      makeProject('p2', 'P2', '2026-01-02T00:00:00Z'),
      makeProject('p3', 'P3', '2026-01-03T00:00:00Z'),
      makeProject('p4', 'P4', '2026-01-04T00:00:00Z'),
      makeProject('p5', 'P5', '2026-01-05T00:00:00Z'),
    ]
    currentProjectIdRef.value = 'p1'

    const wrapper = await mountSuspended(ProjectSelector)
    await wrapper.find('[data-test="project-selector-trigger"]').trigger('click')
    await settle()

    const createEl = wrapper.find('[data-test="project-selector-create"]')
    expect(createEl.exists()).toBe(true)
    // At the limit: rendered as disabled (NOT a nav anchor) with aria-disabled + tooltip
    expect(createEl.element.tagName.toLowerCase()).not.toBe('a')
    expect(createEl.attributes('aria-disabled')).toBe('true')
    expect(createEl.attributes('title')).toBe('projects.create.limitReachedTooltip')
  })

  it('create button enabled when projects.length < 5', async () => {
    projectsRef.value = [P_NEW]
    currentProjectIdRef.value = P_NEW.id

    const wrapper = await mountSuspended(ProjectSelector)
    await wrapper.find('[data-test="project-selector-trigger"]').trigger('click')
    await settle()

    const createEl = wrapper.find('[data-test="project-selector-create"]')
    expect(createEl.exists()).toBe(true)
    // Below limit: rendered as a navigation link
    expect(createEl.element.tagName.toLowerCase()).toBe('a')
    expect(createEl.attributes('aria-disabled')).toBeUndefined()
  })

  it('settings link hidden when currentProject is null', async () => {
    projectsRef.value = [P_NEW]
    currentProjectIdRef.value = null

    const wrapper = await mountSuspended(ProjectSelector)
    await wrapper.find('[data-test="project-selector-trigger"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="project-selector-settings"]').exists()).toBe(false)
  })

  it('settings link visible with correct href when currentProject is set', async () => {
    projectsRef.value = [P_NEW]
    currentProjectIdRef.value = P_NEW.id

    const wrapper = await mountSuspended(ProjectSelector)
    await wrapper.find('[data-test="project-selector-trigger"]').trigger('click')
    await settle()

    const settingsEl = wrapper.find('[data-test="project-selector-settings"]')
    expect(settingsEl.exists()).toBe(true)
    expect(settingsEl.attributes('href')).toBe(`/projects/${P_NEW.id}/settings`)
  })

  it('trigger button has correct ARIA attributes (menu pattern)', async () => {
    projectsRef.value = [P_NEW]
    currentProjectIdRef.value = P_NEW.id

    const wrapper = await mountSuspended(ProjectSelector)
    const trigger = wrapper.find('[data-test="project-selector-trigger"]')
    expect(trigger.attributes('aria-haspopup')).toBe('menu')
    expect(trigger.attributes('aria-expanded')).toBe('false')

    await trigger.trigger('click')
    await settle()
    expect(trigger.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('[role="menu"]').exists()).toBe(true)
    expect(wrapper.findAll('[role="menuitem"]').length).toBeGreaterThan(0)
  })

  it('does not mutate the projects array (sorts via a copy)', async () => {
    const input = [P_OLD, P_NEW, P_MID]
    projectsRef.value = input
    currentProjectIdRef.value = P_NEW.id
    const originalOrder = input.map((p) => p.id)

    await mountSuspended(ProjectSelector)
    await settle()

    expect(projectsRef.value.map((p) => p.id)).toEqual(originalOrder)
  })
})
