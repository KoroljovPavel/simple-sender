import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../../helpers/settle'
import CreateFunnelDialog from '../../../components/funnels/CreateFunnelDialog.vue'

const { createMock, navigateToMock } = vi.hoisted(() => ({
  createMock: vi.fn(),
  navigateToMock: vi.fn(),
}))

// The dialog talks to the store, not useApi directly — mock the store's create action.
mockNuxtImport('useFunnelsStore', () => () => ({ create: createMock }))
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useLocalePath', () => () => (p: string) => p)

function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}
function maybe(sel: string): Element | null {
  return document.querySelector(sel)
}
async function mountOpen() {
  const wrapper = await mountSuspended(CreateFunnelDialog, { props: { open: true, projectId: 'p1' } })
  await settle()
  return wrapper
}
async function setName(v: string) {
  await $('[data-test="funnel-name-input"]').setValue(v)
}
async function submit() {
  await $('[data-test="funnel-create-form"]').trigger('submit')
  await settle()
}

describe('CreateFunnelDialog', () => {
  beforeEach(() => {
    createMock.mockReset()
    navigateToMock.mockReset()
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('validates required name', async () => {
    await mountOpen()
    await submit()

    expect(maybe('[data-test="funnel-name-error"]')).not.toBeNull()
    expect(createMock).not.toHaveBeenCalled()
  })

  it('submits and navigates', async () => {
    createMock.mockResolvedValueOnce({ id: 'f99' })
    const wrapper = await mountOpen()
    await setName('Welcome series')
    await submit()

    expect(createMock).toHaveBeenCalledTimes(1)
    expect(createMock).toHaveBeenCalledWith({ name: 'Welcome series', description: undefined })
    // Navigates into the editor of the created funnel (Task 10 page).
    expect(navigateToMock).toHaveBeenCalledWith('/projects/p1/funnels/f99')
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
  })

  it('maps server error via useApiError', async () => {
    createMock.mockRejectedValueOnce({ statusCode: 422, data: { code: 'funnel_trigger_conflict' } })
    const wrapper = await mountOpen()
    await setName('Welcome series')
    await submit()

    const err = maybe('[data-test="funnel-submit-error"]')
    expect(err).not.toBeNull()
    expect(err!.textContent?.trim().length).toBeGreaterThan(0)
    expect(err!.textContent).not.toContain('errors.') // mapped, not a raw i18n key
    expect(navigateToMock).not.toHaveBeenCalled()
    expect(wrapper.emitted('created')).toBeFalsy()
  })
})
