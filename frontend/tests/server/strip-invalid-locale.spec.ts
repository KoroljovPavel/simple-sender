import { describe, it, expect } from 'vitest'
import { decideLocaleStrip } from '../../server/utils/locale-strip'

const allowList = ['en'] as const

describe('decideLocaleStrip', () => {
  it('invalid prefix /fr/dashboard redirects to /dashboard', () => {
    expect(decideLocaleStrip('/fr/dashboard', allowList)).toEqual({
      kind: 'redirect',
      target: '/dashboard',
      isAuthZone: false,
    })
  })

  it('default-locale prefix /uk/dashboard redirects to /dashboard', () => {
    expect(decideLocaleStrip('/uk/dashboard', allowList)).toEqual({
      kind: 'redirect',
      target: '/dashboard',
      isAuthZone: false,
    })
  })

  it('valid en prefix /en/dashboard passes through', () => {
    expect(decideLocaleStrip('/en/dashboard', allowList)).toEqual({
      kind: 'passthrough',
    })
  })

  it('no prefix /dashboard passes through', () => {
    expect(decideLocaleStrip('/dashboard', allowList)).toEqual({
      kind: 'passthrough',
    })
  })

  it('auth-zone redirect preserves query and flags isAuthZone', () => {
    expect(
      decideLocaleStrip('/fr/auth/verify-email?token=abc', allowList),
    ).toEqual({
      kind: 'redirect',
      target: '/auth/verify-email?token=abc',
      isAuthZone: true,
    })
  })

  it('collapse leading double slashes after strip', () => {
    expect(decideLocaleStrip('/fr//evil.com', allowList)).toEqual({
      kind: 'redirect',
      target: '/evil.com',
      isAuthZone: false,
    })
  })

  it('backslash in path is rejected (raw)', () => {
    expect(decideLocaleStrip('/fr/\\evil.com', allowList)).toEqual({
      kind: 'reject',
      status: 400,
    })
  })

  it('regex lookahead does not match longer words (/enabled passes through)', () => {
    expect(decideLocaleStrip('/enabled', allowList)).toEqual({
      kind: 'passthrough',
    })
  })

  it('malformed percent-encoding is rejected (decodeURIComponent throws)', () => {
    expect(decideLocaleStrip('/fr/%E0%A4%A', allowList)).toEqual({
      kind: 'reject',
      status: 400,
    })
  })

  it('percent-encoded slash %2F resolves safely (no protocol-relative redirect)', () => {
    expect(decideLocaleStrip('/fr/%2Fevil.com', allowList)).toEqual({
      kind: 'redirect',
      target: '/evil.com',
      isAuthZone: false,
    })
  })

  it('percent-encoded backslash %5C is rejected (decoded backslash)', () => {
    expect(decideLocaleStrip('/fr/%5Cevil.com', allowList)).toEqual({
      kind: 'reject',
      status: 400,
    })
  })

  it('double-encoded payload %252F%252F does not produce literal //', () => {
    const result = decideLocaleStrip('/fr/%252F%252Fevil.com', allowList)
    expect(result.kind).toBe('redirect')
    if (result.kind !== 'redirect') return
    expect(result.target.startsWith('//')).toBe(false)
    expect(result.target).not.toContain('//')
  })

  it('uppercase prefix /fR/dashboard passes through (case-sensitive)', () => {
    expect(decideLocaleStrip('/fR/dashboard', allowList)).toEqual({
      kind: 'passthrough',
    })
  })

  it('exact 2-letter path /fr redirects to /', () => {
    expect(decideLocaleStrip('/fr', allowList)).toEqual({
      kind: 'redirect',
      target: '/',
      isAuthZone: false,
    })
  })

  it('/fr/auth (auth without trailing slash) flags isAuthZone', () => {
    expect(decideLocaleStrip('/fr/auth', allowList)).toEqual({
      kind: 'redirect',
      target: '/auth',
      isAuthZone: true,
    })
  })

  it('query-only path /?foo=bar passes through', () => {
    expect(decideLocaleStrip('/?foo=bar', allowList)).toEqual({
      kind: 'passthrough',
    })
  })

  it('root path / passes through', () => {
    expect(decideLocaleStrip('/', allowList)).toEqual({
      kind: 'passthrough',
    })
  })

  it('multi-locale allow-list: /de/dashboard passes through when de is allowed', () => {
    expect(decideLocaleStrip('/de/dashboard', ['en', 'de'] as const)).toEqual({
      kind: 'passthrough',
    })
  })

  it('/fr/auth?token=x flags isAuthZone (auth via query, no path segment)', () => {
    expect(decideLocaleStrip('/fr/auth?token=x', allowList)).toEqual({
      kind: 'redirect',
      target: '/auth?token=x',
      isAuthZone: true,
    })
  })
})
