// @vitest-environment nuxt
import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../../helpers/settle'
import FunnelTriggersPanel from '../../../components/funnels/FunnelTriggersPanel.vue'
import type { FunnelStep, FunnelTrigger } from '../../../types/funnel'

// FunnelTriggersPanel manages the funnel's LIST of triggers (Phase 8 — 17-funnel-multi-entry). on_start is
// the read-only main entry (always step 1); the panel adds/removes `event` triggers and lets the author
// pick each one's entry step over the funnel's OWN steps (passed via the `steps` prop). The whole array is
// emitted back via `update:triggers` (full-replace, mirrors the backend PATCH contract).
//
// The panel mounts FunnelTriggerSettings per row, which lazily fetches tags/fields for tag_added/
// custom_field_set — kept off the wire here by mocking useApi and exercising `event`/on_start only.
mockNuxtImport('useApi', () => () => () => Promise.resolve([]))
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))

function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}
function maybe(sel: string): Element | null {
  return document.querySelector(sel)
}

const STEPS: FunnelStep[] = [
  { stepType: 'MESSAGE', id: 's1', blocks: [{ type: 'TEXT', text: 'Hi' }] },
  { stepType: 'DELAY', id: 's2', delayValue: 5, delayUnit: 'MIN' },
  { stepType: 'MESSAGE' }, // no id (unsaved) — must be skipped in the entry picker
]

function onStart(): FunnelTrigger {
  return { triggerType: 'on_start', triggerValue: '', keywords: null, entryStepId: null }
}
function event(value: string, entryStepId: string | null = 's1'): FunnelTrigger {
  return { triggerType: 'event', triggerValue: value, keywords: null, entryStepId }
}

async function mountPanel(triggers: FunnelTrigger[], steps: FunnelStep[] = STEPS) {
  const wrapper = await mountSuspended(FunnelTriggersPanel, {
    props: { triggers, steps },
    attachTo: document.body,
  })
  await settle()
  return wrapper
}

// The latest emitted array (the panel emits the WHOLE replacement array on every mutation).
function lastTriggers(wrapper: Awaited<ReturnType<typeof mountPanel>>): FunnelTrigger[] {
  const ev = wrapper.emitted('update:triggers')
  if (!ev || ev.length === 0) throw new Error('no update:triggers emitted')
  return ev.at(-1)![0] as FunnelTrigger[]
}

