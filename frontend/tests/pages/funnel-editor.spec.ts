// @vitest-environment nuxt
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../helpers/settle'
import FunnelEditorPage from '../../pages/projects/[projectId]/funnels/[funnelId].vue'
import FunnelStepForm from '../../components/funnels/FunnelStepForm.vue'
import type { FunnelResponse, FunnelStep } from '../../types/funnel'

// The editor page owns the headline Task-10 behaviour: 422-code → inline errors.funnels.* (NOT a global
// toast) and the anti-IDOR 404 → graceful list redirect. The E2E covers it only against a live backend
// (test.skip without one), so these mount specs are the always-on guard for that logic.
const { storeMock, botStoreMock, navMock } = vi.hoisted(() => ({
  storeMock: {
    fetchOne: vi.fn(),
    update: vi.fn(),
    activate: vi.fn(),
    pause: vi.fn(),
  },
  botStoreMock: { current: null as { telegramUsername: string } | null, fetch: vi.fn() },
  navMock: vi.fn(),
}))

mockNuxtImport('useFunnelsStore', () => () => storeMock)
mockNuxtImport('useBotStore', () => () => botStoreMock)
mockNuxtImport('navigateTo', () => navMock)
mockNuxtImport('useLocalePath', () => () => (p: string) => p)
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1', funnelId: 'f1' } }))
// MENU has no remote fetch, but the shared FunnelStepForm auto-imports useApi for the tag/cf pickers —
// stub it so a direct mount never hits a real fetch.
mockNuxtImport('useApi', () => () => () => Promise.resolve([]))

function draft(over: Partial<FunnelResponse> = {}): FunnelResponse {
  return {
    id: 'f1',
    projectId: 'p1',
    name: 'My funnel',
    description: null,
    status: 'draft',
    triggerType: 'on_start',
    triggerValue: '',
    allowReEnter: false,
    steps: [],
    deepLink: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...over,
  }
}

describe('funnels/[funnelId] editor page', () => {
  beforeEach(() => {
    storeMock.fetchOne.mockReset()
    storeMock.update.mockReset()
    storeMock.activate.mockReset()
    storeMock.pause.mockReset()
    botStoreMock.fetch.mockReset().mockResolvedValue(null)
    botStoreMock.current = null
    navMock.mockReset()
  })

  it('maps a 422 activation code to an inline error and keeps status draft', async () => {
    storeMock.fetchOne.mockResolvedValue(draft())
    storeMock.update.mockResolvedValue(draft()) // activate flushes edits first
    storeMock.activate.mockRejectedValue({ statusCode: 422, data: { code: 'funnel_no_steps' } })

    const wrapper = await mountSuspended(FunnelEditorPage)
    await settle()

    await wrapper.get('[data-test="funnel-activate"]').trigger('click')
    await settle()

    const err = wrapper.find('[data-test="funnel-activate-error"]')
    expect(err.exists()).toBe(true)
    // Localized via errors.funnels.funnel_no_steps — not a raw key, not empty.
    expect(err.text().trim().length).toBeGreaterThan(0)
    expect(err.text()).not.toContain('errors.funnels')
    // Status badge stayed draft (activation rejected).
    expect(wrapper.find('[data-test="funnel-status-active"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-status-draft"]').exists()).toBe(true)
  })

  it('uses the code-specific message, not the generic fallback', async () => {
    // Litmus for the errors.funnels.<code> branch: a MAPPED code must render a different message than an
    // UNMAPPED code (which falls through to useApiError's generic). If the mapping branch were deleted,
    // both would resolve to the same generic string and this assertion would fail.
    async function activateErrorText(rejectValue: unknown): Promise<string> {
      storeMock.fetchOne.mockResolvedValue(draft())
      storeMock.update.mockResolvedValue(draft())
      storeMock.activate.mockReset().mockRejectedValue(rejectValue)
      const wrapper = await mountSuspended(FunnelEditorPage)
      await settle()
      await wrapper.get('[data-test="funnel-activate"]').trigger('click')
      await settle()
      return wrapper.get('[data-test="funnel-activate-error"]').text().trim()
    }

    const mapped = await activateErrorText({ statusCode: 422, data: { code: 'funnel_no_steps' } })
    const generic = await activateErrorText({ statusCode: 422, data: { code: 'totally_unknown_code' } })
    expect(mapped.length).toBeGreaterThan(0)
    expect(generic.length).toBeGreaterThan(0)
    expect(mapped).not.toBe(generic)
  })

  it('redirects to the list on a 404 (missing / cross-owner funnel)', async () => {
    storeMock.fetchOne.mockRejectedValue({ statusCode: 404 })

    await mountSuspended(FunnelEditorPage)
    await settle()

    expect(navMock).toHaveBeenCalledWith('/projects/p1/funnels')
  })

  it('shows a retry banner (not a blank page) on a non-404 load failure', async () => {
    storeMock.fetchOne.mockRejectedValue({ statusCode: 500 })

    const wrapper = await mountSuspended(FunnelEditorPage)
    await settle()

    expect(navMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="funnel-load-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-load-retry"]').exists()).toBe(true)
  })

  it('pause flips an active funnel and renders steps', async () => {
    storeMock.fetchOne.mockResolvedValue(
      draft({ status: 'active', steps: [{ stepType: 'SEND_MESSAGE', text: 'Hi' }], deepLink: 't.me/bot?start=' }),
    )
    storeMock.pause.mockResolvedValue(draft({ status: 'paused', steps: [{ stepType: 'SEND_MESSAGE', text: 'Hi' }] }))

    const wrapper = await mountSuspended(FunnelEditorPage)
    await settle()
    // Active funnel shows the Pause button (not Activate) and the one step row.
    expect(wrapper.find('[data-test="funnel-pause"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-step-row-0"]').exists()).toBe(true)

    await wrapper.get('[data-test="funnel-pause"]').trigger('click')
    await settle()
    expect(storeMock.pause).toHaveBeenCalledWith('f1')
    expect(wrapper.find('[data-test="funnel-status-paused"]').exists()).toBe(true)
  })

  it('maps a funnel_broken_edge 422 to an inline error and keeps status draft', async () => {
    storeMock.fetchOne.mockResolvedValue(draft({ steps: [{ stepType: 'SEND_MESSAGE', text: 'Hi' }] }))
    storeMock.update.mockResolvedValue(draft({ steps: [{ stepType: 'SEND_MESSAGE', text: 'Hi' }] }))
    storeMock.activate.mockRejectedValue({ statusCode: 422, data: { code: 'funnel_broken_edge' } })

    const wrapper = await mountSuspended(FunnelEditorPage)
    await settle()

    await wrapper.get('[data-test="funnel-activate"]').trigger('click')
    await settle()

    const err = wrapper.find('[data-test="funnel-activate-error"]')
    expect(err.exists()).toBe(true)
    // Localized via errors.funnels.funnel_broken_edge — not a raw key.
    expect(err.text().trim().length).toBeGreaterThan(0)
    expect(err.text()).not.toContain('errors.funnels')
    expect(err.text()).not.toContain('funnel_broken_edge')
    // Activation rejected → status stays draft.
    expect(wrapper.find('[data-test="funnel-status-active"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-status-draft"]').exists()).toBe(true)
  })
})

