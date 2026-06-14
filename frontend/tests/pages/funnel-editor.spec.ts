// @vitest-environment nuxt
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../helpers/settle'
import FunnelEditorPage from '../../pages/projects/[projectId]/funnels/[funnelId].vue'
import FunnelStepForm from '../../components/funnels/FunnelStepForm.vue'
import FunnelCanvas from '../../components/funnels/FunnelCanvas.client.vue'
import type { FunnelResponse, FunnelStep, FunnelTrigger, FunnelSummaryResponse } from '../../types/funnel'

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
    // Task 4 (Phase 5): SUBSCRIBE_TO_FUNNEL target picker reads funnels + lazy-fetches via fetch('all').
    // `funnels` is assigned a real ref below (out of vi.hoisted, where `ref` is not yet importable) so the
    // form's storeToRefs(...) unwraps it — a plain array would not be reactive.
    fetch: vi.fn(),
    funnels: undefined as unknown as ReturnType<typeof ref<FunnelSummaryResponse[]>>,
  },
  botStoreMock: { current: null as { telegramUsername: string } | null, fetch: vi.fn() },
  navMock: vi.fn(),
  toastMock: { success: vi.fn(), error: vi.fn() },
}))

// Attach the reactive funnels ref now that `ref` is imported (vi.hoisted runs before imports).
storeMock.funnels = ref<FunnelSummaryResponse[]>([])

mockNuxtImport('useFunnelsStore', () => () => storeMock)
mockNuxtImport('useBotStore', () => () => botStoreMock)
mockNuxtImport('navigateTo', () => navMock)
mockNuxtImport('useLocalePath', () => () => (p: string) => p)
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1', funnelId: 'f1' } }))

// vue-sonner's `toast` is imported directly (not auto-imported) — mock the module so success/error
// fan-out is observable. Task 5: success → toast, network/unexpected error → toast, 422 code → inline.
vi.mock('vue-sonner', () => ({ toast: toastMock }))
// The composer keyboard has no remote fetch, but the shared FunnelStepForm auto-imports useApi for the
// tag/cf pickers — stub it so a direct mount never hits a real fetch.
mockNuxtImport('useApi', () => () => () => Promise.resolve([]))

