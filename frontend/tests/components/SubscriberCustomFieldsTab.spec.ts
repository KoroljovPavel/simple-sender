import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { toast } from 'vue-sonner'
import type { CustomFieldDefinition } from '../../types/subscriber'
import SubscriberCustomFieldsTab from '../../components/subscribers/SubscriberCustomFieldsTab.vue'

vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

const PATCH_URL = '/api/v1/projects/p1/subscribers/s1/custom-fields'
const DEFS: CustomFieldDefinition[] = [
  { name: 'city', label: 'City', type: 'STRING', defaultValue: null, createdAt: '2026-01-01T00:00:00Z' },
  { name: 'age', label: 'Age', type: 'NUMBER', defaultValue: null, createdAt: '2026-01-01T00:00:00Z' },
]

async function mountTab() {
  apiMock.mockResolvedValueOnce(DEFS) // GET /custom-fields on mount
  const wrapper = await mountSuspended(SubscriberCustomFieldsTab, {
    props: { projectId: 'p1', subscriberId: 's1', customFields: { city: 'Kyiv', age: 30 } },
  })
  await flushPromises()
  return wrapper
}

describe('SubscriberCustomFieldsTab', () => {
  beforeEach(() => {
    apiMock.mockReset()
    vi.mocked(toast.error).mockReset()
    vi.mocked(toast.success).mockReset()
  })

  it('patch_sendsOnlyEditedFieldKey', async () => {
    const wrapper = await mountTab()
    apiMock.mockResolvedValueOnce({ city: 'Lviv', age: 30 })

    await wrapper.find('[data-test="subscriber-custom-field-city-input"]').setValue('Lviv')
    await wrapper.find('[data-test="subscriber-custom-field-city-save"]').trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenLastCalledWith(PATCH_URL, {
      method: 'PATCH',
      body: { values: { city: 'Lviv' } },
    })
    // Exactly one key — `age` must not leak into the body.
    const body = apiMock.mock.calls.at(-1)![1].body as { values: Record<string, unknown> }
    expect(Object.keys(body.values)).toEqual(['city'])
  })

  it('typeMismatch422_showsErrorToast', async () => {
    const wrapper = await mountTab()
    apiMock.mockRejectedValueOnce({ statusCode: 422, data: { code: 'custom_field_type_mismatch' } })

    await wrapper.find('[data-test="subscriber-custom-field-city-input"]').setValue('x')
    await wrapper.find('[data-test="subscriber-custom-field-city-save"]').trigger('click')
    await flushPromises()

    expect(toast.error).toHaveBeenCalled()
  })
})
