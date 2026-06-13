import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'
import FunnelCanvas from '../../../components/funnels/FunnelCanvas.client.vue'
import type { Connection } from '@vue-flow/core'
import type { FunnelNote, FunnelStep, FunnelTrigger } from '../../../types/funnel'

// Props-in / emits-out component test (TDD Anchor, Task 5). Mount FunnelCanvas with the funnel model as props
// and assert on rendered DOM (data-test handles / nodes / exit badge / broken-edge highlight) + emitted events
// (update:steps / update:triggers). NO useFunnelsStore mock — the canvas does not read the store (that pattern
// belongs to the page test, Task 7). Live drag/zoom is user-verified, not unit-tested; a drawn connection is
// driven via the exposed handleConnect (the same handler the onConnect callback runs in production).

function messageStep(over: Partial<FunnelStep> = {}): FunnelStep {
  return { stepType: 'MESSAGE', id: 's1', blocks: [{ type: 'TEXT', text: 'Hi' }], ...over }
}

async function mountWith(props: {
  steps: FunnelStep[]
  triggers: FunnelTrigger[]
  notes?: FunnelNote[] | null
}) {
  const wrapper = await mountSuspended(FunnelCanvas, { props })
  await settle()
  return wrapper
}

describe('FunnelCanvas', () => {
  it('renders a highlighted broken edge for a dangling edge', async () => {
    // s1.next points at a step that does not exist → the mapping layer flags it broken. The canvas surfaces
    // it (a dangling target has no node to draw an edge at) as a highlighted broken-edge marker + a red node.
    const steps: FunnelStep[] = [messageStep({ id: 's1', next: 'gone' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const broken = wrapper.findAll('[data-test="funnel-canvas-broken-edge"]')
    expect(broken.length).toBeGreaterThan(0)
    // The broken marker carries the owning field so a mis-flagged kind would fail.
    expect(broken.some((b) => b.attributes('data-edge-field') === 'next')).toBe(true)
    // The owning source node renders with the broken highlight class (not a normal node).
    const brokenNode = wrapper.find('[data-node-id="s1"]')
    expect(brokenNode.exists()).toBe(true)
    expect(brokenNode.classes()).toContain('funnel-canvas-node--broken')
  })

  it('renders cross-funnel SUBSCRIBE as an exit badge, not an edge', async () => {
    // A SUBSCRIBE_TO_FUNNEL step → an exit badge on its node, and NO outgoing edge to another funnel.
    const steps: FunnelStep[] = [
      { stepType: 'SUBSCRIBE_TO_FUNNEL', id: 's1', targetFunnelId: 'other-funnel', targetEntryStepId: null },
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    expect(wrapper.find('[data-test="funnel-canvas-exit-badge"]').exists()).toBe(true)
    // The exit-badge text is localized (not the raw key) AND resolves to the expected translated label.
    const exitBadgeText = wrapper.find('[data-test="funnel-canvas-exit-badge"]').text()
    expect(exitBadgeText).not.toContain('funnels.canvas')
    expect(exitBadgeText.trim().length).toBeGreaterThan(0)
    // Default locale is uk (i18n defaultLocale) — assert the resolved translated label, not the key.
    expect(exitBadgeText).toBe('Перехід у воронку')
    // No edge to the cross-funnel target — the target funnel is not a node on this canvas.
    expect(wrapper.find('[data-node-id="other-funnel"]').exists()).toBe(false)
  })

  it('renders the start node broken when on_start entryStepId is null (entry edge deleted)', async () => {
    // on_start trigger present (start node rendered) but its entry edge is deleted (entryStepId == null).
    // The mapping layer flags the entry as broken → the start node gets the broken highlight class and a
    // broken-edge marker for the entry field is surfaced.
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: null }]
    const wrapper = await mountWith({ steps, triggers })

    // The start node is present and carries the broken highlight class (entry edge dangling).
    const startNode = wrapper.find('[data-node-id="start"]')
    expect(startNode.exists()).toBe(true)
    expect(startNode.classes()).toContain('funnel-canvas-node--broken')

    // A broken-edge marker is surfaced for the entry field (a mis-flagged kind would fail).
    const broken = wrapper.findAll('[data-test="funnel-canvas-broken-edge"]')
    expect(broken.length).toBeGreaterThan(0)
    expect(broken.some((b) => b.attributes('data-edge-field') === 'entry')).toBe(true)
  })

  it('does NOT render a start node for an event-only funnel (start node optional)', async () => {
    // No on_start trigger → no start node (start-node-optional negative case). The event trigger node and
    // the step node are present, but [data-node-id="start"] is absent.
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'event', triggerValue: 'evt', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    expect(wrapper.find('[data-node-id="start"]').exists()).toBe(false)
    // The funnel still renders (canvas root + the saved step node present).
    expect(wrapper.find('[data-test="funnel-canvas"]').exists()).toBe(true)
    expect(wrapper.find('[data-node-id="s1"]').exists()).toBe(true)
  })

  it('renders typed output handles per node type', async () => {
    // A MESSAGE step with 2 callback buttons + a timeout → default `next` handle + 2 button handles + 1
    // timeout handle. The on_start trigger renders the entry handle. A mis-typed handle set must fail.
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        buttons: [
          { type: 'callback', label: 'A', targetStepId: null },
          { type: 'callback', label: 'B', targetStepId: null },
          { type: 'url', label: 'Open', url: 'https://example.com' },
        ],
        timeoutValue: 5,
        timeoutUnit: 'MIN',
        timeoutTargetStepId: null,
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const stepNode = wrapper.find('[data-node-id="s1"]')
    expect(stepNode.exists()).toBe(true)
    // default next + 2 callback-button handles + 1 timeout. URL button carries NO handle.
    expect(stepNode.find('[data-test="funnel-canvas-handle-next"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-0"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-1"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-2"]').exists()).toBe(false)
    expect(stepNode.find('[data-test="funnel-canvas-handle-timeout"]').exists()).toBe(true)
    // The step has an input (target) handle for incoming edges.
    expect(stepNode.find('[data-test="funnel-canvas-handle-target"]').exists()).toBe(true)

    // The start node renders the entry handle and NO step output handles.
    const startNode = wrapper.find('[data-node-id="start"]')
    expect(startNode.exists()).toBe(true)
    expect(startNode.find('[data-test="funnel-canvas-handle-entry"]').exists()).toBe(true)
    expect(startNode.find('[data-test="funnel-canvas-handle-next"]').exists()).toBe(false)
  })

  it('a drawn connection emits the updated model', async () => {
    // Draw next:s1 -> s2 (both saved) → emits update:steps with s1.next = 's2'.
    const steps: FunnelStep[] = [messageStep({ id: 's1', next: null }), messageStep({ id: 's2' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const conn: Connection = { source: 's1', target: 's2', sourceHandle: 'next', targetHandle: null }
    ;(wrapper.vm as unknown as { handleConnect: (c: Connection) => void }).handleConnect(conn)
    await settle()

    const emitted = wrapper.emitted('update:steps')
    expect(emitted).toBeTruthy()
    const updatedSteps = emitted![0][0] as FunnelStep[]
    expect(updatedSteps.find((s) => s.id === 's1')?.next).toBe('s2')
  })

  it('a connection to an unsaved target node emits nothing', async () => {
    // The second step has no id (unsaved) → its node id is synthetic; a connection to it is refused.
    const steps: FunnelStep[] = [messageStep({ id: 's1', next: null }), messageStep({ id: null })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const conn: Connection = {
      source: 's1',
      target: 'unsaved-step:1',
      sourceHandle: 'next',
      targetHandle: null,
    }
    ;(wrapper.vm as unknown as { handleConnect: (c: Connection) => void }).handleConnect(conn)
    await settle()

    expect(wrapper.emitted('update:steps')).toBeFalsy()
    expect(wrapper.emitted('update:triggers')).toBeFalsy()
  })

  it('a drawn entry connection emits updated triggers', async () => {
    // Draw entry from the on_start node to s1 → emits update:triggers with the trigger entryStepId set.
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: null }]
    const wrapper = await mountWith({ steps, triggers })

    const conn: Connection = { source: 'start', target: 's1', sourceHandle: 'entry', targetHandle: null }
    ;(wrapper.vm as unknown as { handleConnect: (c: Connection) => void }).handleConnect(conn)
    await settle()

    const emitted = wrapper.emitted('update:triggers')
    expect(emitted).toBeTruthy()
    const updatedTriggers = emitted![0][0] as FunnelTrigger[]
    expect(updatedTriggers[0].entryStepId).toBe('s1')
  })

  it('renders the canvas root and a node per saved step (renders nodes from model)', async () => {
    const steps: FunnelStep[] = [messageStep({ id: 's1' }), messageStep({ id: 's2' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    expect(wrapper.find('[data-test="funnel-canvas"]').exists()).toBe(true)
    expect(wrapper.find('[data-node-id="s1"]').exists()).toBe(true)
    expect(wrapper.find('[data-node-id="s2"]').exists()).toBe(true)
    expect(wrapper.find('[data-node-id="start"]').exists()).toBe(true)
  })
})
