// @vitest-environment nuxt
import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import FunnelStepsList from '../../components/funnels/FunnelStepsList.vue'
import type { FunnelStep } from '../../types/funnel'

const STEPS: FunnelStep[] = [
  { stepType: 'SEND_MESSAGE', text: 'Hi' },
  { stepType: 'DELAY', delayValue: 5, delayUnit: 'MIN' },
  { stepType: 'ADD_TAG', tagSlug: 'vip' },
]

describe('FunnelStepsList', () => {
  it('move up/down disabled at bounds and reorders', async () => {
    const wrapper = await mountSuspended(FunnelStepsList, { props: { steps: STEPS } })
    await settle()

    // First row: ↑ disabled, ↓ enabled.
    expect((wrapper.get('[data-test="funnel-step-move-up-0"]').element as HTMLButtonElement).disabled).toBe(true)
    expect((wrapper.get('[data-test="funnel-step-move-down-0"]').element as HTMLButtonElement).disabled).toBe(false)
    // Last row: ↓ disabled.
    expect((wrapper.get('[data-test="funnel-step-move-down-2"]').element as HTMLButtonElement).disabled).toBe(true)
    expect((wrapper.get('[data-test="funnel-step-move-up-2"]').element as HTMLButtonElement).disabled).toBe(false)

    // Clicking ↓ on row 0 emits a reorder (0 → 1).
    await wrapper.get('[data-test="funnel-step-move-down-0"]').trigger('click')
    const moved = wrapper.emitted('move')
    expect(moved).toBeTruthy()
    expect(moved![0]).toEqual([0, 1])
  })

  it('delete uses inline confirm before emitting', async () => {
    const wrapper = await mountSuspended(FunnelStepsList, { props: { steps: STEPS } })
    await settle()

    await wrapper.get('[data-test="funnel-step-delete-1"]').trigger('click')
    await settle()
    // No delete emit yet — confirm button appears.
    expect(wrapper.emitted('delete')).toBeFalsy()
    expect(wrapper.find('[data-test="funnel-step-delete-confirm-1"]').exists()).toBe(true)

    await wrapper.get('[data-test="funnel-step-delete-confirm-1"]').trigger('click')
    const del = wrapper.emitted('delete')
    expect(del).toBeTruthy()
    expect(del![0]).toEqual([1])
  })

  it('renders empty state with add CTA', async () => {
    const wrapper = await mountSuspended(FunnelStepsList, { props: { steps: [] } })
    await settle()
    expect(wrapper.find('[data-test="funnel-steps-empty"]').exists()).toBe(true)
    await wrapper.get('[data-test="funnel-steps-empty-add"]').trigger('click')
    expect(wrapper.emitted('add')).toBeTruthy()
  })
})
