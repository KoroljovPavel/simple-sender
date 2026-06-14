// Pure model<->graph mapping layer for the funnel canvas (Phase 2 — 18-funnel-canvas, Task 4).
//
// This module is the single place the edge-field semantics live (tech-spec Data Models table,
// Decisions 3/7/9/11). It is deliberately framework-light: it imports ONLY the model types from
// `~/types/funnel` and `@dagrejs/dagre` for layout. It does NOT import `@vue-flow/core` runtime or
// touch the DOM — Task 5's surface component owns the Vue Flow instance and feeds these nodes/edges
// into it. Vue Flow `Node`/`Edge` shapes are mirrored as local interfaces (kept structurally
// compatible) so this layer carries no runtime dependency on the renderer.
//
// Every function is deterministic and side-effect free over its inputs (mutating reverse-mapping
// helpers operate on, and return, an explicitly cloned model — never the caller's object), so each is
// individually unit-testable.

import dagre from '@dagrejs/dagre'
import type {
  Button,
  FunnelResponse,
  FunnelStep,
  FunnelTrigger,
} from '~/types/funnel'

// ---------------------------------------------------------------------------
// Graph types (structurally compatible with Vue Flow's Node/Edge — see module note)
// ---------------------------------------------------------------------------

// Which model field an edge writes back to. The reverse mapper switches on this so exactly one field
// is touched per edge (Decision 9 — mis-wired handle must fail a test).
export type EdgeFieldKind = 'next' | 'button' | 'timeout' | 'entry'

// What kind of model object a node represents.
export type CanvasNodeKind = 'step' | 'trigger' | 'start' | 'note'

export interface CanvasNodeData {
  kind: CanvasNodeKind
  // For 'step': index into FunnelResponse.steps (the array IS the order — Decision 6). null otherwise.
  stepIndex: number | null
  // For 'trigger'/'start': index into FunnelResponse.triggers. null otherwise.
  triggerIndex: number | null
  // For 'note': index into FunnelResponse.notes. null otherwise.
  noteIndex: number | null
  // Cross-funnel SUBSCRIBE_TO_FUNNEL exit badge (Decision 11) — surfaced on the node, NOT as an edge.
  // Present only when the step is a SUBSCRIBE_TO_FUNNEL with a targetFunnelId.
  exitBadge?: { targetFunnelId: string; targetEntryStepId: string | null } | null
}

export interface CanvasNode {
  id: string
  type: CanvasNodeKind
  position: { x: number; y: number }
  data: CanvasNodeData
}

// Discriminator carried on every edge so the reverse mapper writes exactly one model field.
export interface CanvasEdgeData {
  fieldKind: EdgeFieldKind
  // Id of the step / trigger that OWNS the source field. For 'button'/'next'/'timeout' it is the
  // owning step's id; for 'entry' it is the trigger's entryStepId-bearing trigger (keyed by index).
  sourceStepId: string | null
  // Index into FunnelResponse.triggers for 'entry' edges (a trigger has no stable id field). null otherwise.
  triggerIndex: number | null
  // Index among the step's CALLBACK buttons for 'button' edges (a Button has no id — Decision: key on
  // its index among callback buttons, consistent forward+reverse). null otherwise.
  buttonIndex: number | null
}

export interface CanvasEdge {
  id: string
  source: string
  target: string
  data: CanvasEdgeData
}

