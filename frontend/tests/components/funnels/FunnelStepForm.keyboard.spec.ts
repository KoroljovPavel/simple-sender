// @vitest-environment nuxt
import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../../helpers/settle'
import FunnelStepForm from '../../../components/funnels/FunnelStepForm.vue'
import { useFunnelsStore } from '../../../stores/funnels'
import type { FunnelStep, FunnelSummaryResponse } from '../../../types/funnel'

// The two reply-keyboard step types (SET_KEYBOARD / CLEAR_KEYBOARD) — feature 16-persistent-keyboard.
// Both carry a mandatory text message (text + parse mode, ≤4096, HARD block — unlike the composer's soft
// warn). SET_KEYBOARD adds a rows sub-editor (1..10 rows × 1..4 buttons, button text non-blank ≤64, no
// duplicate trimmed button texts) plus «Постійна» (default ON) / «Сховати після натискання» (default OFF)
// checkboxes and NO resize_keyboard control. Client validation MIRRORS the backend Decision 6 rules
// byte-for-byte. The per-button keyword hint (non-blocking amber) warns when a button's lowercased text
// contains no keyword of any active keyword funnel in the funnels store (mirror of containsAnyKeyword).
// The shared form auto-imports useApi and reads the route projectId — stub both.
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
function val(sel: string): string {
  return ($(sel).element as HTMLInputElement | HTMLTextAreaElement).value
}
function checked(sel: string): boolean {
  return ($(sel).element as HTMLInputElement).checked
}

// Seed a funnel summary into the (real Pinia) store so the keyword-hint mirror has data. keywords arrive
// already lowercase-normalized (FunnelTriggerSettings.addKeyword), matching the production contract.
function funnelSummary(over: Partial<FunnelSummaryResponse>): FunnelSummaryResponse {
  return {
    id: 'f1',
    projectId: 'p1',
    name: 'Game',
    description: null,
    status: 'active',
    triggerType: 'keyword',
    triggerValue: null,
    keywords: ['бонус'],
    allowReEnter: false,
    stepCount: 1,
    createdAt: '',
    updatedAt: '',
    ...over,
  }
}

async function mountForm(initial: FunnelStep | null = null, storeFunnels: FunnelSummaryResponse[] = []) {
  const wrapper = await mountSuspended(FunnelStepForm, {
    props: { initial, siblingSteps: [], submitLabel: 'Save' },
    attachTo: document.body,
  })
  await settle()
  if (!initial) {
    await $('[data-test="step-type-select"]').setValue('SET_KEYBOARD')
    await settle()
  }
  // Seed the store AFTER the type switch: switching to SET_KEYBOARD fires ensureFunnelsLoaded() which
  // calls funnelsStore.fetch('all') and OVERWRITES funnels with the mocked useApi payload ([]). Seeding
  // after that settles wins, so the hint computed reads our funnels. (Production loads real data here.)
  if (storeFunnels.length) {
    useFunnelsStore().funnels = storeFunnels
    await settle()
  }
  return wrapper
}
async function selectType(type: string) {
  await $('[data-test="step-type-select"]').setValue(type)
  await settle()
}
async function submitForm() {
  await $('[data-test="step-form"]').trigger('submit')
  await settle()
}

