import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { SubscriberEvent } from '../../types/subscriber'
import SubscriberHistoryTab from '../../components/subscribers/SubscriberHistoryTab.vue'

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

const EVENTS_URL = '/api/v1/projects/p1/subscribers/s1/events'

function ev(id: string, createdAt: string): SubscriberEvent {
  return { id, eventType: `evt_${id}`, metadata: null, createdAt }
}

async function mountTab(rows: SubscriberEvent[]) {
  apiMock.mockResolvedValueOnce(rows)
  const wrapper = await mountSuspended(SubscriberHistoryTab, {
    props: { projectId: 'p1', subscriberId: 's1' },
  })
  await flushPromises()
  return wrapper
}

describe('SubscriberHistoryTab', () => {
  beforeEach(() => {
    apiMock.mockReset()
  })

  it('fetches limit=50 and renders newest-first', async () => {
    const wrapper = await mountTab([
      ev('old', '2026-01-01T00:00:00Z'),
      ev('new', '2026-03-01T00:00:00Z'),
      ev('mid', '2026-02-01T00:00:00Z'),
    ])
    expect(apiMock).toHaveBeenCalledWith(EVENTS_URL, { query: { limit: 50 } })

    const rows = wrapper.findAll('[data-test="subscriber-history-row"]')
    expect(rows).toHaveLength(3)
    expect(rows.map((r) => r.text())).toEqual([
      expect.stringContaining('evt_new'),
      expect.stringContaining('evt_mid'),
      expect.stringContaining('evt_old'),
    ])
  })

  it('renders the empty state when there are no events', async () => {
    const wrapper = await mountTab([])
    expect(wrapper.find('[data-test="subscriber-history-empty"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-test="subscriber-history-row"]')).toHaveLength(0)
  })
})
