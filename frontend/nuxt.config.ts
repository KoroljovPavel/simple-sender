import { NON_DEFAULT_LOCALES } from './shared/i18n-locales'

const AUTH_HEADERS = {
  'X-Robots-Tag': 'noindex, nofollow',
  'Referrer-Policy': 'no-referrer',
} as const

const nonDefaultAuthRouteRules = Object.fromEntries(
  NON_DEFAULT_LOCALES.map((code) => [`/${code}/auth/**`, { headers: { ...AUTH_HEADERS } }]),
)

export default defineNuxtConfig({
  compatibilityDate: '2025-07-01',
  typescript: {
    strict: true,
  },
  modules: [
    '@pinia/nuxt',
    '@nuxtjs/tailwindcss',
    '@nuxtjs/i18n',
  ],
  css: ['~/assets/css/tailwind.css'],
  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE || 'http://localhost:8080',
    },
  },
  i18n: {
    locales: [
      { code: 'uk', language: 'uk', file: 'uk.json' },
      { code: 'en', language: 'en', file: 'en.json' },
    ],
    defaultLocale: 'uk',
    strategy: 'prefix_except_default',
    langDir: 'locales',
    vueI18n: './i18n.config.ts',
    detectBrowserLanguage: {
      useCookie: true,
      cookieKey: 'i18n_lang',
      cookieSecure: process.env.NODE_ENV === 'production',
      cookieCrossOrigin: false,
      redirectOn: 'no prefix',
      alwaysRedirect: false,
      fallbackLocale: 'uk',
    },
  },
  // Auth pages may receive ?token= in URL (verify-email, reset-password). Block search-engine
  // indexing and strip the Referer header on outbound navigation so tokens cannot leak via
  // crawler caches, browser history sync, or cross-site referer chains.
  // Non-default-locale auth paths (e.g. /en/auth/**) get the same headers via NON_DEFAULT_LOCALES
  // map — adding a new locale only requires updating that constant.
  routeRules: {
    '/auth/**': {
      headers: { ...AUTH_HEADERS },
    },
    ...nonDefaultAuthRouteRules,
  },
})
