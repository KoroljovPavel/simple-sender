// @vitest-environment nuxt
import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../../helpers/settle'
import FunnelStepForm from '../../../components/funnels/FunnelStepForm.vue'
import type { FunnelStep } from '../../../types/funnel'

// The composer sub-editor replaces the old flat SEND_MESSAGE / SEND_IMAGE / MENU branches with one
// MESSAGE step that carries an ordered list of ContentBlocks. Client validation MIRRORS the backend
// FunnelService.validateMessage byte-for-byte: 1..10 blocks, per-type required fields, media source =
// http(s) URL or opaque file_id, album 2..10 items + type-mixing, buttons only on the last non-album
// block, over-length text/caption = a soft warning (does NOT block save).
// The shared form auto-imports useApi (tag/cf pickers) and reads the route projectId — stub both.
mockNuxtImport('useApi', () => () => () => Promise.resolve([]))
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1' } }))

const SIBLINGS: FunnelStep[] = [
  { stepType: 'MESSAGE', id: 's1', blocks: [{ type: 'TEXT', text: 'Welcome' }] },
  { stepType: 'DELAY', id: 's2', delayValue: 5, delayUnit: 'MIN' },
]

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

async function mountForm(initial: FunnelStep | null = null) {
  const wrapper = await mountSuspended(FunnelStepForm, {
    props: { initial, siblingSteps: SIBLINGS, submitLabel: 'Save' },
    attachTo: document.body,
  })
  await settle()
  // The default new step is already MESSAGE, but be explicit so an edit-initial of another type still
  // lands on the composer in tests that want it.
  if (!initial) {
    await $('[data-test="step-type-select"]').setValue('MESSAGE')
    await settle()
  }
  return wrapper
}
async function addBlock(type: string) {
  await $('[data-test="step-block-type-picker"]').setValue(type)
  await $('[data-test="step-add-block"]').trigger('click')
  await settle()
}
async function submitForm() {
  await $('[data-test="step-form"]').trigger('submit')
  await settle()
}

