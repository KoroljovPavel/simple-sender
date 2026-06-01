// @vitest-environment nuxt
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import FunnelEditorPage from '../../pages/projects/[projectId]/funnels/[funnelId].vue'
import type { FunnelResponse } from '../../types/funnel'

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
})
