<script setup lang="ts">
import { NON_DEFAULT_LOCALES } from '~/shared/i18n-locales'

const props = defineProps<{ projectId: string }>()

const { t } = useI18n()
const localePath = useLocalePath()
const route = useRoute()

const generalPath = computed(() => `/projects/${props.projectId}/settings`)
const botPath = computed(() => `/projects/${props.projectId}/settings/bot`)

// i18n strategy="prefix_except_default": only NON_DEFAULT_LOCALES get a /xx
// segment on route.path (the default locale stays bare). Strip a known
// non-default prefix before comparing so the matcher works on /projects/...
// AND /en/projects/.... Sourced from the single locales constant so adding a
// third locale only requires updating that file.
const LOCALE_PREFIX_RE = new RegExp(`^/(${NON_DEFAULT_LOCALES.join('|')})(?=/|$)`)

const currentPath = computed(() => {
  const raw = route.path ?? ''
  const trimmed = raw.replace(/\/$/, '')
  return trimmed.replace(LOCALE_PREFIX_RE, '')
})

// General uses exact match — the regression we guard against is Vue Router's
// default prefix match lighting up /settings as "active" on /settings/bot
// (see task edge cases).
const isGeneralActive = computed(() => currentPath.value === generalPath.value)
const isBotActive = computed(
  () => currentPath.value === botPath.value || currentPath.value.startsWith(`${botPath.value}/`),
)

const linkBase = 'px-3 py-2 text-sm hover:underline'
const linkActive = 'subnav-link-active border-b-2 border-blue-600 text-blue-600'
</script>

<template>
  <nav data-test="settings-subnav" class="flex gap-4 border-b mb-4">
    <NuxtLinkLocale
      data-test="settings-subnav-general"
      :to="localePath(generalPath)"
      :class="[linkBase, isGeneralActive ? linkActive : '']"
      :aria-current="isGeneralActive ? 'page' : undefined"
    >
      {{ t('bot.subnav.general') }}
    </NuxtLinkLocale>
    <NuxtLinkLocale
      data-test="settings-subnav-bot"
      :to="localePath(botPath)"
      :class="[linkBase, isBotActive ? linkActive : '']"
      :aria-current="isBotActive ? 'page' : undefined"
    >
      {{ t('bot.subnav.bot') }}
    </NuxtLinkLocale>
  </nav>
</template>
