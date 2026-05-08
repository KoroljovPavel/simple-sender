<script setup lang="ts">
const { locale, locales, setLocale } = useI18n()

type LocaleEntry = { code: string; name?: string }

const items = computed<LocaleEntry[]>(() => {
  const list = (locales.value as unknown as LocaleEntry[]) ?? []
  return list.filter((l) => l.code === 'uk' || l.code === 'en')
})

function isActive(code: string): boolean {
  return code === locale.value
}

async function switchTo(code: string): Promise<void> {
  if (isActive(code)) return
  await setLocale(code)
}
</script>

<template>
  <div class="flex items-center gap-2 text-sm select-none">
    <template v-for="(item, idx) in items" :key="item.code">
      <button
        :data-test="`lang-${item.code}`"
        type="button"
        :aria-current="isActive(item.code) ? 'true' : undefined"
        :aria-label="
          item.code === 'uk'
            ? 'Switch language to Ukrainian'
            : 'Switch language to English'
        "
        :class="
          isActive(item.code)
            ? 'font-semibold text-primary'
            : 'text-muted-foreground hover:text-foreground'
        "
        @click="switchTo(item.code)"
      >
        {{ item.code.toUpperCase() }}
      </button>
      <span v-if="idx < items.length - 1" aria-hidden="true" class="text-muted-foreground">|</span>
    </template>
  </div>
</template>
