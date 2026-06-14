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

  it('shows the start-not-connected message (NOT "target deleted") for an unwired on_start entry', async () => {
    // on_start start node present but never wired (entryStepId null) → reason on_start_entry_null.
    // Nothing was deleted: the banner must read the actionable "start not connected" wording,
    // not the misleading "target deleted" wording. Load-bearing: fails against the generic message.
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: null }]
    const wrapper = await mountWith({ steps, triggers })

    const entryBroken = wrapper
      .findAll('[data-test="funnel-canvas-broken-edge"]')
      .find((b) => b.attributes('data-edge-reason') === 'on_start_entry_null')
    expect(entryBroken).toBeDefined()
    const text = entryBroken!.text()
    // Default locale is uk — the resolved on_start-not-connected label, not the raw key.
    expect(text).toBe("Старт не з'єднано — проведіть ребро до кроку")
    // Must NOT show the "target deleted" wording (nothing was deleted here).
    expect(text).not.toContain('видалено')
  })

  it('shows the "target deleted" message for a missing_target broken edge', async () => {
    // A non-null edge field points at a step that no longer exists → reason missing_target.
    // "target deleted" wording is accurate here and must be kept.
    const steps: FunnelStep[] = [messageStep({ id: 's1', next: 'gone' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const missing = wrapper
      .findAll('[data-test="funnel-canvas-broken-edge"]')
      .find((b) => b.attributes('data-edge-reason') === 'missing_target')
    expect(missing).toBeDefined()
    expect(missing!.text()).toBe("Зламане з'єднання — ціль видалено")
  })

  it('clears the broken-edge banner once the on_start entry is wired to a step', async () => {
    // Wiring a valid entryStepId resolves the on_start_entry_null broken edge → banner gone.
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    expect(wrapper.find('[data-test="funnel-canvas-broken-edges"]').exists()).toBe(false)
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

  // ── Task 6: authoring interactions (palette / start-node lifecycle / delete-warning) ──────────────────

  // NOTE: the event-only "no start node" case is covered by the stronger test above
  // ('does NOT render a start node for an event-only funnel (start node optional)', which also asserts the
  // canvas root + step node render) — the near-duplicate here was removed (T10 audit minor).

  it('start node rendered when on_start exists', async () => {
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    expect(wrapper.find('[data-node-id="start"]').exists()).toBe(true)
  })

  it('re-adding on_start is disabled while it already exists', async () => {
    // With an on_start entry present, the palette's start-node entry is disabled (a funnel has at most one).
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const startItem = wrapper.find('[data-test="funnel-canvas-palette-start"]')
    expect(startItem.exists()).toBe(true)
    expect((startItem.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('adding on_start from the palette is enabled and emits an on_start trigger for an event-only funnel', async () => {
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'event', triggerValue: 'evt', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const startItem = wrapper.find('[data-test="funnel-canvas-palette-start"]')
    expect((startItem.element as HTMLButtonElement).disabled).toBe(false)
    await startItem.trigger('click')
    await settle()

    const emitted = wrapper.emitted('update:triggers')
    expect(emitted).toBeTruthy()
    const updated = emitted![emitted!.length - 1][0] as FunnelTrigger[]
    expect(updated.some((tr) => tr.triggerType === 'on_start')).toBe(true)
  })

  it('deleting the start node removes the on_start entry', async () => {
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    // Drive delete of the start node deterministically (live click is user-verified).
    ;(wrapper.vm as unknown as { requestDelete: (id: string) => void }).requestDelete('start')
    await settle()
    ;(wrapper.vm as unknown as { confirmDelete: () => void }).confirmDelete()
    await settle()

    const emitted = wrapper.emitted('update:triggers')
    expect(emitted).toBeTruthy()
    const updated = emitted![emitted!.length - 1][0] as FunnelTrigger[]
    expect(updated.some((tr) => tr.triggerType === 'on_start')).toBe(false)
  })

  // NOTE: the on_start-with-null-entry broken-edge state is covered by the stronger test above
  // ('renders the start node broken when on_start entryStepId is null (entry edge deleted)') — both asserted
  // the same rendered state (start node --broken + an entry broken-edge marker), so the near-duplicate here
  // was removed (T10 audit minor).

  it('deleting a node warns with the exact disconnect count', async () => {
    // s2 has THREE inbound edges: s1.next, s1 callback button, s3.timeout → deleting s2 warns "3".
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: 's2',
        buttons: [{ type: 'callback', label: 'B', targetStepId: 's2' }],
      }),
      messageStep({ id: 's2' }),
      messageStep({ id: 's3', timeoutValue: 5, timeoutUnit: 'MIN', timeoutTargetStepId: 's2' }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    ;(wrapper.vm as unknown as { requestDelete: (id: string) => void }).requestDelete('s2')
    await settle()

    const warning = wrapper.find('[data-test="funnel-canvas-delete-warning"]')
    expect(warning.exists()).toBe(true)
    // The exact count surfaced to the operator (mapping-layer disconnectedCount).
    expect(warning.attributes('data-disconnect-count')).toBe('3')
    expect(warning.text()).toContain('3')
  })

  // ── Side-panel step submit routing (save-noop-fix: the exact manual-test repro) ───────────────────────
  // BUG (confirmed H1): onPanelStepSubmit matched the edited step back into the array BY ID. A freshly-added
  // palette node carries id:null (no server id until the first PATCH), so `s.id != null && s.id === sel.id`
  // matched NOTHING — the edited step (with its new text) was silently dropped, the array stayed the empty
  // seed, stepReady() was false, and persistSteps() withheld the PATCH (no request, no error). Routing the
  // submit by the node's stable stepINDEX fixes it for unsaved AND saved nodes.
  it('a side-panel submit on a freshly-added (id:null) MESSAGE node emits update:steps with the new text', async () => {
    // One on_start trigger pointing nowhere (null entry — the legit broken-edge state) + a fresh empty MESSAGE
    // node with NO id, exactly like the palette seeds it (newStep → blocks:[{TEXT, text:''}], id:null).
    const steps: FunnelStep[] = [{ stepType: 'MESSAGE', id: null, blocks: [{ type: 'TEXT', text: '' }] }]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', triggerValue: '', entryStepId: null }]
    const wrapper = await mountWith({ steps, triggers })

    // Select the fresh step node (its synthetic id), then relay the form's submit with the filled text —
    // the same path the side panel runs when the author types "Привіт" and clicks Зберегти.
    const vm = wrapper.vm as unknown as {
      selectNode: (n: { id: string; data: Record<string, unknown> }) => void
      onPanelStepSubmit?: (s: FunnelStep) => void
    }
    vm.selectNode({ id: 'unsaved-step:0', data: { kind: 'step', stepIndex: 0 } })
    await settle()
    // Drive the panel's submit relay (the canvas exposes onPanelStepSubmit via defineExpose for this test).
    ;(wrapper.vm as unknown as { onPanelStepSubmit: (s: FunnelStep) => void }).onPanelStepSubmit({
      stepType: 'MESSAGE',
      id: null,
      blocks: [{ type: 'TEXT', text: 'Привіт' }],
    })
    await settle()

    const emitted = wrapper.emitted('update:steps')
    expect(emitted).toBeTruthy()
    const updated = emitted![emitted!.length - 1][0] as FunnelStep[]
    // The edited step replaced the empty seed AT INDEX 0 (matched by index, not by the null id).
    expect(updated).toHaveLength(1)
    expect(updated[0].blocks?.[0]?.text).toBe('Привіт')
  })

  it('a side-panel submit on a SAVED MESSAGE node still routes the edit (index match, no regression)', async () => {
    const steps: FunnelStep[] = [messageStep({ id: 's1', blocks: [{ type: 'TEXT', text: 'old' }] })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    ;(wrapper.vm as unknown as { selectNode: (n: { id: string; data: Record<string, unknown> }) => void }).selectNode({
      id: 's1',
      data: { kind: 'step', stepIndex: 0 },
    })
    await settle()
    ;(wrapper.vm as unknown as { onPanelStepSubmit: (s: FunnelStep) => void }).onPanelStepSubmit({
      stepType: 'MESSAGE',
      id: 's1',
      blocks: [{ type: 'TEXT', text: 'new' }],
    })
    await settle()

    const emitted = wrapper.emitted('update:steps')
    expect(emitted).toBeTruthy()
    const updated = emitted![emitted!.length - 1][0] as FunnelStep[]
    expect(updated[0].blocks?.[0]?.text).toBe('new')
  })

  it('a side-panel submit does NOT overwrite the wrong step when the array shifted (staleness guard)', async () => {
    // Open the panel on step sA at index 0, THEN simulate the array shifting (a different node deleted →
    // reindex) so index 0 now holds a DIFFERENT saved step sB. Submitting must NOT overwrite sB at index 0 —
    // the selected step's id (sA) and the id now at index 0 (sB) differ, so the guard bails (no write).
    const steps: FunnelStep[] = [messageStep({ id: 'sA', blocks: [{ type: 'TEXT', text: 'A' }] }), messageStep({ id: 'sB', blocks: [{ type: 'TEXT', text: 'B' }] })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 'sA' }]
    const wrapper = await mountWith({ steps, triggers })

    // Select sA at its index 0 (the panel captures sel.step = sA).
    ;(wrapper.vm as unknown as { selectNode: (n: { id: string; data: Record<string, unknown> }) => void }).selectNode({
      id: 'sA',
      data: { kind: 'step', stepIndex: 0 },
    })
    await settle()

    // The array shifts WHILE the panel is open: sA is gone, sB slid to index 0 (now stepIndex 0 ≠ sel.step).
    await wrapper.setProps({ steps: [messageStep({ id: 'sB', blocks: [{ type: 'TEXT', text: 'B' }] })] })
    await settle()

    // Submit the (stale) edit for sA. The guard must refuse: index 0 now holds sB, not sA.
    ;(wrapper.vm as unknown as { onPanelStepSubmit: (s: FunnelStep) => void }).onPanelStepSubmit({
      stepType: 'MESSAGE',
      id: 'sA',
      blocks: [{ type: 'TEXT', text: 'A-edited' }],
    })
    await settle()

    // No write: sB at index 0 was NOT overwritten with the stale sA edit. (Pre-guard this emitted update:steps
    // with sB replaced by 'A-edited' at index 0.)
    expect(wrapper.emitted('update:steps')).toBeFalsy()
    expect(wrapper.emitted('step-save')).toBeFalsy()
  })

  it('the palette offers exactly the 9 existing step types (no new executable types)', async () => {
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const stepItems = wrapper.findAll('[data-test^="funnel-canvas-palette-step-"]')
    const types = stepItems.map((i) => i.attributes('data-step-type')).sort()
    expect(types).toEqual(
      [
        'ADD_TAG',
        'CLEAR_KEYBOARD',
        'DELAY',
        'EMIT_EVENT',
        'MESSAGE',
        'REMOVE_TAG',
        'SET_CUSTOM_FIELD',
        'SET_KEYBOARD',
        'SUBSCRIBE_TO_FUNNEL',
      ].sort(),
    )
    // trigger + note + start are separate (non-executable) palette entries.
    expect(wrapper.find('[data-test="funnel-canvas-palette-trigger"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-canvas-palette-note"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-canvas-palette-start"]').exists()).toBe(true)
  })
})
