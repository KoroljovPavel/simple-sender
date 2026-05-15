<script setup lang="ts">
definePageMeta({ layout: 'default' })

const { t } = useI18n()
const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))
</script>

<template>
  <div class="max-w-2xl space-y-8">
    <SettingsSubnav :project-id="projectId" />
    <p data-test="bot-placeholder" class="text-sm text-gray-700">
      {{ t('bot.placeholder') }}
    </p>
  </div>
</template>
