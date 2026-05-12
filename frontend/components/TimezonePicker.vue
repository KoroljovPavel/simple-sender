<script setup lang="ts">
const props = defineProps<{
  modelValue: string
  id?: string
  invalid?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const { t } = useI18n()

const allTimezones = (() => {
  // Intl.supportedValuesOf may be missing on very old Safari; degrade to the
  // resolved browser zone so the picker still has a single valid option.
  if (typeof Intl.supportedValuesOf === 'function') return Intl.supportedValuesOf('timeZone')
  return [Intl.DateTimeFormat().resolvedOptions().timeZone]
})()

// Offsets are mildly DST-dependent, but stable enough across a single page
// session. Computing once on init keeps filtering inexpensive — 400+ zones
// × every keystroke is otherwise noticeable on slower devices.
const offsetByTz = (() => {
  const now = new Date()
  const out: Record<string, string> = {}
  for (const tz of allTimezones) {
    try {
      const parts = new Intl.DateTimeFormat('en', {
        timeZone: tz,
        timeZoneName: 'shortOffset',
      }).formatToParts(now)
      const part = parts.find((p) => p.type === 'timeZoneName')
      out[tz] = part?.value ?? ''
    } catch {
      out[tz] = ''
    }
  }
  return out
})()

const open = ref(false)
const query = ref('')
const activeIndex = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)
const containerRef = ref<HTMLElement | null>(null)
const listboxId = `tz-picker-list-${Math.random().toString(36).slice(2, 8)}`

// The input shows the selected value when closed (browser-recognizable IANA
// id, e.g. "Europe/Kyiv"). When the user opens it and starts typing, the
// query takes over until they pick an option or close without picking.
const inputValue = computed({
  get: () => (open.value ? query.value : props.modelValue),
  set: (v: string) => {
    query.value = v
    if (!open.value) open.value = true
    activeIndex.value = 0
  },
})

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return allTimezones
  return allTimezones.filter((tz) => tz.toLowerCase().includes(q))
})

function openDropdown() {
  if (open.value) return
  open.value = true
  query.value = ''
  // Reset active index to the currently-selected option when reopening, so
  // ArrowDown lands on the next option after the selection rather than the top.
  const idx = allTimezones.indexOf(props.modelValue)
  activeIndex.value = idx >= 0 ? idx : 0
}

function closeDropdown() {
  open.value = false
  query.value = ''
}

function selectOption(tz: string) {
  emit('update:modelValue', tz)
  closeDropdown()
  inputRef.value?.blur()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    if (!open.value) openDropdown()
    if (filtered.value.length === 0) return
    activeIndex.value = Math.min(activeIndex.value + 1, filtered.value.length - 1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value) return
    activeIndex.value = Math.max(activeIndex.value - 1, 0)
  } else if (event.key === 'Enter') {
    if (!open.value) return
    event.preventDefault()
    const choice = filtered.value[activeIndex.value]
    if (choice) selectOption(choice)
  } else if (event.key === 'Escape') {
    if (!open.value) return
    event.preventDefault()
    closeDropdown()
  }
}

function onOutsideClick(event: MouseEvent) {
  if (!open.value) return
  const target = event.target as Node | null
  if (!target) return
  if (containerRef.value?.contains(target)) return
  closeDropdown()
}

onMounted(() => {
  document.addEventListener('mousedown', onOutsideClick)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onOutsideClick)
})
</script>

<template>
  <div ref="containerRef" class="relative" data-test="timezone-picker">
    <input
      :id="props.id"
      ref="inputRef"
      v-model="inputValue"
      data-test="timezone-picker-input"
      type="text"
      role="combobox"
      autocomplete="off"
      :aria-expanded="open ? 'true' : 'false'"
      :aria-controls="listboxId"
      aria-autocomplete="list"
      :aria-activedescendant="open && filtered[activeIndex] ? `${listboxId}-opt-${activeIndex}` : undefined"
      :placeholder="t('timezone.searchPlaceholder')"
      class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
      :class="invalid ? 'border-red-500' : 'border-gray-300'"
      @focus="openDropdown"
      @click="openDropdown"
      @keydown="onKeydown"
    />

    <ul
      v-if="open"
      :id="listboxId"
      role="listbox"
      data-test="timezone-picker-listbox"
      class="absolute z-20 mt-1 max-h-64 w-full overflow-y-auto rounded-md border border-gray-200 bg-white shadow-md"
    >
      <li
        v-for="(tz, idx) in filtered"
        :id="`${listboxId}-opt-${idx}`"
        :key="tz"
        :data-test="`timezone-picker-option-${tz}`"
        role="option"
        :aria-selected="tz === modelValue ? 'true' : 'false'"
        class="cursor-pointer px-3 py-2 text-sm flex items-center justify-between gap-3"
        :class="[
          idx === activeIndex ? 'bg-blue-50' : 'hover:bg-gray-50',
          tz === modelValue ? 'font-medium' : '',
        ]"
        @mousedown.prevent="selectOption(tz)"
        @mouseenter="activeIndex = idx"
      >
        <span class="truncate">{{ tz }}</span>
        <span v-if="offsetByTz[tz]" class="text-xs text-gray-500 whitespace-nowrap">{{ offsetByTz[tz] }}</span>
      </li>
      <li
        v-if="filtered.length === 0"
        data-test="timezone-picker-empty"
        class="px-3 py-2 text-sm text-gray-500"
      >
        {{ t('timezone.noMatches') }}
      </li>
    </ul>
  </div>
</template>
