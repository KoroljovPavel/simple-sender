import { describe, it, expect, vi, afterEach } from 'vitest'
import dagre from '@dagrejs/dagre'
import type { FunnelResponse, FunnelStep, FunnelTrigger, Button } from '../../types/funnel'
import {
  buildNodes,
  buildEdges,
  layoutNodes,
  detectBrokenEdges,
  connectEdge,
  deleteStepNode,
  type EdgeRef,
  type CanvasEdge,
} from '../../composables/useFunnelCanvas'

// ---------------------------------------------------------------------------
// Builders — minimal model objects with all edge-bearing fields explicit.
// ---------------------------------------------------------------------------

function step(id: string | null, over: Partial<FunnelStep> = {}): FunnelStep {
  return {
    stepType: 'MESSAGE',
    id,
    next: null,
    buttons: null,
    timeoutTargetStepId: null,
    canvasPosition: null,
    ...over,
  }
}

function callbackBtn(label: string, targetStepId: string | null): Button {
  return { type: 'callback', label, targetStepId }
}

function urlBtn(label: string, url: string): Button {
  return { type: 'url', label, url }
}

function trigger(triggerType: string, over: Partial<FunnelTrigger> = {}): FunnelTrigger {
  return { triggerType, entryStepId: null, canvasPosition: null, ...over }
}

function funnel(over: Partial<FunnelResponse> = {}): FunnelResponse {
  return {
    id: 'f1',
    projectId: 'p1',
    name: 'Test funnel',
    description: null,
    status: 'draft',
    triggers: [],
    allowReEnter: false,
    steps: [],
    notes: null,
    deepLink: null,
    createdAt: '',
    updatedAt: '',
    ...over,
  }
}

function findEdge(edges: CanvasEdge[], predicate: (e: CanvasEdge) => boolean): CanvasEdge {
  const e = edges.find(predicate)
  if (!e) throw new Error('edge not found')
  return e
}

// ---------------------------------------------------------------------------
// Forward mapping
// ---------------------------------------------------------------------------

describe('useFunnelCanvas — forward mapping', () => {
  it('maps model to nodes and edges with correct field+id', () => {
    const f = funnel({
      triggers: [
        trigger('on_start', { entryStepId: 's1' }),
        trigger('event', { triggerValue: 'evt', entryStepId: 's2' }),
      ],
      steps: [
        step('s1', {
          next: 's2',
          buttons: [callbackBtn('Go A', 's2'), callbackBtn('Go B', 's3'), urlBtn('Link', 'https://x')],
          timeoutTargetStepId: 's3',
        }),
        step('s2'),
        step('s3'),
      ],
      notes: [{ id: 'n1', text: 'hello', canvasPosition: null }],
    })

    const nodes = buildNodes(f)
    const edges = buildEdges(f)

    // Node set: 1 start + 1 trigger + 3 steps + 1 note.
    expect(nodes.filter((n) => n.type === 'start')).toHaveLength(1)
    expect(nodes.filter((n) => n.type === 'trigger')).toHaveLength(1)
    expect(nodes.filter((n) => n.type === 'step').map((n) => n.id).sort()).toEqual(['s1', 's2', 's3'])
    expect(nodes.filter((n) => n.type === 'note')).toHaveLength(1)

    // default `next` edge: s1 -> s2.
    const nextEdge = findEdge(edges, (e) => e.data.fieldKind === 'next')
    expect(nextEdge.source).toBe('s1')
    expect(nextEdge.target).toBe('s2')
    expect(nextEdge.data.sourceStepId).toBe('s1')

    // button edges: the RIGHT button index gets the right target. URL button produces no edge.
    const buttonEdges = edges.filter((e) => e.data.fieldKind === 'button')
    expect(buttonEdges).toHaveLength(2)
    const btn0 = findEdge(buttonEdges, (e) => e.data.buttonIndex === 0)
    expect(btn0.target).toBe('s2')
    const btn1 = findEdge(buttonEdges, (e) => e.data.buttonIndex === 1)
    expect(btn1.target).toBe('s3')

    // timeout edge: s1 -> s3.
    const timeoutEdge = findEdge(edges, (e) => e.data.fieldKind === 'timeout')
    expect(timeoutEdge.source).toBe('s1')
    expect(timeoutEdge.target).toBe('s3')

    // entry edges: on_start -> s1, event trigger -> s2.
    const entryEdges = edges.filter((e) => e.data.fieldKind === 'entry')
    expect(entryEdges).toHaveLength(2)
    const startEntry = findEdge(entryEdges, (e) => e.source === 'start')
    expect(startEntry.target).toBe('s1')
    // event trigger is triggers[1] -> its node id is the exact `trigger:1` namespace value.
    const evtEntry = findEdge(entryEdges, (e) => e.source !== 'start')
    expect(evtEntry.source).toBe('trigger:1')
    expect(evtEntry.target).toBe('s2')
  })

  it('emits NO timeout edge for a non-MESSAGE step (symmetry with the MESSAGE-only timeout handle)', () => {
    // Only a MESSAGE step has a timeout output handle (hasTimeoutHandle requires stepType === 'MESSAGE').
    // A non-MESSAGE step carrying timeoutTargetStepId would yield a timeout edge with no source handle to
    // hang off (an orphan). buildEdges must guard the timeout branch with stepType === 'MESSAGE' so the two
    // layers stay symmetric. (Server validation blocks this today; the layers must agree regardless.)
    const f = funnel({
      steps: [
        step('s1', { stepType: 'DELAY', timeoutTargetStepId: 's2' }),
        step('s2'),
      ],
    })
    const edges = buildEdges(f)
    expect(edges.some((e) => e.data.fieldKind === 'timeout')).toBe(false)
  })

  it('emits a timeout edge for a MESSAGE step with timeoutTargetStepId set', () => {
    // The positive counterpart of the non-MESSAGE guard: a MESSAGE step with a timeout target still wires up.
    const f = funnel({
      steps: [
        step('s1', { stepType: 'MESSAGE', timeoutTargetStepId: 's2' }),
        step('s2'),
      ],
    })
    const edges = buildEdges(f)
    const timeoutEdge = findEdge(edges, (e) => e.data.fieldKind === 'timeout')
    expect(timeoutEdge.source).toBe('s1')
    expect(timeoutEdge.target).toBe('s2')
  })
})

