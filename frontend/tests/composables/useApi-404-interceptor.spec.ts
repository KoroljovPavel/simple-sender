import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref, reactive } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

const { handleStaleCurrentSpy } = vi.hoisted(() => ({
  handleStaleCurrentSpy: vi.fn(),
}))

// Module-level refs to back the mocked store.
const currentProjectIdRef = ref<string | null>(null)
const pendingBannerKeyRef = ref<string | null>(null)

// Wrap in reactive() so refs auto-unwrap on property access — same shape the
// real Pinia setup-store exposes, so the interceptor sees plain values and
// writes propagate back to the underlying refs.
const mockStore = reactive({
  currentProjectId: currentProjectIdRef,
  pendingBannerKey: pendingBannerKeyRef,
  handleStaleCurrent: handleStaleCurrentSpy,
})

mockNuxtImport('useProjectsStore', () => () => mockStore)

import { handleApiResponseError } from '../../composables/useApi'

const CURRENT_ID = 'proj-current-id'
const OTHER_ID = 'proj-other-id'

type ErrCtx = Parameters<typeof handleApiResponseError>[0]

function makeCtx(
  request: ErrCtx['request'],
  status: number,
): ErrCtx {
  return {
    request,
    response: { status } as ErrCtx['response'],
    options: {} as ErrCtx['options'],
  } as ErrCtx
}

describe('useApi 404 interceptor (handleApiResponseError)', () => {
  beforeEach(() => {
    currentProjectIdRef.value = CURRENT_ID
    pendingBannerKeyRef.value = null
    handleStaleCurrentSpy.mockReset()
  })

  it('404 on current project triggers handleStaleCurrent and sets pendingBannerKey', () => {
    const result = handleApiResponseError(
      makeCtx(`/api/v1/projects/${CURRENT_ID}`, 404),
    )
    expect(handleStaleCurrentSpy).toHaveBeenCalledTimes(1)
    expect(pendingBannerKeyRef.value).toBe('errors.projects.unavailable')
    // Non-suppression: the helper returns void (does not turn 404 into resolved).
    expect(result).toBeUndefined()
  })

  it('404 on current project also triggers when request is a URL instance', () => {
    const url = new URL(`http://localhost:8080/api/v1/projects/${CURRENT_ID}?foo=bar`)
    handleApiResponseError(makeCtx(url, 404))
    expect(handleStaleCurrentSpy).toHaveBeenCalledTimes(1)
    expect(pendingBannerKeyRef.value).toBe('errors.projects.unavailable')
  })

  it('404 on current project also triggers when path has a query string', () => {
    handleApiResponseError(
      makeCtx(`/api/v1/projects/${CURRENT_ID}?include_deleted=true`, 404),
    )
    expect(handleStaleCurrentSpy).toHaveBeenCalledTimes(1)
    expect(pendingBannerKeyRef.value).toBe('errors.projects.unavailable')
  })

  it('404 on current project also triggers when request is an absolute URL string (SSR shape)', () => {
    // SSR path: $fetch is created with baseURL='http://localhost:8080', so the
    // request seen by onResponseError is an absolute URL string. This exercises
    // the `new URL(request).pathname` branch in extractPath().
    handleApiResponseError(
      makeCtx(`http://localhost:8080/api/v1/projects/${CURRENT_ID}?x=1`, 404),
    )
    expect(handleStaleCurrentSpy).toHaveBeenCalledTimes(1)
    expect(pendingBannerKeyRef.value).toBe('errors.projects.unavailable')
  })

  it('404 on other project id does NOT trigger', () => {
    handleApiResponseError(makeCtx(`/api/v1/projects/${OTHER_ID}`, 404))
    expect(handleStaleCurrentSpy).not.toHaveBeenCalled()
    expect(pendingBannerKeyRef.value).toBeNull()
  })

  it('404 on sub-path /api/v1/projects/{id}/bots does NOT trigger', () => {
    handleApiResponseError(
      makeCtx(`/api/v1/projects/${CURRENT_ID}/bots`, 404),
    )
    expect(handleStaleCurrentSpy).not.toHaveBeenCalled()
    expect(pendingBannerKeyRef.value).toBeNull()
  })

  it('404 on unrelated endpoint /api/profile does NOT trigger', () => {
    handleApiResponseError(makeCtx('/api/profile', 404))
    expect(handleStaleCurrentSpy).not.toHaveBeenCalled()
    expect(pendingBannerKeyRef.value).toBeNull()
  })

  it('non-404 status does NOT trigger even on matching path', () => {
    handleApiResponseError(
      makeCtx(`/api/v1/projects/${CURRENT_ID}`, 500),
    )
    expect(handleStaleCurrentSpy).not.toHaveBeenCalled()
    expect(pendingBannerKeyRef.value).toBeNull()
  })

  it('404 on /api/v1/projects (collection, no id) does NOT trigger', () => {
    handleApiResponseError(makeCtx('/api/v1/projects', 404))
    expect(handleStaleCurrentSpy).not.toHaveBeenCalled()
    expect(pendingBannerKeyRef.value).toBeNull()
  })
})
