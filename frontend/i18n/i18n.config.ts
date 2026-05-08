export default defineI18nConfig(() => ({
  legacy: false,
  fallbackLocale: 'uk',
  silentTranslationWarn: process.env.NODE_ENV === 'production',
  silentFallbackWarn: process.env.NODE_ENV === 'production',
}))
