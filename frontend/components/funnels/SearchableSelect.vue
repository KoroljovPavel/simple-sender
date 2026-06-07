<script setup lang="ts">
// Generic searchable single-select (combobox) shared by the funnel step form: custom-field key AND tag
// pickers. Presentational — the parent owns the fetch and passes `options`; i18n strings are passed in so
// the component stays locale-agnostic and unit-testable. modelValue holds the option `value`; the closed
// input shows its `label`. Built from scratch (same UX as TimezonePicker) for full keyboard/ARIA control.
export type SearchableOption = { value: string; label: string; hint?: string }

// showValue defaults to true via withDefaults: Vue coerces an absent declared Boolean prop to `false`, so a
// plain optional `showValue?: boolean` would silently flip the default. withDefaults restores the true default,
// preserving the tag/custom-field pickers (which need the value suffix) while letting the MENU target picker
// opt out with :show-value="false".
const props = withDefaults(
  defineProps<{
    modelValue: string
    options: SearchableOption[]
    testPrefix: string
    placeholder: string
    loadingText: string
    emptyText: string
    noMatchesText: string
    loading?: boolean
    id?: string
    invalid?: boolean
    // Whether to render the option `value` as a grey suffix next to the label. True for the tag/custom-field
    // pickers where the value (slug / field name) is a meaningful key the author needs to see. Set false for
    // the MENU target picker where the value is an internal Mongo step id / sentinel that is just noise.
    showValue?: boolean
  }>(),
  { showValue: true },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const open = ref(false)
const query = ref('')
const activeIndex = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)
const containerRef = ref<HTMLElement | null>(null)
const listboxId = `searchable-select-${useId()}`

// Closed input shows the selected option's label; if the stored value has no matching option (e.g. the
// referenced tag/field was deleted after authoring) fall back to the raw value so the user still sees it.
const selectedLabel = computed(() => {
  if (!props.modelValue) return ''
  const o = props.options.find((opt) => opt.value === props.modelValue)
  return o ? o.label : props.modelValue
})

const inputValue = computed({
  get: () => (open.value ? query.value : selectedLabel.value),
  set: (v: string) => {
    query.value = v
    if (!open.value) open.value = true
    activeIndex.value = 0
  },
})

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.options
  return props.options.filter(
    (o) => o.value.toLowerCase().includes(q) || o.label.toLowerCase().includes(q),
  )
})

function openDropdown() {
  if (open.value) return
  open.value = true
  query.value = ''
  const idx = props.options.findIndex((o) => o.value === props.modelValue)
  activeIndex.value = idx >= 0 ? idx : 0
}

function closeDropdown() {
  open.value = false
  query.value = ''
}

function selectOption(option: SearchableOption) {
  emit('update:modelValue', option.value)
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
  <div ref="containerRef" class="relative" :data-test="`${testPrefix}-select`">
    <input
      :id="props.id"
      ref="inputRef"
      v-model="inputValue"
      :data-test="`${testPrefix}-input`"
      type="text"
      role="combobox"
      autocomplete="off"
      :aria-expanded="open ? 'true' : 'false'"
      :aria-controls="listboxId"
      aria-autocomplete="list"
      :aria-activedescendant="open && filtered[activeIndex] ? `${listboxId}-opt-${activeIndex}` : undefined"
      :placeholder="placeholder"
      class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
      :class="invalid ? 'border-red-500' : 'border-gray-300'"
      @focus="openDropdown"
      @click="openDropdown"
      @keydown="onKeydown"
    />

    <ul
      v-if="open"
      :id="listboxId"
      role="listbox"
      :data-test="`${testPrefix}-listbox`"
      class="absolute z-20 mt-1 max-h-64 w-full overflow-y-auto rounded-md border border-gray-200 bg-white shadow-md"
    >
      <li v-if="loading" :data-test="`${testPrefix}-loading`" class="px-3 py-2 text-sm text-gray-500">
        {{ loadingText }}
      </li>
      <li
        v-else-if="options.length === 0"
        :data-test="`${testPrefix}-empty`"
        class="px-3 py-2 text-sm text-gray-500"
      >
        {{ emptyText }}
      </li>
      <template v-else>
        <li
          v-for="(opt, idx) in filtered"
          :id="`${listboxId}-opt-${idx}`"
          :key="opt.value"
          :data-test="`${testPrefix}-option-${opt.value}`"
          role="option"
          :aria-selected="opt.value === modelValue ? 'true' : 'false'"
          class="flex cursor-pointer items-center justify-between gap-3 px-3 py-2 text-sm"
          :class="[
            idx === activeIndex ? 'bg-blue-50' : 'hover:bg-gray-50',
            opt.value === modelValue ? 'font-medium' : '',
          ]"
          @mousedown.prevent="selectOption(opt)"
          @mouseenter="activeIndex = idx"
        >
          <span class="truncate">
            {{ opt.label }}
            <span v-if="showValue && opt.label !== opt.value" class="text-gray-400">· {{ opt.value }}</span>
          </span>
          <span v-if="opt.hint" class="whitespace-nowrap text-xs text-gray-500">{{ opt.hint }}</span>
        </li>
        <li
          v-if="filtered.length === 0"
          :data-test="`${testPrefix}-no-matches`"
          class="px-3 py-2 text-sm text-gray-500"
        >
          {{ noMatchesText }}
        </li>
      </template>
    </ul>
  </div>
</template>
