export type LocaleStripDecision =
  | { kind: 'passthrough' }
  | { kind: 'reject'; status: 400 }
  | { kind: 'redirect'; target: string; isAuthZone: boolean }

const LOCALE_PREFIX_RE = /^\/([a-z]{2})(?=\/|$)/

export function decideLocaleStrip(
  path: string,
  nonDefaultLocales: readonly string[],
): LocaleStripDecision {
  const queryIdx = path.indexOf('?')
  const pathname = queryIdx === -1 ? path : path.slice(0, queryIdx)
  const query = queryIdx === -1 ? '' : path.slice(queryIdx)

  if (pathname.includes('\\')) {
    return { kind: 'reject', status: 400 }
  }

  const match = LOCALE_PREFIX_RE.exec(pathname)
  if (!match) {
    return { kind: 'passthrough' }
  }

  const prefix = match[1]
  if (nonDefaultLocales.includes(prefix)) {
    return { kind: 'passthrough' }
  }

  let target = pathname.slice(3) || '/'
  target = target.replace(/^\/+/, '/')

  try {
    target = decodeURIComponent(target)
  } catch {
    return { kind: 'reject', status: 400 }
  }

  if (target.includes('\\')) {
    return { kind: 'reject', status: 400 }
  }

  target = target.replace(/^\/+/, '/')

  const fullTarget = target + query
  const isAuthZone = target === '/auth' || target.startsWith('/auth/') || target.startsWith('/auth?')

  return { kind: 'redirect', target: fullTarget, isAuthZone }
}
