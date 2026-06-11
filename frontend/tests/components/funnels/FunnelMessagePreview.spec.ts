import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { settle } from '../../helpers/settle'
import FunnelMessagePreview from '../../../components/funnels/FunnelMessagePreview.vue'
import type { ContentBlock, FunnelStep, KeyboardRow, PreviewStepResponse, RenderedBlock } from '../../../types/funnel'

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

function messageStep(blocks: ContentBlock[], over: Partial<FunnelStep> = {}): FunnelStep {
  return { stepType: 'MESSAGE', id: 's1', blocks, ...over }
}

function response(renderedBlocks: RenderedBlock[], over: Partial<PreviewStepResponse> = {}): PreviewStepResponse {
  return { renderedBlocks, sampleData: false, kind: 'message', ...over }
}

// Keyboard-step response shape (kind: 'keyboard'): one rendered TEXT block + raw label rows
// (null for CLEAR_KEYBOARD). Mirrors the Task-4 backend contract.
function keyboardResponse(
  text: string,
  keyboardRows: string[][] | null,
  over: Partial<PreviewStepResponse> = {},
): PreviewStepResponse {
  return { renderedBlocks: [{ type: 'TEXT', text }], sampleData: false, kind: 'keyboard', keyboardRows, ...over }
}

function setKeyboardStep(over: Partial<FunnelStep> = {}): FunnelStep {
  return {
    stepType: 'SET_KEYBOARD',
    id: 's1',
    keyboardText: 'Привіт, {user.first_name}!',
    keyboardParseMode: null,
    keyboardRows: [
      { buttons: [{ text: 'Згенерувати бонус' }] },
      { buttons: [{ text: 'Допомога' }, { text: 'Меню' }] },
    ] as KeyboardRow[],
    ...over,
  }
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

  it('renders an ordered stack of mixed block types', async () => {
    const blocks: ContentBlock[] = [
      { type: 'TEXT', text: 'Hello' },
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/pic.png', caption: 'A pic' },
      { type: 'ALBUM', items: [
        { type: 'IMAGE', mediaUrl: 'https://cdn.example/a.png', caption: 'first' },
        { type: 'IMAGE', mediaUrl: 'https://cdn.example/b.png' },
      ] },
    ]
    previewMock.mockResolvedValueOnce(response([
      { type: 'TEXT', text: 'Hello' },
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/pic.png', caption: 'A pic' },
      { type: 'ALBUM', items: [
        { mediaUrl: 'https://cdn.example/a.png', caption: 'first' },
        { mediaUrl: 'https://cdn.example/b.png' },
      ] },
    ]))
    const wrapper = await mountWith(messageStep(blocks))

    const rendered = wrapper.findAll('[data-test^="funnel-preview-block-"]')
    expect(rendered).toHaveLength(3)
    // DOM order matches the blocks order: TEXT, then IMAGE, then ALBUM.
    const panelHtml = wrapper.find('[data-test="funnel-preview-panel"]').html()
    const iText = panelHtml.indexOf('funnel-preview-block-0')
    const iImage = panelHtml.indexOf('funnel-preview-block-1')
    const iAlbum = panelHtml.indexOf('funnel-preview-block-2')
    expect(iText).toBeLessThan(iImage)
    expect(iImage).toBeLessThan(iAlbum)
    // Each block carries its type as a data hook so order+type are both verifiable.
    expect(wrapper.find('[data-test="funnel-preview-block-0"]').attributes('data-block-type')).toBe('TEXT')
    expect(wrapper.find('[data-test="funnel-preview-block-1"]').attributes('data-block-type')).toBe('IMAGE')
    expect(wrapper.find('[data-test="funnel-preview-block-2"]').attributes('data-block-type')).toBe('ALBUM')
  })

  it('calls store preview with the blocks request shape', async () => {
    const blocks: ContentBlock[] = [{ type: 'TEXT', text: 'Hi {user.first_name}!', parseMode: null }]
    previewMock.mockResolvedValueOnce(response([{ type: 'TEXT', text: 'Hi Olena!' }]))
    await mountWith(messageStep(blocks))

    expect(previewMock).toHaveBeenCalledTimes(1)
    expect(previewMock).toHaveBeenCalledWith('f1', 's1', {
      stepType: 'MESSAGE',
      blocks,
    })
  })

  it('reactive block change re-triggers debounced preview', async () => {
    vi.useFakeTimers()
    try {
      const blocks: ContentBlock[] = [{ type: 'TEXT', text: 'Hi' }]
      previewMock.mockResolvedValue(response([{ type: 'TEXT', text: 'Hi' }]))
      const step = messageStep(blocks)
      const wrapper = await mountSuspended(FunnelMessagePreview, { props: { step, stepNumber: 1 } })
      // immediate run on mount.
      await vi.runOnlyPendingTimersAsync()
      expect(previewMock).toHaveBeenCalledTimes(1)

      // Mutate a block in place (deep) — watch on step.blocks must catch it and debounce a new run.
      step.blocks![0].text = 'Hi there'
      await wrapper.setProps({ step: { ...step } })
      // Before the debounce fires, no extra call yet.
      expect(previewMock).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(600)
      await vi.runOnlyPendingTimersAsync()

      expect(previewMock).toHaveBeenCalledTimes(2)
      expect(previewMock).toHaveBeenLastCalledWith('f1', 's1', {
        stepType: 'MESSAGE',
        blocks: [{ type: 'TEXT', text: 'Hi there' }],
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('renders text/caption as TEXT, never via v-html', async () => {
    // Telegram escaping is NOT browser-safe. A markup payload MUST surface as literal text,
    // never as a created DOM element (stored-XSS guard, OWASP A03).
    const payload = '<img src=x onerror=alert(1)><b>x</b>'
    previewMock.mockResolvedValueOnce(response([
      { type: 'TEXT', text: payload },
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/p.png', caption: payload },
    ]))
    const wrapper = await mountWith(messageStep([
      { type: 'TEXT', text: payload },
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/p.png', caption: payload },
    ]))

    const textBlock = wrapper.find('[data-test="funnel-preview-block-0"]')
    // The payload appears as LITERAL text…
    expect(textBlock.text()).toContain(payload)
    // …and produced NO real elements from the hostile string (no v-html / innerHTML). The IMAGE block
    // has its own real <img> (the media), so we assert against the TEXT block, which must have none.
    expect(textBlock.find('img').exists()).toBe(false)
    expect(textBlock.find('b').exists()).toBe(false)
    // The IMAGE caption is also literal text — no <b> injected from the caption.
    const captionEl = wrapper.find('[data-test="funnel-preview-block-1"] [data-test="funnel-preview-caption"]')
    expect(captionEl.text()).toContain(payload)
    expect(captionEl.find('b').exists()).toBe(false)

    // Hard guard: the component source must not USE v-html / innerHTML. Comments are stripped first.
    const sourceNoComments = componentSource
      .replace(/<!--[\s\S]*?-->/g, '') // HTML comments
      .replace(/\/\/[^\n]*/g, '') // line comments
      .replace(/\/\*[\s\S]*?\*\//g, '') // block comments
    expect(sourceNoComments).not.toMatch(/v-html\s*=/)
    expect(sourceNoComments).not.toMatch(/\.innerHTML\s*=/)
  })

  it('binds media via :src for an IMAGE block', async () => {
    previewMock.mockResolvedValueOnce(response([
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/pic.png', caption: 'Nice caption' },
    ]))
    const wrapper = await mountWith(messageStep([
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/pic.png', caption: 'Nice caption' },
    ]))

    const img = wrapper.find('[data-test="funnel-preview-block-0"] img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('https://cdn.example/pic.png')
    // referrerpolicy is preserved (existing pattern).
    expect(img.attributes('referrerpolicy')).toBe('no-referrer')
    // Caption renders as text BELOW the image.
    const caption = wrapper.find('[data-test="funnel-preview-block-0"] [data-test="funnel-preview-caption"]')
    expect(caption.exists()).toBe(true)
    expect(caption.text()).toContain('Nice caption')
    const html = wrapper.find('[data-test="funnel-preview-block-0"]').html()
    expect(html.indexOf('<img')).toBeLessThan(html.indexOf('funnel-preview-caption'))
  })

  it('renders an IMAGE with a file_id / non-http src as the neutral icon, not an <img> (anti-SSRF/XSS)', async () => {
    for (const url of ['BAADAgADfile_id_token', 'data:image/svg+xml,<svg/>', 'javascript:alert(1)']) {
      previewMock.mockReset()
      previewMock.mockResolvedValueOnce(response([{ type: 'IMAGE', mediaUrl: url, caption: 'cap' }]))
      const wrapper = await mountWith(messageStep([{ type: 'IMAGE', mediaUrl: url, caption: 'cap' }]))

      // No real <img> for an opaque token / non-http scheme — the doomed/unsafe request is skipped.
      expect(wrapper.find('[data-test="funnel-preview-block-0"] img').exists()).toBe(false)
      expect(wrapper.find('[data-test="funnel-preview-block-0"] [data-test="funnel-preview-media-unavailable"]').exists()).toBe(true)
      // Caption still rendered as text.
      expect(wrapper.find('[data-test="funnel-preview-block-0"] [data-test="funnel-preview-caption"]').text()).toContain('cap')
    }
  })

  it('falls back to a neutral placeholder when an IMAGE fails to load (@error, per-block)', async () => {
    previewMock.mockResolvedValueOnce(response([
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/a.png' },
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/b.png' },
    ]))
    const wrapper = await mountWith(messageStep([
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/a.png' },
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/b.png' },
    ]))

    const firstImg = wrapper.find('[data-test="funnel-preview-block-0"] img')
    expect(firstImg.exists()).toBe(true)
    await firstImg.trigger('error')
    await settle()

    // The failing block (0) shows the placeholder; block 1 is untouched (per-block state).
    expect(wrapper.find('[data-test="funnel-preview-block-0"] img').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-preview-block-0"] [data-test="funnel-preview-media-unavailable"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-preview-block-1"] img').exists()).toBe(true)
  })

  it('renders VIDEO / AUDIO via a type icon, not an inline <img> player', async () => {
    previewMock.mockResolvedValueOnce(response([
      { type: 'VIDEO', mediaUrl: 'https://cdn.example/v.mp4', caption: 'clip' },
      { type: 'AUDIO', mediaUrl: 'https://cdn.example/a.mp3' },
    ]))
    const wrapper = await mountWith(messageStep([
      { type: 'VIDEO', mediaUrl: 'https://cdn.example/v.mp4', caption: 'clip' },
      { type: 'AUDIO', mediaUrl: 'https://cdn.example/a.mp3' },
    ]))

    // No <img> media element for video/audio (inline player undesirable).
    expect(wrapper.find('[data-test="funnel-preview-block-0"] img').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-preview-block-1"] img').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-preview-block-0"] [data-test="funnel-preview-media-icon"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="funnel-preview-block-1"] [data-test="funnel-preview-media-icon"]').exists()).toBe(true)
    // Video caption still rendered as text.
    expect(wrapper.find('[data-test="funnel-preview-block-0"] [data-test="funnel-preview-caption"]').text()).toContain('clip')
  })

  it('renders a FILE href only for an http(s) scheme', async () => {
    previewMock.mockResolvedValueOnce(response([
      { type: 'FILE', mediaUrl: 'https://cdn.example/doc.pdf', caption: 'Doc' },
    ]))
    const wrapper = await mountWith(messageStep([
      { type: 'FILE', mediaUrl: 'https://cdn.example/doc.pdf', caption: 'Doc' },
    ]))

    const link = wrapper.find('[data-test="funnel-preview-block-0"] a[data-test="funnel-preview-file-link"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('https://cdn.example/doc.pdf')
  })

  it('renders a FILE as a non-clickable icon for a non-http scheme / file_id (anti-SSRF/XSS)', async () => {
    for (const url of ['BAADAgADfile_id_token', 'file:///etc/passwd', 'javascript:alert(1)', 'data:text/html,x']) {
      previewMock.mockReset()
      previewMock.mockResolvedValueOnce(response([{ type: 'FILE', mediaUrl: url, caption: 'F' }]))
      const wrapper = await mountWith(messageStep([{ type: 'FILE', mediaUrl: url, caption: 'F' }]))

      // No clickable href for an opaque token / non-http scheme.
      expect(wrapper.find('[data-test="funnel-preview-block-0"] a[data-test="funnel-preview-file-link"]').exists()).toBe(false)
      // …rendered as a type icon instead.
      expect(wrapper.find('[data-test="funnel-preview-block-0"] [data-test="funnel-preview-media-icon"]').exists()).toBe(true)
    }
  })

  it('renders an ALBUM as a grid of items; caption only on the first', async () => {
    previewMock.mockResolvedValueOnce(response([
      { type: 'ALBUM', items: [
        { mediaUrl: 'https://cdn.example/1.png', caption: 'first cap' },
        { mediaUrl: 'https://cdn.example/2.png' },
        { mediaUrl: 'https://cdn.example/3.png' },
      ] },
    ]))
    const wrapper = await mountWith(messageStep([
      { type: 'ALBUM', items: [
        { type: 'IMAGE', mediaUrl: 'https://cdn.example/1.png', caption: 'first cap' },
        { type: 'IMAGE', mediaUrl: 'https://cdn.example/2.png' },
        { type: 'IMAGE', mediaUrl: 'https://cdn.example/3.png' },
      ] },
    ]))

    const items = wrapper.findAll('[data-test="funnel-preview-block-0"] [data-test^="funnel-preview-album-item-"]')
    expect(items).toHaveLength(3)
    // Caption is shown once (only the first album element).
    const captions = wrapper.findAll('[data-test="funnel-preview-block-0"] [data-test="funnel-preview-caption"]')
    expect(captions).toHaveLength(1)
    expect(captions[0].text()).toContain('first cap')
  })

  it('renders buttons under the last block', async () => {
    previewMock.mockResolvedValueOnce(response([
      { type: 'TEXT', text: 'Hello' },
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/p.png' },
    ]))
    const wrapper = await mountWith(messageStep(
      [
        { type: 'TEXT', text: 'Hello' },
        { type: 'IMAGE', mediaUrl: 'https://cdn.example/p.png' },
      ],
      { buttons: [
        { type: 'callback', label: 'Yes', targetStepId: 's2' },
        { type: 'url', label: 'Open', url: 'https://example.com' },
      ] },
    ))

    const buttons = wrapper.find('[data-test="funnel-preview-buttons"]')
    expect(buttons.exists()).toBe(true)
    expect(buttons.text()).toContain('Yes')
    expect(buttons.text()).toContain('Open')
    // Buttons come AFTER the last block (block-1) in the DOM.
    const panelHtml = wrapper.find('[data-test="funnel-preview-panel"]').html()
    expect(panelHtml.indexOf('funnel-preview-block-1')).toBeLessThan(panelHtml.indexOf('funnel-preview-buttons'))
  })

  it('renders safely when renderedBlocks is shorter than blocks (backend/front desync)', async () => {
    // Two blocks requested, only one rendered back → render the min length, never crash.
    previewMock.mockResolvedValueOnce(response([{ type: 'TEXT', text: 'Only one' }]))
    const wrapper = await mountWith(messageStep([
      { type: 'TEXT', text: 'Only one' },
      { type: 'IMAGE', mediaUrl: 'https://cdn.example/p.png' },
    ]))

    const blocks = wrapper.findAll('[data-test^="funnel-preview-block-"]')
    expect(blocks).toHaveLength(1)
    expect(blocks[0].text()).toContain('Only one')
  })

  it('shows placeholder for a non-message step and makes no backend call', async () => {
    const wrapper = await mountWith({ stepType: 'DELAY', id: 's2', delayValue: 1, delayUnit: 'MIN' })

    expect(previewMock).not.toHaveBeenCalled()
    const placeholder = wrapper.find('[data-test="funnel-preview-placeholder"]')
    expect(placeholder.exists()).toBe(true)
    expect(placeholder.text().trim().length).toBeGreaterThan(0)
    expect(wrapper.findAll('[data-test^="funnel-preview-block-"]')).toHaveLength(0)
  })

  it('shows a neutral message on a preview error', async () => {
    previewMock.mockRejectedValueOnce({ statusCode: 404 })
    const wrapper = await mountWith(messageStep([{ type: 'TEXT', text: 'Hi' }]))

    const error = wrapper.find('[data-test="funnel-preview-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text().trim().length).toBeGreaterThan(0)
    // The panel did not blow up / stay blank — no rendered blocks on error.
    expect(wrapper.findAll('[data-test^="funnel-preview-block-"]')).toHaveLength(0)
  })

  it('shows the loading state while the preview is in flight', async () => {
    let resolveFn: (v: PreviewStepResponse) => void = () => {}
    previewMock.mockImplementationOnce(() => new Promise<PreviewStepResponse>((r) => { resolveFn = r }))
    const wrapper = await mountSuspended(FunnelMessagePreview, {
      props: { step: messageStep([{ type: 'TEXT', text: 'Hi' }]), stepNumber: 1 },
    })
    // Before resolving, the loading indicator is visible.
    expect(wrapper.find('[data-test="funnel-preview-loading"]').exists()).toBe(true)
    resolveFn(response([{ type: 'TEXT', text: 'Hi' }]))
    await settle()
    expect(wrapper.find('[data-test="funnel-preview-loading"]').exists()).toBe(false)
  })

  it('shows the sample-data indicator when flagged', async () => {
    previewMock.mockResolvedValueOnce(response([{ type: 'TEXT', text: 'Hi' }], { sampleData: true }))
    const wrapper = await mountWith(messageStep([{ type: 'TEXT', text: 'Hi' }]))

    const indicator = wrapper.find('[data-test="funnel-preview-sample-data"]')
    expect(indicator.exists()).toBe(true)
    expect(indicator.text().trim().length).toBeGreaterThan(0)
  })

  it('does NOT show the sample-data indicator when not flagged', async () => {
    previewMock.mockResolvedValueOnce(response([{ type: 'TEXT', text: 'Hi' }]))
    const wrapper = await mountWith(messageStep([{ type: 'TEXT', text: 'Hi' }]))

    expect(wrapper.find('[data-test="funnel-preview-sample-data"]').exists()).toBe(false)
  })

  it('shows a neutral empty state when no step is selected', async () => {
    const wrapper = await mountWith(null)

    expect(previewMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="funnel-preview-empty"]').exists()).toBe(true)
  })

  it('shows the "Step N · type" heading for a message step', async () => {
    previewMock.mockResolvedValueOnce(response([{ type: 'TEXT', text: 'Hi' }]))
    const wrapper = await mountWith(messageStep([{ type: 'TEXT', text: 'Hi' }]), 2)

    const heading = wrapper.find('[data-test="funnel-preview-step-heading"]')
    expect(heading.exists()).toBe(true)
    expect(heading.text()).toContain('2')
    // The heading composes a localized template, never the raw template key.
    expect(heading.text()).not.toContain('funnels.editor.previewStepHeading')
  })

  it('shows the heading for a non-message step too (alongside the placeholder)', async () => {
    const wrapper = await mountWith({ stepType: 'DELAY', id: 's2', delayValue: 1, delayUnit: 'MIN' }, 3)

    const heading = wrapper.find('[data-test="funnel-preview-step-heading"]')
    expect(heading.exists()).toBe(true)
    expect(heading.text()).toContain('3')
    expect(wrapper.find('[data-test="funnel-preview-placeholder"]').exists()).toBe(true)
  })

  it('omits the heading in the empty state (no step)', async () => {
    const wrapper = await mountWith(null)
    expect(wrapper.find('[data-test="funnel-preview-step-heading"]').exists()).toBe(false)
  })

  // --- 16-persistent-keyboard: keyboard step preview branch ---

  it('SET_KEYBOARD renders the backend-rendered text and a keyboard mock of label rows', async () => {
    previewMock.mockResolvedValueOnce(
      keyboardResponse('Привіт, Olena!', [['Згенерувати бонус'], ['Допомога', 'Меню']]),
    )
    const wrapper = await mountWith(setKeyboardStep())

    // The rendered (variable-substituted) text shows as plain text.
    const text = wrapper.find('[data-test="funnel-preview-keyboard-text"]')
    expect(text.exists()).toBe(true)
    expect(text.text()).toContain('Привіт, Olena!')

    // The bottom-keyboard mock shows 2 rows with the labels in order.
    const mock = wrapper.find('[data-test="funnel-preview-keyboard"]')
    expect(mock.exists()).toBe(true)
    const rows = wrapper.findAll('[data-test^="funnel-preview-keyboard-row-"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('Згенерувати бонус')
    expect(rows[1].text()).toContain('Допомога')
    expect(rows[1].text()).toContain('Меню')
    // Row order: row 0 before row 1 in the DOM.
    const mockHtml = mock.html()
    expect(mockHtml.indexOf('funnel-preview-keyboard-row-0')).toBeLessThan(
      mockHtml.indexOf('funnel-preview-keyboard-row-1'),
    )
    // The chips are NOT the inline-button block — that hook must be absent for a keyboard step.
    expect(wrapper.find('[data-test="funnel-preview-buttons"]').exists()).toBe(false)
    // No MESSAGE block stack is rendered for a keyboard step.
    expect(wrapper.findAll('[data-test^="funnel-preview-block-"]')).toHaveLength(0)
  })

  it('CLEAR_KEYBOARD renders the rendered text only, no keyboard mock', async () => {
    previewMock.mockResolvedValueOnce(keyboardResponse('Клавіатуру прибрано', null))
    const wrapper = await mountWith({
      stepType: 'CLEAR_KEYBOARD',
      id: 's1',
      keyboardText: 'Клавіатуру прибрано',
      keyboardParseMode: null,
    })

    const text = wrapper.find('[data-test="funnel-preview-keyboard-text"]')
    expect(text.exists()).toBe(true)
    expect(text.text()).toContain('Клавіатуру прибрано')
    // No keyboard mock for a null keyboardRows response.
    expect(wrapper.find('[data-test="funnel-preview-keyboard"]').exists()).toBe(false)
  })

  it('renders no keyboard mock when keyboardRows is an empty array', async () => {
    previewMock.mockResolvedValueOnce(keyboardResponse('Текст', []))
    const wrapper = await mountWith(setKeyboardStep())

    expect(wrapper.find('[data-test="funnel-preview-keyboard-text"]').text()).toContain('Текст')
    expect(wrapper.find('[data-test="funnel-preview-keyboard"]').exists()).toBe(false)
  })

  it('keyboard response with empty renderedBlocks renders no text node, no crash', async () => {
    // Front/back desync: kind 'keyboard' but no rendered TEXT block → render nothing rather than crash
    // (mirrors the MESSAGE "renderedBlocks shorter than blocks" defensive case).
    previewMock.mockResolvedValueOnce({
      renderedBlocks: [],
      sampleData: false,
      kind: 'keyboard',
      keyboardRows: null,
    })
    const wrapper = await mountWith(setKeyboardStep())

    expect(wrapper.find('[data-test="funnel-preview-keyboard-text"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="funnel-preview-keyboard"]').exists()).toBe(false)
    // The panel still mounted (no thrown error) — the heading is present.
    expect(wrapper.find('[data-test="funnel-preview-panel"]').exists()).toBe(true)
  })

  it('keyboard step preview payload carries keyboardText/keyboardParseMode/keyboardRows', async () => {
    previewMock.mockResolvedValueOnce(keyboardResponse('Привіт, Olena!', [['Допомога']]))
    const step = setKeyboardStep({ keyboardParseMode: 'HTML' })
    await mountWith(step)

    expect(previewMock).toHaveBeenCalledTimes(1)
    expect(previewMock).toHaveBeenCalledWith('f1', 's1', {
      stepType: 'SET_KEYBOARD',
      keyboardText: step.keyboardText,
      keyboardParseMode: 'HTML',
      keyboardRows: step.keyboardRows,
    })
  })

  it('editing a keyboard field re-triggers the debounced preview', async () => {
    vi.useFakeTimers()
    try {
      previewMock.mockResolvedValue(keyboardResponse('text', [['A']]))
      const step = setKeyboardStep()
      const wrapper = await mountSuspended(FunnelMessagePreview, { props: { step, stepNumber: 1 } })
      await vi.runOnlyPendingTimersAsync()
      expect(previewMock).toHaveBeenCalledTimes(1)

      // Edit the text — watch on keyboardText must catch it and debounce a new run with the new value.
      await wrapper.setProps({ step: { ...step, keyboardText: 'Новий текст' } })
      expect(previewMock).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(600)
      await vi.runOnlyPendingTimersAsync()
      expect(previewMock).toHaveBeenCalledTimes(2)
      expect(previewMock).toHaveBeenLastCalledWith(
        'f1',
        's1',
        expect.objectContaining({ keyboardText: 'Новий текст' }),
      )

      // Edit a row label — deep-watch on keyboardRows must catch it too and send the mutated rows.
      await wrapper.setProps({
        step: { ...step, keyboardText: 'Новий текст', keyboardRows: [{ buttons: [{ text: 'B' }] }] },
      })
      await vi.advanceTimersByTimeAsync(600)
      await vi.runOnlyPendingTimersAsync()
      expect(previewMock).toHaveBeenCalledTimes(3)
      expect(previewMock).toHaveBeenLastCalledWith(
        'f1',
        's1',
        expect.objectContaining({ keyboardRows: [{ buttons: [{ text: 'B' }] }] }),
      )
    } finally {
      vi.useRealTimers()
    }
  })

  it('hostile keyboard label and rendered text surface as literal text (never v-html)', async () => {
    const payload = '<img src=x onerror=alert(1)><b>x</b>'
    previewMock.mockResolvedValueOnce(keyboardResponse(payload, [[payload]]))
    const wrapper = await mountWith(setKeyboardStep())

    const text = wrapper.find('[data-test="funnel-preview-keyboard-text"]')
    expect(text.text()).toContain(payload)
    expect(text.find('img').exists()).toBe(false)
    expect(text.find('b').exists()).toBe(false)

    const mock = wrapper.find('[data-test="funnel-preview-keyboard"]')
    expect(mock.text()).toContain(payload)
    expect(mock.find('img').exists()).toBe(false)
    expect(mock.find('b').exists()).toBe(false)
  })
})
