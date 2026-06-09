// @vitest-environment nuxt
import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../helpers/settle'
import EditStepDialog from '../../components/funnels/EditStepDialog.vue'
import type { FunnelStep } from '../../types/funnel'

// The tag picker (ADD_TAG/REMOVE_TAG) is a searchable select that fetches the project tags via useApi and
// reads projectId from the route — mock both so the select has options to prefill / pick.
const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))

function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}
async function submit() {
  await $('[data-test="step-form"]').trigger('submit')
  await settle()
}

describe('EditStepDialog', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('prefills existing tag step and emits the re-selected tag', async () => {
    // label === null → the option falls back to the slug, so the closed input shows the raw slug.
    apiMock.mockResolvedValueOnce([
      { slug: 'vip', label: null, subscriberCount: 0, createdAt: '2026-01-01T00:00:00Z' },
      { slug: 'gold', label: null, subscriberCount: 0, createdAt: '2026-01-01T00:00:00Z' },
    ])
    const step: FunnelStep = { stepType: 'ADD_TAG', tagSlug: 'vip' }
    const wrapper = await mountSuspended(EditStepDialog, { props: { open: true, step, index: 2 } })
    await settle()

    // Form is prefilled from the existing step (slug shown in the closed combobox input).
    expect(($('[data-test="step-tag-input"]').element as HTMLInputElement).value).toBe('vip')

    await $('[data-test="step-tag-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-tag-option-gold"]').trigger('mousedown')
    await settle()
    await submit()

    const emitted = wrapper.emitted('save')
    expect(emitted).toBeTruthy()
    // save emits (index, updatedStep) — index is preserved, slug updated.
    expect(emitted![0][0]).toBe(2)
    expect(emitted![0][1]).toMatchObject({ stepType: 'ADD_TAG', tagSlug: 'gold' })
  })

  it('prefills a MESSAGE step text block', async () => {
    const step: FunnelStep = {
      stepType: 'MESSAGE',
      blocks: [{ type: 'TEXT', text: 'Hello', parseMode: 'HTML' }],
    }
    await mountSuspended(EditStepDialog, { props: { open: true, step, index: 0 } })
    await settle()
    expect(($('[data-test="step-block-text-0"]').element as HTMLTextAreaElement).value).toBe('Hello')
  })
})
