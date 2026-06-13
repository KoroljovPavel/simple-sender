// @vitest-environment nuxt
import { describe, it, expect } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'
import FunnelCanvasSidePanel from '../../../components/funnels/FunnelCanvasSidePanel.vue'
import type { FunnelStep } from '../../../types/funnel'

// Group A (Task 6 TDD Anchor). The side panel mounts the UNCHANGED FunnelStepForm with
// :hide-target-pickers="true" for step nodes, and FunnelTriggerSettings for trigger/start nodes. Because the
// panel mounts FunnelStepForm — which reads useFunnelsStore + useApi + the route — those imports are mocked
// here (legitimate per the TDD Anchor; this spec's mocking rules are SEPARATE from FunnelCanvas.spec.ts).
mockNuxtImport('useApi', () => () => () => Promise.resolve([]))
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))
mockNuxtImport('useFunnelsStore', () => () => ({ funnels: [], fetchFunnels: () => Promise.resolve([]) }))

// A MESSAGE step carrying ALL three edge-owning target fields populated (a callback button target, a timeout
// target, and — irrelevant for MESSAGE but proves carry-through nothing is zeroed) so we can assert the
// hidden-picker form preserves them on submit.
function messageStepWithTargets(): FunnelStep {
  return {
    stepType: 'MESSAGE',
    id: 's1',
    blocks: [{ type: 'TEXT', text: 'Hi there' }],
    buttons: [{ type: 'callback', label: 'Go', targetStepId: 's2' }],
    timeoutValue: 5,
    timeoutUnit: 'MIN',
    timeoutTargetStepId: 's3',
  }
}

async function mountPanel(node: unknown) {
  const wrapper = await mountSuspended(FunnelCanvasSidePanel, {
    props: { node },
    attachTo: document.body,
  })
  await settle()
  return wrapper
}

describe('FunnelCanvasSidePanel', () => {
  it('side panel mounts FunnelStepForm with hideTargetPickers hides target SearchableSelects', async () => {
    // For a step node the panel mounts FunnelStepForm with hideTargetPickers → the button-target,
    // timeout-target and SUBSCRIBE-entry SearchableSelects are absent. Each SearchableSelect renders
    // data-test="${testPrefix}-select"; assert all three prefixes are gone.
    const wrapper = await mountPanel({ kind: 'step', step: messageStepWithTargets() })

    // The step form itself IS mounted.
    expect(document.querySelector('[data-test="step-form"]')).not.toBeNull()
    // No button target picker (prefix step-menu-target-<i>).
    expect(document.querySelector('[data-test^="step-menu-target-"]')).toBeNull()
    // No timeout target picker.
    expect(document.querySelector('[data-test="step-menu-timeout-target-select"]')).toBeNull()
    // No SUBSCRIBE entry picker.
    expect(document.querySelector('[data-test="step-subscribe-entry-select"]')).toBeNull()

    wrapper.unmount()
  })

  it('emitted step still carries existing fields with pickers hidden', async () => {
    // Submitting the hidden-pickers form must emit a FunnelStep whose targetStepId / timeoutTargetStepId
    // values are preserved (not dropped, not zeroed), AND a sibling field that must NOT change (the message
    // text block) stays intact.
    const wrapper = await mountPanel({ kind: 'step', step: messageStepWithTargets() })
    await settle()

    // Submit the form (the panel relays the form's submit event upward).
    ;(document.querySelector('[data-test="step-form"]') as HTMLFormElement).requestSubmit()
    await settle()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const step = emitted![0][0] as FunnelStep
    // Edge-owned targets preserved unchanged.
    expect(step.buttons?.[0]?.targetStepId).toBe('s2')
    expect(step.timeoutTargetStepId).toBe('s3')
    // Sibling content untouched.
    expect(step.stepType).toBe('MESSAGE')
    expect(step.blocks?.[0]).toMatchObject({ type: 'TEXT', text: 'Hi there' })

    wrapper.unmount()
  })

  it('mounts FunnelTriggerSettings for a trigger node, not the step form', async () => {
    const wrapper = await mountPanel({
      kind: 'trigger',
      trigger: { triggerType: 'event', triggerValue: 'evt', entryStepId: 's1' },
    })

    expect(document.querySelector('[data-test="funnel-trigger"]')).not.toBeNull()
    expect(document.querySelector('[data-test="step-form"]')).toBeNull()

    wrapper.unmount()
  })

  it('renders nothing actionable when no node is selected', async () => {
    const wrapper = await mountPanel(null)
    expect(document.querySelector('[data-test="step-form"]')).toBeNull()
    expect(document.querySelector('[data-test="funnel-trigger"]')).toBeNull()
    wrapper.unmount()
  })
})
