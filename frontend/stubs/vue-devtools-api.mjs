// No-op stub for @vue/devtools-api (aliased in nuxt.config `vite.resolve.alias`).
//
// vee-validate 4.x and Pinia register a Vue Devtools plugin in dev via
// `await import('@vue/devtools-api')` → `setupDevtoolsPlugin(...)`. In this dependency tree the bare
// specifier resolves to @vue/devtools-api v7/v8 (pulled in by Nuxt's devtools tooling), which REMOVED
// `setupDevtoolsPlugin` — it only exists in v6 — so the call throws "setupDevtoolsPlugin is not a
// function" as an unhandled rejection in the dev console. Production is unaffected: vee-validate and
// Pinia strip the whole devtools block under NODE_ENV=production, so this module is never imported there.
//
// The project already runs with Nuxt Devtools disabled (see `devtools: { enabled: false }`), so rather
// than fight the multi-version resolution we point the package at this no-op. The only runtime consumers
// in the app (vee-validate, Pinia) just get a harmless no-op registration.
export function setupDevtoolsPlugin() {}
export default { setupDevtoolsPlugin }
