import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'
import FunnelCanvas from '../../../components/funnels/FunnelCanvas.client.vue'
import { ConnectionMode } from '@vue-flow/core'
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

  // ── connect-precision: robust connection config (Strict mode + card target id + radius + validity guard) ──

  it('exposes a strict connection config (small radius) so an edge end cannot snap to a foreign output dot', async () => {
    // Root cause of the snap bug: VueFlow had no connection config → default Loose mode + large radius let the
    // edge end land on a neighbouring node's source dot. The fix wires Strict mode + a small radius. These are
    // exposed for assertion (live pointer-drag is user-verified).
    const steps: FunnelStep[] = [messageStep({ id: 's1', next: null }), messageStep({ id: 's2' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const cfg = (wrapper.vm as unknown as { connectionConfig: { mode: string; radius: number } }).connectionConfig
    // Strict forbids ending on a source handle → kills the cross-node output snap. Assert against the
    // ConnectionMode enum (not a 'strict' literal) so the config can't drift from the template binding.
    expect(cfg.mode).toBe(ConnectionMode.Strict)
    // Small radius so the end is not pulled toward distant dots (the whole-card target provides the drop area).
    expect(cfg.radius).toBeLessThanOrEqual(12)
    expect(cfg.radius).toBeGreaterThan(0)
  })

  it('isValidConnection rejects source-as-target / missing target and accepts a real destination', async () => {
    // Defense-in-depth guard: a connection must have a target that is NOT the source node.
    const steps: FunnelStep[] = [messageStep({ id: 's1', next: null }), messageStep({ id: 's2' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const isValid = (wrapper.vm as unknown as { isValidConnection: (c: Connection) => boolean }).isValidConnection
    // Valid: a real destination node, different from the source.
    expect(isValid({ source: 's1', target: 's2', sourceHandle: 'next', targetHandle: 'in' })).toBe(true)
    // Invalid: self-node (would land back on the same card).
    expect(isValid({ source: 's1', target: 's1', sourceHandle: 'next', targetHandle: 'in' })).toBe(false)
    // Invalid: no target at all.
    expect(isValid({ source: 's1', target: null, sourceHandle: 'next', targetHandle: null } as unknown as Connection)).toBe(false)
  })

  it('the whole-card target handle carries the stable "in" id (kills the no-id cross-node competition)', async () => {
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const target = wrapper.find('[data-node-id="s1"]').find('[data-test="funnel-canvas-handle-target"]')
    expect(target.exists()).toBe(true)
    expect(target.attributes('data-handle-id')).toBe('in')
  })

  it('handleConnect still resolves the destination by node id even when targetHandle is the new "in" id', async () => {
    // The card target now has id "in" → conn.targetHandle is "in" (was null). handleConnect resolves by
    // conn.target (node id), so it must still write s1.next = 's2'. Load-bearing: a regression to targetHandle
    // parsing would break this.
    const steps: FunnelStep[] = [messageStep({ id: 's1', next: null }), messageStep({ id: 's2' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const conn: Connection = { source: 's1', target: 's2', sourceHandle: 'next', targetHandle: 'in' }
    ;(wrapper.vm as unknown as { handleConnect: (c: Connection) => void }).handleConnect(conn)
    await settle()

    const emitted = wrapper.emitted('update:steps')
    expect(emitted).toBeTruthy()
    expect((emitted![0][0] as FunnelStep[]).find((s) => s.id === 's1')?.next).toBe('s2')
  })

  it('an entry connection with targetHandle "in" still resolves to triggers', async () => {
    // Same resolution check for the entry source (start → step), with the new card target id.
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: null }]
    const wrapper = await mountWith({ steps, triggers })

    const conn: Connection = { source: 'start', target: 's1', sourceHandle: 'entry', targetHandle: 'in' }
    ;(wrapper.vm as unknown as { handleConnect: (c: Connection) => void }).handleConnect(conn)
    await settle()

    const emitted = wrapper.emitted('update:triggers')
    expect(emitted).toBeTruthy()
    expect((emitted![0][0] as FunnelTrigger[])[0].entryStepId).toBe('s1')
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

  it('renders the media caption for an http(s) media block', async () => {
    // previewCaption surfaces the media block's caption (escaped via {{ }}) below the thumbnail. Positive
    // assertion: an http(s) IMAGE with a caption renders both the thumbnail and the caption text.
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: null,
        blocks: [{ type: 'IMAGE', mediaUrl: 'https://example.com/a.png', caption: 'Hello caption' }],
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const node = wrapper.find('[data-node-id="s1"]')
    const caption = node.find('[data-test="funnel-canvas-preview-caption"]')
    expect(caption.exists()).toBe(true)
    expect(caption.text()).toBe('Hello caption')
  })

  it('renders an ALBUM thumbnail + caption from items[0] (http(s))', async () => {
    // previewThumbUrl/previewCaption ALBUM path (Decision 5): the thumbnail comes from items[0].mediaUrl and
    // the caption from items[0].caption. An http(s) items[0] renders as an <img :src> + its caption text.
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: null,
        blocks: [
          {
            type: 'ALBUM',
            items: [
              { mediaUrl: 'https://example.com/album0.png', caption: 'Album caption' },
              { mediaUrl: 'https://example.com/album1.png' },
            ],
          },
        ],
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const node = wrapper.find('[data-node-id="s1"]')
    const img = node.find('[data-test="funnel-canvas-preview-thumb"]')
    expect(img.exists()).toBe(true)
    // The thumbnail is the FIRST album item (not the second), proving the items[0] path.
    expect(img.attributes('src')).toBe('https://example.com/album0.png')
    const caption = node.find('[data-test="funnel-canvas-preview-caption"]')
    expect(caption.exists()).toBe(true)
    expect(caption.text()).toBe('Album caption')
  })

  it('falls back to the media-type label for an ALBUM whose items[0] is non-http(s)', async () => {
    // ALBUM items[0].mediaUrl is an opaque Telegram file_id (no http(s) scheme) → the scheme guard rejects it,
    // so the album falls back to the icon/label, no <img :src>.
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: null,
        blocks: [
          {
            type: 'ALBUM',
            items: [
              { mediaUrl: 'tg-file-id-opaque', caption: 'cap' },
              { mediaUrl: 'https://example.com/album1.png' },
            ],
          },
        ],
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const node = wrapper.find('[data-node-id="s1"]')
    expect(node.find('[data-test="funnel-canvas-preview-thumb"]').exists()).toBe(false)
    expect(node.find('[data-test="funnel-canvas-preview-media-fallback"]').exists()).toBe(true)
    expect(node.html()).not.toContain('tg-file-id-opaque')
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

  it('a media block with a javascript: url does NOT become an <img src> (icon fallback)', async () => {
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

  it('a media block with a data: url does NOT become an <img src> (icon fallback)', async () => {
    // data: is a non-http(s) scheme → the scheme guard rejects it (anti-XSS, no inline-payload <img>). It must
    // fall back to the media-type label exactly like javascript:, and the data: URL must NOT reach the DOM.
    const dataUrl = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQAY3Y2wAAAAAElFTkSuQmCC'
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: null,
        blocks: [{ type: 'IMAGE', mediaUrl: dataUrl }],
      }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const node = wrapper.find('[data-node-id="s1"]')
    expect(node.find('[data-test="funnel-canvas-preview-thumb"]').exists()).toBe(false)
    expect(node.find('[data-test="funnel-canvas-preview-media-fallback"]').exists()).toBe(true)
    expect(node.html()).not.toContain('data:image/png')
  })

  // ── keyboard-preview-fix: in-card SET_KEYBOARD / CLEAR_KEYBOARD preview ────────────────────────────────

  it('renders the in-card SET_KEYBOARD preview: the text + both button labels', async () => {
    const steps: FunnelStep[] = [
      {
        stepType: 'SET_KEYBOARD',
        id: 's1',
        next: null,
        keyboardText: 'Choose an option',
        keyboardRows: [{ buttons: [{ text: 'Yes' }, { text: 'No' }] }],
      },
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const node = wrapper.find('[data-node-id="s1"]')
    expect(node.find('[data-test="funnel-canvas-node-keyboard-preview"]').exists()).toBe(true)
    // The message text is shown.
    expect(node.find('[data-test="funnel-canvas-preview-keyboard-text"]').text()).toBe('Choose an option')
    // Both button labels are shown as chips.
    const keys = node.findAll('[data-test="funnel-canvas-preview-keyboard-key"]')
    expect(keys).toHaveLength(2)
    const labels = keys.map((k) => k.text())
    expect(labels).toContain('Yes')
    expect(labels).toContain('No')
  })

  it('renders the in-card CLEAR_KEYBOARD indicator (localized, not a raw key)', async () => {
    const steps: FunnelStep[] = [
      { stepType: 'CLEAR_KEYBOARD', id: 's1', next: null, keyboardText: 'Bye' },
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const node = wrapper.find('[data-node-id="s1"]')
    const cleared = node.find('[data-test="funnel-canvas-preview-keyboard-cleared"]')
    expect(cleared.exists()).toBe(true)
    // Default locale uk — resolved indicator, never the raw key.
    expect(cleared.text().trim().length).toBeGreaterThan(0)
    expect(cleared.text()).not.toContain('funnels.canvas')
    expect(cleared.text()).toBe('Клавіатуру прибрано')
    // No button-label chips for CLEAR_KEYBOARD.
    expect(node.find('[data-test="funnel-canvas-preview-keyboard-key"]').exists()).toBe(false)
  })

  it('escapes a SET_KEYBOARD text / button label containing HTML (never an <img> sink)', async () => {
    const payload = '<img src=x onerror=alert(1)>'
    const steps: FunnelStep[] = [
      {
        stepType: 'SET_KEYBOARD',
        id: 's1',
        next: null,
        keyboardText: payload,
        keyboardRows: [{ buttons: [{ text: payload }] }],
      },
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    const node = wrapper.find('[data-node-id="s1"]')
    const html = node.html()
    // No executable <img onerror> sink — the payload is present only entity-encoded (rendered via {{ }}).
    expect(/<img[^>]*onerror/i.test(html)).toBe(false)
    expect(html).toContain('&lt;img')
    // A SET_KEYBOARD node has no validated thumbnail → no real <img> at all.
    expect(node.findAll('img')).toHaveLength(0)
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

  // ── node-delete-ui: discoverable delete affordance (side-panel button + keydown) ──────────────────────

  it('side-panel Delete on a selected step node → confirm → emits cleaned steps + triggers (inbound edges nulled)', async () => {
    // s2 is selected; s1.next + s1 callback button + s3.timeout + the on_start entry all point at s2.
    // Side-panel Delete → confirm must emit update:steps WITHOUT s2 AND with every referencing field cleared,
    // plus update:triggers (the entry edge to s2 nulled) so the page persists the cleanup.
    const steps: FunnelStep[] = [
      messageStep({
        id: 's1',
        next: 's2',
        buttons: [{ type: 'callback', label: 'B', targetStepId: 's2' }],
      }),
      messageStep({ id: 's2' }),
      messageStep({ id: 's3', timeoutValue: 5, timeoutUnit: 'MIN', timeoutTargetStepId: 's2' }),
    ]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's2' }]
    const wrapper = await mountWith({ steps, triggers })

    // Select s2, then click the side-panel Delete button (the discoverable affordance — no exposed call).
    ;(wrapper.vm as unknown as { selectNode: (n: { id: string; data: Record<string, unknown> }) => void }).selectNode({
      id: 's2',
      data: { kind: 'step', stepIndex: 1 },
    })
    await settle()
    const delBtn = wrapper.find('[data-test="funnel-canvas-side-panel-delete"]')
    expect(delBtn.exists()).toBe(true)
    await delBtn.trigger('click')
    await settle()

    // The EXISTING warning surfaces the exact disconnect count (4: next + button + timeout + entry).
    const warning = wrapper.find('[data-test="funnel-canvas-delete-warning"]')
    expect(warning.exists()).toBe(true)
    expect(warning.attributes('data-disconnect-count')).toBe('4')
    expect(warning.text()).toContain('4')

    // Confirm via the EXISTING confirm button.
    await wrapper.find('[data-test="funnel-canvas-delete-confirm"]').trigger('click')
    await settle()

    const steplog = wrapper.emitted('update:steps')
    expect(steplog).toBeTruthy()
    const updated = steplog!.at(-1)![0] as FunnelStep[]
    // s2 is gone.
    expect(updated.some((s) => s.id === 's2')).toBe(false)
    // Inbound step edges cleaned.
    expect(updated.find((s) => s.id === 's1')?.next).toBeNull()
    expect(updated.find((s) => s.id === 's1')?.buttons?.[0]?.targetStepId).toBeNull()
    expect(updated.find((s) => s.id === 's3')?.timeoutTargetStepId).toBeNull()
    // The inbound entry edge was nulled → triggers emitted for the page to persist.
    const trigLog = wrapper.emitted('update:triggers')
    expect(trigLog).toBeTruthy()
    expect((trigLog!.at(-1)![0] as FunnelTrigger[])[0].entryStepId).toBeNull()
  })

  it('side-panel Delete on a note node → confirm → removes it from notes[]', async () => {
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const notes: FunnelNote[] = [{ id: null, text: 'keep', canvasPosition: null }, { id: null, text: 'drop', canvasPosition: null }]
    const wrapper = await mountWith({ steps, triggers, notes })

    // Select the second note node (note:1) and delete it via the side-panel button.
    ;(wrapper.vm as unknown as { selectNode: (n: { id: string; data: Record<string, unknown> }) => void }).selectNode({
      id: 'note:1',
      data: { kind: 'note', noteIndex: 1 },
    })
    await settle()
    await wrapper.find('[data-test="funnel-canvas-side-panel-delete"]').trigger('click')
    await settle()
    await wrapper.find('[data-test="funnel-canvas-delete-confirm"]').trigger('click')
    await settle()

    const emitted = wrapper.emitted('update:notes')
    expect(emitted).toBeTruthy()
    const updated = emitted!.at(-1)![0] as FunnelNote[]
    expect(updated).toHaveLength(1)
    expect(updated[0].text).toBe('keep')
  })

  it('side-panel Delete on a TRIGGER node → confirm → removes that trigger from triggers[]', async () => {
    // An event trigger node (trigger:1) is selected; side-panel Delete → confirm must emit update:triggers with
    // exactly that trigger removed, the others untouched (filter by triggerIndex). Value-based: asserts the
    // surviving trigger set, not just an emit. Load-bearing: a mis-indexed delete drops the wrong trigger.
    const steps: FunnelStep[] = [messageStep({ id: 's1' }), messageStep({ id: 's2' })]
    const triggers: FunnelTrigger[] = [
      { triggerType: 'on_start', entryStepId: 's1' },
      { triggerType: 'event', triggerValue: 'evtA', entryStepId: 's2' },
      { triggerType: 'event', triggerValue: 'evtB', entryStepId: null },
    ]
    const wrapper = await mountWith({ steps, triggers })

    // Select the second trigger node (trigger:1 = the evtA event trigger) and delete via the side-panel button.
    ;(wrapper.vm as unknown as { selectNode: (n: { id: string; data: Record<string, unknown> }) => void }).selectNode({
      id: 'trigger:1',
      data: { kind: 'trigger', triggerIndex: 1 },
    })
    await settle()
    await wrapper.find('[data-test="funnel-canvas-side-panel-delete"]').trigger('click')
    await settle()
    await wrapper.find('[data-test="funnel-canvas-delete-confirm"]').trigger('click')
    await settle()

    const emitted = wrapper.emitted('update:triggers')
    expect(emitted).toBeTruthy()
    const updated = emitted!.at(-1)![0] as FunnelTrigger[]
    // The evtA event trigger is gone; on_start + evtB survive (order preserved).
    expect(updated).toHaveLength(2)
    expect(updated.some((tr) => tr.triggerType === 'event' && tr.triggerValue === 'evtA')).toBe(false)
    expect(updated.some((tr) => tr.triggerType === 'on_start')).toBe(true)
    expect(updated.some((tr) => tr.triggerType === 'event' && tr.triggerValue === 'evtB')).toBe(true)
  })

  it('the START (on_start) node has NO side-panel Delete button (no-delete-start)', async () => {
    // no-delete-start: the start node must have no delete affordance. Selecting it opens the side panel (the
    // trigger settings editor), but the side-panel Delete button is gated on kind !== 'start' → absent.
    // step/trigger/note still render the button (covered by the tests above).
    const steps: FunnelStep[] = [messageStep({ id: 's1' }), messageStep({ id: 's2' })]
    const triggers: FunnelTrigger[] = [
      { triggerType: 'on_start', entryStepId: 's1' },
      { triggerType: 'event', triggerValue: 'evt', entryStepId: 's2' },
    ]
    const wrapper = await mountWith({ steps, triggers })

    // The start node carries id 'start' / kind 'start' (triggerIndex omitted — start lifecycle is special-cased).
    ;(wrapper.vm as unknown as { selectNode: (n: { id: string; data: Record<string, unknown> }) => void }).selectNode({
      id: 'start',
      data: { kind: 'start' },
    })
    await settle()

    // The side panel is open (the trigger settings editor mounts) but exposes no delete button for the start node.
    expect(wrapper.find('[data-test="funnel-canvas-side-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-canvas-side-panel-delete"]').exists()).toBe(false)
    // No warning was armed either — there is no delete path from the panel for the start node.
    expect(wrapper.find('[data-test="funnel-canvas-delete-warning"]').exists()).toBe(false)
  })

  it('Delete key on a selected node triggers the delete flow', async () => {
    const steps: FunnelStep[] = [messageStep({ id: 's1', next: 's2' }), messageStep({ id: 's2' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    ;(wrapper.vm as unknown as { selectNode: (n: { id: string; data: Record<string, unknown> }) => void }).selectNode({
      id: 's2',
      data: { kind: 'step', stepIndex: 1 },
    })
    await settle()

    // Press Delete with focus NOT in a text field → opens the warning (the same flow as the button).
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    await settle()
    expect(wrapper.find('[data-test="funnel-canvas-delete-warning"]').exists()).toBe(true)
  })

  it('Backspace key on a selected node triggers the delete flow', async () => {
    // Mirror of the Delete-key test: Backspace is the second accepted delete key (onKeydown accepts both).
    // Load-bearing: if Backspace were dropped from the key guard this opens nothing and the assertion fails.
    const steps: FunnelStep[] = [messageStep({ id: 's1', next: 's2' }), messageStep({ id: 's2' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    ;(wrapper.vm as unknown as { selectNode: (n: { id: string; data: Record<string, unknown> }) => void }).selectNode({
      id: 's2',
      data: { kind: 'step', stepIndex: 1 },
    })
    await settle()

    // Press Backspace with focus NOT in a text field → opens the SAME warning as Delete / the button.
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }))
    await settle()
    expect(wrapper.find('[data-test="funnel-canvas-delete-warning"]').exists()).toBe(true)
  })

  it('Delete/Backspace on a selected START node do NOT trigger the delete flow (no-delete-start)', async () => {
    // no-delete-start: the start node has no delete path at all — neither the side-panel button (gated) nor the
    // keyboard. The keydown handler skips when the selected node kind is 'start', so Delete/Backspace arm
    // nothing. Load-bearing: dropping the kind guard would surface the warning here.
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    ;(wrapper.vm as unknown as { selectNode: (n: { id: string; data: Record<string, unknown> }) => void }).selectNode({
      id: 'start',
      data: { kind: 'start' },
    })
    await settle()

    // Neither key (focus NOT in a text field) arms a deletion for the start node.
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    await settle()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }))
    await settle()
    expect(wrapper.find('[data-test="funnel-canvas-delete-warning"]').exists()).toBe(false)
  })

  it('Delete/Backspace do NOT fire while focus is in a [contenteditable] element', async () => {
    // Complements the <input> guard test: isEditableTarget also suppresses delete when focus is inside a
    // contenteditable region (a rich-text author is editing). Load-bearing: dropping the contenteditable
    // branch from the guard would arm a node deletion mid-edit and this would surface the warning.
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    ;(wrapper.vm as unknown as { selectNode: (n: { id: string; data: Record<string, unknown> }) => void }).selectNode({
      id: 's1',
      data: { kind: 'step', stepIndex: 0 },
    })
    await settle()

    // A real contenteditable element with focus inside it. jsdom doesn't auto-set isContentEditable from the
    // attribute, so the guard's `closest('[contenteditable="true"]')` branch is the one under test here.
    const editable = document.createElement('div')
    editable.setAttribute('contenteditable', 'true')
    document.body.appendChild(editable)
    editable.focus()

    editable.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete', bubbles: true }))
    await settle()
    editable.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true }))
    await settle()

    // Neither key armed a deletion — the author is editing text inside the contenteditable region.
    expect(wrapper.find('[data-test="funnel-canvas-delete-warning"]').exists()).toBe(false)
    editable.remove()
  })

  it('Delete key does NOT fire while focus is in a text input', async () => {
    const steps: FunnelStep[] = [messageStep({ id: 's1' })]
    const triggers: FunnelTrigger[] = [{ triggerType: 'on_start', entryStepId: 's1' }]
    const wrapper = await mountWith({ steps, triggers })

    ;(wrapper.vm as unknown as { selectNode: (n: { id: string; data: Record<string, unknown> }) => void }).selectNode({
      id: 's1',
      data: { kind: 'step', stepIndex: 0 },
    })
    await settle()

    // Focus a real input, then press Delete: the guard must suppress the delete (author is editing text).
    const input = document.createElement('input')
    document.body.appendChild(input)
    input.focus()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete', bubbles: true }))
    await settle()

    expect(wrapper.find('[data-test="funnel-canvas-delete-warning"]').exists()).toBe(false)
    input.remove()
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