// A broken edge: its target id does not resolve to a node, OR an on_start trigger lost its entry.
export interface BrokenEdge {
  // For a resolvable-source edge: the edge id. For an on_start trigger with null entry: a synthetic id.
  edgeId: string
  fieldKind: EdgeFieldKind
  sourceStepId: string | null
  triggerIndex: number | null
  buttonIndex: number | null
  // The id the edge tried to reach, or null for the on_start-entry-deleted case.
  targetStepId: string | null
  reason: 'missing_target' | 'on_start_entry_null'
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const NODE_WIDTH = 220
const NODE_HEIGHT = 96
const GRID_GAP_X = 280
const GRID_GAP_Y = 160
const GRID_COLUMNS = 4

// Node id namespaces. Step nodes use the server-minted step id verbatim (so edges can point straight
// at them); the others are prefixed because they have no model id (or share the id space).
const TRIGGER_PREFIX = 'trigger:'
const START_ID = 'start'
const NOTE_PREFIX = 'note:'

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function triggerNodeId(index: number): string {
  return `${TRIGGER_PREFIX}${index}`
}

function noteNodeId(index: number): string {
  return `${NOTE_PREFIX}${index}`
}

// Callback buttons in declaration order. URL buttons carry no edge (they have no targetStepId), so
// they are excluded — buttonIndex is the position WITHIN this filtered list, stable forward+reverse.
function callbackButtons(step: FunnelStep): Button[] {
  return (step.buttons ?? []).filter((b) => b.type === 'callback')
}

function isFiniteNumber(n: unknown): n is number {
  return typeof n === 'number' && Number.isFinite(n)
}

function hasStoredPosition(pos: { x: number; y: number } | null | undefined): pos is { x: number; y: number } {
  return !!pos && isFiniteNumber(pos.x) && isFiniteNumber(pos.y)
}

// ---------------------------------------------------------------------------
// Forward mapping: model -> nodes + edges
// ---------------------------------------------------------------------------

// Build the node set. Order: optional start node, trigger nodes, step nodes, note nodes.
// Notes are read from `funnel.notes[]` only — they never come from `steps[]` (Decision 7).
export function buildNodes(funnel: FunnelResponse): CanvasNode[] {
  const nodes: CanvasNode[] = []
  const triggers = funnel.triggers ?? []
  const steps = funnel.steps ?? []
  const notes = funnel.notes ?? []

  // on_start start node is optional — render it only when an on_start trigger exists (Decision 11).
  const onStartIndex = triggers.findIndex((t) => t.triggerType === 'on_start')
  if (onStartIndex !== -1) {
    const trig = triggers[onStartIndex]
    nodes.push({
      id: START_ID,
      type: 'start',
      position: storedOrZero(trig.canvasPosition),
      data: { kind: 'start', stepIndex: null, triggerIndex: onStartIndex, noteIndex: null },
    })
  }

  // Event/keyword/tag/field triggers each get a trigger node (NOT the on_start one — that is the start node).
  triggers.forEach((trig, index) => {
    if (trig.triggerType === 'on_start') return
    nodes.push({
      id: triggerNodeId(index),
      type: 'trigger',
      position: storedOrZero(trig.canvasPosition),
      data: { kind: 'trigger', stepIndex: null, triggerIndex: index, noteIndex: null },
    })
  })

  // One node per SAVED step. An unsaved step (id == null) cannot be an edge target, but it still gets a
  // node so the user can see it; edges to it are refused by the edge builder.
  steps.forEach((step, index) => {
    const isSubscribe = step.stepType === 'SUBSCRIBE_TO_FUNNEL' && !!step.targetFunnelId
    nodes.push({
      id: step.id ?? `unsaved-step:${index}`,
      type: 'step',
      position: storedOrZero(step.canvasPosition),
      data: {
        kind: 'step',
        stepIndex: index,
        triggerIndex: null,
        noteIndex: null,
        // Cross-funnel SUBSCRIBE is an exit badge on the node, not an edge (Decision 11).
        exitBadge: isSubscribe
          ? { targetFunnelId: step.targetFunnelId!, targetEntryStepId: step.targetEntryStepId ?? null }
          : null,
      },
    })
  })

  notes.forEach((_note, index) => {
    nodes.push({
      id: noteNodeId(index),
      type: 'note',
      position: storedOrZero(notes[index].canvasPosition),
      data: { kind: 'note', stepIndex: null, triggerIndex: null, noteIndex: index },
    })
  })

  return nodes
}

function storedOrZero(pos: { x: number; y: number } | null | undefined): { x: number; y: number } {
  return hasStoredPosition(pos) ? { x: pos.x, y: pos.y } : { x: 0, y: 0 }
}

// Build the edge set from the edge-bearing model fields. Each edge is tagged with the field it owns so
// the reverse mapper writes exactly that field. Edges are produced ONLY to saved nodes (target id
// resolves to a step with a non-null id present in the graph); a field pointing at a missing/unsaved id
// is left out of `edges` and surfaced via detectBrokenEdges instead.
export function buildEdges(funnel: FunnelResponse): CanvasEdge[] {
  const edges: CanvasEdge[] = []
  const steps = funnel.steps ?? []
  const triggers = funnel.triggers ?? []
  const savedStepIds = new Set(steps.map((s) => s.id).filter((id): id is string => !!id))

  const push = (
    sourceNodeId: string,
    targetId: string,
    data: CanvasEdgeData,
    edgeKey: string,
  ): void => {
    // Saved-node-only constraint: refuse an edge whose target id does not resolve to a saved step.
    if (!targetId || !savedStepIds.has(targetId)) return
    edges.push({ id: edgeKey, source: sourceNodeId, target: targetId, data })
  }

  steps.forEach((step) => {
    if (!step.id) return // unsaved step has no source node id edges can hang off
    const sid = step.id

    // default `next` edge
    if (step.next != null && step.next !== '') {
      push(
        sid,
        step.next,
        { fieldKind: 'next', sourceStepId: sid, triggerIndex: null, buttonIndex: null },
        `e:next:${sid}`,
      )
    }

    // one edge per CALLBACK button with a target (URL buttons carry no edge)
    callbackButtons(step).forEach((btn, btnIndex) => {
      if (btn.targetStepId != null && btn.targetStepId !== '') {
        push(
          sid,
          btn.targetStepId,
          { fieldKind: 'button', sourceStepId: sid, triggerIndex: null, buttonIndex: btnIndex },
          `e:button:${sid}:${btnIndex}`,
        )
      }
    })

    // timeout edge
    if (step.timeoutTargetStepId != null && step.timeoutTargetStepId !== '') {
      push(
        sid,
        step.timeoutTargetStepId,
        { fieldKind: 'timeout', sourceStepId: sid, triggerIndex: null, buttonIndex: null },
        `e:timeout:${sid}`,
      )
    }
  })

  // trigger / start entry edges — source is the start node (on_start) or the trigger node.
  triggers.forEach((trig, index) => {
    if (trig.entryStepId != null && trig.entryStepId !== '') {
      const sourceNodeId = trig.triggerType === 'on_start' ? START_ID : triggerNodeId(index)
      push(
        sourceNodeId,
        trig.entryStepId,
        { fieldKind: 'entry', sourceStepId: null, triggerIndex: index, buttonIndex: null },
        `e:entry:${index}`,
      )
    }
  })

  return edges
}

// ---------------------------------------------------------------------------
// Layout (dagre auto-layout + grid fallback)
// ---------------------------------------------------------------------------

// Apply layout to a node set. Nodes whose model carries a stored canvasPosition keep those coords
// as-is; nodes without one are laid out by dagre over the FULL node/edge forest (never assume a single
// root — multiple trigger entryStepIds = multiple roots; orphans are preserved). In the mixed case
// only the unpositioned nodes receive dagre coords. If dagre throws on a degenerate/malformed graph,
// fall back to a deterministic grid so the canvas never gets a blank/NaN layout (Risks table).
export function layoutNodes(funnel: FunnelResponse, nodes: CanvasNode[], edges: CanvasEdge[]): CanvasNode[] {
  const stored = new Map<string, { x: number; y: number }>()
  for (const node of nodes) {
    const pos = storedPositionFor(funnel, node)
    if (hasStoredPosition(pos)) stored.set(node.id, { x: pos.x, y: pos.y })
  }

  // Nodes that already have a stored position are not moved.
  const toLayout = nodes.filter((n) => !stored.has(n.id))
  if (toLayout.length === 0) {
    return nodes.map((n) => ({ ...n, position: stored.get(n.id)! }))
  }

  let computed: Map<string, { x: number; y: number }>
  try {
    computed = dagreLayout(nodes, edges)
    // Defensive: dagre can return NaN for degenerate inputs without throwing.
    for (const id of toLayout.map((n) => n.id)) {
      const p = computed.get(id)
      if (!p || !isFiniteNumber(p.x) || !isFiniteNumber(p.y)) throw new Error('dagre produced non-finite coords')
    }
  } catch {
    computed = gridLayout(toLayout)
  }

  return nodes.map((n) => {
    if (stored.has(n.id)) return { ...n, position: stored.get(n.id)! }
    return { ...n, position: computed.get(n.id) ?? { x: 0, y: 0 } }
  })
}

function storedPositionFor(funnel: FunnelResponse, node: CanvasNode): { x: number; y: number } | null | undefined {
  const { kind, stepIndex, triggerIndex, noteIndex } = node.data
  if (kind === 'step' && stepIndex != null) return funnel.steps?.[stepIndex]?.canvasPosition
  if ((kind === 'trigger' || kind === 'start') && triggerIndex != null)
    return funnel.triggers?.[triggerIndex]?.canvasPosition
  if (kind === 'note' && noteIndex != null) return funnel.notes?.[noteIndex]?.canvasPosition
  return null
}

// dagre over the full forest. Returns top-left coords (dagre gives center coords, so subtract half
// the node box). Throws are propagated to the caller's try/catch -> grid fallback.
function dagreLayout(nodes: CanvasNode[], edges: CanvasEdge[]): Map<string, { x: number; y: number }> {
  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: 'TB', nodesep: GRID_GAP_X - NODE_WIDTH, ranksep: GRID_GAP_Y - NODE_HEIGHT })
  g.setDefaultEdgeLabel(() => ({}))

  for (const node of nodes) {
    g.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT })
  }
  for (const edge of edges) {
    // Only wire edges whose endpoints are both in the node set (dagre throws on dangling endpoints).
    if (g.hasNode(edge.source) && g.hasNode(edge.target)) g.setEdge(edge.source, edge.target)
  }

  dagre.layout(g)

  const out = new Map<string, { x: number; y: number }>()
  for (const node of nodes) {
    const laid = g.node(node.id) as { x: number; y: number } | undefined
    if (laid) out.set(node.id, { x: laid.x - NODE_WIDTH / 2, y: laid.y - NODE_HEIGHT / 2 })
  }
  return out
}

