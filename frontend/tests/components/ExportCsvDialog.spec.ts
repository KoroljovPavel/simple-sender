import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { toast } from 'vue-sonner'
import { settle } from '../helpers/settle'
import { defaultFilter } from '../../types/subscriber'
import ExportCsvDialog from '../../components/subscribers/ExportCsvDialog.vue'

vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

const URL = '/api/v1/projects/p1/subscribers/export'

function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}
async function mountOpen() {
  return mountSuspended(ExportCsvDialog, {
    props: { open: true, projectId: 'p1', filter: { ...defaultFilter(), status: 'active' } },
  })
}

describe('ExportCsvDialog', () => {
  beforeEach(() => {
    apiMock.mockReset()
    vi.mocked(toast.success).mockReset()
    vi.mocked(toast.error).mockReset()
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('submit_postsExportFilter_andSuccessToast', async () => {
    apiMock.mockResolvedValueOnce({ exportId: 'e1', status: 'PENDING' })
    await mountOpen()
    await settle()
    await $('[data-test="export-submit"]').trigger('click')
    await settle()

    expect(apiMock).toHaveBeenCalledTimes(1)
    const [url, opts] = apiMock.mock.calls[0]
    expect(url).toBe(URL)
    expect(opts.method).toBe('POST')
    // SegmentFilter enum-name mapping: status uppercased, sort enum-name.
    expect((opts.body as { filter: Record<string, unknown> }).filter.status).toBe('ACTIVE')
    expect((opts.body as { filter: Record<string, unknown> }).filter.sort).toBe('CREATED_DESC')
    expect(toast.success).toHaveBeenCalled()
  })

  it('inFlight409_showsErrorToast', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 409, data: { code: 'export_in_flight' } })
    await mountOpen()
    await settle()
    await $('[data-test="export-submit"]').trigger('click')
    await settle()
    expect(toast.error).toHaveBeenCalled()
  })

  it('tooLarge422_showsErrorToast', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 422, data: { code: 'export_filter_too_large' } })
    await mountOpen()
    await settle()
    await $('[data-test="export-submit"]').trigger('click')
    await settle()
    expect(toast.error).toHaveBeenCalled()
  })
})
