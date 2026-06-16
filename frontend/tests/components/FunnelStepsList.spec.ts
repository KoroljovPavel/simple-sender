// @vitest-environment nuxt
import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../helpers/settle'
import FunnelStepsList from '../../components/funnels/FunnelStepsList.vue'
import type { FunnelStep } from '../../types/funnel'

const STEPS: FunnelStep[] = [
  { stepType: 'MESSAGE', blocks: [{ type: 'TEXT', text: 'Hi' }] },
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

  it('readonly=true hides all structural mutators and shows the read-only notice (Decision 2)', async () => {
    // On the canvas-driven editor the list is a VIEW only — the canvas owns structural edits. readonly must
    // remove every mutator (add / edit / delete / move) and surface the read-only notice. Load-bearing: if
    // readonly stopped gating these the canvas would no longer be the sole structural-editing surface.
    const wrapper = await mountSuspended(FunnelStepsList, { props: { steps: STEPS, readonly: true } })
    await settle()

    // The read-only notice is shown.
    expect(wrapper.find('[data-test="funnel-steps-readonly-notice"]').exists()).toBe(true)

    // No structural mutators on ANY row: move-up/down, edit, delete are all absent.
    expect(wrapper.find('[data-test="funnel-step-move-up-0"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-step-move-down-0"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-step-edit-0"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-step-delete-0"]').exists()).toBe(false)

    // The rows themselves still render (it's a view), and `select` (non-structural) is preserved.
    expect(wrapper.find('[data-test="funnel-step-row-0"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-step-select-0"]').exists()).toBe(true)
  })

  it('readonly=true on an empty list shows the notice but NOT the add CTA', async () => {
    // The empty-state add button is a structural mutator → also gated by readonly (only the notice remains).
    const wrapper = await mountSuspended(FunnelStepsList, { props: { steps: [], readonly: true } })
    await settle()

    expect(wrapper.find('[data-test="funnel-steps-readonly-notice"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-steps-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-steps-empty-add"]').exists()).toBe(false)
  })

  it('default (readonly=false) still shows the structural mutators and no read-only notice', async () => {
    // The editable default is the contrast case: mutators present, notice absent. Guards against a regression
    // where the readonly gating accidentally hid controls in the normal (editable) mode.
    const wrapper = await mountSuspended(FunnelStepsList, { props: { steps: STEPS } })
    await settle()

    expect(wrapper.find('[data-test="funnel-steps-readonly-notice"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-step-move-up-0"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-step-move-down-0"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-step-edit-0"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-step-delete-0"]').exists()).toBe(true)
  })

  it('renders empty state with add CTA', async () => {
    const wrapper = await mountSuspended(FunnelStepsList, { props: { steps: [] } })
    await settle()
    expect(wrapper.find('[data-test="funnel-steps-empty"]').exists()).toBe(true)
    await wrapper.get('[data-test="funnel-steps-empty-add"]').trigger('click')
    expect(wrapper.emitted('add')).toBeTruthy()
  })
})
