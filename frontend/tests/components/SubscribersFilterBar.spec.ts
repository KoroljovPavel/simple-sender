import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { Tag } from '../../types/subscriber'
import SubscribersFilterBar from '../../components/subscribers/SubscribersFilterBar.vue'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

const TAGS: Tag[] = [
  { slug: 'vip', label: 'VIP', subscriberCount: 1, createdAt: '2026-01-01T00:00:00Z' },
  { slug: 'cold', label: 'Cold', subscriberCount: 1, createdAt: '2026-01-01T00:00:00Z' },
]

async function mountBar() {
  apiMock.mockResolvedValue(TAGS) // tag-list fetch on mount
  const wrapper = await mountSuspended(SubscribersFilterBar, { props: { projectId: 'p1' } })
  await flushPromises() // resolve the tags fetch so chips render
  return wrapper
}

describe('SubscribersFilterBar', () => {
  beforeEach(() => {
    apiMock.mockReset()
  })

  it('search_below2Chars_noEmit', async () => {
    const wrapper = await mountBar() // mount under real timers (mountSuspended + tag fetch)
    vi.useFakeTimers()
    try {
      await wrapper.find('[data-test="subscribers-filter-search"]').setValue('a')
      vi.advanceTimersByTime(300)
    } finally {
      vi.useRealTimers()
    }
    await flushPromises()
    expect(wrapper.emitted('update:filter')).toBeFalsy()
  })

  it('search_debounced300ms', async () => {
    const wrapper = await mountBar()
    const input = wrapper.find('[data-test="subscribers-filter-search"]')
    vi.useFakeTimers()
    try {
      for (const v of ['a', 'ab', 'abc', 'abcd', 'abcde']) {
        await input.setValue(v)
        vi.advanceTimersByTime(50) // each keystroke within the 300ms window resets the timer
      }
      vi.advanceTimersByTime(300)
    } finally {
      vi.useRealTimers()
    }
    await flushPromises()
    const emits = wrapper.emitted('update:filter')
    expect(emits).toHaveLength(1)
    expect((emits![0][0] as { search?: string }).search).toBe('abcde')
  })

  it('tagInclude_andExclude_independentMultiSelect', async () => {
    const wrapper = await mountBar()
    await wrapper.find('[data-test="subscribers-filter-tag-include-vip"]').trigger('click')
    await wrapper.find('[data-test="subscribers-filter-tag-exclude-cold"]').trigger('click')

    const emits = wrapper.emitted('update:filter')
    expect(emits).toBeTruthy()
    const last = emits![emits!.length - 1][0] as { tagsInclude: string[]; tagsExclude: string[] }
    expect(last.tagsInclude).toEqual(['vip'])
    expect(last.tagsExclude).toEqual(['cold'])
  })
})