// Deterministic grid placement — the safety net when dagre fails. Always finite coords.
function gridLayout(nodes: CanvasNode[]): Map<string, { x: number; y: number }> {
  const out = new Map<string, { x: number; y: number }>()
  nodes.forEach((node, i) => {
    const col = i % GRID_COLUMNS
    const row = Math.floor(i / GRID_COLUMNS)
    out.set(node.id, { x: col * GRID_GAP_X, y: row * GRID_GAP_Y })
  })
  return out
}

// ---------------------------------------------------------------------------
// Broken-edge detection
// ---------------------------------------------------------------------------

// Flag any edge-bearing field whose target id does not resolve to a saved step, and any on_start
// trigger whose entryStepId is null while the start node is present (entry edge was deleted ->
// would 422 funnel_broken_edge on activate).
export function detectBrokenEdges(funnel: FunnelResponse): BrokenEdge[] {
  const broken: BrokenEdge[] = []
  const steps = funnel.steps ?? []
  const triggers = funnel.triggers ?? []
  const savedStepIds = new Set(steps.map((s) => s.id).filter((id): id is string => !!id))

  const flagMissing = (
    edgeId: string,
    fieldKind: EdgeFieldKind,
    sourceStepId: string | null,
    triggerIndex: number | null,
    buttonIndex: number | null,
    targetId: string | null | undefined,
  ): void => {
    // Only `null`/`undefined` means "no edge". A non-null value that does NOT resolve to a saved
    // step — INCLUDING "" — is a broken edge (canvas highlights it; it 422s on activate).
    if (targetId == null) return
    if (savedStepIds.has(targetId)) return
    broken.push({ edgeId, fieldKind, sourceStepId, triggerIndex, buttonIndex, targetStepId: targetId, reason: 'missing_target' })
  }

  steps.forEach((step) => {
    if (!step.id) return
    const sid = step.id
    flagMissing(`e:next:${sid}`, 'next', sid, null, null, step.next)
    callbackButtons(step).forEach((btn, btnIndex) => {
      flagMissing(`e:button:${sid}:${btnIndex}`, 'button', sid, null, btnIndex, btn.targetStepId)
    })
    flagMissing(`e:timeout:${sid}`, 'timeout', sid, null, null, step.timeoutTargetStepId)
  })

  const onStartPresent = triggers.some((t) => t.triggerType === 'on_start')
  triggers.forEach((trig, index) => {
    flagMissing(`e:entry:${index}`, 'entry', null, index, null, trig.entryStepId)
    // on_start with a null entry while its start node is present == deleted entry edge -> broken.
    // ("" is handled by flagMissing above as a missing_target broken edge, not this null case.)
    if (trig.triggerType === 'on_start' && onStartPresent && trig.entryStepId == null) {
      broken.push({
        edgeId: `e:entry:${index}`,
        fieldKind: 'entry',
        sourceStepId: null,
        triggerIndex: index,
        buttonIndex: null,
        targetStepId: null,
        reason: 'on_start_entry_null',
      })
    }
  })

  return broken
}

