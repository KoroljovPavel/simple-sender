import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import SubscriberTagsTab from '../../components/subscribers/SubscriberTagsTab.vue'

vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

const BASE = '/api/v1/projects/p1/subscribers/s1/tags'

async function mountTab(tags: string[]) {
  apiMock.mockImplementation((url: string) => {
    if (url.endsWith('/tags') && url.includes('/projects/p1/tags')) return Promise.resolve([])
    return Promise.resolve([]) // tag-list fetch on mount (GET /projects/p1/tags)
  })
  const wrapper = await mountSuspended(SubscriberTagsTab, {
    props: { projectId: 'p1', subscriberId: 's1', tags },
  })
  await flushPromises()
  apiMock.mockReset() // forget the on-mount tag-list fetch; isolate the mutation call below
  apiMock.mockResolvedValue(undefined)
  return wrapper
}

describe('SubscriberTagsTab', () => {
  beforeEach(() => {
    apiMock.mockReset()
  })

  it('addTag_callsPost_andRefreshes', async () => {
    const wrapper = await mountTab([])
    await wrapper.find('[data-test="subscriber-tags-combobox"]').setValue('newtag')
    await wrapper.find('[data-test="subscriber-tag-add"]').trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith(BASE, { method: 'POST', body: { slug: 'newtag' } })
    expect(wrapper.emitted('refresh')).toBeTruthy()
  })

  it('removeChip_callsDelete', async () => {
    const wrapper = await mountTab(['vip'])
    await wrapper.find('[data-test="subscriber-tag-remove-vip"]').trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith(`${BASE}/vip`, { method: 'DELETE' })
    expect(wrapper.emitted('refresh')).toBeTruthy()
  })
})
