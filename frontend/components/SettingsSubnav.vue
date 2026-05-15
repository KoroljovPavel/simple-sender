<script setup lang="ts">
const props = defineProps<{ projectId: string }>()

const { t } = useI18n()
const localePath = useLocalePath()
const route = useRoute()

const generalPath = computed(() => `/projects/${props.projectId}/settings`)
const botPath = computed(() => `/projects/${props.projectId}/settings/bot`)

// i18n strategy="prefix_except_default" puts a /uk segment on non-default routes
// (and the default /en route stays bare). Strip a known locale prefix before
// comparing so the matcher works on both /projects/... and /uk/projects/...
const currentPath = computed(() => {
  const raw = route.path ?? ''
  const trimmed = raw.replace(/\/$/, '')
  return trimmed.replace(/^\/(en|uk)(?=\/|$)/, '')
})

// General uses exact match — the regression we guard against is Vue Router's
// default prefix match lighting up /settings as "active" on /settings/bot
// (see task edge cases).
const isGeneralActive = computed(() => currentPath.value === generalPath.value)
const isBotActive = computed(
  () => currentPath.value === botPath.value || currentPath.value.startsWith(`${botPath.value}/`),
)
</script>

<template>
  <nav data-test="settings-subnav" class="flex gap-4 border-b mb-4">
    <NuxtLinkLocale
      data-test="settings-subnav-general"
      :to="localePath(generalPath)"
      class="px-3 py-2 text-sm hover:underline"
      :class="isGeneralActive ? 'subnav-link-active border-b-2 border-blue-600 text-blue-600' : ''"
    >
      {{ t('bot.subnav.general') }}
    </NuxtLinkLocale>
    <NuxtLinkLocale
      data-test="settings-subnav-bot"
      :to="localePath(botPath)"
      class="px-3 py-2 text-sm hover:underline"
      :class="isBotActive ? 'subnav-link-active border-b-2 border-blue-600 text-blue-600' : ''"
    >
      {{ t('bot.subnav.bot') }}
    </NuxtLinkLocale>
  </nav>
</template>
