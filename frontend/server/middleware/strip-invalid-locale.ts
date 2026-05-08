import { NON_DEFAULT_LOCALES } from '../../shared/i18n-locales'
import { decideLocaleStrip } from '../utils/locale-strip'

export default defineEventHandler((event) => {
  const decision = decideLocaleStrip(event.path, NON_DEFAULT_LOCALES)

  if (decision.kind === 'passthrough') {
    return
  }

  if (decision.kind === 'reject') {
    setResponseStatus(event, 400)
    return 'Bad Request'
  }

  if (decision.isAuthZone) {
    setResponseHeaders(event, {
      'X-Robots-Tag': 'noindex, nofollow',
      'Referrer-Policy': 'no-referrer',
    })
  }
  return sendRedirect(event, decision.target, 302)
})
