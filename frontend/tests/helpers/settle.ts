import { flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'

// vee-validate runs zod validation on a microtask; flushPromises alone races with it.
// One short macrotask + flushes covers both the validation cycle and the subsequent render.
export async function settle(): Promise<void> {
  await flushPromises()
  await new Promise((r) => setTimeout(r, 50))
  await flushPromises()
  await nextTick()
}
