import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

const { tMock, teMock } = vi.hoisted(() => ({
  tMock: vi.fn(),
  teMock: vi.fn(),
}))

mockNuxtImport('useI18n', () => () => ({ t: tMock, te: teMock }))

import { useApiError } from '../../composables/useApiError'

describe('useApiError', () => {
  beforeEach(() => {
    tMock.mockReset()
    teMock.mockReset()
    tMock.mockImplementation((key: string) => `MSG:${key}`)
  })

  it('factory_returnsFunction', () => {
    teMock.mockReturnValue(false)
    const handler = useApiError()
    expect(typeof handler).toBe('function')
  })

  it('handler_401LoginContext_returnsSpecificKey', () => {
    teMock.mockImplementation((key: string) => key === 'errors.login.401')
    const handler = useApiError()
    const result = handler({ statusCode: 401 }, 'login')
    expect(result).toBe('MSG:errors.login.401')
    expect(tMock).toHaveBeenCalledWith('errors.login.401')
  })

  it('handler_403LoginContextMissing_fallsBackToContextGeneric', () => {
    teMock.mockImplementation(
      (key: string) => key === 'errors.login.generic',
    )
    const handler = useApiError()
    const result = handler({ statusCode: 403 }, 'login')
    expect(result).toBe('MSG:errors.login.generic')
    expect(tMock).toHaveBeenCalledWith('errors.login.generic')
    expect(tMock).not.toHaveBeenCalledWith('errors.login.403')
  })

  it('handler_unknownContext_fallsBackToErrorsGeneric', () => {
    teMock.mockImplementation((key: string) => key === 'errors.generic')
    const handler = useApiError()
    const result = handler({ statusCode: 500 }, 'unknown')
    expect(result).toBe('MSG:errors.generic')
    expect(tMock).toHaveBeenCalledWith('errors.generic')
  })

  it('handler_extractsStatusFromStatusCodeField', () => {
    teMock.mockImplementation((key: string) => key === 'errors.login.401')
    const handler = useApiError()
    handler({ statusCode: 401 }, 'login')
    expect(teMock).toHaveBeenCalledWith('errors.login.401')
  })

  it('handler_extractsStatusFromStatusField', () => {
    teMock.mockImplementation((key: string) => key === 'errors.login.401')
    const handler = useApiError()
    handler({ status: 401 }, 'login')
    expect(teMock).toHaveBeenCalledWith('errors.login.401')
  })

  it('handler_extractsStatusFromResponseStatusField', () => {
    teMock.mockImplementation((key: string) => key === 'errors.login.401')
    const handler = useApiError()
    handler({ response: { status: 401 } }, 'login')
    expect(teMock).toHaveBeenCalledWith('errors.login.401')
  })

  it('handler_noRecognizableStatus_fallsBackToErrorsGeneric_emptyError', () => {
    teMock.mockImplementation((key: string) => key === 'errors.generic')
    const handler = useApiError()
    const result = handler({}, 'login')
    expect(result).toBe('MSG:errors.generic')
    // No specific-status lookup attempted
    const calls = teMock.mock.calls.map((c) => c[0])
    expect(calls).not.toContain('errors.login.undefined')
    expect(calls).not.toContain('errors.login.NaN')
  })

  it('handler_noRecognizableStatus_fallsBackToErrorsGeneric_null', () => {
    teMock.mockImplementation((key: string) => key === 'errors.generic')
    const handler = useApiError()
    expect(handler(null, 'login')).toBe('MSG:errors.generic')
  })

  it('handler_noRecognizableStatus_fallsBackToErrorsGeneric_undefined', () => {
    teMock.mockImplementation((key: string) => key === 'errors.generic')
    const handler = useApiError()
    expect(handler(undefined, 'login')).toBe('MSG:errors.generic')
  })

  it('handler_callsTOnlyAfterTeConfirms', () => {
    teMock.mockReturnValue(false)
    const handler = useApiError()
    handler({ statusCode: 401 }, 'login')
    // No te() returned true → t() should never have been called
    expect(tMock).not.toHaveBeenCalled()
  })

  it('handler_stringStatus_normalizedToNumber', () => {
    teMock.mockImplementation((key: string) => key === 'errors.login.401')
    const handler = useApiError()
    handler({ statusCode: '401' as unknown as number }, 'login')
    expect(teMock).toHaveBeenCalledWith('errors.login.401')
  })

  it('handler_nonFiniteStatus_skipsSpecificLookup', () => {
    teMock.mockImplementation((key: string) => key === 'errors.login.generic')
    const handler = useApiError()
    handler({ statusCode: NaN }, 'login')
    const calls = teMock.mock.calls.map((c) => c[0])
    expect(calls).not.toContain('errors.login.NaN')
    expect(calls).toContain('errors.login.generic')
  })
})
