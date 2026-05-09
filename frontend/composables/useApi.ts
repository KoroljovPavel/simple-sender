function readXsrfCookie(ssrCookieHeader?: string): string | null {
  // On SSR, read from the forwarded Cookie request header. On the client, read
  // from document.cookie (XSRF-TOKEN is set with HttpOnly=false by Spring's
  // CookieServerCsrfTokenRepository specifically so JavaScript can read it).
  if (import.meta.server) {
    if (!ssrCookieHeader) return null
    const match = ssrCookieHeader.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
    if (!match) return null
    try {
      return decodeURIComponent(match[1])
    } catch {
      return null
    }
  }
  if (typeof document === 'undefined') return null
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  if (!match) return null
  try {
    return decodeURIComponent(match[1])
  } catch {
    return null
  }
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

export function useApi() {
  const config = useRuntimeConfig()
  // SSR hits the backend directly (apiBaseSsr); browser uses the relative path
  // and goes through nitro.devProxy. credentials:'include' has no effect on SSR
  // since $fetch runs server-side without a cookie jar — we forward the incoming
  // request's Cookie header explicitly instead.
  const ssrCookieHeader = import.meta.server
    ? useRequestHeaders(['cookie']).cookie
    : undefined
  const baseURL = import.meta.server
    ? (config.apiBaseSsr as string)
    : config.public.apiBase

  return $fetch.create({
    baseURL,
    credentials: 'include',
    onRequest({ options }) {
      const headers = new Headers(options.headers as HeadersInit | undefined)
      if (ssrCookieHeader && !headers.has('cookie')) {
        headers.set('cookie', ssrCookieHeader)
      }
      const method = String(options.method ?? 'GET').toUpperCase()
      if (!SAFE_METHODS.has(method)) {
        const token = readXsrfCookie(ssrCookieHeader)
        if (token) headers.set('X-XSRF-TOKEN', token)
      }
      options.headers = headers
    },
  })
}
