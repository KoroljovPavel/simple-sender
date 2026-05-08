type ApiErrorShape = {
  statusCode?: unknown
  status?: unknown
  response?: { status?: unknown }
}

function extractStatus(error: unknown): number | null {
  if (error === null || typeof error !== 'object') return null
  const e = error as ApiErrorShape
  const raw = e.statusCode ?? e.status ?? e.response?.status
  if (raw === null || raw === undefined) return null
  const n = Number(raw)
  return Number.isFinite(n) ? n : null
}

export function useApiError() {
  const { t, te } = useI18n()

  return function resolveMessage(error: unknown, contextKey: string): string {
    const status = extractStatus(error)
    const candidates: string[] = []
    if (status !== null && contextKey) {
      candidates.push(`errors.${contextKey}.${status}`)
    }
    if (contextKey) {
      candidates.push(`errors.${contextKey}.generic`)
    }
    candidates.push('errors.generic')

    for (const key of candidates) {
      if (te(key)) return t(key)
    }
    return ''
  }
}
