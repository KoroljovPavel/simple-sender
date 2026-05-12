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

  it('create button disabled with VISIBLE limit text when projects.length >= 5', async () => {
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
    // At the limit: rendered as disabled (NOT a nav anchor) with aria-disabled.
    expect(createEl.element.tagName.toLowerCase()).not.toBe('a')
    expect(createEl.attributes('aria-disabled')).toBe('true')
    // Regression: native title is NOT used — it was invisible on disabled buttons
    // and on mobile (smoke-test 2.1 finding). Limit text must live in the DOM.
    expect(createEl.attributes('title')).toBeUndefined()
    // Limit text rendered visibly under the disabled button (i18n key in test env).
    const limitText = wrapper.find('[data-test="project-selector-create-limit-text"]')
    expect(limitText.exists()).toBe(true)
    expect(limitText.text()).toBe('projects.create.limitReachedTooltip')
    // Linked via aria-describedby so screen readers announce the limit when
    // the user focuses the disabled item.
    expect(createEl.attributes('aria-describedby')).toBe('project-selector-create-limit-text')
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

  it('settings link hidden when currentProject is null; trigger shows placeholder label', async () => {
    projectsRef.value = [P_NEW]
    currentProjectIdRef.value = null

    const wrapper = await mountSuspended(ProjectSelector)
    const trigger = wrapper.find('[data-test="project-selector-trigger"]')
    expect(trigger.text()).toContain('layout.projectSelector.placeholder')

    await trigger.trigger('click')
    await settle()

    expect(wrapper.find('[data-test="project-selector-settings"]').exists()).toBe(false)
  })

  it('settings link hidden when currentProjectId is set but project missing (mid-fetch race)', async () => {
    projectsRef.value = [P_NEW]
    currentProjectIdRef.value = 'not-in-list'

    const wrapper = await mountSuspended(ProjectSelector)
    const trigger = wrapper.find('[data-test="project-selector-trigger"]')
    // currentProject computed returns null → trigger renders the placeholder.
    expect(trigger.text()).toContain('layout.projectSelector.placeholder')

    await trigger.trigger('click')
    await settle()

    expect(wrapper.find('[data-test="project-selector-settings"]').exists()).toBe(false)
  })

  it('filters out soft-deleted projects from the dropdown (AC-19)', async () => {
    const softDeleted: Project = {
      ...makeProject('zz', 'Zombie', '2026-04-01T00:00:00Z'),
      deletedAt: '2026-04-02T00:00:00Z',
    }
    projectsRef.value = [P_NEW, softDeleted, P_MID]
    currentProjectIdRef.value = P_NEW.id

    const wrapper = await mountSuspended(ProjectSelector)
    await wrapper.find('[data-test="project-selector-trigger"]').trigger('click')
    await settle()

    expect(wrapper.find(`[data-test="project-selector-item-${softDeleted.id}"]`).exists()).toBe(false)
    const items = wrapper.findAll('[data-test^="project-selector-item-"]')
    expect(items).toHaveLength(2)
  })

  it('create button stays enabled when 4 active + 2 soft-deleted (AC-30 counts active only)', async () => {
    const mkSoft = (id: string, created: string): Project => ({
      ...makeProject(id, id.toUpperCase(), created),
      deletedAt: '2026-04-10T00:00:00Z',
    })
    projectsRef.value = [
      makeProject('p1', 'P1', '2026-01-01T00:00:00Z'),
      makeProject('p2', 'P2', '2026-01-02T00:00:00Z'),
      makeProject('p3', 'P3', '2026-01-03T00:00:00Z'),
      makeProject('p4', 'P4', '2026-01-04T00:00:00Z'),
      mkSoft('p5', '2026-01-05T00:00:00Z'),
      mkSoft('p6', '2026-01-06T00:00:00Z'),
    ]
    currentProjectIdRef.value = 'p1'

    const wrapper = await mountSuspended(ProjectSelector)
    await wrapper.find('[data-test="project-selector-trigger"]').trigger('click')
    await settle()

    const createEl = wrapper.find('[data-test="project-selector-create"]')
    // 4 active < 5 → rendered as nav anchor, NOT a disabled button.
    expect(createEl.element.tagName.toLowerCase()).toBe('a')
    expect(createEl.attributes('aria-disabled')).toBeUndefined()
  })

  it('outside-click closes the dropdown', async () => {
    projectsRef.value = [P_NEW]
    currentProjectIdRef.value = P_NEW.id

    const wrapper = await mountSuspended(ProjectSelector)
    await wrapper.find('[data-test="project-selector-trigger"]').trigger('click')
    await settle()
    expect(wrapper.find('[role="menu"]').exists()).toBe(true)

    // Dispatch a mousedown on the document body — outside both panel and trigger refs.
    document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await settle()
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
  })

  it('Escape closes the dropdown', async () => {
    projectsRef.value = [P_NEW]
    currentProjectIdRef.value = P_NEW.id

    const wrapper = await mountSuspended(ProjectSelector)
    await wrapper.find('[data-test="project-selector-trigger"]').trigger('click')
    await settle()
    expect(wrapper.find('[role="menu"]').exists()).toBe(true)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await settle()
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
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