// ---------------------------------------------------------------------------
// Reverse mapping: graph mutation -> model
// ---------------------------------------------------------------------------

// Deep-ish clone of the funnel so reverse mutations never touch the caller's object. Steps/triggers/
// notes and their nested edge-bearing fields are copied; we don't need a structural clone of opaque
// per-type content (blocks etc.) because the reverse mapper only writes edge fields.
function cloneFunnel(funnel: FunnelResponse): FunnelResponse {
  return {
    ...funnel,
    triggers: (funnel.triggers ?? []).map((t) => ({ ...t, keywords: t.keywords ? [...t.keywords] : t.keywords })),
    steps: (funnel.steps ?? []).map((s) => ({
      ...s,
      buttons: s.buttons ? s.buttons.map((b) => ({ ...b })) : s.buttons,
    })),
    notes: funnel.notes ? funnel.notes.map((n) => ({ ...n })) : funnel.notes,
  }
}

// A mutation descriptor identifying which model field a graph edge owns.
export interface EdgeRef {
  fieldKind: EdgeFieldKind
  sourceStepId: string | null
  triggerIndex: number | null
  buttonIndex: number | null
}

// Resolve the EdgeRef to the model object and write `value` (a target id, or null for "no edge") onto
// exactly the owned field — touching no sibling. Returns a NEW funnel. Internal helper of `connectEdge`
// (the only edge-write the app uses); not exported (MINOR-1 — the standalone export was dead/parallel).
function applyEdgeValue(funnel: FunnelResponse, ref: EdgeRef, value: string | null): FunnelResponse {
  const next = cloneFunnel(funnel)

  switch (ref.fieldKind) {
    case 'next': {
      const step = findStepById(next, ref.sourceStepId)
      if (step) step.next = value
      break
    }
    case 'timeout': {
      const step = findStepById(next, ref.sourceStepId)
      if (step) step.timeoutTargetStepId = value
      break
    }
    case 'button': {
      const step = findStepById(next, ref.sourceStepId)
      if (step && ref.buttonIndex != null) {
        const cbs = callbackButtons(step)
        const target = cbs[ref.buttonIndex]
        if (target) target.targetStepId = value
      }
      break
    }
    case 'entry': {
      if (ref.triggerIndex != null) {
        const trig = (next.triggers ?? [])[ref.triggerIndex]
        if (trig) trig.entryStepId = value
      }
      break
    }
  }

  return next
}