// Render Teleport content inline for EVERY editor-page mount so (a) wrapper.find() sees the DialogPortal'd
// confirm dialog, and (b) the page's always-mounted Add/Edit/Stop dialogs unmount with the wrapper instead
// of leaving body-teleported fragments that race with the composer keyboard's body-wipe (nextSibling-of-null
// on a later unmount). Same per-mount stub used by settings/bot.spec.ts.
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
    // Phase 8 (17-funnel-multi-entry): a funnel carries a LIST of triggers (the flat trio is gone). Always
    // at least the on_start main entry; tests add event triggers via the panel.
    triggers: [{ triggerType: 'on_start', triggerValue: '', keywords: null, entryStepId: null }],
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
      draft({ status: 'active', steps: [{ stepType: 'MESSAGE', blocks: [{ type: 'TEXT', text: 'Hi' }] }], deepLink: 't.me/bot?start=' }),
    )
    storeMock.pause.mockResolvedValue(draft({ status: 'paused', steps: [{ stepType: 'MESSAGE', blocks: [{ type: 'TEXT', text: 'Hi' }] }] }))

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

  // ─── Trigger array persistence via the canvas (Decision A / MAJOR-2 — 18-funnel-canvas) ───────
  // On the editor page the FunnelTriggersPanel is now READ-ONLY (the canvas side panel is the SOLE
  // trigger-editing surface). A committed trigger edit therefore arrives via the canvas's `update:triggers`
  // emit (→ onCanvasTriggers → triggers ref → the EXISTING full-replace persist(), one save path). The page's
  // per-element triggerReady gate + 600ms debounce still guards the deep-watch (in-place field edits), but a
  // committed canvas array is a discrete edit that persists directly. These tests assert: the panel renders
  // read-only (no Add/edit controls), and a canvas-committed array persists as a SANITIZED array (never the
  // flat trio).
  describe('trigger array persistence (read-only panel + canvas-committed edits)', () => {
    async function mountLoaded(over: Partial<FunnelResponse> = {}) {
      storeMock.fetchOne.mockResolvedValue(draft(over))
      storeMock.update.mockImplementation((_id: string, body: Partial<FunnelResponse>) =>
        Promise.resolve(draft({ ...over, ...body })),
      )
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()
      storeMock.update.mockClear()
      return wrapper
    }

    // Relay a new triggers array through the canvas (the single trigger-editing surface, Decision A).
    function emitTriggers(wrapper: Awaited<ReturnType<typeof mountLoaded>>, triggers: FunnelTrigger[]): void {
      wrapper.findComponent(FunnelCanvas).vm.$emit('update:triggers', triggers)
    }

    const onStart = (over: Partial<FunnelTrigger> = {}): FunnelTrigger => ({
      triggerType: 'on_start',
      triggerValue: '',
      keywords: null,
      entryStepId: null,
      ...over,
    })
    const event = (over: Partial<FunnelTrigger> = {}): FunnelTrigger => ({
      triggerType: 'event',
      triggerValue: 'order_paid',
      keywords: null,
      entryStepId: 'step1',
      ...over,
    })

    it('renders the Triggers panel read-only (no Add/edit/delete controls) but still shows the triggers', async () => {
      const wrapper = await mountLoaded()
      // The real panel is mounted (not stubbed) as a VIEW: on_start slot shown, read-only notice present, but
      // no Add control (the canvas side panel owns trigger editing — Decision A).
      expect(wrapper.find('[data-test="funnel-triggers"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="funnel-trigger-on-start"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="funnel-triggers-readonly-notice"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="funnel-trigger-add"]').exists()).toBe(false)
    })

    it('persists a canvas-committed trigger array as a SANITIZED array (never the flat trio)', async () => {
      const wrapper = await mountLoaded({
        steps: [{ stepType: 'MESSAGE', id: 'step1', blocks: [{ type: 'TEXT', text: 'Hi' }] }],
      })
      // The canvas commits a completed array (Decision A — the sole trigger-editing surface).
      emitTriggers(wrapper, [onStart(), event({ triggerValue: 'order_paid' })])
      await settle()

      expect(storeMock.update).toHaveBeenCalledTimes(1)
      const body = storeMock.update.mock.calls[0][1]
      // The payload carries the ARRAY, never the flat trio.
      expect(Array.isArray(body.triggers)).toBe(true)
      expect(body.triggerType).toBeUndefined()
      expect(body.triggerValue).toBeUndefined()
      expect(body.keywords).toBeUndefined()
      // on_start main entry + the committed event trigger.
      expect(body.triggers).toHaveLength(2)
      const ev = body.triggers.find((tr: { triggerType: string }) => tr.triggerType === 'event')
      expect(ev.triggerValue).toBe('order_paid')
      expect(ev.entryStepId).toBe('step1')
      // event owns triggerValue and clears keywords (per-element sanitizer).
      expect(ev.keywords).toEqual([])
    })

    // ─── Deep-watch debounce persist (regression guard for the audit-fix orphan) ──────────
    // The canvas-committed array test above rides onCanvasTriggers → persist() DIRECTLY (no debounce). It does
    // NOT cover the page's OTHER live trigger-persist path: the deep `watch(triggers, scheduleTriggerPersist,
    // {deep:true})` that fires on an IN-PLACE field edit of an existing element, gated by triggerReady + a 600ms
    // debounce. The audit-fix removed the 7 panel-level tests that used to exercise this. These two cases restore
    // it by mutating a field through the live `triggers` array the page binds into the canvas prop (the same
    // reference the deep watch observes — exactly how a live in-place edit reaches the page model).
    //
    // Drive an in-place field edit on an existing trigger element (NOT a new-array commit). props('triggers')
    // is the live array the page owns and the deep watch observes; mutating an element field is the in-place
    // edit the debounce path guards.
    function liveTriggers(wrapper: Awaited<ReturnType<typeof mountLoaded>>): FunnelTrigger[] {
      return wrapper.findComponent(FunnelCanvas).props('triggers') as FunnelTrigger[]
    }
    // Real-timer wait past the 600ms debounce (mirrors the read-only-list suite's 700ms drain). Fake timers
    // would not interleave with the mount's promise microtasks the same way the existing suite expects.
    const PAST_DEBOUNCE = 700

    it('debounce-persists an in-place trigger field edit exactly once after 600ms (sanitized value)', async () => {
      const wrapper = await mountLoaded({
        steps: [{ stepType: 'MESSAGE', id: 'step1', blocks: [{ type: 'TEXT', text: 'Hi' }] }],
        // Seed a COMPLETE event trigger so every element starts ready (the gate is satisfied); the edit below
        // changes its value in place — the deep watch must schedule one debounced PATCH.
        triggers: [onStart(), event({ triggerValue: 'order_paid', entryStepId: 'step1' })],
      })
      // Drain the known on-mount autosave (a ready trigger array debounce-persists once on mount) past the
      // window, THEN clear the spy so the count below reflects ONLY this test's in-place edit.
      await new Promise((r) => setTimeout(r, PAST_DEBOUNCE))
      await settle()
      storeMock.update.mockClear()

      // Two in-place keystroke-style edits inside the 600ms window — the debounce must coalesce them into a
      // SINGLE persist (not one PATCH per keystroke).
      const live = liveTriggers(wrapper)
      live[1].triggerValue = 'order_pai'
      await settle()
      live[1].triggerValue = 'order_shipped'
      await settle()
      // Not yet — still inside the debounce window (settle only advances ~50ms).
      expect(storeMock.update).not.toHaveBeenCalled()

      await new Promise((r) => setTimeout(r, PAST_DEBOUNCE))
      await settle()

      // Exactly one PATCH after the debounce, carrying the final sanitized value (the array, never the trio).
      expect(storeMock.update).toHaveBeenCalledTimes(1)
      const body = storeMock.update.mock.calls[0][1]
      expect(Array.isArray(body.triggers)).toBe(true)
      const ev = body.triggers.find((tr: { triggerType: string }) => tr.triggerType === 'event')
      expect(ev.triggerValue).toBe('order_shipped')
      expect(ev.entryStepId).toBe('step1')
      expect(ev.keywords).toEqual([])
    })

    it('does NOT debounce-persist while a trigger is incomplete (triggerReady gate)', async () => {
      const wrapper = await mountLoaded({
        steps: [{ stepType: 'MESSAGE', id: 'step1', blocks: [{ type: 'TEXT', text: 'Hi' }] }],
        triggers: [onStart(), event({ triggerValue: 'order_paid', entryStepId: 'step1' })],
      })
      // Drain the on-mount autosave + clear, so the silence below counts only the incomplete in-place edit.
      await new Promise((r) => setTimeout(r, PAST_DEBOUNCE))
      await settle()
      storeMock.update.mockClear()

      // Clear the event trigger's required value in place → the array is now NOT every(triggerReady), so the
      // gate must cancel/withhold the scheduled PATCH even after the full debounce window elapses.
      liveTriggers(wrapper)[1].triggerValue = ''
      await settle()
      await new Promise((r) => setTimeout(r, PAST_DEBOUNCE))
      await settle()

      expect(storeMock.update).not.toHaveBeenCalled()
    })
  })

  it('maps a funnel_broken_edge 422 to an inline error and keeps status draft', async () => {
    storeMock.fetchOne.mockResolvedValue(draft({ steps: [{ stepType: 'MESSAGE', blocks: [{ type: 'TEXT', text: 'Hi' }] }] }))
    storeMock.update.mockResolvedValue(draft({ steps: [{ stepType: 'MESSAGE', blocks: [{ type: 'TEXT', text: 'Hi' }] }] }))
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

    it('Duplicate calls the store, success-toasts, and navigates into the NEW funnel editor', async () => {
      storeMock.fetchOne.mockResolvedValue(draft())
      storeMock.duplicate.mockResolvedValue(draft({ id: 'f1-copy', name: 'My funnel (copy)' }))
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()

      await wrapper.get('[data-test="funnel-editor-duplicate"]').trigger('click')
      await settle()

      expect(storeMock.duplicate).toHaveBeenCalledWith('f1')
      expect(toastMock.success).toHaveBeenCalled()
      expect(toastMock.error).not.toHaveBeenCalled()
      // Bug fix: redirect to the duplicated funnel's editor (NOT stay on the source funnel). useLocalePath
      // is mocked to identity, so navigateTo receives the bare path with the NEW id.
      expect(navMock).toHaveBeenCalledWith('/projects/p1/funnels/f1-copy')
    })

    it('Duplicate failure toasts and does NOT navigate', async () => {
      storeMock.fetchOne.mockResolvedValue(draft())
      storeMock.duplicate.mockRejectedValue({ statusCode: 500 })
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()

      await wrapper.get('[data-test="funnel-editor-duplicate"]').trigger('click')
      await settle()

      expect(toastMock.error).toHaveBeenCalled()
      expect(toastMock.success).not.toHaveBeenCalled()
      expect(navMock).not.toHaveBeenCalled()
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

  // ─── Preview selection: click any step row to drive the preview panel ──────────
  // The panel previews the clicked step (message → render via store.preview; non-message → placeholder).
  // Default (no click, no edit dialog) = the first message step. The clicked row gets a highlight.
  describe('preview step selection', () => {
    // Steps: a non-message DELAY first, then two message steps, so the default (first MESSAGE step) is
    // NOT row 0 — proving the fallback skips non-message steps, and a click can override it.
    const MIXED_STEPS: FunnelStep[] = [
      { stepType: 'DELAY', id: 'd0', delayValue: 1, delayUnit: 'MIN' },
      { stepType: 'MESSAGE', id: 'm1', blocks: [{ type: 'TEXT', text: 'First message', parseMode: null }] },
      { stepType: 'MESSAGE', id: 'm2', blocks: [{ type: 'TEXT', text: 'Second message', parseMode: null }] },
    ]

    // NOTE (Task 7/8 boundary): the preview RENDER details (block markers, response shape) are owned by
    // Task 8's FunnelMessagePreview. These page-level cases only assert the editor's step-SELECTION wiring
    // (which step drives the panel + the row highlight); they use Task 8's current component contract
    // (preview({stepType, blocks}) → {renderedBlocks, sampleData, kind}; funnel-preview-block-N markers).
    async function mountWithPreview(steps: FunnelStep[] = MIXED_STEPS) {
      storeMock.fetchOne.mockResolvedValue(draft({ steps }))
      storeMock.preview.mockReset().mockResolvedValue({
        renderedBlocks: [{ type: 'TEXT', text: 'rendered' }],
        sampleData: false,
        kind: 'message',
      })
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      await settle()
      await wrapper.get('[data-test="funnel-preview-toggle"]').trigger('click')
      await settle()
      return wrapper
    }

    it('defaults to the first MESSAGE step (skips the leading non-message step)', async () => {
      const wrapper = await mountWithPreview()
      // Default with no click = row 1 (m1) → backend previewed with its id (render path), heading shows
      // step 2 (1-based). The first-tick preview is synchronous (panel primes immediately on mount).
      expect(storeMock.preview).toHaveBeenLastCalledWith('f1', 'm1', expect.objectContaining({ stepType: 'MESSAGE' }))
      const heading = wrapper.get('[data-test="funnel-preview-step-heading"]')
      expect(heading.text()).toContain('2')
      // The defaulted row is highlighted.
      expect(wrapper.get('[data-test="funnel-step-row-1"]').classes().join(' ')).toContain('ring-2')
    })

    it('clicking a message step row drives the preview to THAT step and highlights it', async () => {
      const wrapper = await mountWithPreview()

      await wrapper.get('[data-test="funnel-step-select-2"]').trigger('click')
      await settle()

      // Heading now names the clicked step (row 2 → step 3, 1-based) and the rendered (message) body shows.
      expect(wrapper.get('[data-test="funnel-preview-step-heading"]').text()).toContain('3')
      expect(wrapper.find('[data-test="funnel-preview-block-0"]').exists()).toBe(true)
      // Highlight moved to the clicked row, off the default.
      expect(wrapper.get('[data-test="funnel-step-row-2"]').classes().join(' ')).toContain('ring-2')
      expect(wrapper.get('[data-test="funnel-step-row-1"]').classes().join(' ')).not.toContain('ring-2')
    })

    it('clicking a NON-message step row shows the placeholder (no backend call) + heading', async () => {
      const wrapper = await mountWithPreview()
      storeMock.preview.mockClear()

      await wrapper.get('[data-test="funnel-step-select-0"]').trigger('click')
      await settle()

      expect(storeMock.preview).not.toHaveBeenCalled()
      expect(wrapper.find('[data-test="funnel-preview-placeholder"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="funnel-preview-block-0"]').exists()).toBe(false)
      // Heading still tells which step (row 0 → step 1) is in focus.
      expect(wrapper.get('[data-test="funnel-preview-step-heading"]').text()).toContain('1')
      expect(wrapper.get('[data-test="funnel-step-row-0"]').classes().join(' ')).toContain('ring-2')
    })

    it('the read-only list exposes no structural action buttons but row selection still drives preview', async () => {
      // Task 7 (Decision 2): the canvas is the sole structural-editing surface, so the vertical list is now
      // VIEW-ONLY — its move/edit/delete affordances are gone. The non-structural `select` (preview driving)
      // affordance is preserved, so clicking a row still moves the preview + highlight.
      const wrapper = await mountWithPreview()
      await wrapper.get('[data-test="funnel-step-select-2"]').trigger('click')
      await settle()

      // No structural mutators rendered for any row.
      expect(wrapper.find('[data-test="funnel-step-edit-1"]').exists()).toBe(false)
      expect(wrapper.find('[data-test="funnel-step-move-down-1"]').exists()).toBe(false)
      expect(wrapper.find('[data-test="funnel-step-delete-1"]').exists()).toBe(false)

      // Selection (a view affordance) still works: row 2 drives the preview + carries the highlight.
      expect(wrapper.get('[data-test="funnel-preview-step-heading"]').text()).toContain('3')
      expect(wrapper.get('[data-test="funnel-step-row-2"]').classes().join(' ')).toContain('ring-2')
    })

    it('clicking another row switches the preview (selection-driven, no list-opened edit dialog)', async () => {
      // With the list read-only, the preview is driven purely by row selection (the former list→edit-dialog
      // pin path is gone). Clicking a different row re-targets the preview + moves the highlight.
      const wrapper = await mountWithPreview()

      await wrapper.get('[data-test="funnel-step-select-1"]').trigger('click')
      await settle()
      expect(wrapper.get('[data-test="funnel-preview-step-heading"]').text()).toContain('2')

      await wrapper.get('[data-test="funnel-step-select-2"]').trigger('click')
      await settle()
      expect(wrapper.get('[data-test="funnel-preview-step-heading"]').text()).toContain('3')
      expect(wrapper.get('[data-test="funnel-step-row-2"]').classes().join(' ')).toContain('ring-2')
      expect(wrapper.get('[data-test="funnel-step-row-1"]').classes().join(' ')).not.toContain('ring-2')
    })
  })
})

// ─── Task 7 (18-funnel-canvas): canvas wiring + read-only list + escaped notes ──────────────────────────
// The canvas is the single live structural-editing surface. The page mounts FunnelCanvas (client-only),
// relays its emits into the local model and persists via the existing debounced full-replace persist().
// These specs assert the WIRING (drag-stop → canvasPosition → update PATCH), the read-only list, and the
// stored-XSS guard (note text rendered escaped via {{ }}, never v-html). Live Vue Flow drag is user-verified;
// the drag-stop event is driven through the child component's emit (the same event onNodeDragStop re-emits).
describe('funnels/[funnelId] canvas wiring (Task 7)', () => {
  beforeEach(() => {
    storeMock.fetchOne.mockReset()
    storeMock.update.mockReset()
    botStoreMock.fetch.mockReset().mockResolvedValue(null)
    botStoreMock.current = null
    navMock.mockReset()
  })

  function step(over: Partial<FunnelStep> = {}): FunnelStep {
    return { stepType: 'MESSAGE', id: 's1', blocks: [{ type: 'TEXT', text: 'Hi' }], next: 's2', ...over }
  }

  it('mounts the canvas client-only on the editor page', async () => {
    storeMock.fetchOne.mockResolvedValue(draft({ steps: [step()] }))
    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
    await settle()

    expect(wrapper.find('[data-test="funnel-canvas-host"]').exists()).toBe(true)
    expect(wrapper.findComponent(FunnelCanvas).exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-canvas"]').exists()).toBe(true)
  })

  it('drag-stop writes canvasPosition into the matching step and PATCHes (sibling field unchanged)', async () => {
    const seed = draft({ steps: [step({ id: 's1', next: 's2' })] })
    storeMock.fetchOne.mockResolvedValue(seed)
    storeMock.update.mockImplementation((_id: string, body: Partial<FunnelResponse>) =>
      Promise.resolve(draft({ ...body })),
    )
    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
    await settle()
    storeMock.update.mockClear()

    // Drive the canvas's drag-stop event for the saved step node (its id IS the node id).
    wrapper.findComponent(FunnelCanvas).vm.$emit('node-drag-stop', { nodeId: 's1', position: { x: 120, y: 240 } })
    await settle()

    expect(storeMock.update).toHaveBeenCalledTimes(1)
    const body = storeMock.update.mock.calls[0][1] as Partial<FunnelResponse>
    const persisted = body.steps?.find((s) => s.id === 's1')
    expect(persisted?.canvasPosition).toEqual({ x: 120, y: 240 })
    // The drag must only touch canvasPosition — the step's `next` edge is left intact.
    expect(persisted?.next).toBe('s2')
  })

  it('drag-stop writes canvasPosition into the matching trigger and the trigger PATCHes (value-based)', async () => {
    // Only step nodes were exercised above. Trigger entry nodes carry their own canvasPosition too: the canvas
    // emits `trigger:<i>` for non-on_start entries (on_start emits `start`). Seed [on_start, event] so index 1 is
    // a real trigger node (`trigger:1`); the drag must write {x,y} onto triggers[1] and survive sanitizeTrigger
    // into the PATCH body (a regression guard — the sanitizer must not strip canvasPosition).
    const eventTrigger: FunnelTrigger = {
      triggerType: 'event',
      triggerValue: 'signup',
      keywords: null,
      entryStepId: 's1',
    }
    const seed = draft({
      steps: [step({ id: 's1' })],
      triggers: [{ triggerType: 'on_start', triggerValue: '', keywords: null, entryStepId: null }, eventTrigger],
    })
    storeMock.fetchOne.mockResolvedValue(seed)
    storeMock.update.mockImplementation((_id: string, body: Partial<FunnelResponse>) =>
      Promise.resolve(draft({ ...body })),
    )
    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
    await settle()
    storeMock.update.mockClear()

    wrapper.findComponent(FunnelCanvas).vm.$emit('node-drag-stop', { nodeId: 'trigger:1', position: { x: 80, y: 160 } })
    await settle()

    expect(storeMock.update).toHaveBeenCalledTimes(1)
    const body = storeMock.update.mock.calls[0][1] as Partial<FunnelResponse>
    expect(body.triggers?.[1]?.canvasPosition).toEqual({ x: 80, y: 160 })
    // The sibling on_start entry must be untouched (still no coordinate).
    expect(body.triggers?.[0]?.canvasPosition ?? null).toBeNull()
    // The dragged trigger's own fields are preserved through the sanitizer.
    expect(body.triggers?.[1]?.triggerType).toBe('event')
    expect(body.triggers?.[1]?.triggerValue).toBe('signup')
  })

  it('drag-stop on an unknown node id is a no-op (no PATCH)', async () => {
    storeMock.fetchOne.mockResolvedValue(draft({ steps: [step({ id: 's1' })] }))
    storeMock.update.mockResolvedValue(draft())
    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
    await settle()
    storeMock.update.mockClear()

    // An unsaved node carries a synthetic id with no model match → nothing to write, no save.
    wrapper.findComponent(FunnelCanvas).vm.$emit('node-drag-stop', { nodeId: 'unsaved-step:9', position: { x: 1, y: 2 } })
    await settle()
    expect(storeMock.update).not.toHaveBeenCalled()
  })

  it('persists notes through the same PATCH when the canvas emits update:notes', async () => {
    storeMock.fetchOne.mockResolvedValue(draft({ steps: [step()] }))
    storeMock.update.mockImplementation((_id: string, body: Partial<FunnelResponse>) =>
      Promise.resolve(draft({ ...body })),
    )
    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
    await settle()
    storeMock.update.mockClear()

    wrapper
      .findComponent(FunnelCanvas)
      .vm.$emit('update:notes', [{ id: null, text: 'a note', canvasPosition: { x: 10, y: 20 } }])
    await settle()

    expect(storeMock.update).toHaveBeenCalledTimes(1)
    const body = storeMock.update.mock.calls[0][1] as Partial<FunnelResponse>
    expect(body.notes).toEqual([{ id: null, text: 'a note', canvasPosition: { x: 10, y: 20 } }])
  })

  it('round-trips notes from the response without dropping them (applyResponse reads notes)', async () => {
    // The server echoes a minted note id; a subsequent save must carry that note (not lose it).
    storeMock.fetchOne.mockResolvedValue(
      draft({ steps: [step()], notes: [{ id: 'n1', text: 'kept', canvasPosition: { x: 5, y: 5 } }] }),
    )
    storeMock.update.mockImplementation((_id: string, body: Partial<FunnelResponse>) =>
      Promise.resolve(draft({ ...body })),
    )
    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
    await settle()
    storeMock.update.mockClear()

    // Trigger a save via a drag-stop; the persisted body must still include the loaded note.
    wrapper.findComponent(FunnelCanvas).vm.$emit('node-drag-stop', { nodeId: 's1', position: { x: 1, y: 1 } })
    await settle()
    const body = storeMock.update.mock.calls[0][1] as Partial<FunnelResponse>
    expect(body.notes).toEqual([{ id: 'n1', text: 'kept', canvasPosition: { x: 5, y: 5 } }])
  })

  it('renders the steps list read-only (no structural mutators) and shows the notice', async () => {
    storeMock.fetchOne.mockResolvedValue(draft({ steps: [step({ id: 's1' })] }))
    storeMock.update.mockResolvedValue(draft({ steps: [step({ id: 's1' })] }))
    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
    // Drain the known on-mount autosave (a ready on_start trigger persists once — see the trigger-watch suite)
    // past the 600ms debounce, THEN clear the spy so the silence assertion below counts only the list's effect.
    await new Promise((r) => setTimeout(r, 700))
    await settle()
    storeMock.update.mockClear()

    // The list is still mounted as a view…
    expect(wrapper.find('[data-test="funnel-steps"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-step-row-0"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-steps-readonly-notice"]').exists()).toBe(true)
    // …but exposes NO structural mutators (move / edit / delete / add).
    expect(wrapper.find('[data-test="funnel-step-move-up-0"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-step-move-down-0"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-step-edit-0"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-step-delete-0"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-add-step"]').exists()).toBe(false)

    // Behavioral silence (complements the DOM-absence check): exercising the list — including its one kept
    // non-structural affordance, row `select` — drives ZERO further saves. A view-only list wires no structural
    // emit to persist(); only the canvas does. Past the trigger debounce window to catch any deferred PATCH.
    await wrapper.get('[data-test="funnel-step-select-0"]').trigger('click')
    await new Promise((r) => setTimeout(r, 700))
    await settle()
    expect(storeMock.update).not.toHaveBeenCalled()
  })

  // ─── Step-readiness gate on canvas-driven step persist (addnode-fix regression) ──────────────────────
  // Adding a node from the palette appends a fresh, INCOMPLETE step (e.g. MESSAGE → blocks:[{TEXT, text:''}])
  // and emits update:steps. The backend validates per-step content on every save (MESSAGE needs non-empty
  // TEXT, DELAY needs delayValue, ADD_TAG needs a tagSlug, …), so an immediate full-replace PATCH 422s
  // (funnel_step_invalid). The page must MIRROR the existing triggerReady gate: withhold the save while any
  // step is incomplete, then persist once every step is valid. These tests are value-based + load-bearing —
  // they fail against the pre-fix unconditional onCanvasSteps→persist().
  describe('step-readiness gate (do not persist an incomplete new node)', () => {
    async function mountLoaded(over: Partial<FunnelResponse> = {}) {
      storeMock.fetchOne.mockResolvedValue(draft(over))
      // Mirror the backend: on save the server MINTS an ObjectId for any step that arrives with id:null, so the
      // echoed response carries a stable id (applyResponse re-reads it → the node becomes a wireable target).
      storeMock.update.mockImplementation((_id: string, body: Partial<FunnelResponse>) =>
        Promise.resolve(
          draft({
            ...over,
            ...body,
            steps: (body.steps ?? []).map((s, i) => ({ ...s, id: s.id ?? `minted-${i}` })),
          }),
        ),
      )
      const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
      // Drain the known on-mount autosave (a ready on_start trigger debounce-persists once), THEN clear the
      // spy so the assertions below count only the canvas-driven step emit.
      await new Promise((r) => setTimeout(r, 700))
      await settle()
      storeMock.update.mockClear()
      return wrapper
    }
    function emitSteps(wrapper: Awaited<ReturnType<typeof mountLoaded>>, next: FunnelStep[]): void {
      wrapper.findComponent(FunnelCanvas).vm.$emit('update:steps', next)
    }

    it('does NOT PATCH when a freshly-added MESSAGE node has empty TEXT (would 422)', async () => {
      const wrapper = await mountLoaded()
      // Exactly the shape FunnelCanvas.newStep('MESSAGE') appends from the palette: id null, empty TEXT.
      emitSteps(wrapper, [{ stepType: 'MESSAGE', id: null, blocks: [{ type: 'TEXT', text: '' }] }])
      await settle()
      // The empty-text step is withheld — no full-replace PATCH that the backend would reject.
      expect(storeMock.update).not.toHaveBeenCalled()
    })

    it('does NOT PATCH when a freshly-added DELAY node has no delayValue (generality, not MESSAGE-only)', async () => {
      const wrapper = await mountLoaded()
      // FunnelCanvas.newStep('DELAY') appends a bare { stepType:'DELAY', id:null } — no delayValue/unit yet.
      emitSteps(wrapper, [{ stepType: 'DELAY', id: null }])
      await settle()
      expect(storeMock.update).not.toHaveBeenCalled()
    })

    it('does NOT PATCH when a freshly-added ADD_TAG node has no tagSlug', async () => {
      const wrapper = await mountLoaded()
      emitSteps(wrapper, [{ stepType: 'ADD_TAG', id: null }])
      await settle()
      expect(storeMock.update).not.toHaveBeenCalled()
    })

    it('PATCHes once the new MESSAGE step is filled in (gate opens) and the step becomes wireable', async () => {
      const wrapper = await mountLoaded()
      // 1) Add an incomplete MESSAGE node → withheld.
      emitSteps(wrapper, [{ stepType: 'MESSAGE', id: null, blocks: [{ type: 'TEXT', text: '' }] }])
      await settle()
      expect(storeMock.update).not.toHaveBeenCalled()

      // 2) The side-panel edit fills the text → every step is now ready → the save proceeds.
      emitSteps(wrapper, [{ stepType: 'MESSAGE', id: null, blocks: [{ type: 'TEXT', text: 'Welcome' }] }])
      await settle()
      expect(storeMock.update).toHaveBeenCalledTimes(1)
      const body = storeMock.update.mock.calls[0][1] as Partial<FunnelResponse>
      expect(body.steps).toEqual([{ stepType: 'MESSAGE', id: null, blocks: [{ type: 'TEXT', text: 'Welcome' }] }])

      // 3) The server mints the id on the round-trip; applyResponse re-reads it so the node is now a saved,
      // wireable edge target (its id is present in the persisted steps the canvas binds).
      const live = wrapper.findComponent(FunnelCanvas).props('steps') as FunnelStep[]
      expect(live.some((s) => s.stepType === 'MESSAGE' && !!s.id)).toBe(true)
    })

    it('PATCHes a valid DELAY step once filled (gate is per-type, not MESSAGE-only)', async () => {
      const wrapper = await mountLoaded()
      emitSteps(wrapper, [{ stepType: 'DELAY', id: null }])
      await settle()
      expect(storeMock.update).not.toHaveBeenCalled()

      emitSteps(wrapper, [{ stepType: 'DELAY', id: null, delayValue: 5, delayUnit: 'MIN' }])
      await settle()
      expect(storeMock.update).toHaveBeenCalledTimes(1)
      const body = storeMock.update.mock.calls[0][1] as Partial<FunnelResponse>
      expect(body.steps).toEqual([{ stepType: 'DELAY', id: null, delayValue: 5, delayUnit: 'MIN' }])
    })

    it('withholds the save when ANY step is incomplete, even if others are valid', async () => {
      // A saved, valid MESSAGE step already exists; adding a second incomplete node must NOT flush a PATCH
      // that would 422 on the incomplete one (the whole full-replace array is validated server-side).
      const wrapper = await mountLoaded({
        steps: [{ stepType: 'MESSAGE', id: 's1', blocks: [{ type: 'TEXT', text: 'Hi' }] }],
      })
      emitSteps(wrapper, [
        { stepType: 'MESSAGE', id: 's1', blocks: [{ type: 'TEXT', text: 'Hi' }] },
        { stepType: 'MESSAGE', id: null, blocks: [{ type: 'TEXT', text: '' }] },
      ])
      await settle()
      expect(storeMock.update).not.toHaveBeenCalled()
    })

    // ─── Round-1 follow-up: the trigger canvas path is gated by step readiness too ──────────────────────
    // The full-replace PATCH always carries steps.value, so a canvas-committed TRIGGER edit must ALSO be
    // withheld while any step is incomplete — otherwise it 422s funnel_step_invalid on the unfinished node
    // (the same bug the step gate was meant to prevent, just reached via the trigger path). This is
    // value-based + load-bearing: it FAILS against the pre-fix onCanvasTriggers→void persist() (which
    // bypassed the gate), passes once onCanvasTriggers routes through persistSteps().
    function emitTriggers(wrapper: Awaited<ReturnType<typeof mountLoaded>>, triggers: FunnelTrigger[]): void {
      wrapper.findComponent(FunnelCanvas).vm.$emit('update:triggers', triggers)
    }

    it('does NOT PATCH a canvas trigger commit while a step is incomplete, then DOES once the step is filled', async () => {
      // An incomplete MESSAGE node coexists with the triggers; committing a trigger via the canvas must not
      // flush the (incomplete) full-replace array.
      const wrapper = await mountLoaded()
      emitSteps(wrapper, [{ stepType: 'MESSAGE', id: null, blocks: [{ type: 'TEXT', text: '' }] }])
      await settle()
      expect(storeMock.update).not.toHaveBeenCalled()

      // Commit a (complete) trigger array via the canvas while the step is STILL incomplete → withheld.
      emitTriggers(wrapper, [{ triggerType: 'on_start', triggerValue: 'promo', keywords: null, entryStepId: null }])
      await settle()
      expect(storeMock.update).not.toHaveBeenCalled()

      // Fill the step → now every step is ready, so a subsequent trigger commit DOES persist (one PATCH),
      // carrying both the completed step and the sanitized trigger array.
      emitSteps(wrapper, [{ stepType: 'MESSAGE', id: null, blocks: [{ type: 'TEXT', text: 'Welcome' }] }])
      await settle()
      storeMock.update.mockClear()

      emitTriggers(wrapper, [{ triggerType: 'on_start', triggerValue: 'promo2', keywords: null, entryStepId: null }])
      await settle()
      expect(storeMock.update).toHaveBeenCalledTimes(1)
      const body = storeMock.update.mock.calls[0][1] as Partial<FunnelResponse>
      expect(Array.isArray(body.triggers)).toBe(true)
      const onStart = body.triggers?.find((tr) => tr.triggerType === 'on_start')
      expect(onStart?.triggerValue).toBe('promo2')
    })
  })

  it('renders a note containing an injection payload ESCAPED (no v-html, no img element)', async () => {
    const payload = '<img src=x onerror=alert(1)>'
    storeMock.fetchOne.mockResolvedValue(
      draft({ steps: [step()], notes: [{ id: 'n1', text: payload, canvasPosition: { x: 0, y: 0 } }] }),
    )
    const wrapper = await mountSuspended(FunnelEditorPage, editorMountOptions)
    await settle()

    const noteNode = wrapper.find('[data-node-id="note:0"]')
    expect(noteNode.exists()).toBe(true)
    // The literal markup is visible as TEXT (escaped via {{ }}), and no real <img> was created from it.
    expect(noteNode.text()).toContain(payload)
    expect(noteNode.find('img').exists()).toBe(false)
    // Stronger than text(): assert the RAW innerHTML never carries an `<img` tag. text() alone would still
    // pass under an accidental v-html (which would inject a live <img>), so guard the serialized markup too.
    expect(noteNode.element.innerHTML).not.toContain('<img')
    // Defensive: the payload-derived element must not exist anywhere in the canvas DOM.
    expect(wrapper.findAll('img').some((i) => i.attributes('onerror'))).toBe(false)
  })
})

// ─── MESSAGE composer keyboard (Decision 2) ──────────────────────────────────
// The inline keyboard now attaches to the LAST non-album block of a MESSAGE composer step (it replaces the
// former MENU step-kind). ≥1 callback button (label + target = another step or "End"), URL buttons (label +
// http(s) link). Mounted standalone (lighter than via the dialog) with the sibling-step context the target
// picker needs. The keyboard is opt-in — the helper adds the first button row. data-test idiom throughout.
const SIBLINGS: FunnelStep[] = [
  { stepType: 'MESSAGE', id: 's1', blocks: [{ type: 'TEXT', text: 'Welcome' }] },
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

// Mount the composer (default MESSAGE) and add ONE keyboard button row so the per-button tests have a row 0.
async function mountMenuForm(initial: FunnelStep | null = null) {
  const wrapper = await mountSuspended(FunnelStepForm, {
    props: { initial, siblingSteps: SIBLINGS, submitLabel: 'Save' },
    attachTo: document.body,
  })
  await settle()
  // The seeded TEXT block is the last non-album block → the keyboard section is available. Add a row.
  if (!maybe('[data-test="step-menu-button-row-0"]')) {
    await $('[data-test="step-menu-add-button"]').trigger('click')
    await settle()
  }
  return wrapper
}
async function submitForm() {
  await $('[data-test="step-form"]').trigger('submit')
  await settle()
}
// Fill the seeded text block so a valid composer never blocks a keyboard-focused submit.
async function fillText(text = 'Pick one') {
  await $('[data-test="step-block-text-0"]').setValue(text)
}

describe('FunnelStepForm — MESSAGE keyboard', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders MESSAGE in the step-type picker and shows the buttons section', async () => {
    const wrapper = await mountSuspended(FunnelStepForm, {
      props: { siblingSteps: SIBLINGS, submitLabel: 'Save' },
      attachTo: document.body,
    })
    await settle()
    const options = wrapper.findAll('[data-test="step-type-select"] option').map((o) => o.attributes('value'))
    expect(options).toContain('MESSAGE')
    expect(options).not.toContain('MENU')

    // The default composer's last block is a non-album TEXT block → the keyboard section is available.
    expect(maybe('[data-test="step-menu-buttons"]')).not.toBeNull()
    expect(maybe('[data-test="step-block-text-0"]')).not.toBeNull()
  })

  it('adds and removes button rows', async () => {
    await mountMenuForm()
    // The helper added one row.
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
    await fillText('Pick one')
    // Leave the label blank; set a valid target so only the label is wrong.
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-target-0-option-s1"]').trigger('mousedown')
    await settle()
    await submitForm()
    expect(maybe('[data-test="step-menu-button-label-error-0"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('blocks submit when the keyboard has no callback button', async () => {
    const wrapper = await mountMenuForm()
    await fillText('Links only')
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
    await fillText('Menu')
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

  it('emits a valid MESSAGE keyboard with End encoded as targetStepId null and preserves id/next', async () => {
    const wrapper = await mountMenuForm({
      stepType: 'MESSAGE',
      blocks: [{ type: 'TEXT', text: 'Old' }],
      buttons: [],
      id: 'menu1',
      next: null,
    })
    await fillText('Pick one')
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
    expect(step.stepType).toBe('MESSAGE')
    expect(step.blocks).toEqual([{ type: 'TEXT', text: 'Pick one', parseMode: null }])
    expect(step.id).toBe('menu1')
    expect(step.buttons).toEqual([
      { type: 'callback', label: 'Continue', targetStepId: 's1', url: null },
      // End → targetStepId null (NOT "" — empty string is a broken edge server-side).
      { type: 'callback', label: 'Finish', targetStepId: null, url: null },
    ])
  })

  it('omits timeout fields when the author leaves the timeout blank', async () => {
    const wrapper = await mountMenuForm()
    await fillText('Pick one')
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
    await fillText('Pick one')
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
    await fillText('Pick one')
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
    await fillText('Pick one')
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

  it('pre-fills the buttons of an edited MESSAGE step', async () => {
    await mountMenuForm({
      stepType: 'MESSAGE',
      blocks: [{ type: 'TEXT', text: 'Existing' }],
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

// ─── SUBSCRIBE_TO_FUNNEL step form (Phase 5, Task 4) ──────────────────────────
// The step enrolls the subscriber into ANOTHER funnel of the project. The form picks a target funnel
// (status-aware, so it can hint when the target is not active), an optional entry step (sentinel = from
// the start), and an "end this funnel after starting" checkbox. The target picker pulls funnels through
// the shared store (fetch('all') + funnels ref), entry steps via fetchOne(targetFunnelId).steps.
function summary(over: Partial<FunnelSummaryResponse> = {}): FunnelSummaryResponse {
  return {
    id: 'sub1',
    projectId: 'p1',
    name: 'Sub funnel',
    description: null,
    status: 'active',
    triggers: [{ triggerType: 'on_start', triggerValue: '', keywords: null, entryStepId: null }],
    allowReEnter: false,
    stepCount: 2,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...over,
  }
}

async function mountSubscribeForm(initial: FunnelStep | null = null) {
  const wrapper = await mountSuspended(FunnelStepForm, {
    props: { initial, submitLabel: 'Save' },
    attachTo: document.body,
  })
  await settle()
  await $('[data-test="step-type-select"]').setValue('SUBSCRIBE_TO_FUNNEL')
  await settle()
  return wrapper
}

describe('FunnelStepForm — SUBSCRIBE_TO_FUNNEL', () => {
  beforeEach(() => {
    // Non-vacuous fixtures: at least one ACTIVE and one non-active funnel so the picker carries both and
    // the inactive-target hint can actually be exercised.
    storeMock.funnels.value = [
      summary({ id: 'active1', name: 'Active funnel', status: 'active' }),
      summary({ id: 'draft1', name: 'Draft funnel', status: 'draft' }),
    ]
    storeMock.fetch.mockReset().mockResolvedValue(undefined)
    storeMock.fetchOne.mockReset().mockResolvedValue(
      draft({
        id: 'active1',
        status: 'active',
        steps: [
          { stepType: 'MESSAGE', id: 'st1', blocks: [{ type: 'TEXT', text: 'Hi' }] },
          { stepType: 'DELAY', id: 'st2', delayValue: 1, delayUnit: 'MIN' },
        ],
      }),
    )
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders SUBSCRIBE_TO_FUNNEL in the type picker + the target/entry pickers and the end checkbox', async () => {
    const wrapper = await mountSubscribeForm()
    const options = wrapper.findAll('[data-test="step-type-select"] option').map((o) => o.attributes('value'))
    expect(options).toContain('SUBSCRIBE_TO_FUNNEL')

    expect(maybe('[data-test="step-subscribe-target-select"]')).not.toBeNull()
    expect(maybe('[data-test="step-subscribe-entry-select"]')).not.toBeNull()
    expect(maybe('[data-test="step-subscribe-end-parent"]')).not.toBeNull()
    // The return-pattern hint under the checkbox is always shown (recommends endParentAfter=true).
    expect(maybe('[data-test="step-subscribe-return-hint"]')).not.toBeNull()
    // The target picker fetched the project's funnels via the store.
    expect(storeMock.fetch).toHaveBeenCalledWith('all')
  })

  it('emits the SUBSCRIBE payload with the chosen target, entry step and end flag', async () => {
    const wrapper = await mountSubscribeForm()
    // Pick the active target funnel.
    await $('[data-test="step-subscribe-target-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-subscribe-target-option-active1"]').trigger('mousedown')
    await settle()
    // Its steps are lazily fetched → pick the first one as the entry step.
    await $('[data-test="step-subscribe-entry-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-subscribe-entry-option-st1"]').trigger('mousedown')
    await settle()
    // Tick the "end this funnel after starting" checkbox.
    await $('[data-test="step-subscribe-end-parent"]').setValue(true)
    await settle()
    await submitForm()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const step = emitted![0][0] as FunnelStep
    expect(step.stepType).toBe('SUBSCRIBE_TO_FUNNEL')
    expect(step.targetFunnelId).toBe('active1')
    expect(step.targetEntryStepId).toBe('st1')
    expect(step.endParentAfter).toBe(true)
  })

  it('encodes the "from the start" entry sentinel as targetEntryStepId null', async () => {
    const wrapper = await mountSubscribeForm()
    await $('[data-test="step-subscribe-target-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-subscribe-target-option-active1"]').trigger('mousedown')
    await settle()
    // Leave the entry picker on its default "from the start" sentinel → null.
    await submitForm()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const step = emitted![0][0] as FunnelStep
    expect(step.targetFunnelId).toBe('active1')
    expect(step.targetEntryStepId).toBeNull()
    // Unchecked checkbox → endParentAfter false.
    expect(step.endParentAfter).toBe(false)
  })

  it('shows the inactive-target hint only when a non-active funnel is chosen', async () => {
    const wrapper = await mountSubscribeForm()
    // Non-vacuous guard: the picker must carry BOTH the active and the non-active option, otherwise the
    // hint would be unreachable for the wrong reason (the inactive funnel simply absent from the list).
    await $('[data-test="step-subscribe-target-input"]').trigger('focus')
    await settle()
    expect(maybe('[data-test="step-subscribe-target-option-active1"]')).not.toBeNull()
    expect(maybe('[data-test="step-subscribe-target-option-draft1"]')).not.toBeNull()

    // Active target → no hint.
    await $('[data-test="step-subscribe-target-option-active1"]').trigger('mousedown')
    await settle()
    expect(maybe('[data-test="step-subscribe-inactive-hint"]')).toBeNull()

    // Switch to the draft (non-active) target → the inline hint appears.
    await $('[data-test="step-subscribe-target-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-subscribe-target-option-draft1"]').trigger('mousedown')
    await settle()
    expect(maybe('[data-test="step-subscribe-inactive-hint"]')).not.toBeNull()
    expect(wrapper).toBeTruthy()
  })

  it('resets the entry step to "from the start" when the target funnel changes', async () => {
    const wrapper = await mountSubscribeForm()
    // Pick target active1 then an explicit entry step.
    await $('[data-test="step-subscribe-target-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-subscribe-target-option-active1"]').trigger('mousedown')
    await settle()
    await $('[data-test="step-subscribe-entry-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-subscribe-entry-option-st1"]').trigger('mousedown')
    await settle()

    // Switch the target → the previously-chosen entry id (funnel-local) must reset to the sentinel.
    await $('[data-test="step-subscribe-target-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-subscribe-target-option-draft1"]').trigger('mousedown')
    await settle()
    await submitForm()

    const step = wrapper.emitted('submit')![0][0] as FunnelStep
    expect(step.targetFunnelId).toBe('draft1')
    // Reset to sentinel → null, NOT the stale st1 from the previous funnel.
    expect(step.targetEntryStepId).toBeNull()
  })

  it('pre-fills target / entry / end flag from an edited SUBSCRIBE step and re-emits them', async () => {
    const wrapper = await mountSubscribeForm({
      stepType: 'SUBSCRIBE_TO_FUNNEL',
      targetFunnelId: 'active1',
      targetEntryStepId: 'st2',
      endParentAfter: true,
    })
    // Target funnel label shows in the closed picker input.
    expect((($('[data-test="step-subscribe-target-input"]').element) as HTMLInputElement).value).toContain('Active funnel')
    // The end-parent checkbox is checked.
    expect((($('[data-test="step-subscribe-end-parent"]').element) as HTMLInputElement).checked).toBe(true)

    // Submitting without touching anything must re-emit the pre-filled entry id (not lost / not reset).
    await submitForm()
    const step = wrapper.emitted('submit')![0][0] as FunnelStep
    expect(step.targetFunnelId).toBe('active1')
    expect(step.targetEntryStepId).toBe('st2')
    expect(step.endParentAfter).toBe(true)
  })
})
