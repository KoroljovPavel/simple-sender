import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'
import FunnelMessagePreview from '../../../components/funnels/FunnelMessagePreview.vue'
import type { FunnelStep } from '../../../types/funnel'

// Read the component's own source for the static no-v-html guard (the compiled SFC object does not
// expose its template string). vitest runs with cwd = frontend/.
const componentSource = readFileSync(
  resolve(process.cwd(), 'components/funnels/FunnelMessagePreview.vue'),
  'utf8',
)

const { previewMock } = vi.hoisted(() => ({ previewMock: vi.fn() }))

// The panel talks to the store, not useApi directly — mock the store's preview action.
mockNuxtImport('useFunnelsStore', () => () => ({ preview: previewMock }))
// useRoute supplies funnelId; the panel needs it to call preview(funnelId, stepId, payload).
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1', funnelId: 'f1' } }))

function messageStep(over: Partial<FunnelStep> = {}): FunnelStep {
  return { stepType: 'SEND_MESSAGE', id: 's1', text: 'Hi {user.first_name}!', parseMode: null, ...over }
}

async function mountWith(step: FunnelStep | null, stepNumber: number | null = null) {
  const wrapper = await mountSuspended(FunnelMessagePreview, { props: { step, stepNumber } })
  await settle()
  return wrapper
}