describe('FunnelStepForm — reply keyboard (SET_KEYBOARD / CLEAR_KEYBOARD)', () => {
  beforeEach(() => {
    useFunnelsStore().funnels = []
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders the SET_KEYBOARD form block', async () => {
    await mountForm()
    expect(maybe('[data-test="step-keyboard-text"]')).not.toBeNull()
    expect(maybe('[data-test="step-keyboard-parsemode"]')).not.toBeNull()
    // Seeded with EXACTLY one row + one button (load-bearing for the cap/add/remove tests' arithmetic).
    expect(document.querySelectorAll('[data-test^="step-keyboard-row-"]')).toHaveLength(1)
    expect(maybe('[data-test="step-keyboard-button-0-0"]')).not.toBeNull()
    expect(maybe('[data-test="step-keyboard-button-0-1"]')).toBeNull()
    expect(maybe('[data-test="step-keyboard-button-1-0"]')).toBeNull()
    expect(maybe('[data-test="step-keyboard-persistent"]')).not.toBeNull()
    expect(maybe('[data-test="step-keyboard-onetime"]')).not.toBeNull()
  })

  it('renders the CLEAR_KEYBOARD form block', async () => {
    await mountForm()
    await selectType('CLEAR_KEYBOARD')
    expect(maybe('[data-test="step-keyboard-text"]')).not.toBeNull()
    expect(maybe('[data-test="step-keyboard-parsemode"]')).not.toBeNull()
    // No rows sub-editor, no checkboxes for CLEAR_KEYBOARD.
    expect(maybe('[data-test="step-keyboard-button-0-0"]')).toBeNull()
    expect(maybe('[data-test="step-keyboard-add-row"]')).toBeNull()
    expect(maybe('[data-test="step-keyboard-persistent"]')).toBeNull()
    expect(maybe('[data-test="step-keyboard-onetime"]')).toBeNull()
  })

  it('rows sub-editor adds and removes rows and buttons', async () => {
    await mountForm()
    // Seed: row 0 with button 0-0. Add a button to row 0.
    await $('[data-test="step-keyboard-add-button-0"]').trigger('click')
    await settle()
    expect(maybe('[data-test="step-keyboard-button-0-1"]')).not.toBeNull()
    // Add a new row.
    await $('[data-test="step-keyboard-add-row"]').trigger('click')
    await settle()
    expect(maybe('[data-test="step-keyboard-button-1-0"]')).not.toBeNull()
    // Remove the second button of row 0.
    await $('[data-test="step-keyboard-remove-button-0-1"]').trigger('click')
    await settle()
    expect(maybe('[data-test="step-keyboard-button-0-1"]')).toBeNull()
    // Remove the second row.
    await $('[data-test="step-keyboard-remove-row-1"]').trigger('click')
    await settle()
    expect(maybe('[data-test="step-keyboard-button-1-0"]')).toBeNull()
  })

  it('caps rows at 10 and buttons per row at 4', async () => {
    await mountForm()
    // Seed is 1 row → add 9 more to hit 10.
    for (let i = 1; i < 10; i++) {
      await $('[data-test="step-keyboard-add-row"]').trigger('click')
      await settle()
    }
    expect(maybe('[data-test="step-keyboard-button-9-0"]')).not.toBeNull()
    expect(($('[data-test="step-keyboard-add-row"]').element as HTMLButtonElement).disabled).toBe(true)
    // Row 0 seed is 1 button → add 3 more to hit 4.
    for (let i = 1; i < 4; i++) {
      await $('[data-test="step-keyboard-add-button-0"]').trigger('click')
      await settle()
    }
    expect(maybe('[data-test="step-keyboard-button-0-3"]')).not.toBeNull()
    expect(($('[data-test="step-keyboard-add-button-0"]').element as HTMLButtonElement).disabled).toBe(true)
  })

  it('blocks submit on blank keyboard text', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-keyboard-button-0-0"]').setValue('Menu')
    // Leave text blank (whitespace only).
    await $('[data-test="step-keyboard-text"]').setValue('   ')
    await submitForm()
    expect(maybe('[data-test="step-keyboard-text-error"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('blocks submit on text over 4096 (hard block, not a soft warn)', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-keyboard-button-0-0"]').setValue('Menu')
    await $('[data-test="step-keyboard-text"]').setValue('x'.repeat(4097))
    await submitForm()
    expect(maybe('[data-test="step-keyboard-text-error"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('blocks submit on a blank button text', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-keyboard-text"]').setValue('Pick one')
    // Leave button 0-0 blank.
    await submitForm()
    expect(maybe('[data-test="step-keyboard-button-error-0-0"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('blocks submit on a button text over 64', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-keyboard-text"]').setValue('Pick one')
    await $('[data-test="step-keyboard-button-0-0"]').setValue('y'.repeat(65))
    await submitForm()
    expect(maybe('[data-test="step-keyboard-button-error-0-0"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('blocks submit on duplicate button texts in one keyboard (trimmed)', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-keyboard-text"]').setValue('Pick one')
    await $('[data-test="step-keyboard-button-0-0"]').setValue('Go')
    // Second button in the same row, trimmed duplicate of the first.
    await $('[data-test="step-keyboard-add-button-0"]').trigger('click')
    await settle()
    await $('[data-test="step-keyboard-button-0-1"]').setValue('Go ')
    await submitForm()
    expect(maybe('[data-test="step-keyboard-duplicate-error"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('renders no resize_keyboard control (exactly two keyboard checkboxes)', async () => {
    await mountForm()
    expect(maybe('[data-test="step-keyboard-resize"]')).toBeNull()
    // resize_keyboard is hardcoded true server-side and never surfaced — only persistent + one-time exist.
    const checkboxes = [
      maybe('[data-test="step-keyboard-persistent"]'),
      maybe('[data-test="step-keyboard-onetime"]'),
    ].filter(Boolean)
    expect(checkboxes).toHaveLength(2)
    expect(document.querySelectorAll('[data-test="step-keyboard-rows"] input[type="checkbox"]')).toHaveLength(0)
  })

  it('blocks submit on blank CLEAR_KEYBOARD text', async () => {
    const wrapper = await mountForm()
    await selectType('CLEAR_KEYBOARD')
    await $('[data-test="step-keyboard-text"]').setValue('   ')
    await submitForm()
    expect(maybe('[data-test="step-keyboard-text-error"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('checkbox defaults — persistent ON, one-time OFF on a fresh SET_KEYBOARD step', async () => {
    await mountForm()
    expect(checked('[data-test="step-keyboard-persistent"]')).toBe(true)
    expect(checked('[data-test="step-keyboard-onetime"]')).toBe(false)
  })

  it('shows the keyword hint for a button matching no active keyword funnel', async () => {
    await mountForm(null, [funnelSummary({ keywords: ['бонус'] })])
    // Button text does not contain "бонус" → hint visible.
    await $('[data-test="step-keyboard-button-0-0"]').setValue('Профіль')
    await settle()
    expect(maybe('[data-test="step-keyboard-hint-0-0"]')).not.toBeNull()
  })

  it('hides the keyword hint when an active keyword funnel matches (contains, lowercase)', async () => {
    await mountForm(null, [funnelSummary({ keywords: ['бонус'] })])
    // Button text contains the keyword (case-insensitive) → hint absent.
    await $('[data-test="step-keyboard-button-0-0"]').setValue('Згенерувати Бонус')
    await settle()
    expect(maybe('[data-test="step-keyboard-hint-0-0"]')).toBeNull()
  })

  it('does not show the hint against an inactive or non-keyword funnel', async () => {
    await mountForm(null, [
      funnelSummary({ id: 'a', status: 'paused', keywords: ['бонус'] }),
      funnelSummary({ id: 'b', triggerType: 'on_start', keywords: null }),
    ])
    await $('[data-test="step-keyboard-button-0-0"]').setValue('бонус')
    await settle()
    // The only funnel carrying the keyword is paused → still no active match → hint visible.
    expect(maybe('[data-test="step-keyboard-hint-0-0"]')).not.toBeNull()
  })

  it('keyword hint never blocks submit', async () => {
    const wrapper = await mountForm(null, [funnelSummary({ keywords: ['бонус'] })])
    await $('[data-test="step-keyboard-text"]').setValue('Menu')
    await $('[data-test="step-keyboard-button-0-0"]').setValue('Профіль') // no match → hint shows
    await settle()
    expect(maybe('[data-test="step-keyboard-hint-0-0"]')).not.toBeNull()
    await submitForm()
    expect(wrapper.emitted('submit')).toBeTruthy()
  })

  it('emits a SET_KEYBOARD step preserving id and next', async () => {
    const wrapper = await mountForm({
      stepType: 'SET_KEYBOARD',
      id: 'k1',
      next: 'n1',
      keyboardText: 'Головне меню',
      keyboardParseMode: 'HTML',
      keyboardRows: [{ buttons: [{ text: 'Згенерувати бонус' }, { text: 'Профіль' }] }],
      isPersistent: false,
      oneTimeKeyboard: true,
    })
    // Edit-seeded values render.
    expect(val('[data-test="step-keyboard-text"]')).toBe('Головне меню')
    expect(val('[data-test="step-keyboard-button-0-0"]')).toBe('Згенерувати бонус')
    expect(val('[data-test="step-keyboard-button-0-1"]')).toBe('Профіль')
    // Explicit false/true seed (not the defaults).
    expect(checked('[data-test="step-keyboard-persistent"]')).toBe(false)
    expect(checked('[data-test="step-keyboard-onetime"]')).toBe(true)
    await submitForm()
    const step = wrapper.emitted('submit')![0][0] as FunnelStep
    expect(step.stepType).toBe('SET_KEYBOARD')
    expect(step.id).toBe('k1')
    expect(step.next).toBe('n1')
    expect(step.keyboardText).toBe('Головне меню')
    expect(step.keyboardParseMode).toBe('HTML')
    expect(step.keyboardRows).toEqual([
      { buttons: [{ text: 'Згенерувати бонус' }, { text: 'Профіль' }] },
    ])
    expect(step.isPersistent).toBe(false)
    expect(step.oneTimeKeyboard).toBe(true)
  })

  it('emits a CLEAR_KEYBOARD step preserving id and next, without keyboard-only fields', async () => {
    const wrapper = await mountForm({
      stepType: 'CLEAR_KEYBOARD',
      id: 'c1',
      next: 'n2',
      keyboardText: 'Меню сховано',
      keyboardParseMode: null,
    })
    expect(val('[data-test="step-keyboard-text"]')).toBe('Меню сховано')
    await submitForm()
    const step = wrapper.emitted('submit')![0][0] as FunnelStep
    expect(step.stepType).toBe('CLEAR_KEYBOARD')
    expect(step.id).toBe('c1')
    expect(step.next).toBe('n2')
    expect(step.keyboardText).toBe('Меню сховано')
    expect(step.keyboardParseMode).toBeNull()
    // The backend strictly rejects these on CLEAR_KEYBOARD (Decision 6) — they must be absent.
    expect(step.keyboardRows).toBeUndefined()
    expect(step.isPersistent).toBeUndefined()
    expect(step.oneTimeKeyboard).toBeUndefined()
  })

  it('emits trimmed button texts and a fresh SET_KEYBOARD step with defaults', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-keyboard-text"]').setValue('Menu')
    await $('[data-test="step-keyboard-button-0-0"]').setValue('  Bonus  ')
    await submitForm()
    const step = wrapper.emitted('submit')![0][0] as FunnelStep
    expect(step.keyboardRows).toEqual([{ buttons: [{ text: 'Bonus' }] }])
    expect(step.isPersistent).toBe(true)
    expect(step.oneTimeKeyboard).toBe(false)
    expect(step.keyboardParseMode).toBeNull()
  })
})