// ---------------------------------------------------------------------------
// Reverse mapping — connect
// ---------------------------------------------------------------------------

describe('useFunnelCanvas — reverse connect', () => {
  it('connecting a default edge writes next, siblings unchanged', () => {
    const f = funnel({
      steps: [
        step('s1', { next: null, timeoutTargetStepId: 't0', buttons: [callbackBtn('b', 'b0')] }),
        step('s2'),
      ],
    })
    const ref: EdgeRef = { fieldKind: 'next', sourceStepId: 's1', triggerIndex: null, buttonIndex: null }
    const out = connectEdge(f, ref, 's2')

    const s1 = out.steps[0]
    expect(s1.next).toBe('s2')
    // siblings unchanged
    expect(s1.timeoutTargetStepId).toBe('t0')
    expect(s1.buttons![0].targetStepId).toBe('b0')
    // original not mutated
    expect(f.steps[0].next).toBeNull()
  })

  it('connecting a button edge writes that button targetStepId only', () => {
    const f = funnel({
      steps: [
        step('s1', {
          next: 'n0',
          timeoutTargetStepId: 't0',
          buttons: [callbackBtn('A', null), callbackBtn('B', 'keep'), urlBtn('U', 'https://x')],
        }),
        step('s2'),
      ],
    })
    const ref: EdgeRef = { fieldKind: 'button', sourceStepId: 's1', triggerIndex: null, buttonIndex: 0 }
    const out = connectEdge(f, ref, 's2')

    const s1 = out.steps[0]
    expect(s1.buttons![0].targetStepId).toBe('s2')
    // other callback button unchanged
    expect(s1.buttons![1].targetStepId).toBe('keep')
    // next + timeout unchanged
    expect(s1.next).toBe('n0')
    expect(s1.timeoutTargetStepId).toBe('t0')
  })

  it('connecting a timeout edge writes timeoutTargetStepId only', () => {
    const f = funnel({
      steps: [step('s1', { next: 'n0', timeoutTargetStepId: null, buttons: [callbackBtn('b', 'b0')] }), step('s2')],
    })
    const ref: EdgeRef = { fieldKind: 'timeout', sourceStepId: 's1', triggerIndex: null, buttonIndex: null }
    const out = connectEdge(f, ref, 's2')

    const s1 = out.steps[0]
    expect(s1.timeoutTargetStepId).toBe('s2')
    expect(s1.next).toBe('n0')
    expect(s1.buttons![0].targetStepId).toBe('b0')
  })

  it('connecting a trigger/start entry edge writes entryStepId', () => {
    const f = funnel({
      triggers: [trigger('on_start', { entryStepId: null }), trigger('event', { triggerValue: 'e', entryStepId: 'keep' })],
      steps: [step('s1'), step('s2')],
    })
    const ref: EdgeRef = { fieldKind: 'entry', sourceStepId: null, triggerIndex: 0, buttonIndex: null }
    const out = connectEdge(f, ref, 's1')

    expect(out.triggers[0].entryStepId).toBe('s1')
    // sibling trigger unchanged
    expect(out.triggers[1].entryStepId).toBe('keep')
  })

  it('connectEdge rejects a non-null but non-saved/unknown target id', () => {
    const f = funnel({ steps: [step('s1', { next: null }), step('s2')] })
    const ref: EdgeRef = { fieldKind: 'next', sourceStepId: 's1', triggerIndex: null, buttonIndex: null }
    // 'ghost' is non-null but is not a saved node in the funnel -> refused.
    expect(() => connectEdge(f, ref, 'ghost')).toThrow()
    // and the empty-string id is refused too (never written as an edge).
    expect(() => connectEdge(f, ref, '')).toThrow()
    // original untouched.
    expect(f.steps[0].next).toBeNull()
  })

  it('add node/edge produces correct PATCH payload', () => {
    // Add a NEW (unsaved) step — id null — then add a saved step and wire to it.
    const f = funnel({
      steps: [step('s1', { next: null }), step('s2'), step(null /* freshly added, unsaved */)],
    })

    // Wiring to the unsaved node is refused (saved-node-only constraint).
    expect(() =>
      connectEdge(f, { fieldKind: 'next', sourceStepId: 's1', triggerIndex: null, buttonIndex: null }, null),
    ).toThrow()
    // No edge is produced to the unsaved node in the forward pass either.
    const edges = buildEdges(funnel({ steps: [step('s1', { next: 'no-such-or-unsaved' }), step(null)] }))
    expect(edges.filter((e) => e.data.fieldKind === 'next')).toHaveLength(0)

    // Wire s1.next -> s2 (saved): the reverse mapper writes the field on the cloned model.
    const wired = connectEdge(f, { fieldKind: 'next', sourceStepId: 's1', triggerIndex: null, buttonIndex: null }, 's2')
    expect(wired.steps).toHaveLength(3)
    expect(wired.steps[0].next).toBe('s2')
    // the unsaved node is preserved as a step with id null (it just can't be an edge target yet)
    expect(wired.steps[2].id).toBeNull()
  })
})

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

