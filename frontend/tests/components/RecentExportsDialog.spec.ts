import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { toast } from 'vue-sonner'
import { settle } from '../helpers/settle'
import RecentExportsDialog from '../../components/subscribers/RecentExportsDialog.vue'

// Component-level coverage for the row state machine + refresh-URL matrix. The E2E spec
// (e2e/subscribers.spec.ts) self-skips without a backend, so these unit assertions are the only
// runnable protection for Task 11 AC line 103 (refresh codes 200/409/410/503 → toasts).

vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}
function maybe(sel: string): Element | null {
  return document.querySelector(sel)
}

interface Row {
  exportId: string
  status: string
  rowCount: number | null
  createdAt: string
  completedAt: string | null
  downloadUrl: string | null
  expiresAt: string | null
}
function row(p: Partial<Row> & { exportId: string; status: string }): Row {
  return {
    rowCount: null,
    createdAt: '2026-01-01T00:00:00Z',
    completedAt: null,
    downloadUrl: null,
    expiresAt: null,
    ...p,
  }
}

// Mount already-open: the immediate open-watch fires loadExports (apiMock's first queued value).
async function mountWithRows(rows: Row[]) {
  apiMock.mockResolvedValueOnce(rows)
  const wrapper = await mountSuspended(RecentExportsDialog, { props: { open: true, projectId: 'p1' } })
  await settle()
  return wrapper
}

describe('RecentExportsDialog', () => {
  beforeEach(() => {
    apiMock.mockReset()
    vi.mocked(toast.error).mockReset()
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('rowStateMachine_rendersCorrectActionPerStatus', async () => {
    await mountWithRows([
      row({ exportId: 'e1', status: 'DONE', rowCount: 2, downloadUrl: 'https://x/d', expiresAt: '2099-01-01T00:00:00Z' }),
      row({ exportId: 'e2', status: 'DONE', rowCount: 5, downloadUrl: null }), // expired token
      row({ exportId: 'e3', status: 'RUNNING' }),
      row({ exportId: 'e4', status: 'FAILED' }),
      row({ exportId: 'e5', status: 'PURGED' }),
    ])

    // DONE + valid token → Download link pointing at downloadUrl.
    expect(maybe('[data-test="recent-export-download-e1"]')).not.toBeNull()
    expect($('[data-test="recent-export-download-e1"]').attributes('href')).toBe('https://x/d')
    // DONE + expired token (downloadUrl null) → Refresh URL button.
    expect(maybe('[data-test="recent-export-refresh-e2"]')).not.toBeNull()
    expect(maybe('[data-test="recent-export-download-e2"]')).toBeNull()
    // RUNNING → progress indicator, no action button.
    expect(maybe('[data-test="recent-export-progress-e3"]')).not.toBeNull()
    expect(maybe('[data-test="recent-export-refresh-e3"]')).toBeNull()
    // FAILED / PURGED → no action control.
    for (const id of ['e4', 'e5']) {
      expect(maybe(`[data-test="recent-export-download-${id}"]`)).toBeNull()
      expect(maybe(`[data-test="recent-export-refresh-${id}"]`)).toBeNull()
      expect(maybe(`[data-test="recent-export-progress-${id}"]`)).toBeNull()
    }
  })

  it('emptyList_showsEmptyState', async () => {
    await mountWithRows([])
    expect(maybe('[data-test="recent-exports-empty"]')).not.toBeNull()
  })

  it('refreshUrl_200_flipsToDownloadLink', async () => {
    await mountWithRows([row({ exportId: 'e2', status: 'DONE', downloadUrl: null })])
    // Second apiMock call = the refresh POST.
    apiMock.mockResolvedValueOnce({ downloadUrl: 'https://x/fresh', expiresAt: '2099-02-02T00:00:00Z' })

    await $('[data-test="recent-export-refresh-e2"]').trigger('click')
    await settle()

    expect(apiMock).toHaveBeenLastCalledWith(
      '/api/v1/projects/p1/subscribers/exports/e2/refresh-url',
      expect.objectContaining({ method: 'POST' }),
    )
    // Row flips: refresh button gone, Download link present with the fresh URL.
    expect(maybe('[data-test="recent-export-refresh-e2"]')).toBeNull()
    expect($('[data-test="recent-export-download-e2"]').attributes('href')).toBe('https://x/fresh')
  })

  it('refreshUrl_terminalCodes_toastAndDisableRow', async () => {
    for (const code of ['export_purged', 'export_failed']) {
      apiMock.mockReset()
      vi.mocked(toast.error).mockReset()
      const wrapper = await mountWithRows([row({ exportId: 'e2', status: 'DONE', downloadUrl: null })])
      apiMock.mockRejectedValueOnce({ statusCode: 410, data: { code } })

      await $('[data-test="recent-export-refresh-e2"]').trigger('click')
      await settle()

      expect(toast.error).toHaveBeenCalled()
      // Terminal (file gone) → row disabled.
      expect($('[data-test="recent-export-refresh-e2"]').attributes('disabled')).toBeDefined()
      wrapper.unmount()
      document.body.innerHTML = ''
    }
  })

  it('refreshUrl_retryableCodes_toastButKeepsRowEnabled', async () => {
    for (const [status, code] of [
      [409, 'export_in_flight'],
      [429, 'refresh_rate_limited'],
      [503, 'service_unavailable'],
    ] as const) {
      apiMock.mockReset()
      vi.mocked(toast.error).mockReset()
      const wrapper = await mountWithRows([row({ exportId: 'e2', status: 'DONE', downloadUrl: null })])
      apiMock.mockRejectedValueOnce({ statusCode: status, data: { code } })

      await $('[data-test="recent-export-refresh-e2"]').trigger('click')
      await settle()

      expect(toast.error).toHaveBeenCalled()
      // Retryable → button stays enabled so the operator can try again.
      expect($('[data-test="recent-export-refresh-e2"]').attributes('disabled')).toBeUndefined()
      wrapper.unmount()
      document.body.innerHTML = ''
    }
  })
})
