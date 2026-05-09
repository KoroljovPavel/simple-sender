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
  // Disable Nuxt DevTools — its `@vue/devtools-api` ships only as CJS and is
  // not pre-bundled by Vite in this stack, which means it throws
  // `ReferenceError: exports is not defined` in the browser at hydration and
  // silently breaks every client-side handler (LangSwitcher's setLocale,
  // form submissions, etc.). DevTools is dev-only ergonomics, not feature-
  // critical, so we turn it off rather than fight the bundling.
  devtools: { enabled: false },
  modules: [
    '@pinia/nuxt',
    '@nuxtjs/tailwindcss',
    '@nuxtjs/i18n',
  ],
  css: ['~/assets/css/tailwind.css'],
  runtimeConfig: {
    // Server-side base URL: SSR fetch hits the backend directly. Browser-side
    // calls go through the dev proxy (see nitro.devProxy below), so the public
    // base URL is relative — same-origin from the browser's perspective. This
    // keeps the SESSION cookie attached to the Nuxt origin (:3000) instead of
    // the cross-origin backend (:8080), so it survives a hard refresh.
    apiBaseSsr: process.env.NUXT_API_BASE_SSR || 'http://localhost:8080',
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE || '',
    },
  },
  nitro: {
    devProxy: {
      '/api': {
        target: 'http://localhost:8080/api',
        // changeOrigin defaults to false: browser's Origin header (http://localhost:3000)
        // is forwarded as-is so the backend CORS check in SecurityConfig still passes.
      },
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
    // Suppress upstream deprecation warning; we don't use the v-t directive.
    bundle: { optimizeTranslationDirective: false },
    // Disable browser-language detection under Vitest — happy-dom defaults
    // `navigator.language` to 'en-US', which would otherwise flip the locale
    // away from `uk` in unit tests and break the AC18 UA-literal asserts.
    detectBrowserLanguage: process.env.VITEST
      ? false
      : {
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