describe('FunnelTriggersPanel', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('adds an event trigger', async () => {
    const wrapper = await mountPanel([onStart()])
    await $('[data-test="funnel-trigger-add"]').trigger('click')
    await settle()

    const next = lastTriggers(wrapper)
    // on_start is preserved; a fresh empty `event` trigger is appended.
    expect(next).toHaveLength(2)
    expect(next[0].triggerType).toBe('on_start')
    expect(next[1].triggerType).toBe('event')
    expect(next[1].triggerValue ?? '').toBe('')
    // The add path defaults entryStepId to the FIRST step's id (event requires a non-null entry —
    // Decision 14). If this regressed to null, the funnel would 422 on save.
    expect(next[1].entryStepId).toBe('s1')
  })

  it('removes a trigger', async () => {
    const wrapper = await mountPanel([onStart(), event('purchase')])
    // Two-click confirm delete (no silent drop).
    await $('[data-test="funnel-trigger-delete-1"]').trigger('click')
    await settle()
    await $('[data-test="funnel-trigger-delete-confirm-1"]').trigger('click')
    await settle()

    const next = lastTriggers(wrapper)
    expect(next).toHaveLength(1)
    expect(next[0].triggerType).toBe('on_start')
  })

  it('entry-step picker lists the funnel steps', async () => {
    await mountPanel([onStart(), event('purchase')])
    // Open the event row's entry-step combobox.
    await $('[data-test="funnel-trigger-entry-1-input"]').trigger('focus')
    await settle()

    // Sentinel "from start" + one option per step WITH an id (s1, s2); the id-less step is skipped.
    expect(maybe('[data-test="funnel-trigger-entry-1-option-__START__"]')).not.toBeNull()
    expect(maybe('[data-test="funnel-trigger-entry-1-option-s1"]')).not.toBeNull()
    expect(maybe('[data-test="funnel-trigger-entry-1-option-s2"]')).not.toBeNull()
    // Labels are "{N}. {step type}".
    expect($('[data-test="funnel-trigger-entry-1-option-s1"]').text()).toContain('1.')
    expect($('[data-test="funnel-trigger-entry-1-option-s2"]').text()).toContain('2.')
  })

  it('picking a step projects entryStepId', async () => {
    const wrapper = await mountPanel([onStart(), event('purchase', 's1')])
    await $('[data-test="funnel-trigger-entry-1-input"]').trigger('focus')
    await settle()
    await $('[data-test="funnel-trigger-entry-1-option-s2"]').trigger('mousedown')
    await settle()

    const next = lastTriggers(wrapper)
    expect(next[1].entryStepId).toBe('s2')
  })

  it('picking the "from start" sentinel maps the entry to step 1 id (not null)', async () => {
    // The reverse direction of the sentinel mapping: choosing "from start" must emit the FIRST step's id,
    // NOT null — event mid-entry requires a non-null entry step (Decision 14). Start the row on s2 so the
    // selection genuinely changes.
    const wrapper = await mountPanel([onStart(), event('purchase', 's2')])
    await $('[data-test="funnel-trigger-entry-1-input"]').trigger('focus')
    await settle()
    await $('[data-test="funnel-trigger-entry-1-option-__START__"]').trigger('mousedown')
    await settle()

    const next = lastTriggers(wrapper)
    expect(next[1].entryStepId).toBe('s1')
  })

  it('null/__START__ entry maps to step 1', async () => {
    // An event trigger whose entryStepId is the first step's id shows the sentinel "from start" selected
    // (mid-entry is event-only and the backend requires a non-null entry step, so "from start" === step 1's
    // id, not null).
    await mountPanel([onStart(), event('purchase', 's1')])
    const input = $('[data-test="funnel-trigger-entry-1-input"]').element as HTMLInputElement
    // The closed combobox shows the sentinel label (NOT the raw step label "1. <type>"). Capture the closed
    // value, then open the listbox to read the sentinel option's label and compare (locale-agnostic).
    const closedValue = input.value
    expect(closedValue).not.toContain('1. ')
    await $('[data-test="funnel-trigger-entry-1-input"]').trigger('focus')
    await settle()
    const sentinelLabel = $('[data-test="funnel-trigger-entry-1-option-__START__"]').text().trim()
    expect(closedValue).toBe(sentinelLabel)
  })

  it('flags a duplicate event name', async () => {
    // Two event rows with the same triggerValue → the duplicate row shows an advisory error.
    await mountPanel([onStart(), event('purchase'), event('purchase')])
    await settle()
    expect(maybe('[data-test="funnel-trigger-duplicate-2"]')).not.toBeNull()
    // The first occurrence is NOT flagged.
    expect(maybe('[data-test="funnel-trigger-duplicate-1"]')).toBeNull()
  })

  it('normalizes whitespace when comparing duplicates and ignores empty names', async () => {
    // The guard trims before comparing ('purchase' === ' purchase '), and a blank value is never a
    // duplicate (an unnamed draft row must not flag another unnamed draft row).
    await mountPanel([onStart(), event('purchase'), event(' purchase '), event(''), event('')])
    await settle()
    expect(maybe('[data-test="funnel-trigger-duplicate-2"]')).not.toBeNull() // trimmed match
    expect(maybe('[data-test="funnel-trigger-duplicate-3"]')).toBeNull() // empty — not flagged
    expect(maybe('[data-test="funnel-trigger-duplicate-4"]')).toBeNull() // empty — not flagged
  })

  it('shows the on_start main entry and an empty state for no event triggers', async () => {
    await mountPanel([onStart()])
    expect(maybe('[data-test="funnel-trigger-on-start"]')).not.toBeNull()
    expect(maybe('[data-test="funnel-triggers-empty"]')).not.toBeNull()
  })
})