describe('useFunnelCanvas — layout', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('dagre lays out nodes when canvasPosition absent', () => {
    const f = funnel({
      triggers: [trigger('on_start', { entryStepId: 's1' })],
      steps: [step('s1', { next: 's2' }), step('s2')],
    })
    const nodes = buildNodes(f)
    const edges = buildEdges(f)
    const laid = layoutNodes(f, nodes, edges)

    for (const n of laid) {
      expect(Number.isFinite(n.position.x)).toBe(true)
      expect(Number.isFinite(n.position.y)).toBe(true)
    }
    // Two laid-out step nodes must not collapse onto the same point.
    const s1 = laid.find((n) => n.id === 's1')!
    const s2 = laid.find((n) => n.id === 's2')!
    expect(s1.position.x !== s2.position.x || s1.position.y !== s2.position.y).toBe(true)
  })

  it('stored canvasPosition used as-is', () => {
    const f = funnel({
      steps: [step('s1', { canvasPosition: { x: 123, y: 456 } }), step('s2', { canvasPosition: { x: 7, y: 8 } })],
    })
    const nodes = buildNodes(f)
    const edges = buildEdges(f)
    const laid = layoutNodes(f, nodes, edges)

    expect(laid.find((n) => n.id === 's1')!.position).toEqual({ x: 123, y: 456 })
    expect(laid.find((n) => n.id === 's2')!.position).toEqual({ x: 7, y: 8 })
  })

  it('mixed layout lays out only unpositioned nodes', () => {
    const f = funnel({
      steps: [
        step('s1', { next: 's2', canvasPosition: { x: 999, y: 111 } }),
        step('s2', { canvasPosition: null }),
      ],
    })
    const nodes = buildNodes(f)
    const edges = buildEdges(f)
    const laid = layoutNodes(f, nodes, edges)

    // positioned node kept exactly
    const s1 = laid.find((n) => n.id === 's1')!
    expect(s1.position).toEqual({ x: 999, y: 111 })
    // unpositioned node got finite dagre coords...
    const s2 = laid.find((n) => n.id === 's2')!
    expect(Number.isFinite(s2.position.x)).toBe(true)
    expect(Number.isFinite(s2.position.y)).toBe(true)
    // ...that were ACTUALLY computed: NOT equal to the stored coord (a silent dagre no-op → a finite
    // {0,0}/default would pass a bare isFinite check) AND NOT collapsed onto its positioned sibling.
    expect(s2.position).not.toEqual({ x: 999, y: 111 })
    expect(s2.position).not.toEqual(s1.position)
  })

  it('orphan forest (disconnected nodes) still yields finite coords', () => {
    // dagre handles disconnected nodes fine — this proves the orphan-forest layout path, not the
    // try/catch fallback (the real fallback is covered by the next test).
    const steps: FunnelStep[] = []
    for (let i = 0; i < 10; i++) steps.push(step(`s${i}`))
    const f = funnel({ steps })
    const nodes = buildNodes(f)
    const laid = layoutNodes(f, nodes, [])
    for (const n of laid) {
      expect(Number.isFinite(n.position.x)).toBe(true)
      expect(Number.isFinite(n.position.y)).toBe(true)
    }
  })

  it('dagre failure falls back to deterministic grid placement', () => {
    // Force the layout call to throw so the try/catch -> gridLayout branch is actually exercised.
    const spy = vi.spyOn(dagre, 'layout').mockImplementation(() => {
      throw new Error('forced dagre failure')
    })

    const steps: FunnelStep[] = []
    for (let i = 0; i < 5; i++) steps.push(step(`s${i}`))
    const f = funnel({ steps })
    const nodes = buildNodes(f)
    const laid = layoutNodes(f, nodes, [])

    expect(spy).toHaveBeenCalled()
    // All coords finite (no NaN leaked from the failed dagre run).
    for (const n of laid) {
      expect(Number.isFinite(n.position.x)).toBe(true)
      expect(Number.isFinite(n.position.y)).toBe(true)
    }
    // Grid layout is deterministic: GRID_COLUMNS=4, GRID_GAP_X=280, GRID_GAP_Y=160.
    // node 0 -> (0,0); node 4 -> col 0, row 1 -> (0,160).
    const s0 = laid.find((n) => n.id === 's0')!
    const s4 = laid.find((n) => n.id === 's4')!
    expect(s0.position).toEqual({ x: 0, y: 0 })
    expect(s4.position).toEqual({ x: 0, y: 160 })
  })
})

