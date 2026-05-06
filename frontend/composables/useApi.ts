function readXsrfCookie(): string | null {
  if (!import.meta.client || typeof document === 'undefined') return null
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : null
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

export function useApi() {
  const config = useRuntimeConfig()
  return $fetch.create({
    baseURL: config.public.apiBase,
    credentials: 'include',
    onRequest({ options }) {
      const method = String(options.method ?? 'GET').toUpperCase()
      if (SAFE_METHODS.has(method)) return
      const token = readXsrfCookie()
      if (!token) return
      const headers = new Headers(options.headers as HeadersInit | undefined)
      headers.set('X-XSRF-TOKEN', token)
      options.headers = headers
    },
  })
}
