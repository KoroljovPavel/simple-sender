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
const { storeMock, botStoreMock, navMock, toastMock } = vi.hoisted(() => ({
  storeMock: {
    fetchOne: vi.fn(),
    update: vi.fn(),
    activate: vi.fn(),
    pause: vi.fn(),
    // Task 5: author-tooling actions wired into the header.
    duplicate: vi.fn(),
    stopAllExecutions: vi.fn(),
    testRun: vi.fn(),
    // Task 6: message-preview panel action (only called when a message step is in focus).
    preview: vi.fn(),
  },
  botStoreMock: { current: null as { telegramUsername: string } | null, fetch: vi.fn() },
  navMock: vi.fn(),
  toastMock: { success: vi.fn(), error: vi.fn() },
}))

mockNuxtImport('useFunnelsStore', () => () => storeMock)
mockNuxtImport('useBotStore', () => () => botStoreMock)
mockNuxtImport('navigateTo', () => navMock)
mockNuxtImport('useLocalePath', () => () => (p: string) => p)
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1', funnelId: 'f1' } }))

// vue-sonner's `toast` is imported directly (not auto-imported) — mock the module so success/error
// fan-out is observable. Task 5: success → toast, network/unexpected error → toast, 422 code → inline.
vi.mock('vue-sonner', () => ({ toast: toastMock }))
// MENU has no remote fetch, but the shared FunnelStepForm auto-imports useApi for the tag/cf pickers —
// stub it so a direct mount never hits a real fetch.
mockNuxtImport('useApi', () => () => () => Promise.resolve([]))