describe('FunnelMessagePreview', () => {
  beforeEach(() => {
    previewMock.mockReset()
  })

  it('renders backend result for a message step', async () => {
    previewMock.mockResolvedValueOnce({ rendered: 'Hi Olena!', sampleData: false, kind: 'message' })
    const wrapper = await mountWith(messageStep())

    expect(previewMock).toHaveBeenCalledTimes(1)
    expect(previewMock).toHaveBeenCalledWith('f1', 's1', {
      stepType: 'SEND_MESSAGE',
      text: 'Hi {user.first_name}!',
      parseMode: null,
    })
    const panel = wrapper.find('[data-test="funnel-preview-panel"]')
    expect(panel.text()).toContain('Hi Olena!')
  })

  it('shows placeholder for a non-message step', async () => {
    const wrapper = await mountWith({ stepType: 'DELAY', id: 's2', delayValue: 1, delayUnit: 'MIN' })

    // Non-message steps never call the backend.
    expect(previewMock).not.toHaveBeenCalled()
    const placeholder = wrapper.find('[data-test="funnel-preview-placeholder"]')
    expect(placeholder.exists()).toBe(true)
    expect(placeholder.text().trim().length).toBeGreaterThan(0)
    // The rendered output area is not shown for non-message steps.
    expect(wrapper.find('[data-test="funnel-preview-rendered"]').exists()).toBe(false)
  })

  it('shows sample-data indicator when flagged', async () => {
    previewMock.mockResolvedValueOnce({ rendered: 'Hi Sample!', sampleData: true, kind: 'message' })
    const wrapper = await mountWith(messageStep())

    const indicator = wrapper.find('[data-test="funnel-preview-sample-data"]')
    expect(indicator.exists()).toBe(true)
    expect(indicator.text().trim().length).toBeGreaterThan(0)
  })

  it('does NOT show sample-data indicator when not flagged', async () => {
    previewMock.mockResolvedValueOnce({ rendered: 'Hi Olena!', sampleData: false, kind: 'message' })
    const wrapper = await mountWith(messageStep())

    expect(wrapper.find('[data-test="funnel-preview-sample-data"]').exists()).toBe(false)
  })

  it('renders rendered output as TEXT, never via v-html', async () => {
    // Telegram escaping is NOT browser-safe. A markup payload in `rendered` MUST surface as literal text,
    // never as a created DOM element (stored-XSS guard, OWASP A03).
    const payload = '<img src=x onerror=alert(1)><b>x</b>'
    previewMock.mockResolvedValueOnce({ rendered: payload, sampleData: false, kind: 'message' })
    const wrapper = await mountWith(messageStep())

    const rendered = wrapper.find('[data-test="funnel-preview-rendered"]')
    // The payload appears as LITERAL text…
    expect(rendered.text()).toContain(payload)
    // …and produced NO real elements (no v-html / innerHTML).
    expect(rendered.find('img').exists()).toBe(false)
    expect(rendered.find('b').exists()).toBe(false)
    expect(rendered.element.querySelector('img')).toBeNull()
    // Hard guard: the component source must not USE v-html / innerHTML (a v-html="" directive binding or
    // an .innerHTML assignment). Comments that merely mention the words are stripped first.
    const sourceNoComments = componentSource
      .replace(/<!--[\s\S]*?-->/g, '') // HTML comments
      .replace(/\/\/[^\n]*/g, '') // line comments
      .replace(/\/\*[\s\S]*?\*\//g, '') // block comments
    expect(sourceNoComments).not.toMatch(/v-html\s*=/)
    expect(sourceNoComments).not.toMatch(/\.innerHTML\s*=/)
  })

  it('renders the image ABOVE the caption for a SEND_IMAGE step with a valid imageUrl', async () => {
    previewMock.mockResolvedValueOnce({ rendered: 'Nice caption', sampleData: false, kind: 'message' })
    const wrapper = await mountWith(
      messageStep({ stepType: 'SEND_IMAGE', text: null, caption: 'Nice caption', imageUrl: 'https://cdn.example/pic.png' }),
    )

    // The image is rendered with the step's imageUrl as :src (bound, not v-html).
    const img = wrapper.find('img[data-test="funnel-preview-image"]')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('https://cdn.example/pic.png')
    // …and the caption is still rendered, BELOW the image.
    const caption = wrapper.find('[data-test="funnel-preview-rendered"]')
    expect(caption.exists()).toBe(true)
    expect(caption.text()).toContain('Nice caption')
    // DOM order: <img> precedes the caption box.
    const panelHtml = wrapper.find('[data-test="funnel-preview-panel"]').html()
    expect(panelHtml.indexOf('funnel-preview-image')).toBeLessThan(
      panelHtml.indexOf('funnel-preview-rendered'),
    )
    // No placeholder when a valid image is present.
    expect(wrapper.find('[data-test="funnel-preview-image-unavailable"]').exists()).toBe(false)
  })

  it('renders ONLY the image (no empty caption box) when the caption is empty', async () => {
    previewMock.mockResolvedValueOnce({ rendered: '', sampleData: false, kind: 'message' })
    const wrapper = await mountWith(
      messageStep({ stepType: 'SEND_IMAGE', text: null, caption: '', imageUrl: 'https://cdn.example/pic.png' }),
    )

    expect(wrapper.find('img[data-test="funnel-preview-image"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-preview-rendered"]').exists()).toBe(false)
  })

  it('shows a neutral placeholder (no broken <img>) when SEND_IMAGE imageUrl is blank, caption still shown', async () => {
    previewMock.mockResolvedValueOnce({ rendered: 'Caption text', sampleData: false, kind: 'message' })
    const wrapper = await mountWith(
      messageStep({ stepType: 'SEND_IMAGE', text: null, caption: 'Caption text', imageUrl: '   ' }),
    )

    // No <img> element at all for a blank URL.
    expect(wrapper.find('img[data-test="funnel-preview-image"]').exists()).toBe(false)
    // Neutral "image unavailable" placeholder instead.
    const placeholder = wrapper.find('[data-test="funnel-preview-image-unavailable"]')
    expect(placeholder.exists()).toBe(true)
    expect(placeholder.text().trim().length).toBeGreaterThan(0)
    // Caption still renders below.
    expect(wrapper.find('[data-test="funnel-preview-rendered"]').text()).toContain('Caption text')
  })

  it('shows the placeholder when the image fails to load (@error)', async () => {
    previewMock.mockResolvedValueOnce({ rendered: 'Caption text', sampleData: false, kind: 'message' })
    const wrapper = await mountWith(
      messageStep({ stepType: 'SEND_IMAGE', text: null, caption: 'Caption text', imageUrl: 'https://cdn.example/broken.png' }),
    )

    const img = wrapper.find('img[data-test="funnel-preview-image"]')
    expect(img.exists()).toBe(true)
    await img.trigger('error')
    await settle()

    // After the load error the <img> is replaced by the neutral placeholder.
    expect(wrapper.find('img[data-test="funnel-preview-image"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-preview-image-unavailable"]').exists()).toBe(true)
    // Caption still shown.
    expect(wrapper.find('[data-test="funnel-preview-rendered"]').text()).toContain('Caption text')
  })

  it('does NOT render an image for SEND_MESSAGE / MENU / non-message steps', async () => {
    // SEND_MESSAGE — even if it somehow carried an imageUrl, the image is SEND_IMAGE-only.
    previewMock.mockResolvedValueOnce({ rendered: 'Hi Olena!', sampleData: false, kind: 'message' })
    const sendMessage = await mountWith(messageStep({ imageUrl: 'https://cdn.example/x.png' }))
    expect(sendMessage.find('img[data-test="funnel-preview-image"]').exists()).toBe(false)
    expect(sendMessage.find('[data-test="funnel-preview-image-unavailable"]').exists()).toBe(false)

    // MENU.
    previewMock.mockResolvedValueOnce({ rendered: 'Menu body', sampleData: false, kind: 'message' })
    const menu = await mountWith(messageStep({ stepType: 'MENU', text: 'Menu body', imageUrl: 'https://cdn.example/x.png' }))
    expect(menu.find('img[data-test="funnel-preview-image"]').exists()).toBe(false)
    expect(menu.find('[data-test="funnel-preview-image-unavailable"]').exists()).toBe(false)

    // Non-message (DELAY) — backend not called, no image.
    const delay = await mountWith({ stepType: 'DELAY', id: 's2', delayValue: 1, delayUnit: 'MIN', imageUrl: 'https://cdn.example/x.png' })
    expect(delay.find('img[data-test="funnel-preview-image"]').exists()).toBe(false)
  })

  it('renders neutral message on preview error', async () => {
    previewMock.mockRejectedValueOnce({ statusCode: 404 })
    const wrapper = await mountWith(messageStep())

    const error = wrapper.find('[data-test="funnel-preview-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text().trim().length).toBeGreaterThan(0)
    // The panel did not blow up / stay blank — the rendered output is not shown on error.
    expect(wrapper.find('[data-test="funnel-preview-rendered"]').exists()).toBe(false)
  })

  it('shows a neutral empty state when no step is selected', async () => {
    const wrapper = await mountWith(null)

    expect(previewMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="funnel-preview-empty"]').exists()).toBe(true)
  })

  it('shows the "Step N · type" heading for a message step', async () => {
    previewMock.mockResolvedValueOnce({ rendered: 'Hi Olena!', sampleData: false, kind: 'message' })
    const wrapper = await mountWith(messageStep(), 2)

    const heading = wrapper.find('[data-test="funnel-preview-step-heading"]')
    expect(heading.exists()).toBe(true)
    // 1-based number + the localized SEND_MESSAGE type name (not a raw i18n key).
    expect(heading.text()).toContain('2')
    expect(heading.text()).not.toContain('funnels.editor.previewStepHeading')
    expect(heading.text()).not.toContain('SEND_MESSAGE')
  })

  it('shows the heading for a non-message step too (alongside the placeholder)', async () => {
    const wrapper = await mountWith({ stepType: 'DELAY', id: 's2', delayValue: 1, delayUnit: 'MIN' }, 3)

    const heading = wrapper.find('[data-test="funnel-preview-step-heading"]')
    expect(heading.exists()).toBe(true)
    expect(heading.text()).toContain('3')
    expect(heading.text()).not.toContain('DELAY')
    // The placeholder still renders for the non-message step.
    expect(wrapper.find('[data-test="funnel-preview-placeholder"]').exists()).toBe(true)
  })

  it('omits the heading in the empty state (no step)', async () => {
    const wrapper = await mountWith(null)
    expect(wrapper.find('[data-test="funnel-preview-step-heading"]').exists()).toBe(false)
  })
})