// ─── MENU step form (Phase 2, Task 6) ────────────────────────────────────────
// The MENU step is a message + inline keyboard: ≥1 callback button (label + target = another step or
// "End"), URL buttons (label + http(s) link). Mounted standalone (lighter than via the dialog) with the
// sibling-step context the target picker needs. data-test idiom throughout (patterns.md).
const SIBLINGS: FunnelStep[] = [
  { stepType: 'SEND_MESSAGE', text: 'Welcome', id: 's1' },
  { stepType: 'DELAY', delayValue: 5, delayUnit: 'MIN', id: 's2' },
]

function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}
function maybe(sel: string): Element | null {
  return document.querySelector(sel)
}

async function mountMenuForm(initial: FunnelStep | null = null) {
  const wrapper = await mountSuspended(FunnelStepForm, {
    props: { initial, siblingSteps: SIBLINGS, submitLabel: 'Save' },
    attachTo: document.body,
  })
  await settle()
  await $('[data-test="step-type-select"]').setValue('MENU')
  await settle()
  return wrapper
}
async function submitForm() {
  await $('[data-test="step-form"]').trigger('submit')
  await settle()
}

describe('FunnelStepForm — MENU', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders MENU in the step-type picker and shows the buttons section', async () => {
    const wrapper = await mountSuspended(FunnelStepForm, {
      props: { siblingSteps: SIBLINGS, submitLabel: 'Save' },
      attachTo: document.body,
    })
    await settle()
    const options = wrapper.findAll('[data-test="step-type-select"] option').map((o) => o.attributes('value'))
    expect(options).toContain('MENU')

    await $('[data-test="step-type-select"]').setValue('MENU')
    await settle()
    expect(maybe('[data-test="step-menu-buttons"]')).not.toBeNull()
    expect(maybe('[data-test="step-menu-text-input"]')).not.toBeNull()
  })

  it('adds and removes button rows', async () => {
    await mountMenuForm()
    // Starts with one row (a menu needs ≥1 callback button anyway).
    expect(maybe('[data-test="step-menu-button-row-0"]')).not.toBeNull()
    await $('[data-test="step-menu-add-button"]').trigger('click')
    await settle()
    expect(maybe('[data-test="step-menu-button-row-1"]')).not.toBeNull()
    // Each row has a label input and a type switch.
    expect(maybe('[data-test="step-menu-button-label-1"]')).not.toBeNull()
    expect(maybe('[data-test="step-menu-button-type-1"]')).not.toBeNull()

    await $('[data-test="step-menu-button-remove-1"]').trigger('click')
    await settle()
    expect(maybe('[data-test="step-menu-button-row-1"]')).toBeNull()
  })

  it('callback target picker lists the other steps plus End', async () => {
    await mountMenuForm()
    // Row 0 defaults to a callback button → its target picker is the SearchableSelect.
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()
    // Options = the sibling step ids + the "End" sentinel.
    expect(maybe('[data-test="step-menu-target-0-option-s1"]')).not.toBeNull()
    expect(maybe('[data-test="step-menu-target-0-option-s2"]')).not.toBeNull()
    expect(maybe('[data-test="step-menu-target-0-option-__END__"]')).not.toBeNull()
  })

  it('blocks submit on an empty button label', async () => {
    const wrapper = await mountMenuForm()
    await $('[data-test="step-menu-text-input"]').setValue('Pick one')
    // Leave the label blank; set a valid target so only the label is wrong.
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-target-0-option-s1"]').trigger('mousedown')
    await settle()
    await submitForm()
    expect(maybe('[data-test="step-menu-button-label-error-0"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('blocks submit when the MENU has no callback button', async () => {
    const wrapper = await mountMenuForm()
    await $('[data-test="step-menu-text-input"]').setValue('Links only')
    // Turn the single row into a URL button → zero callback buttons.
    await $('[data-test="step-menu-button-type-0"]').setValue('url')
    await settle()
    await $('[data-test="step-menu-button-label-0"]').setValue('Open site')
    await $('[data-test="step-menu-button-url-0"]').setValue('https://example.com')
    await submitForm()
    expect(maybe('[data-test="step-menu-error"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('blocks a non-http(s) url button', async () => {
    const wrapper = await mountMenuForm()
    await $('[data-test="step-menu-text-input"]').setValue('Menu')
    // Row 0 = a valid callback (so the ≥1-callback rule passes and only the URL is wrong).
    await $('[data-test="step-menu-button-label-0"]').setValue('Continue')
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-target-0-option-s1"]').trigger('mousedown')
    await settle()
    // Row 1 = a URL button with a javascript: scheme.
    await $('[data-test="step-menu-add-button"]').trigger('click')
    await settle()
    await $('[data-test="step-menu-button-type-1"]').setValue('url')
    await settle()
    await $('[data-test="step-menu-button-label-1"]').setValue('Evil')
    await $('[data-test="step-menu-button-url-1"]').setValue('javascript:alert(1)')
    await submitForm()
    expect(maybe('[data-test="step-menu-button-url-error-1"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('emits a valid MENU with End encoded as targetStepId null and preserves id/next', async () => {
    const wrapper = await mountMenuForm({
      stepType: 'MENU',
      text: 'Old',
      buttons: [],
      id: 'menu1',
      next: null,
    })
    await $('[data-test="step-menu-text-input"]').setValue('Pick one')
    // Row 0: callback → step s1.
    await $('[data-test="step-menu-button-label-0"]').setValue('Continue')
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-target-0-option-s1"]').trigger('mousedown')
    await settle()
    // Row 1: callback → End.
    await $('[data-test="step-menu-add-button"]').trigger('click')
    await settle()
    await $('[data-test="step-menu-button-label-1"]').setValue('Finish')
    await $('[data-test="step-menu-target-1-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-target-1-option-__END__"]').trigger('mousedown')
    await settle()
    await submitForm()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const step = emitted![0][0] as FunnelStep
    expect(step.stepType).toBe('MENU')
    expect(step.text).toBe('Pick one')
    expect(step.id).toBe('menu1')
    expect(step.buttons).toEqual([
      { type: 'callback', label: 'Continue', targetStepId: 's1', url: null },
      // End → targetStepId null (NOT "" — empty string is a broken edge server-side).
      { type: 'callback', label: 'Finish', targetStepId: null, url: null },
    ])
  })

  it('pre-fills the buttons of an edited MENU step', async () => {
    await mountMenuForm({
      stepType: 'MENU',
      text: 'Existing',
      id: 'menu1',
      buttons: [
        { type: 'callback', label: 'Go', targetStepId: 's1', url: null },
        { type: 'url', label: 'Site', targetStepId: null, url: 'https://example.com' },
      ],
    })
    expect((($('[data-test="step-menu-button-label-0"]').element) as HTMLInputElement).value).toBe('Go')
    expect((($('[data-test="step-menu-button-label-1"]').element) as HTMLInputElement).value).toBe('Site')
    // Row 1 is a URL button → its url input shows the stored link.
    expect((($('[data-test="step-menu-button-url-1"]').element) as HTMLInputElement).value).toBe('https://example.com')
  })
})