// Render Teleport content inline for EVERY editor-page mount so (a) wrapper.find() sees the DialogPortal'd
// confirm dialog, and (b) the page's always-mounted Add/Edit/Stop dialogs unmount with the wrapper instead
// of leaving body-teleported fragments that race with the MENU section's body-wipe (nextSibling-of-null on
// a later unmount). Same per-mount stub used by settings/bot.spec.ts.
const editorMountOptions = {
  global: { stubs: { teleport: { template: '<div data-test-teleport><slot /></div>' } } },
}

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
    storeMock.duplicate.mockReset()
    storeMock.stopAllExecutions.mockReset()
    storeMock.testRun.mockReset()
    botStoreMock.fetch.mockReset().mockResolvedValue(null)
    botStoreMock.current = null
    navMock.mockReset()
    toastMock.success.mockReset()
    toastMock.error.mockReset()
  })

  it('maps a 422 activation code to an inline error and keeps status draft', async () => {
    storeMock.fetchOne.mockResolvedValue(draft())
    storeMock.update.mockResolvedValue(draft()) // activate flushes edits first
    storeMock.activate.mockRejectedValue({ statusCode: 422, data: { code: 'funnel_no_steps' } })

    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
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
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
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

    await mountSuspended(FunnelEditorPage, editorMountOptions)
    await settle()

    expect(navMock).toHaveBeenCalledWith('/projects/p1/funnels')
  })

  it('shows a retry banner (not a blank page) on a non-404 load failure', async () => {
    storeMock.fetchOne.mockRejectedValue({ statusCode: 500 })

    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
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

    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
    await settle()
    // Active funnel shows the Pause button (not Activate) and the one step row.
    expect(wrapper.find('[data-test="funnel-pause"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-step-row-0"]').exists()).toBe(true)

    await wrapper.get('[data-test="funnel-pause"]').trigger('click')
    await settle()
    expect(storeMock.pause).toHaveBeenCalledWith('f1')
    expect(wrapper.find('[data-test="funnel-status-paused"]').exists()).toBe(true)
  })

  // ─── Trigger auto-save readiness (Task 15 finding) ────────────────────────────
  // Switching the trigger type must NOT fire a PATCH while the new type's required value is still empty
  // (backend returns 422). The debounced auto-save only schedules once the active type is "ready".
  describe('trigger auto-save readiness', () => {
    beforeEach(() => {
      vi.useFakeTimers()
    })
    afterEach(() => {
      vi.runOnlyPendingTimers()
      vi.useRealTimers()
    })

    // Drive the watch+debounce deterministically: flush microtasks (watch callbacks run on a microtask),
    // advance past the 600ms debounce, then flush the resulting persist() promise.
    async function tick(ms: number): Promise<void> {
      await Promise.resolve()
      await vi.advanceTimersByTimeAsync(ms)
      await Promise.resolve()
    }

    async function mountLoaded(over: Partial<FunnelResponse> = {}) {
      storeMock.fetchOne.mockResolvedValue(draft(over))
      storeMock.update.mockImplementation((_id: string, body: Partial<FunnelResponse>) =>
        Promise.resolve(draft({ ...over, ...body })),
      )
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      // load() runs on a microtask; advance enough to settle the initial mount with fake timers.
      await tick(700)
      storeMock.update.mockClear()
      return wrapper
    }

    it('does NOT PATCH when the type switches to keyword with no keywords yet', async () => {
      const wrapper = await mountLoaded()
      await wrapper.get('[data-test="funnel-trigger-type-select"]').setValue('keyword')
      await tick(700)
      expect(storeMock.update).not.toHaveBeenCalled()
    })

    it('does NOT PATCH when the type switches to tag_added / custom_field_set / event with an empty value', async () => {
      for (const ty of ['tag_added', 'custom_field_set', 'event']) {
        const wrapper = await mountLoaded()
        await wrapper.get('[data-test="funnel-trigger-type-select"]').setValue(ty)
        await tick(700)
        expect(storeMock.update, `type ${ty} must not auto-save while empty`).not.toHaveBeenCalled()
      }
    })

    it('PATCHes keyword with keywords set and triggerValue null once a keyword is added', async () => {
      const wrapper = await mountLoaded()
      await wrapper.get('[data-test="funnel-trigger-type-select"]').setValue('keyword')
      await tick(700)
      expect(storeMock.update).not.toHaveBeenCalled()

      await wrapper.get('[data-test="funnel-trigger-keyword-input"]').setValue('hello')
      await wrapper.get('[data-test="funnel-trigger-keyword-add"]').trigger('click')
      await tick(700)

      expect(storeMock.update).toHaveBeenCalledTimes(1)
      const body = storeMock.update.mock.calls[0][1]
      expect(body.triggerType).toBe('keyword')
      expect(body.keywords).toEqual(['hello'])
      expect(body.triggerValue).toBeNull()
    })

    it('PATCHes event with triggerValue set and keywords [] once a valid slug is typed', async () => {
      const wrapper = await mountLoaded()
      await wrapper.get('[data-test="funnel-trigger-type-select"]').setValue('event')
      await tick(700)
      expect(storeMock.update).not.toHaveBeenCalled()

      await wrapper.get('[data-test="funnel-trigger-event-input"]').setValue('order_paid')
      await tick(700)

      expect(storeMock.update).toHaveBeenCalledTimes(1)
      const body = storeMock.update.mock.calls[0][1]
      expect(body.triggerType).toBe('event')
      expect(body.triggerValue).toBe('order_paid')
      expect(body.keywords).toEqual([])
    })

    it('on_start still persists an empty value but not an invalid one', async () => {
      const wrapper = await mountLoaded({ triggerType: 'on_start', triggerValue: 'promo' })
      // Empty is valid for on_start (bare /start) → persists.
      await wrapper.get('[data-test="funnel-trigger-value-input"]').setValue('')
      await tick(700)
      expect(storeMock.update).toHaveBeenCalledTimes(1)
      expect(storeMock.update.mock.calls[0][1].triggerValue).toBe('')

      storeMock.update.mockClear()
      // A value with spaces/specials fails TRIGGER_VALUE_RE → no PATCH.
      await wrapper.get('[data-test="funnel-trigger-value-input"]').setValue('bad value!')
      await tick(700)
      expect(storeMock.update).not.toHaveBeenCalled()
    })
  })

  it('maps a funnel_broken_edge 422 to an inline error and keeps status draft', async () => {
    storeMock.fetchOne.mockResolvedValue(draft({ steps: [{ stepType: 'SEND_MESSAGE', text: 'Hi' }] }))
    storeMock.update.mockResolvedValue(draft({ steps: [{ stepType: 'SEND_MESSAGE', text: 'Hi' }] }))
    storeMock.activate.mockRejectedValue({ statusCode: 422, data: { code: 'funnel_broken_edge' } })

    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
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

  // ─── Task 5: header action buttons (Duplicate / Stop-all / Test for me) ───────
  describe('header action buttons', () => {
    it('renders Duplicate / Stop-all / Test for me + Preview toggle in the header', async () => {
      storeMock.fetchOne.mockResolvedValue(draft())
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()

      expect(wrapper.find('[data-test="funnel-editor-duplicate"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="funnel-editor-stop-all"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="funnel-test-run"]').exists()).toBe(true)
      // Task 6: Preview toggle is present and starts OFF (panel not mounted until toggled).
      expect(wrapper.find('[data-test="funnel-preview-toggle"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="funnel-preview-panel"]').exists()).toBe(false)
    })

    it('Preview toggle mounts the message-preview panel', async () => {
      storeMock.fetchOne.mockResolvedValue(draft())
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()

      await wrapper.get('[data-test="funnel-preview-toggle"]').trigger('click')
      await settle()

      expect(wrapper.find('[data-test="funnel-preview-panel"]').exists()).toBe(true)
    })

    it('Duplicate calls the store with the funnelId and success-toasts', async () => {
      storeMock.fetchOne.mockResolvedValue(draft())
      storeMock.duplicate.mockResolvedValue(draft({ id: 'f1-copy', name: 'My funnel (copy)' }))
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()

      await wrapper.get('[data-test="funnel-editor-duplicate"]').trigger('click')
      await settle()

      expect(storeMock.duplicate).toHaveBeenCalledWith('f1')
      expect(toastMock.success).toHaveBeenCalled()
      expect(toastMock.error).not.toHaveBeenCalled()
    })

    it('Test for me on an unlinked bot shows an inline hint (not a toast)', async () => {
      storeMock.fetchOne.mockResolvedValue(draft())
      storeMock.testRun.mockRejectedValue({ statusCode: 422, data: { code: 'funnel_owner_not_linked' } })
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()

      await wrapper.get('[data-test="funnel-test-run"]').trigger('click')
      await settle()

      const err = wrapper.find('[data-test="funnel-test-run-error"]')
      expect(err.exists()).toBe(true)
      // Localized via errors.funnels.funnel_owner_not_linked — not a raw key.
      expect(err.text().trim().length).toBeGreaterThan(0)
      expect(err.text()).not.toContain('errors.funnels')
      expect(err.text()).not.toContain('funnel_owner_not_linked')
      // Business 422 → inline, never a toast.
      expect(toastMock.error).not.toHaveBeenCalled()
    })

    it('Test for me network error (no code) toasts, no inline message', async () => {
      storeMock.fetchOne.mockResolvedValue(draft())
      storeMock.testRun.mockRejectedValue({ statusCode: 500 })
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()

      await wrapper.get('[data-test="funnel-test-run"]').trigger('click')
      await settle()

      expect(toastMock.error).toHaveBeenCalled()
      expect(wrapper.find('[data-test="funnel-test-run-error"]').exists()).toBe(false)
    })

    it('Test for me success-toasts when the enroll registers', async () => {
      storeMock.fetchOne.mockResolvedValue(draft())
      storeMock.testRun.mockResolvedValue(undefined)
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()

      await wrapper.get('[data-test="funnel-test-run"]').trigger('click')
      await settle()

      expect(storeMock.testRun).toHaveBeenCalledWith('f1')
      expect(toastMock.success).toHaveBeenCalled()
      expect(wrapper.find('[data-test="funnel-test-run-error"]').exists()).toBe(false)
    })

    it('Test for me is present and enabled on a draft funnel', async () => {
      storeMock.fetchOne.mockResolvedValue(draft({ status: 'draft' }))
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()

      const btn = wrapper.get('[data-test="funnel-test-run"]')
      expect(btn.exists()).toBe(true)
      expect((btn.element as HTMLButtonElement).disabled).toBe(false)
    })

    it('Stop-all opens a confirm dialog, then reports the cancelled count', async () => {
      storeMock.fetchOne.mockResolvedValue(draft())
      storeMock.stopAllExecutions.mockResolvedValue({ cancelled: 4 })
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()

      // Not called until confirmed.
      await wrapper.get('[data-test="funnel-editor-stop-all"]').trigger('click')
      await settle()
      expect(storeMock.stopAllExecutions).not.toHaveBeenCalled()
      expect(wrapper.find('[data-test="funnel-stop-all-confirm"]').exists()).toBe(true)

      await wrapper.get('[data-test="funnel-stop-all-confirm"]').trigger('click')
      await settle()

      expect(storeMock.stopAllExecutions).toHaveBeenCalledWith('f1')
      // Count surfaced via the stopAll.result toast ({count} interpolation).
      const msg = toastMock.success.mock.calls.at(-1)?.[0] as string
      expect(msg).toContain('4')
    })

    it('Stop-all cancel is a no-op and closes the dialog', async () => {
      storeMock.fetchOne.mockResolvedValue(draft())
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()

      await wrapper.get('[data-test="funnel-editor-stop-all"]').trigger('click')
      await settle()
      await wrapper.get('[data-test="funnel-stop-all-cancel"]').trigger('click')
      await settle()

      expect(storeMock.stopAllExecutions).not.toHaveBeenCalled()
      expect(wrapper.find('[data-test="funnel-stop-all-confirm"]').exists()).toBe(false)
    })
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

  it('hides the internal step id / sentinel in the target picker options', async () => {
    // UX-fix: the target picker passes :show-value="false", so options show only the step number+name
    // (and "End"), never the raw Mongo step id or the __END__ sentinel as a grey suffix.
    await mountMenuForm()
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()

    const step = $('[data-test="step-menu-target-0-option-s1"]')
    expect(step.text()).toContain('1.') // step number + name still shown
    expect(step.text()).not.toContain('s1') // raw id suffix gone

    const end = $('[data-test="step-menu-target-0-option-__END__"]')
    expect(end.text().length).toBeGreaterThan(0)
    expect(end.text()).not.toContain('__END__') // sentinel suffix gone
  })

  it('keeps the timeout-target picker free of the raw step id', async () => {
    const wrapper = await mountMenuForm()
    await $('[data-test="step-menu-timeout-value"]').setValue('3')
    await $('[data-test="step-menu-timeout-unit"]').setValue('HOUR')
    await settle()
    await $('[data-test="step-menu-timeout-target-input"]').trigger('focus')
    await settle()

    const step = $('[data-test="step-menu-timeout-target-option-s2"]')
    expect(step.text()).not.toContain('s2')
    expect(wrapper).toBeTruthy()
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

  it('omits timeout fields when the author leaves the timeout blank', async () => {
    const wrapper = await mountMenuForm()
    await $('[data-test="step-menu-text-input"]').setValue('Pick one')
    // A single valid callback button; the timeout inputs are left untouched.
    await $('[data-test="step-menu-button-label-0"]').setValue('Continue')
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-target-0-option-s1"]').trigger('mousedown')
    await settle()
    await submitForm()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const step = emitted![0][0] as FunnelStep
    // Timeout omitted → all three fields unset (engine waits indefinitely).
    expect(step.timeoutValue).toBeUndefined()
    expect(step.timeoutUnit).toBeUndefined()
    expect(step.timeoutTargetStepId).toBeUndefined()
  })

  it('emits the timeout fields with the chosen unit and a step target', async () => {
    const wrapper = await mountMenuForm()
    await $('[data-test="step-menu-text-input"]').setValue('Pick one')
    await $('[data-test="step-menu-button-label-0"]').setValue('Continue')
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-target-0-option-s1"]').trigger('mousedown')
    await settle()
    // Engage the timeout: 3 HOUR → target step s2.
    await $('[data-test="step-menu-timeout-value"]').setValue('3')
    await $('[data-test="step-menu-timeout-unit"]').setValue('HOUR')
    await settle()
    await $('[data-test="step-menu-timeout-target-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-timeout-target-option-s2"]').trigger('mousedown')
    await settle()
    await submitForm()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const step = emitted![0][0] as FunnelStep
    expect(step.timeoutValue).toBe(3)
    expect(step.timeoutUnit).toBe('HOUR')
    expect(step.timeoutTargetStepId).toBe('s2')
  })

  it('encodes a timeout target of End as timeoutTargetStepId null', async () => {
    const wrapper = await mountMenuForm()
    await $('[data-test="step-menu-text-input"]').setValue('Pick one')
    await $('[data-test="step-menu-button-label-0"]').setValue('Continue')
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-target-0-option-s1"]').trigger('mousedown')
    await settle()
    // Timeout with the default End target (sentinel) → null.
    await $('[data-test="step-menu-timeout-value"]').setValue('30')
    await $('[data-test="step-menu-timeout-unit"]').setValue('MIN')
    await settle()
    await submitForm()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const step = emitted![0][0] as FunnelStep
    expect(step.timeoutValue).toBe(30)
    expect(step.timeoutUnit).toBe('MIN')
    // End sentinel → null (not the sentinel string, not "").
    expect(step.timeoutTargetStepId).toBeNull()
  })

  it('blocks submit when a timeout unit is set without a value', async () => {
    const wrapper = await mountMenuForm()
    await $('[data-test="step-menu-text-input"]').setValue('Pick one')
    await $('[data-test="step-menu-button-label-0"]').setValue('Continue')
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-target-0-option-s1"]').trigger('mousedown')
    await settle()
    // Unit chosen but value left blank → invalid (mirrors backend requireTimeout both-or-neither).
    await $('[data-test="step-menu-timeout-unit"]').setValue('HOUR')
    await settle()
    await submitForm()

    expect(maybe('[data-test="step-menu-timeout-error"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
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
