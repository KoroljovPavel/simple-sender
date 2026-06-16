// @vitest-environment nuxt
import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { DOMWrapper } from '@vue/test-utils'
import { settle } from '../../helpers/settle'
import FunnelStepForm from '../../../components/funnels/FunnelStepForm.vue'
import { useFunnelsStore } from '../../../stores/funnels'
import type { FunnelStep, FunnelSummaryResponse } from '../../../types/funnel'

// SUBSCRIBE_TO_FUNNEL ("Почати воронку") target picker: the form must NOT let a funnel subscribe to
// itself, so the funnel CURRENTLY being edited (route param funnelId) is excluded from the target-funnel
// options. The picker reads the project's funnels from the shared (real Pinia) funnels store and renders
// each option as data-test="step-subscribe-target-option-<funnelId>" (SearchableSelect testPrefix). The
// form resolves the current funnel id the same way it already reads projectId — from useRoute().params.
mockNuxtImport('useApi', () => () => () => Promise.resolve([]))
// The editor route is /projects/[projectId]/funnels/[funnelId]; funnelId === the funnel being edited (X).
mockNuxtImport('useRoute', () => () => ({ params: { projectId: 'p1', funnelId: 'fX' } }))

function maybe(sel: string): Element | null {
  return document.querySelector(sel)
}

function funnelSummary(over: Partial<FunnelSummaryResponse> = {}): FunnelSummaryResponse {
  return {
    id: 'f1',
    projectId: 'p1',
    name: 'A funnel',
    description: null,
    status: 'active',
    triggers: [{ triggerType: 'on_start', triggerValue: null, keywords: null, entryStepId: null }],
    allowReEnter: false,
    stepCount: 1,
    createdAt: '',
    updatedAt: '',
    ...over,
  }
}

async function mountSubscribeForm(storeFunnels: FunnelSummaryResponse[]) {
  const wrapper = await mountSuspended(FunnelStepForm, {
    props: { initial: null, siblingSteps: [], submitLabel: 'Save' },
    attachTo: document.body,
  })
  await settle()
  await new DOMWrapper(document.querySelector('[data-test="step-type-select"]')!).setValue(
    'SUBSCRIBE_TO_FUNNEL',
  )
  await settle()
  // Seed the store AFTER the type switch: switching to SUBSCRIBE_TO_FUNNEL fires ensureFunnelsLoaded()
  // which calls funnelsStore.fetch('all') and OVERWRITES funnels with the mocked useApi payload ([]).
  // Seeding after that settles wins, so the target-options computed reads our funnels (same idiom as the
  // keyboard spec). Production loads real data here.
  useFunnelsStore().funnels = storeFunnels
  await settle()
  return wrapper
}

describe('FunnelStepForm — SUBSCRIBE_TO_FUNNEL target picker', () => {
  beforeEach(() => {
    useFunnelsStore().funnels = []
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('excludes the current funnel (route funnelId) from the target-funnel options, keeps the others', async () => {
    await mountSubscribeForm([
      funnelSummary({ id: 'fX', name: 'This funnel' }), // the funnel being edited
      funnelSummary({ id: 'fA', name: 'Other A' }),
      funnelSummary({ id: 'fB', name: 'Other B' }),
    ])

    // SearchableSelect renders its option <li>s only while the dropdown is open — focus to open it.
    await new DOMWrapper(
      document.querySelector('[data-test="step-subscribe-target-input"]')!,
    ).trigger('focus')
    await settle()

    // The current funnel must NOT be selectable as its own subscribe target.
    expect(maybe('[data-test="step-subscribe-target-option-fX"]')).toBeNull()
    // Every OTHER funnel of the project is still selectable.
    expect(maybe('[data-test="step-subscribe-target-option-fA"]')).not.toBeNull()
    expect(maybe('[data-test="step-subscribe-target-option-fB"]')).not.toBeNull()
  })
})