// ---------------------------------------------------------------------------
// Cycle / notes / delete / broken-edge / SUBSCRIBE
// ---------------------------------------------------------------------------

describe('useFunnelCanvas — cycle, notes, delete, broken, subscribe', () => {
  it('cycle maps to nodes+edges without infinite recursion', () => {
    // return-to-menu cycle: A.next -> B, B.button -> A. The forward mapper is a flat field pass (no graph
    // traversal), so a cycle produces a finite node/edge set and terminates.
    const f = funnel({
      steps: [
        step('A', { next: 'B' }),
        step('B', { buttons: [callbackBtn('back', 'A')] }),
      ],
    })
    const nodes = buildNodes(f)
    const edges = buildEdges(f)
    const laid = layoutNodes(f, nodes, edges)
    expect(laid.filter((n) => n.type === 'step')).toHaveLength(2)
    expect(edges.map((e) => e.data.fieldKind).sort()).toEqual(['button', 'next'])
    // Both cycle edges resolve to saved nodes (the cycle round-trips, not silently dropped).
    expect(edges.find((e) => e.data.fieldKind === 'next')!.target).toBe('B')
    expect(edges.find((e) => e.data.fieldKind === 'button')!.target).toBe('A')
  })

  it('delete node auto-cleans all inbound edges with exact count', () => {
    // s2 is the delete target. Inbound: s1.next, s3.button, s4.timeout, trigger.entry. = 4 disconnects.
    const f = funnel({
      triggers: [trigger('event', { triggerValue: 'e', entryStepId: 's2' })],
      steps: [
        step('s1', { next: 's2', timeoutTargetStepId: 'keepT' }),
        step('s2'),
        step('s3', { buttons: [callbackBtn('go', 's2'), callbackBtn('other', 'keepB')] }),
        step('s4', { timeoutTargetStepId: 's2', next: 'keepN' }),
        step('keepN'),
        step('keepT'),
        step('keepB'),
      ],
    })

    const { funnel: out, disconnectedCount } = deleteStepNode(f, 's2')

    expect(disconnectedCount).toBe(4)
    // step removed
    expect(out.steps.find((s) => s.id === 's2')).toBeUndefined()
    // inbound edges nulled
    expect(out.steps.find((s) => s.id === 's1')!.next).toBeNull()
    expect(out.steps.find((s) => s.id === 's3')!.buttons![0].targetStepId).toBeNull()
    expect(out.steps.find((s) => s.id === 's4')!.timeoutTargetStepId).toBeNull()
    expect(out.triggers[0].entryStepId).toBeNull()
    // surviving edges/fields unchanged
    expect(out.steps.find((s) => s.id === 's1')!.timeoutTargetStepId).toBe('keepT')
    expect(out.steps.find((s) => s.id === 's3')!.buttons![1].targetStepId).toBe('keepB')
    expect(out.steps.find((s) => s.id === 's4')!.next).toBe('keepN')
  })

  it('broken edge detection flags missing target', () => {
    const f = funnel({
      steps: [step('s1', { next: 'ghost', timeoutTargetStepId: 's1' }), step('s1b', { buttons: [callbackBtn('b', 'gone')] })],
    })
    // normalize ids — make s1b distinct
    f.steps[1].id = 's2'
    const broken = detectBrokenEdges(f)
    const reasons = broken.map((b) => `${b.fieldKind}:${b.targetStepId}`)
    expect(reasons).toContain('next:ghost')
    expect(reasons).toContain('button:gone')
    // s1.timeout -> s1 resolves (s1 exists) so NOT flagged
    expect(broken.find((b) => b.fieldKind === 'timeout')).toBeUndefined()
  })

  it('broken edge detection flags empty-string target (only null means no edge)', () => {
    // next === "" is a non-null value that resolves to no node -> broken (not silently dropped).
    const f = funnel({
      steps: [step('s1', { next: '' }), step('s2')],
    })
    const broken = detectBrokenEdges(f)
    const nextBroken = broken.find((b) => b.fieldKind === 'next' && b.sourceStepId === 's1')
    expect(nextBroken).toBeDefined()
    expect(nextBroken!.targetStepId).toBe('')
    expect(nextBroken!.reason).toBe('missing_target')
  })

  it('broken edge detection flags on_start entry null', () => {
    const f = funnel({
      triggers: [trigger('on_start', { entryStepId: null })],
      steps: [step('s1')],
    })
    const broken = detectBrokenEdges(f)
    const onStartBroken = broken.find((b) => b.reason === 'on_start_entry_null')
    expect(onStartBroken).toBeDefined()
    expect(onStartBroken!.fieldKind).toBe('entry')
    expect(onStartBroken!.triggerIndex).toBe(0)
  })

  it('notes isolated from steps', () => {
    const f = funnel({
      steps: [step('s1')],
      notes: [{ id: 'n1', text: 'note A', canvasPosition: { x: 1, y: 2 } }, { id: null, text: 'fresh', canvasPosition: null }],
    })

    // A note is NEVER a step node — the forward mapper reads notes from notes[] only (Decision 7).
    const nodes = buildNodes(f)
    const noteNodes = nodes.filter((n) => n.type === 'note')
    const stepNodes = nodes.filter((n) => n.type === 'step')
    expect(noteNodes).toHaveLength(2)
    expect(stepNodes).toHaveLength(1)
    // step nodes carry exactly the funnel's step ids — no note id leaked into the step node set.
    expect(stepNodes.map((n) => n.id)).toEqual(['s1'])
    // note nodes carry no step index (they index into notes[], not steps[]).
    expect(noteNodes.every((n) => n.data.stepIndex === null && n.data.noteIndex !== null)).toBe(true)
    // notes produce NO edges (a note has no graph fields).
    const edges = buildEdges(f)
    expect(edges).toHaveLength(0)
  })

  it('cross-funnel SUBSCRIBE renders as exit badge not edge', () => {
    const f = funnel({
      steps: [
        step('s1', { next: 's2' }),
        step('s2', {
          stepType: 'SUBSCRIBE_TO_FUNNEL',
          targetFunnelId: 'other-funnel',
          targetEntryStepId: 'other-step',
        }),
      ],
    })
    const nodes = buildNodes(f)
    const edges = buildEdges(f)

    const subNode = nodes.find((n) => n.id === 's2')!
    expect(subNode.data.exitBadge).toBeTruthy()
    expect(subNode.data.exitBadge!.targetFunnelId).toBe('other-funnel')
    expect(subNode.data.exitBadge!.targetEntryStepId).toBe('other-step')

    // no edge points at the cross-funnel target (it lives in ANOTHER funnel, not this node set)
    expect(edges.some((e) => e.target === 'other-step')).toBe(false)
    expect(edges.some((e) => e.target === 'other-funnel')).toBe(false)
    // the only edge is s1.next -> s2
    expect(edges).toHaveLength(1)
    expect(edges[0].data.fieldKind).toBe('next')
  })
})
