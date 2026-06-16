// @vitest-environment nuxt
import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'
import FunnelCanvasSidePanel from '../../../components/funnels/FunnelCanvasSidePanel.vue'
import type { FunnelStep } from '../../../types/funnel'

// Group A (Task 6 TDD Anchor). The side panel mounts the UNCHANGED FunnelStepForm with
// :hide-target-pickers="true" for step nodes, and FunnelTriggerSettings for trigger/start nodes. Because the
// panel mounts FunnelStepForm — which reads useFunnelsStore + useApi + the route — useApi + the route are
// stubbed here. The funnels store uses the REAL Pinia store (like FunnelStepForm.keyboard.spec.ts) so
// storeToRefs() works for the SUBSCRIBE_TO_FUNNEL path (a plain mock object breaks storeToRefs).
mockNuxtImport('useApi', () => () => () => Promise.resolve([]))
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))

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

// A SUBSCRIBE_TO_FUNNEL step with a concrete cross-funnel entry step id — the SUBSCRIBE entry picker only
// renders for this step type, so the picker-absence assertion is only meaningful here (a MESSAGE fixture
// would make it vacuously pass). targetEntryStepId must survive the hidden-picker round-trip on submit.
function subscribeStepWithEntry(): FunnelStep {
  return {
    stepType: 'SUBSCRIBE_TO_FUNNEL',
    id: 's1',
    targetFunnelId: 'f2',
    targetEntryStepId: 's9',
    endParentAfter: true,
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
  // attachTo: document.body keeps mounted forms in the DOM; clear between cases so an absence assertion in
  // one test can't read a leftover form from a prior test.
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('side panel mounts FunnelStepForm with hideTargetPickers hides button + timeout target SearchableSelects', async () => {
    // For a MESSAGE step node the panel mounts FunnelStepForm with hideTargetPickers → the button-target and
    // timeout-target SearchableSelects are absent. Each SearchableSelect renders data-test="${testPrefix}-select".
    const wrapper = await mountPanel({ kind: 'step', step: messageStepWithTargets() })

    // The step form itself IS mounted.
    expect(document.querySelector('[data-test="step-form"]')).not.toBeNull()
    // No button target picker (prefix step-menu-target-<i>).
    expect(document.querySelector('[data-test^="step-menu-target-"]')).toBeNull()
    // No timeout target picker.
    expect(document.querySelector('[data-test="step-menu-timeout-target-select"]')).toBeNull()

    wrapper.unmount()
  })

  it('hides the SUBSCRIBE entry picker and carries targetEntryStepId through on submit', async () => {
    // The SUBSCRIBE entry SearchableSelect only renders for SUBSCRIBE_TO_FUNNEL steps, so a MESSAGE fixture
    // would make this absence assertion vacuous. With a real SUBSCRIBE step + hideTargetPickers the picker
    // is absent AND the seeded targetEntryStepId flows through unchanged on submit (the edge owns it).
    const wrapper = await mountPanel({ kind: 'step', step: subscribeStepWithEntry() })
    await settle()

    // The step form is mounted, but the SUBSCRIBE entry picker is hidden.
    expect(document.querySelector('[data-test="step-form"]')).not.toBeNull()
    expect(document.querySelector('[data-test="step-subscribe-entry-select"]')).toBeNull()

    ;(document.querySelector('[data-test="step-form"]') as HTMLFormElement).requestSubmit()
    await settle()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const step = emitted![0][0] as FunnelStep
    // The cross-funnel entry id is preserved unchanged (not dropped, not zeroed).
    expect(step.targetEntryStepId).toBe('s9')
    // Sibling fields untouched.
    expect(step.stepType).toBe('SUBSCRIBE_TO_FUNNEL')
    expect(step.targetFunnelId).toBe('f2')

    wrapper.unmount()
  })

  it('renders a callback button row WITHOUT the target picker and WITHOUT the URL input (F1 regression)', async () => {
    // Round-1 F1 regression: the callback target picker hide was wrongly placed on the v-if/v-else BRANCH
    // selector, so a `callback` button under hideTargetPickers fell into the v-else and rendered the URL
    // input. The branch must key only on row.type; the hide nests on the inner SearchableSelect. A callback
    // button must therefore show its row (label input) but NEITHER the target picker NOR the URL input.
    const wrapper = await mountPanel({ kind: 'step', step: messageStepWithTargets() })
    await settle()

    // The callback button row is present (its label input renders).
    expect(document.querySelector('[data-test="step-menu-button-label-0"]')).not.toBeNull()
    // The target picker is hidden (edge owns it).
    expect(document.querySelector('[data-test^="step-menu-target-"]')).toBeNull()
    // CRITICAL: a callback button must NOT render the URL input — that is the F1 corruption.
    expect(document.querySelector('[data-test="step-menu-button-url-0"]')).toBeNull()

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

  // Decision B / MINOR-2 — minimal note-text editor for a NOTE node.
  it('mounts a bounded note-text editor for a note node and emits the edited text', async () => {
    const wrapper = await mountPanel({ kind: 'note', noteIndex: 0, noteText: 'before' })

    const textarea = document.querySelector('[data-test="funnel-canvas-note-textarea"]') as HTMLTextAreaElement | null
    expect(textarea).not.toBeNull()
    // Seeded from the note's text.
    expect(textarea!.value).toBe('before')
    // Bounded to the backend NOTE_TEXT_MAX (@Size) so the server never 422s on length.
    expect(textarea!.getAttribute('maxlength')).toBe('2000')
    // No step form / trigger form for a note node.
    expect(document.querySelector('[data-test="step-form"]')).toBeNull()
    expect(document.querySelector('[data-test="funnel-trigger"]')).toBeNull()

    // Editing emits update:note-text with the note index + new text (parent persists via update:notes).
    textarea!.value = 'after'
    textarea!.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()

    const emitted = wrapper.emitted('update:note-text')
    expect(emitted).toBeTruthy()
    expect(emitted!.at(-1)![0]).toEqual({ noteIndex: 0, text: 'after' })

    wrapper.unmount()
  })

  // node-delete-ui: the side panel is the discoverable delete affordance. Every node kind (step/trigger/
  // start/note) must render a Delete control that emits `delete` with the selected node id — the canvas
  // relays it into the EXISTING requestDelete → warning → confirmDelete flow.
  it('renders a Delete button that emits delete with the node id for a step node', async () => {
    const wrapper = await mountPanel({ kind: 'step', nodeId: 's1', step: messageStepWithTargets() })

    const del = document.querySelector('[data-test="funnel-canvas-side-panel-delete"]') as HTMLButtonElement | null
    expect(del).not.toBeNull()
    // Localized label (default locale uk) — not the raw key.
    expect(del!.textContent?.trim()).toBe('Видалити')

    del!.click()
    await settle()

    const emitted = wrapper.emitted('delete')
    expect(emitted).toBeTruthy()
    expect(emitted!.at(-1)![0]).toBe('s1')

    wrapper.unmount()
  })

  it('renders a Delete button for trigger, start and note nodes (all kinds covered)', async () => {
    for (const node of [
      { kind: 'trigger', nodeId: 'trigger:0', trigger: { triggerType: 'event', triggerValue: 'e', entryStepId: null } },
      { kind: 'start', nodeId: 'start', trigger: { triggerType: 'on_start', triggerValue: '', entryStepId: null } },
      { kind: 'note', nodeId: 'note:0', noteIndex: 0, noteText: 'hi' },
    ]) {
      const wrapper = await mountPanel(node)
      const del = document.querySelector('[data-test="funnel-canvas-side-panel-delete"]') as HTMLButtonElement | null
      expect(del, `delete button for ${node.kind}`).not.toBeNull()
      del!.click()
      await settle()
      expect(wrapper.emitted('delete')!.at(-1)![0]).toBe(node.nodeId)
      wrapper.unmount()
      document.body.innerHTML = ''
    }
  })

  it('does NOT render a Delete button when no node is selected', async () => {
    const wrapper = await mountPanel(null)
    expect(document.querySelector('[data-test="funnel-canvas-side-panel-delete"]')).toBeNull()
    wrapper.unmount()
  })

  it('renders an injection-payload note ESCAPED in the editor (no v-html)', async () => {
    // Stored-XSS guard (Decision 7): a note whose text is an injection payload must be held as inert DATA in
    // the <textarea> value (the browser never parses an attribute value as markup), never injected as live
    // markup. The node-PREVIEW escaping ({{ }} → innerHTML has no `<img`) is covered in funnel-editor.spec.ts.
    const payload = '<img src=x onerror="alert(1)">'
    const wrapper = await mountPanel({ kind: 'note', noteIndex: 0, noteText: payload })

    const textarea = document.querySelector('[data-test="funnel-canvas-note-textarea"]') as HTMLTextAreaElement
    // The payload is the textarea's value (inert data), not parsed markup.
    expect(textarea.value).toBe(payload)
    // No live <img> element was created anywhere in the editor (would catch an accidental v-html).
    const editor = document.querySelector('[data-test="funnel-canvas-note-editor"]') as HTMLElement
    expect(editor.querySelector('img')).toBeNull()
    expect(document.querySelectorAll('img')).toHaveLength(0)

    wrapper.unmount()
  })
})