describe('FunnelStepForm — composer (block editor)', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('shows MESSAGE in the step picker (and not the removed flat types) and renders the composer', async () => {
    const wrapper = await mountForm()
    const options = wrapper
      .findAll('[data-test="step-type-select"] option')
      .map((o) => o.attributes('value'))
    expect(options).toContain('MESSAGE')
    expect(options).not.toContain('SEND_MESSAGE')
    expect(options).not.toContain('SEND_IMAGE')
    expect(options).not.toContain('MENU')
    expect(maybe('[data-test="step-composer"]')).not.toBeNull()
  })

  it('adds a block of the picked type', async () => {
    await mountForm()
    // Seeded with a single TEXT block.
    expect(maybe('[data-test="step-block-text-0"]')).not.toBeNull()
    await addBlock('IMAGE')
    expect(maybe('[data-test="step-block-media-url-1"]')).not.toBeNull()
  })

  it('renders the per-type widget for each block type', async () => {
    await mountForm()
    // TEXT → textarea + parseMode select.
    expect(maybe('[data-test="step-block-text-0"]')).not.toBeNull()
    expect(maybe('[data-test="step-block-parsemode-0"]')).not.toBeNull()

    // IMAGE/VIDEO/AUDIO/FILE → media url + caption + parseMode.
    await addBlock('VIDEO')
    expect(maybe('[data-test="step-block-media-url-1"]')).not.toBeNull()
    expect(maybe('[data-test="step-block-caption-1"]')).not.toBeNull()
    expect(maybe('[data-test="step-block-parsemode-1"]')).not.toBeNull()

    // ALBUM → starts with 2 items; caption only on the first.
    await addBlock('ALBUM')
    expect(maybe('[data-test="step-album-item-2-0"]')).not.toBeNull()
    expect(maybe('[data-test="step-album-item-2-1"]')).not.toBeNull()
    expect(maybe('[data-test="step-album-item-caption-2-0"]')).not.toBeNull()
    expect(maybe('[data-test="step-album-item-caption-2-1"]')).toBeNull()
  })

  it('moves a block up and down', async () => {
    await mountForm()
    // Block 0 = TEXT (seed). Add an IMAGE so we have two blocks to reorder.
    await addBlock('IMAGE')
    await $('[data-test="step-block-text-0"]').setValue('first')
    await $('[data-test="step-block-media-url-1"]').setValue('https://e.com/a.png')

    // Move the IMAGE (index 1) up → now index 0 is media, index 1 is text.
    await $('[data-test="step-block-move-up-1"]').trigger('click')
    await settle()
    expect(maybe('[data-test="step-block-media-url-0"]')).not.toBeNull()
    expect(maybe('[data-test="step-block-text-1"]')).not.toBeNull()
    expect(val('[data-test="step-block-text-1"]')).toBe('first')

    // Move it back down.
    await $('[data-test="step-block-move-down-0"]').trigger('click')
    await settle()
    expect(maybe('[data-test="step-block-text-0"]')).not.toBeNull()
    expect(maybe('[data-test="step-block-media-url-1"]')).not.toBeNull()
  })

  it('removes a block', async () => {
    await mountForm()
    await addBlock('IMAGE')
    expect(maybe('[data-test="step-block-1"]')).not.toBeNull()
    await $('[data-test="step-block-remove-1"]').trigger('click')
    await settle()
    expect(maybe('[data-test="step-block-1"]')).toBeNull()
  })

  it('emits a MESSAGE step with ordered blocks on submit', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-block-text-0"]').setValue('Hello')
    await addBlock('IMAGE')
    await $('[data-test="step-block-media-url-1"]').setValue('https://e.com/a.png')
    await $('[data-test="step-block-caption-1"]').setValue('A photo')
    await submitForm()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const step = emitted![0][0] as FunnelStep
    expect(step.stepType).toBe('MESSAGE')
    expect(step.blocks).toEqual([
      { type: 'TEXT', text: 'Hello', parseMode: null },
      { type: 'IMAGE', mediaUrl: 'https://e.com/a.png', caption: 'A photo', parseMode: null },
    ])
  })

  // ── Validation mirror (backend validateMessage) ─────────────────────────────

  it('blocks submit on an empty composer', async () => {
    const wrapper = await mountForm()
    // Remove the seeded block → 0 blocks.
    await $('[data-test="step-block-remove-0"]').trigger('click')
    await settle()
    await submitForm()
    expect(maybe('[data-test="step-composer-error"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('caps the composer at 10 blocks (the add-block button disables)', async () => {
    await mountForm()
    // Seed is 1 TEXT block; add up to the 10-block cap.
    for (let i = 1; i < 10; i++) {
      await addBlock('TEXT')
    }
    expect(maybe('[data-test="step-block-text-9"]')).not.toBeNull()
    // At 10 blocks the add-block button is disabled (UX mirror of the backend MAX_BLOCKS=10 rule).
    expect(($('[data-test="step-add-block"]').element as HTMLButtonElement).disabled).toBe(true)
  })

  it('blocks an empty TEXT block', async () => {
    const wrapper = await mountForm()
    // Leave the seeded TEXT block empty.
    await submitForm()
    expect(maybe('[data-test="step-block-error-0"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('rejects a non-http(s) media url and accepts http(s) / a bare file_id', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-block-remove-0"]').trigger('click')
    await settle()
    await addBlock('IMAGE')
    // index becomes 0 after removing the seed and adding one.
    await $('[data-test="step-block-media-url-0"]').setValue('javascript:alert(1)')
    await submitForm()
    expect(maybe('[data-test="step-block-error-0"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()

    // file:// is also rejected.
    await $('[data-test="step-block-media-url-0"]').setValue('file:///etc/passwd')
    await submitForm()
    expect(maybe('[data-test="step-block-error-0"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()

    // An http(s) URL is accepted.
    await $('[data-test="step-block-media-url-0"]').setValue('https://e.com/a.png')
    await submitForm()
    expect(wrapper.emitted('submit')).toBeTruthy()
  })

  it('accepts an opaque file_id as the media source', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-block-remove-0"]').trigger('click')
    await settle()
    await addBlock('FILE')
    // A bare token with no scheme → treated as a Telegram file_id (accepted).
    await $('[data-test="step-block-media-url-0"]').setValue('BQACAgIAAxkBAAEab2-file-id')
    await submitForm()
    expect(wrapper.emitted('submit')).toBeTruthy()
    const step = wrapper.emitted('submit')![0][0] as FunnelStep
    expect(step.blocks![0]).toMatchObject({ type: 'FILE', mediaUrl: 'BQACAgIAAxkBAAEab2-file-id' })
  })

  it('blocks an album with fewer than 2 or more than 10 items', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-block-remove-0"]').trigger('click')
    await settle()
    await addBlock('ALBUM')
    // Starts at 2 items. Remove one → 1 item → invalid.
    await $('[data-test="step-album-remove-item-0-1"]').trigger('click')
    await settle()
    await $('[data-test="step-album-item-url-0-0"]').setValue('https://e.com/a.png')
    await submitForm()
    expect(maybe('[data-test="step-block-error-0"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('blocks an album with an invalid type mix (audio mixed with photo)', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-block-remove-0"]').trigger('click')
    await settle()
    await addBlock('ALBUM')
    // Item 0 = IMAGE, item 1 = AUDIO → audio must not mix with another kind.
    await $('[data-test="step-album-item-type-0-0"]').setValue('IMAGE')
    await $('[data-test="step-album-item-url-0-0"]').setValue('https://e.com/a.png')
    await $('[data-test="step-album-item-type-0-1"]').setValue('AUDIO')
    await $('[data-test="step-album-item-url-0-1"]').setValue('https://e.com/a.mp3')
    await submitForm()
    expect(maybe('[data-test="step-block-error-0"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  it('accepts a valid photo+video album mix', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-block-remove-0"]').trigger('click')
    await settle()
    await addBlock('ALBUM')
    await $('[data-test="step-album-item-type-0-0"]').setValue('IMAGE')
    await $('[data-test="step-album-item-url-0-0"]').setValue('https://e.com/a.png')
    await $('[data-test="step-album-item-type-0-1"]').setValue('VIDEO')
    await $('[data-test="step-album-item-url-0-1"]').setValue('https://e.com/a.mp4')
    await submitForm()
    expect(wrapper.emitted('submit')).toBeTruthy()
    const step = wrapper.emitted('submit')![0][0] as FunnelStep
    expect(step.blocks![0]).toMatchObject({
      type: 'ALBUM',
      items: [
        { type: 'IMAGE', mediaUrl: 'https://e.com/a.png' },
        { type: 'VIDEO', mediaUrl: 'https://e.com/a.mp4' },
      ],
    })
  })

  it('warns but does not block on over-length text or caption', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-block-text-0"]').setValue('x'.repeat(4097))
    await submitForm()
    expect(maybe('[data-test="step-block-warn-0"]')).not.toBeNull()
    // Soft warning → submit STILL goes through.
    expect(wrapper.emitted('submit')).toBeTruthy()
  })

  // ── Buttons section on the last non-album block (Decision 2) ────────────────

  it('shows the buttons section when the last block is non-album, hides it when it is an album', async () => {
    await mountForm()
    // Seed is a TEXT block → buttons section is available.
    expect(maybe('[data-test="step-menu-buttons"]')).not.toBeNull()
    // Make the last block an album → the buttons section disappears.
    await addBlock('ALBUM')
    expect(maybe('[data-test="step-menu-buttons"]')).toBeNull()
    expect(maybe('[data-test="step-composer-album-tail-hint"]')).not.toBeNull()
  })

  it('emits buttons attached to the step when the last block is non-album', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-block-text-0"]').setValue('Pick one')
    // The keyboard is opt-in: add a button row first.
    await $('[data-test="step-menu-add-button"]').trigger('click')
    await settle()
    await $('[data-test="step-menu-button-label-0"]').setValue('Continue')
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-target-0-option-s1"]').trigger('mousedown')
    await settle()
    await submitForm()

    const step = wrapper.emitted('submit')![0][0] as FunnelStep
    expect(step.buttons).toEqual([
      { type: 'callback', label: 'Continue', targetStepId: 's1', url: null },
    ])
  })

  it('does not block submit on stale hidden button rows when the last block is an album', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-block-text-0"]').setValue('Intro')
    // Add a half-filled (invalid) button row while the keyboard is visible.
    await $('[data-test="step-menu-add-button"]').trigger('click')
    await settle()
    // Now append an album as the LAST block → the keyboard section hides; its stale row must not block save.
    await addBlock('ALBUM')
    await $('[data-test="step-album-item-url-1-0"]').setValue('https://e.com/a.png')
    await $('[data-test="step-album-item-url-1-1"]').setValue('https://e.com/b.png')
    await submitForm()
    const step = wrapper.emitted('submit')![0][0] as FunnelStep
    // Buttons are omitted (album last block) — only the two blocks are emitted.
    expect(step.buttons).toBeUndefined()
    expect(step.blocks!.map((b) => b.type)).toEqual(['TEXT', 'ALBUM'])
  })

  it('blocks submit when an attached button has an empty label', async () => {
    const wrapper = await mountForm()
    await $('[data-test="step-block-text-0"]').setValue('Pick one')
    // Add a button, leave its label empty but pick a valid target → only the label is wrong.
    await $('[data-test="step-menu-add-button"]').trigger('click')
    await settle()
    await $('[data-test="step-menu-target-0-input"]').trigger('focus')
    await settle()
    await $('[data-test="step-menu-target-0-option-s1"]').trigger('mousedown')
    await settle()
    await submitForm()
    expect(maybe('[data-test="step-menu-button-label-error-0"]')).not.toBeNull()
    expect(wrapper.emitted('submit')).toBeFalsy()
  })

  // ── Edit seeding ────────────────────────────────────────────────────────────

  it('seeds the composer from an edited MESSAGE step and preserves id/next', async () => {
    const wrapper = await mountForm({
      stepType: 'MESSAGE',
      id: 'm1',
      next: null,
      blocks: [
        { type: 'TEXT', text: 'Hi', parseMode: 'HTML' },
        { type: 'IMAGE', mediaUrl: 'https://e.com/a.png', caption: 'cap' },
      ],
      buttons: [{ type: 'callback', label: 'Go', targetStepId: 's1', url: null }],
    })
    expect(val('[data-test="step-block-text-0"]')).toBe('Hi')
    expect(val('[data-test="step-block-media-url-1"]')).toBe('https://e.com/a.png')
    expect(val('[data-test="step-block-caption-1"]')).toBe('cap')
    // Move the media block out so the last block is the text tail (carries the buttons), then submit.
    await $('[data-test="step-block-move-up-1"]').trigger('click')
    await settle()
    await submitForm()
    const step = wrapper.emitted('submit')![0][0] as FunnelStep
    expect(step.id).toBe('m1')
    expect(step.blocks!.map((b) => b.type)).toEqual(['IMAGE', 'TEXT'])
  })
})
