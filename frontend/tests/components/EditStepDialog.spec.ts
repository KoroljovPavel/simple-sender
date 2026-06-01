// @vitest-environment nuxt
import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../helpers/settle'
import EditStepDialog from '../../components/funnels/EditStepDialog.vue'
import type { FunnelStep } from '../../types/funnel'

function $(sel: string): DOMWrapper<Element> {
  const el = document.querySelector(sel)
  if (!el) throw new Error(`not found: ${sel}`)
  return new DOMWrapper(el)
}
async function submit() {
  await $('[data-test="step-form"]').trigger('submit')
  await settle()
}

describe('EditStepDialog', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('prefills existing step and emits updated step', async () => {
    const step: FunnelStep = { stepType: 'ADD_TAG', tagSlug: 'vip' }
    const wrapper = await mountSuspended(EditStepDialog, { props: { open: true, step, index: 2 } })
    await settle()

    // Form is prefilled from the existing step.
    expect(($('[data-test="step-tag-input"]').element as HTMLInputElement).value).toBe('vip')

    await $('[data-test="step-tag-input"]').setValue('gold')
    await submit()

    const emitted = wrapper.emitted('save')
    expect(emitted).toBeTruthy()
    // save emits (index, updatedStep) — index is preserved, slug updated.
    expect(emitted![0][0]).toBe(2)
    expect(emitted![0][1]).toMatchObject({ stepType: 'ADD_TAG', tagSlug: 'gold' })
  })

  it('prefills a send-message step text', async () => {
    const step: FunnelStep = { stepType: 'SEND_MESSAGE', text: 'Hello', parseMode: 'HTML' }
    await mountSuspended(EditStepDialog, { props: { open: true, step, index: 0 } })
    await settle()
    expect(($('[data-test="step-text-input"]').element as HTMLTextAreaElement).value).toBe('Hello')
  })
})
