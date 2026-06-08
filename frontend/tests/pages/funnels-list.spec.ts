// @vitest-environment nuxt
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { reactive } from 'vue'
import { settle } from '../helpers/settle'
import FunnelsListPage from '../../pages/projects/[projectId]/funnels/index.vue'
import type { FunnelSummaryResponse } from '../../types/funnel'

// Render Teleport inline so the DialogPortal'd stop-all confirm is reachable via wrapper.find() and
// unmounts with the page (a real body teleport races with happy-dom on unmount). Pattern from
// settings/bot.spec.ts.
const mountOptions = {
  global: { stubs: { teleport: { template: '<div data-test-teleport><slot /></div>' } } },
}

// Task 5: the list row gains a Duplicate + Stop-all (confirm) tool pair. Test for me is editor-only
// (the list has no open-funnel context). Mirrors funnel-editor.spec.ts: mountSuspended + store/toast mocks.
const { storeMock, navMock, toastMock } = vi.hoisted(() => ({
  storeMock: {
    fetch: vi.fn(),
    delete: vi.fn(),
    duplicate: vi.fn(),
    stopAllExecutions: vi.fn(),
    // present so a mis-wired button surfaces, even though the list never calls these:
    fetchOne: vi.fn(),
    update: vi.fn(),
    activate: vi.fn(),
    pause: vi.fn(),
    testRun: vi.fn(),
    // `funnels` is filled with a reactive array at module load (after vue is importable) so a duplicate
    // that pushes a row re-renders the list, exactly as the Pinia ref does in the app.
    funnels: [] as FunnelSummaryResponse[],
    loading: false,
  },
  navMock: vi.fn(),
  toastMock: { success: vi.fn(), error: vi.fn() },
}))

// Swap in a reactive array now that `reactive` is importable (vi.hoisted runs before imports).
storeMock.funnels = reactive<FunnelSummaryResponse[]>([])

mockNuxtImport('useFunnelsStore', () => () => storeMock)
mockNuxtImport('navigateTo', () => navMock)
mockNuxtImport('useLocalePath', () => () => (p: string) => p)
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))
mockNuxtImport('useApi', () => () => () => Promise.resolve([]))

vi.mock('vue-sonner', () => ({ toast: toastMock }))

function summary(id: string, over: Partial<FunnelSummaryResponse> = {}): FunnelSummaryResponse {
  return {
    id,
    projectId: 'p1',
    name: `Funnel ${id}`,
    description: null,
    status: 'active',
    triggerType: 'on_start',
    triggerValue: '',
    allowReEnter: false,
    stepCount: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...over,
  }
}

async function mountList(rows: FunnelSummaryResponse[]) {
  storeMock.funnels.splice(0, storeMock.funnels.length, ...rows)
  storeMock.fetch.mockResolvedValue(undefined)
  const wrapper = await mountSuspended(FunnelsListPage, mountOptions)
  await settle()
  return wrapper
}

describe('funnels/index list page — Task 5 row actions', () => {
  beforeEach(() => {
    storeMock.fetch.mockReset()
    storeMock.duplicate.mockReset()
    storeMock.stopAllExecutions.mockReset()
    storeMock.delete.mockReset()
    storeMock.funnels.splice(0, storeMock.funnels.length)
    storeMock.loading = false
    navMock.mockReset()
    toastMock.success.mockReset()
    toastMock.error.mockReset()
  })

  it('row renders Duplicate + Stop-all, and NO Test for me (editor-only)', async () => {
    const wrapper = await mountList([summary('a')])
    expect(wrapper.find('[data-test="funnel-duplicate-a"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-stop-all-a"]').exists()).toBe(true)
    // Test for me belongs to the editor header, not the list row.
    expect(wrapper.find('[data-test="funnel-test-run"]').exists()).toBe(false)
  })

  it('Duplicate calls the store, refetches the list (current filter), and the new draft row appears + success-toast', async () => {
    const wrapper = await mountList([summary('a')])
    // Bug fix: the store's duplicate() can't append the copy (its id isn't in the loaded list — syncRow is
    // a no-op for it), so the page must refetch to surface it. The duplicate mock does NOT touch the list;
    // the refetch is what makes the new row appear — mirroring the real refetch-after-duplicate path.
    storeMock.duplicate.mockResolvedValue({ ...summary('a-copy'), steps: [], deepLink: null })
    storeMock.fetch.mockImplementation(async () => {
      storeMock.funnels.splice(
        0,
        storeMock.funnels.length,
        summary('a'),
        summary('a-copy', { status: 'draft', name: 'Funnel a (copy)' }),
      )
    })

    // fetch was called once on mount; reset so we assert the refetch triggered by duplicate.
    storeMock.fetch.mockClear()

    await wrapper.get('[data-test="funnel-duplicate-a"]').trigger('click')
    await settle()

    expect(storeMock.duplicate).toHaveBeenCalledWith('a')
    // Refetch respects the active status filter ('all' by default in this mount).
    expect(storeMock.fetch).toHaveBeenCalledWith('all')
    expect(toastMock.success).toHaveBeenCalled()
    expect(wrapper.find('[data-test="funnel-row-a-copy"]').exists()).toBe(true)
  })

  it('Stop-all confirm flow: confirm calls store, cancel does not', async () => {
    storeMock.stopAllExecutions.mockResolvedValue({ cancelled: 2 })

    // Cancel path first.
    const wrapper = await mountList([summary('a')])
    await wrapper.get('[data-test="funnel-stop-all-a"]').trigger('click')
    await settle()
    expect(wrapper.find('[data-test="funnel-stop-all-confirm"]').exists()).toBe(true)

    await wrapper.get('[data-test="funnel-stop-all-cancel"]').trigger('click')
    await settle()
    expect(storeMock.stopAllExecutions).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="funnel-stop-all-confirm"]').exists()).toBe(false)

    // Confirm path.
    await wrapper.get('[data-test="funnel-stop-all-a"]').trigger('click')
    await settle()
    await wrapper.get('[data-test="funnel-stop-all-confirm"]').trigger('click')
    await settle()

    expect(storeMock.stopAllExecutions).toHaveBeenCalledWith('a')
    const msg = toastMock.success.mock.calls.at(-1)?.[0] as string
    expect(msg).toContain('2')
  })
})
