export default defineNuxtConfig({
  compatibilityDate: '2025-07-01',
  typescript: {
    strict: true,
  },
  modules: [
    '@pinia/nuxt',
    '@nuxtjs/tailwindcss',
  ],
  css: ['~/assets/css/tailwind.css'],
  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE || 'http://localhost:8080',
    },
  },
  // Auth pages may receive ?token= in URL (verify-email, reset-password). Block search-engine
  // indexing and strip the Referer header on outbound navigation so tokens cannot leak via
  // crawler caches, browser history sync, or cross-site referer chains.
  routeRules: {
    '/auth/**': {
      headers: {
        'X-Robots-Tag': 'noindex, nofollow',
        'Referrer-Policy': 'no-referrer',
      },
    },
  },
})