function findStepById(funnel: FunnelResponse, id: string | null): FunnelStep | undefined {
  if (!id) return undefined
  return (funnel.steps ?? []).find((s) => s.id === id)
}

// Connect an edge: write the target node's saved id onto the owned field. Refuses (throws) when the
// target is an unsaved node (id == null) — the saved-node-only edge constraint (code-research §2).
export function connectEdge(funnel: FunnelResponse, ref: EdgeRef, targetStepId: string | null): FunnelResponse {
  if (targetStepId == null || targetStepId === '') {
    throw new Error('connectEdge: target step id is null/empty — cannot wire an edge to an unsaved node')
  }
  const savedStepIds = new Set((funnel.steps ?? []).map((s) => s.id).filter((id): id is string => !!id))
  if (!savedStepIds.has(targetStepId)) {
    throw new Error(`connectEdge: target step id ${targetStepId} is not a saved node`)
  }
  return applyEdgeValue(funnel, ref, targetStepId)
}

// Result of deleting a node: the new funnel + the exact count of inbound edges that were nulled.
export interface DeleteNodeResult {
  funnel: FunnelResponse
  disconnectedCount: number
}

// Delete a step node by its id: remove it from steps[] and auto-clean EVERY inbound edge that pointed
// at it (default `next`, each callback button `targetStepId`, `timeoutTargetStepId`, trigger
// `entryStepId`) by setting that field to null. Returns the exact disconnect count. Surviving
// edges/fields are left unchanged.
export function deleteStepNode(funnel: FunnelResponse, stepId: string): DeleteNodeResult {
  const next = cloneFunnel(funnel)
  let disconnected = 0

  // Auto-clean every inbound edge that pointed at the deleted step.
  for (const step of next.steps ?? []) {
    if (step.id === stepId) continue // the node being deleted — its own outbound fields go away with it
    if (step.next === stepId) {
      step.next = null
      disconnected++
    }
    if (step.timeoutTargetStepId === stepId) {
      step.timeoutTargetStepId = null
      disconnected++
    }
    for (const btn of step.buttons ?? []) {
      if (btn.type === 'callback' && btn.targetStepId === stepId) {
        btn.targetStepId = null
        disconnected++
      }
    }
  }
  for (const trig of next.triggers ?? []) {
    if (trig.entryStepId === stepId) {
      trig.entryStepId = null
      disconnected++
    }
  }

  // Remove the step itself.
  next.steps = (next.steps ?? []).filter((s) => s.id !== stepId)

  return { funnel: next, disconnectedCount: disconnected }
}
