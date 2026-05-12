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

// Matches exactly one path segment after /api/v1/projects/ — i.e. a single
// resource hit like /api/v1/projects/{id}. Sub-paths (.../bots) and the
// collection (/api/v1/projects) intentionally do not match. Anchored on
// both ends to avoid false positives (Decision 11 / Risk R7).
const PROJECT_RESOURCE_PATH = /^\/api\/v1\/projects\/([^/]+)$/

function extractPath(request: unknown): string | null {
  // ofetch passes `request` as either a string (relative path or absolute URL)
  // or a URL instance. Normalize to a pathname; query string is stripped.
  if (request instanceof URL) return request.pathname
  if (typeof request !== 'string') return null
  if (request.startsWith('/')) {
    const qIdx = request.indexOf('?')
    return qIdx === -1 ? request : request.slice(0, qIdx)
  }
  // Absolute URL string.
  try {
    return new URL(request).pathname
  } catch {
    return null
  }
}

type ResponseErrorCtx = {
  request: unknown
  response: { status: number } | undefined
  options?: unknown
}

export function handleApiResponseError(ctx: ResponseErrorCtx): void {
  if (ctx.response?.status !== 404) return
  const path = extractPath(ctx.request)
  if (path === null) return
  const match = path.match(PROJECT_RESOURCE_PATH)
  if (!match) return
  // Pinia store is read lazily so the composable does not eagerly require
  // the store at SSR boot (Pinia may not be installed in early plugins yet).
  const projectsStore = useProjectsStore()
  // Race note: a stale-in-flight 404 may resolve AFTER the user manually
  // switched to a different project. In that case the captured ID does NOT
  // equal the now-current currentProjectId, so we correctly leave state alone.
  if (match[1] !== projectsStore.currentProjectId) return
  projectsStore.handleStaleCurrent()
  projectsStore.pendingBannerKey = 'errors.projects.unavailable'
}

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
    onResponseError: (ctx) => handleApiResponseError(ctx),
  })
}
