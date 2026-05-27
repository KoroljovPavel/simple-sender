import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { toast } from 'vue-sonner'
import { settle } from '../helpers/settle'
import SendPersonalMessageDialog from '../../components/subscribers/SendPersonalMessageDialog.vue'

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
}))

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useApi', () => () => apiMock)

const URL = '/api/v1/projects/p1/subscribers/s1/messages'

function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}

async function mountOpen() {
  return mountSuspended(SendPersonalMessageDialog, {
    props: { open: true, projectId: 'p1', subscriberId: 's1' },
  })
}

describe('SendPersonalMessageDialog', () => {
  beforeEach(() => {
    apiMock.mockReset()
    vi.mocked(toast.success).mockReset()
    vi.mocked(toast.warning).mockReset()
    vi.mocked(toast.error).mockReset()
  })
  afterEach(() => {
    document.body.innerHTML = '' // drop teleported dialog content so the next test queries fresh nodes
  })

  it('text_emptyRejected', async () => {
    await mountOpen()
    await settle()
    await $('[data-test="send-personal-message-form"]').trigger('submit')
    await settle()
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('text_4097CharsRejected', async () => {
    await mountOpen()
    await settle()
    await $('[data-test="send-personal-message-text"]').setValue('x'.repeat(4097))
    await settle()
    await $('[data-test="send-personal-message-form"]').trigger('submit')
    await settle()
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('sentResponse_successToast', async () => {
    apiMock.mockResolvedValueOnce({ status: 'sent', message: 'Message sent' })
    await mountOpen()
    await settle()
    await $('[data-test="send-personal-message-text"]').setValue('hello')
    await settle()
    await $('[data-test="send-personal-message-form"]').trigger('submit')
    await settle()
    expect(apiMock).toHaveBeenCalledTimes(1)
    expect(toast.success).toHaveBeenCalled()
  })

  it('blockedResponse_warningToast_andRefreshEmitted', async () => {
    apiMock.mockResolvedValueOnce({ status: 'blocked', message: 'Subscriber has blocked the bot' })
    const wrapper = await mountOpen()
    await settle()
    await $('[data-test="send-personal-message-text"]').setValue('hello')
    await settle()
    await $('[data-test="send-personal-message-form"]').trigger('submit')
    await settle()
    expect(toast.warning).toHaveBeenCalled()
    expect(wrapper.emitted('refresh')).toBeTruthy()
  })

  it('429Response_rateLimitedToast', async () => {
    apiMock.mockRejectedValueOnce({ statusCode: 429, data: { code: 'personal_message_rate_limited' } })
    await mountOpen()
    await settle()
    await $('[data-test="send-personal-message-text"]').setValue('hello')
    await settle()
    await $('[data-test="send-personal-message-form"]').trigger('submit')
    await settle()
    expect(toast.error).toHaveBeenCalled()
  })

  it('massAssignment_onlyTextSent', async () => {
    apiMock.mockResolvedValueOnce({ status: 'sent', message: 'Message sent' })
    await mountOpen()
    await settle()
    await $('[data-test="send-personal-message-text"]').setValue('hello')
    await settle()
    await $('[data-test="send-personal-message-form"]').trigger('submit')
    await settle()
    expect(apiMock).toHaveBeenCalledWith(URL, { method: 'POST', body: { text: 'hello' } })
  })
})
