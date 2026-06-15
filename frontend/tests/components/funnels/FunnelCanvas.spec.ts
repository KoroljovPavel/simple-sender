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

    // The container is a generic group: its aria-label is the NEUTRAL group label, not the
    // per-reason "target deleted" message — misleading when the list has only this on_start item.
    const container = wrapper.find('[data-test="funnel-canvas-broken-edges"]')
    expect(container.attributes('aria-label')).toBe("Зламані з'єднання")
    expect(container.attributes('aria-label')).not.toContain('видалено')
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
    // card-preview semantics: a MESSAGE step WITH callback buttons WAITS — each callback button fires its own
    // edge, OR the timeout elapses. `next` is dead (engine never fires it). So with 2 callback buttons + timeout
    // and next=null → 2 button handles + 1 timeout handle, NO `next` handle. URL button carries NO handle.
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
    // WITH callback buttons → `next` is gated OFF (dead in the engine), timeout is ON.
    expect(stepNode.find('[data-test="funnel-canvas-handle-next"]').exists()).toBe(false)
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-0"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-1"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-2"]').exists()).toBe(false)
    expect(stepNode.find('[data-test="funnel-canvas-handle-timeout"]').exists()).toBe(true)
    // Whole-card drop target (handles-redesign): the step is connectable as a target via a single
    // node-covering target handle — NO separate visible input dot element.
    expect(stepNode.find('[data-test="funnel-canvas-handle-target"]').exists()).toBe(true)

    // The start node renders the entry handle and NO step output handles.
    const startNode = wrapper.find('[data-node-id="start"]')
    expect(startNode.exists()).toBe(true)
    expect(startNode.find('[data-test="funnel-canvas-handle-entry"]').exists()).toBe(true)
    expect(startNode.find('[data-test="funnel-canvas-handle-next"]').exists()).toBe(false)
  })

  it('renders stacked, labeled, monochrome output handles + a whole-card drop target (handles-redesign)', async () => {
    // handles-redesign: outputs are stacked monochrome circles, each carrying a text LABEL (not a per-kind
    // color). next→"Далі", each callback button→its text, timeout→"Таймаут". The input is the WHOLE card
    // (one node-covering target handle), so there is NO separate input dot.
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
    // card-preview semantics: WITH callback buttons + timeout, next=null → 3 stacked output handles
    // (btn:0 + btn:1 + timeout). `next` is gated OFF (dead in the engine). URL button excluded from handles.
    const outputs = stepNode.findAll('[data-test^="funnel-canvas-handle-"]:not([data-test="funnel-canvas-handle-target"])')
    const outputIds = outputs.map((h) => h.attributes('data-handle-id'))
    expect(outputIds).toEqual(['btn:0', 'btn:1', 'timeout'])

    // Monochrome: every output carries the shared class and NONE of the old per-kind color classes.
    for (const h of outputs) {
      expect(h.classes()).toContain('funnel-handle')
      expect(h.classes()).not.toContain('funnel-handle--next')
      expect(h.classes()).not.toContain('funnel-handle--button')
      expect(h.classes()).not.toContain('funnel-handle--timeout')
    }

    // Each output handle is wrapped in a labeled row whose visible text is the output's label. (The preview
    // text block also lives in the card, so we read labels from the output ROWS, not all labels in the card.)
    const labels = stepNode
      .findAll('[data-test="funnel-canvas-output-row"] [data-test="funnel-canvas-output-label"]')
      .map((l) => l.text())
    expect(labels).toEqual(['A', 'B', 'Таймаут'])

    // Whole-card drop target: a single node-covering target handle, NO separate input dot.
    const targets = stepNode.findAll('[data-test="funnel-canvas-handle-target"]')
    expect(targets).toHaveLength(1)
    expect(targets[0].classes()).toContain('funnel-handle--card-target')

    // The start node renders exactly ONE output (entry, labeled "Вхід") and NO input dot / target.
    const startNode = wrapper.find('[data-node-id="start"]')
    const startOutputs = startNode.findAll('[data-test^="funnel-canvas-handle-"]:not([data-test="funnel-canvas-handle-target"])')
    expect(startOutputs.map((h) => h.attributes('data-handle-id'))).toEqual(['entry'])
    expect(startNode.find('[data-test="funnel-canvas-output-label"]').text()).toBe('Вхід')
    expect(startNode.find('[data-test="funnel-canvas-handle-target"]').exists()).toBe(false)
  })

  it('lays the node out as a header + stacked output rows (title never inside the outputs overlay)', async () => {
    // node-layout-fix: the title lives in its OWN full-width header element, separate from the outputs
    // section. Each output is its OWN row holding the label + the source Handle (data-handle-id) so the dot
    // lines up on the right border at that row. The title must NOT be rendered inside the outputs container
    // (the old overlap bug: outputs absolutely positioned over the title).
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        buttons: [{ type: 'callback', label: 'A', targetStepId: null }],
        timeoutValue: 5,
        timeoutUnit: 'MIN',
        timeoutTargetStepId: null,
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const stepNode = wrapper.find('[data-node-id="s1"]')

    // The header holds the localized title (MESSAGE → "Повідомлення") and is its own element.
    const header = stepNode.find('[data-test="funnel-canvas-node-header"]')
    expect(header.exists()).toBe(true)
    const title = header.find('[data-test="funnel-canvas-node-title"]')
    expect(title.exists()).toBe(true)
    expect(title.text()).toBe('Повідомлення')

    // The outputs container is separate and does NOT contain the title (no overlap/truncation).
    const outputsBox = stepNode.find('[data-test="funnel-canvas-node-outputs"]')
    expect(outputsBox.exists()).toBe(true)
    expect(outputsBox.find('[data-test="funnel-canvas-node-title"]').exists()).toBe(false)

    // The header is NOT nested inside the outputs box.
    expect(outputsBox.find('[data-test="funnel-canvas-node-header"]').exists()).toBe(false)

    // One row per output, each row carrying its OWN label + handle (label + dot live together in the row).
    // card-preview semantics: this step HAS a callback button → `next` is gated OFF; rows are btn:0 + timeout.
    const rows = outputsBox.findAll('[data-test="funnel-canvas-output-row"]')
    expect(rows).toHaveLength(2) // btn:0 + timeout
    expect(rows.map((r) => r.find('[data-test="funnel-canvas-output-label"]').text())).toEqual([
      'A',
      'Таймаут',
    ])
    expect(
      rows.map((r) =>
        r.find('[data-test^="funnel-canvas-handle-"]').attributes('data-handle-id'),
      ),
    ).toEqual(['btn:0', 'timeout'])
  })

  it('truncates a long callback-button output label (handles-redesign)', async () => {
    // A long button text is truncated to 16 chars + … so the stacked label stays compact.
    const longLabel = 'This is a very long button caption that overflows'
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        buttons: [{ type: 'callback', label: longLabel, targetStepId: null }],
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const btnLabel = wrapper
      .find('[data-node-id="s1"]')
      .findAll('[data-test="funnel-canvas-output-label"]')
      .find((l) => l.text().endsWith('…'))
    expect(btnLabel).toBeDefined()
    expect(btnLabel!.text()).toBe(`${longLabel.slice(0, 16)}…`)
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

  // ── card-preview: semantics-aware outputs (gated next/timeout + keep-if-set safety) ───────────────────

  it('MESSAGE with callback buttons + timeout (next null): button rows + Таймаут, NO Далі', async () => {
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: null,
        buttons: [
          { type: 'callback', label: 'A', targetStepId: null },
          { type: 'callback', label: 'B', targetStepId: null },
        ],
        timeoutValue: 5,
        timeoutUnit: 'MIN',
        timeoutTargetStepId: null,
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const stepNode = wrapper.find('[data-node-id="s1"]')
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-0"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-1"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-handle-timeout"]').exists()).toBe(true)
    // next is dead when callback buttons exist → no handle.
    expect(stepNode.find('[data-test="funnel-canvas-handle-next"]').exists()).toBe(false)
  })

  it('MESSAGE with no buttons + next set: Далі handle shown, NO Таймаут', async () => {
    // No callback buttons → only `next` fires; timeoutTargetStepId is inert → no timeout handle.
    const steps: FunnelStep[] = [
      messageStep({ id: 's1', next: 's2' }),
      messageStep({ id: 's2' }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const stepNode = wrapper.find('[data-node-id="s1"]')
    expect(stepNode.find('[data-test="funnel-canvas-handle-next"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-handle-timeout"]').exists()).toBe(false)
  })

  it('gating safety: WITH buttons but next!=null → Далі handle still shown (no orphaned edge)', async () => {
    // next is "dead" semantically, BUT an existing next edge must keep its source handle or the drawn edge
    // would dangle (buildEdges emits e:next:s1 because next!=null). The keep-if-set rule guarantees this.
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: 's2',
        buttons: [{ type: 'callback', label: 'A', targetStepId: null }],
      }),
      messageStep({ id: 's2' }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const stepNode = wrapper.find('[data-node-id="s1"]')
    expect(stepNode.find('[data-test="funnel-canvas-handle-next"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-0"]').exists()).toBe(true)
  })

  it('gating safety: no buttons but timeoutTargetStepId!=null → Таймаут handle still shown', async () => {
    // timeout is inert without buttons, BUT an existing timeout edge must keep its source handle (buildEdges
    // emits e:timeout:s1 because timeoutTargetStepId!=null) → keep-if-set rule.
    const steps: FunnelStep[] = [
      messageStep({ id: 's1', next: null, timeoutTargetStepId: 's2' }),
      messageStep({ id: 's2' }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const stepNode = wrapper.find('[data-node-id="s1"]')
    expect(stepNode.find('[data-test="funnel-canvas-handle-timeout"]').exists()).toBe(true)
  })

  it('URL button appears in the preview but has NO btn handle; btn indexing matches callback order', async () => {
    // buttons = [callback A, url Open, callback B]. callbackButtons() filters URL → A=btn:0, B=btn:1.
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: null,
        buttons: [
          { type: 'callback', label: 'A', targetStepId: null },
          { type: 'url', label: 'Open', url: 'https://example.com' },
          { type: 'callback', label: 'B', targetStepId: null },
        ],
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const stepNode = wrapper.find('[data-node-id="s1"]')
    // Exactly two btn handles, indexed by callback order (URL excluded, no shift).
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-0"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-1"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-handle-button-2"]').exists()).toBe(false)
    // The URL button is surfaced in the preview (its label is visible) but carries NO handle row.
    expect(stepNode.find('[data-test="funnel-canvas-preview-url-button"]').exists()).toBe(true)
    expect(stepNode.find('[data-test="funnel-canvas-preview-url-button"]').text()).toContain('Open')
  })

  // ── card-preview: in-card message preview ─────────────────────────────────────────────────────────────

  it('renders a truncated text preview for a MESSAGE node (escaped, no v-html)', async () => {
    const long = 'a'.repeat(200)
    const steps: FunnelStep[] = [
      messageStep({ id: 's1', next: null, blocks: [{ type: 'TEXT', text: long }] }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const preview = wrapper.find('[data-node-id="s1"]').find('[data-test="funnel-canvas-preview-text"]')
    expect(preview.exists()).toBe(true)
    const text = preview.text()
    expect(text.endsWith('…')).toBe(true)
    expect(text.length).toBeLessThan(long.length)
  })

  it('renders an http(s) media thumbnail as an <img :src>', async () => {
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: null,
        blocks: [{ type: 'IMAGE', mediaUrl: 'https://example.com/a.png', caption: 'cap' }],
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const img = wrapper.find('[data-node-id="s1"]').find('[data-test="funnel-canvas-preview-thumb"]')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('https://example.com/a.png')
  })

  it('falls back to a media-type label when the block has no http(s) thumbnail', async () => {
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: null,
        blocks: [{ type: 'VIDEO', mediaUrl: 'tg-file-id-opaque' }],
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const node = wrapper.find('[data-node-id="s1"]')
    expect(node.find('[data-test="funnel-canvas-preview-thumb"]').exists()).toBe(false)
    expect(node.find('[data-test="funnel-canvas-preview-media-fallback"]').exists()).toBe(true)
  })

  // ── card-preview: XSS / scheme safety ─────────────────────────────────────────────────────────────────

  it('renders message text / button label / caption containing HTML as escaped (never an <img> sink)', async () => {
    const payload = '<img src=x onerror=alert(1)>'
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: null,
        blocks: [
          { type: 'TEXT', text: payload },
          { type: 'IMAGE', mediaUrl: 'https://example.com/a.png', caption: payload },
        ],
        buttons: [{ type: 'callback', label: payload, targetStepId: null }],
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const node = wrapper.find('[data-node-id="s1"]')
    const html = node.html()
    // The injected payload must NOT materialize as a REAL <img onerror> element — only the legitimate
    // scheme-validated thumbnail is a real <img>, and it carries the data-test marker (no onerror). An
    // un-escaped `<img` followed by onerror would be an executable sink; the escaped `&lt;img …&gt;` text is safe.
    expect(/<img(?![^>]*data-test="funnel-canvas-preview-thumb")[^>]*onerror/i.test(html)).toBe(false)
    // The payload is present only in ESCAPED form (entity-encoded), proving it was rendered as text via {{ }}.
    expect(html).toContain('&lt;img')
    // Exactly one real <img> (the validated thumbnail), no extra injected element.
    expect(node.findAll('img')).toHaveLength(1)
    expect(node.find('img').attributes('data-test')).toBe('funnel-canvas-preview-thumb')
  })

  it('a media block with a javascript:/data: url does NOT become an <img src> (icon fallback)', async () => {
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: null,
        blocks: [{ type: 'IMAGE', mediaUrl: 'javascript:alert(1)' }],
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const node = wrapper.find('[data-node-id="s1"]')
    expect(node.find('[data-test="funnel-canvas-preview-thumb"]').exists()).toBe(false)
    expect(node.find('[data-test="funnel-canvas-preview-media-fallback"]').exists()).toBe(true)
    expect(node.html()).not.toContain('javascript:alert(1)')
  })

  // ── card-preview: edge arrows ─────────────────────────────────────────────────────────────────────────

  it('drawn edges carry a markerEnd arrow', async () => {
    // The canvas sets a default-edge-options markerEnd (ArrowClosed) on VueFlow; the rendered edge path
    // carries a marker-end attribute referencing the arrow marker.
    const steps: FunnelStep[] = [messageStep({ id: 's1', next: 's2' }), messageStep({ id: 's2' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const html = wrapper.html()
    // Vue Flow always renders a `marker-end` attribute, but WITHOUT our default-edge-options it is the empty
    // `url('#')` and no <marker> defs / arrowclosed marker are registered. With the ArrowClosed wiring the edge
    // marker-end resolves to a real arrow marker (id contains "arrowclosed") — so assert the populated arrow,
    // not the always-present empty attribute (which would make this test trivially pass).
    expect(/marker-end="url\('#'\)"/i.test(html)).toBe(false)
    expect(/arrowclosed/i.test(html)).toBe(true)
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
