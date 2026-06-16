// @vitest-environment nuxt
import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../../helpers/settle'
import FunnelStepForm from '../../../components/funnels/FunnelStepForm.vue'
import { useFunnelsStore } from '../../../stores/funnels'
import type { FunnelStep } from '../../../types/funnel'

// Data-loss regression (18-funnel-canvas): editing + saving a NON-MESSAGE step (DELAY, ADD_TAG,
// SUBSCRIBE_TO_FUNNEL, …) must carry the server-minted graph fields (id, next, canvasPosition) the form does
// NOT edit straight through. Before the fix these branches rebuilt the step as { stepType, ...editedFields }
// and dropped id/next → on save the backend minted a NEW id and every inbound edge to the step dangled
// ("broken connection — target deleted"). These tests prove the fix is GENERAL (not DELAY-only).
mockNuxtImport('useApi', () => () => () => Promise.resolve([]))
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1', funnelId: 'fX' } }))

function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}
async function submitForm() {
  await $('[data-test="step-form"]').trigger('submit')
  await settle()
}

async function mountWith(initial: FunnelStep) {
  const wrapper = await mountSuspended(FunnelStepForm, {
    props: { initial, siblingSteps: [], submitLabel: 'Save' },
    attachTo: document.body,
  })
  await settle()
  return wrapper
}

function emittedStep(wrapper: Awaited<ReturnType<typeof mountWith>>): FunnelStep {
  const events = wrapper.emitted('submit')
  expect(events).toBeTruthy()
  return (events as unknown[][])[0][0] as FunnelStep
}

describe('FunnelStepForm — preserves id/next/canvasPosition for every step type on edit', () => {
  beforeEach(() => {
    useFunnelsStore().funnels = []
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('DELAY: editing the value keeps id + next (the inbound SET_KEYBOARD.next stays valid)', async () => {
    const wrapper = await mountWith({
      stepType: 'DELAY',
      delayValue: 5,
      delayUnit: 'MIN',
      id: 'delay-1',
      next: 'after-1',
      canvasPosition: { x: 12, y: 34 },
    })
    // Edit the param the form DOES own.
    await $('[data-test="step-delay-value-input"]').setValue('9')
    await settle()
    await submitForm()

    const step = emittedStep(wrapper)
    expect(step.stepType).toBe('DELAY')
    expect(step.delayValue).toBe(9)
    // The repro: id MUST survive so a SET_KEYBOARD.next pointing here does not dangle.
    expect(step.id).toBe('delay-1')
    expect(step.next).toBe('after-1')
    expect(step.canvasPosition).toEqual({ x: 12, y: 34 })
  })

  it('ADD_TAG: editing keeps id + next', async () => {
    const wrapper = await mountWith({
      stepType: 'ADD_TAG',
      tagSlug: 'vip',
      id: 'tag-1',
      next: 'after-tag',
      canvasPosition: { x: 1, y: 2 },
    })
    await settle()
    await submitForm()

    const step = emittedStep(wrapper)
    expect(step.stepType).toBe('ADD_TAG')
    expect(step.tagSlug).toBe('vip')
    expect(step.id).toBe('tag-1')
    expect(step.next).toBe('after-tag')
    expect(step.canvasPosition).toEqual({ x: 1, y: 2 })
  })

  it('SUBSCRIBE_TO_FUNNEL: editing keeps id + next', async () => {
    const wrapper = await mountWith({
      stepType: 'SUBSCRIBE_TO_FUNNEL',
      targetFunnelId: 'fA',
      targetEntryStepId: null,
      endParentAfter: false,
      id: 'sub-1',
      next: 'after-sub',
      canvasPosition: { x: 7, y: 8 },
    })
    await settle()
    await submitForm()

    const step = emittedStep(wrapper)
    expect(step.stepType).toBe('SUBSCRIBE_TO_FUNNEL')
    expect(step.id).toBe('sub-1')
    expect(step.next).toBe('after-sub')
    expect(step.canvasPosition).toEqual({ x: 7, y: 8 })
  })
})
