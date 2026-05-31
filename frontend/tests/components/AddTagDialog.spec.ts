import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { toast } from 'vue-sonner'
import { settle } from '../helpers/settle'
// The "Add tag" dialog component is named CreateTagDialog.vue (Task 11 file list); this spec keeps the
// AddTagDialog name mandated by the tech-spec Vitest list + Task 11 TDD anchors.
import CreateTagDialog from '../../components/tags/CreateTagDialog.vue'

vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

const URL = '/api/v1/projects/p1/tags'

// Dialog content teleports to <body> (reka-ui), so component-tree finds miss it — query the DOM.
function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}
function maybe(sel: string): Element | null {
  return document.querySelector(sel)
}
async function mountOpen() {
  const wrapper = await mountSuspended(CreateTagDialog, { props: { open: true, projectId: 'p1' } })
  await settle()
  return wrapper
}
async function setSlug(v: string) {
  await $('[data-test="tag-slug-input"]').setValue(v)
}
async function setLabel(v: string) {
  await $('[data-test="tag-label-input"]').setValue(v)
}
async function submit() {
  await $('[data-test="tag-create-form"]').trigger('submit')
  await settle()
}

describe('CreateTagDialog (AddTag dialog)', () => {
  beforeEach(() => {
    apiMock.mockReset()
    vi.mocked(toast.success).mockReset()
    vi.mocked(toast.error).mockReset()
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('AddTagDialog_emptySubmit_showsRequiredErrors', async () => {
    await mountOpen()
    await submit()
    expect(maybe('[data-test="tag-slug-error"]')).not.toBeNull()
    expect(maybe('[data-test="tag-label-error"]')).not.toBeNull()
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('AddTagDialog_invalidSlug_showsRegexError', async () => {
    await mountOpen()
    await setSlug('Foo Bar')
    await setLabel('Foo')
    await submit()
    const err = maybe('[data-test="tag-slug-error"]')
    expect(err).not.toBeNull()
    expect(err!.textContent?.trim().length).toBeGreaterThan(0)
    // Localized via validation.tagSlugPattern — not zod's raw English defaults.
    expect(err!.textContent).not.toContain('Invalid')
    expect(err!.textContent).not.toContain('String must contain')
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('AddTagDialog_canonicalRegex_acceptsHyphenAndDigitLeading', async () => {
    // hyphen + digit-leading both pass the canonical backend regex ^[a-z0-9_-]{1,32}$. Remount per
    // case for full isolation (no reliance on resetForm() ordering between iterations).
    for (const slug of ['paid-2024', '2024_vip']) {
      apiMock.mockReset()
      apiMock.mockResolvedValueOnce({ slug, label: 'X', subscriberCount: 0 })
      const wrapper = await mountOpen()
      await setSlug(slug)
      await setLabel('X')
      await submit()
      expect(maybe('[data-test="tag-slug-error"]')).toBeNull()
      expect(apiMock).toHaveBeenCalledTimes(1)
      wrapper.unmount()
      document.body.innerHTML = ''
    }
  })

  it('AddTagDialog_canonicalRegex_rejectsOver32Chars', async () => {
    await mountOpen()
    await setSlug('a'.repeat(33))
    await setLabel('X')
    await submit()
    expect(maybe('[data-test="tag-slug-error"]')).not.toBeNull()
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('AddTagDialog_validInput_emitsCreated', async () => {
    apiMock.mockResolvedValueOnce({ slug: 'vip', label: 'VIP', subscriberCount: 0 })
    const wrapper = await mountOpen()
    await setSlug('vip')
    await setLabel('VIP')
    await submit()

    expect(apiMock).toHaveBeenCalledTimes(1)
    const [url, opts] = apiMock.mock.calls[0]
    expect(url).toBe(URL)
    expect(opts.method).toBe('POST')
    expect(opts.body).toEqual({ slug: 'vip', label: 'VIP' })
    expect(wrapper.emitted('created')).toBeTruthy()
    expect(toast.success).toHaveBeenCalled()
  })

  it('AddTagDialog_apiConflict_rendersSubmitError', async () => {
    // 409 tag_name_taken (canonical code per tech-spec + Task 4). Task 2 did not seed
    // errors.tags.create.codes.*, so useApiError walks to errors.generic — a real, non-key string.
    apiMock.mockRejectedValueOnce({ statusCode: 409, data: { code: 'tag_name_taken' } })
    const wrapper = await mountOpen()
    await setSlug('vip')
    await setLabel('VIP')
    await submit()

    const err = maybe('[data-test="submit-error"]')
    expect(err).not.toBeNull()
    expect(err!.textContent?.trim().length).toBeGreaterThan(0)
    expect(wrapper.emitted('created')).toBeFalsy()
  })
})
