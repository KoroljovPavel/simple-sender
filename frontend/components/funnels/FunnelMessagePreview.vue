<script setup lang="ts">
import type { FunnelStep, PreviewStepResponse, RenderedBlock } from '~/types/funnel'

// Message-preview panel for the funnel composer (15-message-composer, Task 8). Renders the CURRENT
// (possibly unsaved) MESSAGE step the author is editing AS IT WILL LOOK IN TELEGRAM: an ordered STACK of
// heterogeneous content blocks (one rendered "message" per block), in the same order as step.blocks. Each
// block's text/caption is substituted + parse_mode-escaped on the backend (Decision 8); the panel only
// calls the store `preview` action and renders the structured result.
//
// CRITICAL (stored-XSS guard, OWASP A03 — Decision 8 / tech-spec Risks): the rendered text/caption carry
// Telegram escaping (MarkdownV2/HTML), which is NOT browser-safe. They are output EXCLUSIVELY as text via
// {{ }} interpolation — NEVER v-html / innerHTML (matches the "never v-html" convention in
// FunnelStepForm.vue). Line breaks are preserved via CSS (whitespace-pre-wrap), not markup. Media is bound
// via :src / :href (never v-html): an <img src> / <a href> cannot execute JS, so the no-HTML-sink invariant
// holds. FILE/document hrefs are additionally scheme-guarded (http(s) only — anti-SSRF/anti-XSS, Decision 6).
const props = defineProps<{ step: FunnelStep | null; stepNumber?: number | null }>()

const { t } = useI18n()

// http(s)-only scheme guard for clickable media. Mirrors FunnelStepForm IMAGE_URL_RE — rejects
// file:// / data: / javascript: AND opaque Telegram file_id tokens (which carry no scheme). A media URL
// that fails this guard is NOT turned into a clickable :href / :src; it falls back to a type icon.
const HTTP_URL_RE = /^https?:\/\//i
function isHttpUrl(url: string | null | undefined): boolean {
  return typeof url === 'string' && HTTP_URL_RE.test(url.trim())
}

// Heading for the focused step: "Крок {N} · {localized type}". Shown for BOTH message and non-message
// steps so the author always knows WHICH step is previewed; hidden only in the empty (no step) state.
// {type} reuses the existing funnels.steps.type.* keys — never a hardcoded type name. Output is plain
// text via {{ }} (the heading composes localized strings, no markup).
const stepHeading = computed<string | null>(() => {
  if (!props.step || props.stepNumber == null) return null
  return t('funnels.editor.previewStepHeading', {
    number: props.stepNumber,
    type: t(`funnels.steps.type.${props.step.stepType}`),
  })
})

const route = useRoute()
const funnelsStore = useFunnelsStore()
// Error mapping lives in the component setup (NOT the store) — useApiError pulls useI18n() and the store
// action re-throws unmapped (store contract). 404 / network both resolve to a neutral panel message.
const resolveError = useApiError()

const funnelId = computed(() => String(route.params.funnelId))

// Only the MESSAGE composer step renders a Telegram message stack; everything else gets a neutral
// placeholder (no backend call). The former SEND_MESSAGE/SEND_IMAGE/MENU kinds are gone (Task 1/6).
const isMessageStep = computed(() => props.step?.stepType === 'MESSAGE')

const rendered = ref<PreviewStepResponse | null>(null)
const errorMessage = ref<string | null>(null)
const loading = ref(false)

// The rendered blocks paired with the step's own blocks, clamped to the shorter length so a backend/front
// desync (renderedBlocks shorter/longer than blocks) renders safely instead of crashing. Each rendered
// block already carries everything the panel needs (type/text/caption/mediaUrl/items), so we render off
// `renderedBlocks` and use the original block only to keep counts aligned.
const previewBlocks = computed<RenderedBlock[]>(() => {
  const blocks = props.step?.blocks ?? []
  const renderedList = rendered.value?.renderedBlocks ?? []
  return renderedList.slice(0, Math.min(blocks.length, renderedList.length))
})

// Per-block media-load-failure flags (replaces the old single global imageLoadFailed ref). Keyed by block
// index; an @error on a block's <img> flips ONLY that block to the neutral placeholder. Reset whenever a
// fresh preview arrives so re-rendered media gets a new chance.
const mediaLoadFailed = ref<Record<number, boolean>>({})
function onMediaError(index: number) {
  mediaLoadFailed.value = { ...mediaLoadFailed.value, [index]: true }
}

// Buttons attach to the step (the last non-album block — Decision 2); rendered under the last block.
const buttons = computed(() => props.step?.buttons ?? [])

async function runPreview() {
  const step = props.step
  // No step / non-message step → nothing to fetch; the template shows the empty/placeholder state.
  if (!step || !isMessageStep.value) {
    rendered.value = null
    errorMessage.value = null
    return
  }
  loading.value = true
  errorMessage.value = null
  mediaLoadFailed.value = {}
  try {
    rendered.value = await funnelsStore.preview(funnelId.value, step.id ?? '', {
      stepType: step.stepType,
      blocks: step.blocks ?? [],
    })
  } catch (err) {
    // 404 (unknown step) / network / 5xx → neutral in-panel message, never a throw or blank screen.
    rendered.value = null
    errorMessage.value = resolveError(err, 'funnels.preview')
  } finally {
    loading.value = false
  }
}

// Debounced reactive preview — mirrors the scheduleTriggerPersist/triggerTimer idiom in [funnelId].vue so
// editing the composer does not bombard the backend on every keystroke.
let previewTimer: ReturnType<typeof setTimeout> | null = null
function schedulePreview() {
  if (previewTimer) clearTimeout(previewTimer)
  previewTimer = setTimeout(() => void runPreview(), 600)
}

// Re-run when the focused step OR its content blocks change. immediate so the panel renders on mount
// (tests rely on the first call happening without an edit). Run directly on the first tick, debounce edits.
// Deep-watch step.blocks so an in-place block edit (text/caption/mediaUrl/album item) re-triggers preview —
// the old flat text/caption/parseMode fields are gone (Task 1/6).
let primed = false
watch(
  () => [props.step?.stepType, props.step?.id, props.step?.blocks] as const,
  () => {
    if (!primed) {
      primed = true
      void runPreview()
    } else {
      schedulePreview()
    }
  },
  { immediate: true, deep: true },
)

onBeforeUnmount(() => {
  if (previewTimer) clearTimeout(previewTimer)
})
</script>

<template>
  <aside
    data-test="funnel-preview-panel"
    class="rounded-md border bg-gray-50 p-4 text-sm"
    aria-live="polite"
  >
    <h3 class="mb-3 font-semibold text-gray-700">{{ t('funnels.editor.preview') }}</h3>

    <!-- Which step is previewed: "Крок {N} · {type}". Shown for any focused step (message or not); plain
         text via {{ }}, never v-html. -->
    <p
      v-if="stepHeading"
      data-test="funnel-preview-step-heading"
      class="mb-3 text-xs font-medium text-gray-500"
    >{{ stepHeading }}</p>

    <!-- No step in focus → neutral empty state (not an error). -->
    <p v-if="!step" data-test="funnel-preview-empty" class="text-gray-500">
      {{ t('funnels.editor.previewPlaceholder') }}
    </p>

    <!-- Non-message step → neutral placeholder; the backend is never called. -->
    <p
      v-else-if="!isMessageStep"
      data-test="funnel-preview-placeholder"
      class="text-gray-500"
    >
      {{ t('funnels.editor.previewPlaceholder') }}
    </p>

    <template v-else>
      <p v-if="loading" data-test="funnel-preview-loading" class="text-gray-500">
        {{ t('common.loading') }}
      </p>

      <!-- Error (404 unknown step / network / 5xx) → neutral in-panel message, never blank/500. -->
      <p
        v-else-if="errorMessage"
        data-test="funnel-preview-error"
        class="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-amber-800"
      >
        {{ errorMessage }}
      </p>

      <template v-else-if="rendered">
        <!-- Sample-data indicator: backend substituted sample placeholders (e.g. bot owner not linked). -->
        <p
          v-if="rendered.sampleData"
          data-test="funnel-preview-sample-data"
          class="mb-2 inline-block rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-800"
        >
          {{ t('funnels.editor.previewSampleData') }}
        </p>

        <!-- The ordered stack of rendered blocks — one "message" per block, in step.blocks order. Each
             block renders per its BlockType. ALL text/caption is output as TEXT ONLY ({{ }}), NEVER v-html
             (Telegram escaping is not browser-safe → v-html would be a stored-XSS sink, OWASP A03). -->
        <div class="space-y-3">
          <div
            v-for="(block, index) in previewBlocks"
            :key="index"
            :data-test="`funnel-preview-block-${index}`"
            :data-block-type="block.type"
            class="rounded-md border bg-white"
          >
            <!-- TEXT — the Telegram-rendered string as plain text. Line breaks via CSS. -->
            <div
              v-if="block.type === 'TEXT'"
              class="whitespace-pre-wrap break-words px-3 py-2 text-gray-900"
            >{{ block.text }}</div>

            <!-- IMAGE — :src bind (never v-html). An @error load failure (per-block) falls back to a
                 neutral type-icon placeholder instead of a broken-image. Caption below as text. -->
            <template v-else-if="block.type === 'IMAGE'">
              <img
                v-if="!mediaLoadFailed[index]"
                :src="block.mediaUrl ?? undefined"
                :alt="t('funnels.editor.previewImageAlt')"
                referrerpolicy="no-referrer"
                class="max-h-64 w-full rounded-t-md object-contain"
                @error="onMediaError(index)"
              >
              <div
                v-else
                data-test="funnel-preview-media-unavailable"
                class="flex items-center gap-2 border-b border-dashed px-3 py-4 text-gray-500"
              >
                <span data-test="funnel-preview-media-icon" aria-hidden="true">🖼️</span>
                <span>{{ t('funnels.editor.previewImageUnavailable') }}</span>
              </div>
              <p
                v-if="block.caption"
                data-test="funnel-preview-caption"
                class="whitespace-pre-wrap break-words px-3 py-2 text-gray-900"
              >{{ block.caption }}</p>
            </template>

            <!-- VIDEO / AUDIO / FILE — a type icon (inline players are undesirable in a preview). FILE
                 additionally renders a clickable :href ONLY for an http(s) scheme (opaque file_id / non-http
                 → icon without href, anti-SSRF/anti-XSS, Decision 6). Caption below as text. -->
            <template v-else-if="block.type === 'VIDEO' || block.type === 'AUDIO' || block.type === 'FILE'">
              <a
                v-if="block.type === 'FILE' && isHttpUrl(block.mediaUrl)"
                data-test="funnel-preview-file-link"
                :href="block.mediaUrl ?? undefined"
                target="_blank"
                rel="noopener noreferrer nofollow"
                referrerpolicy="no-referrer"
                class="flex items-center gap-2 px-3 py-2 text-blue-600 underline"
              >
                <span data-test="funnel-preview-media-icon" aria-hidden="true">📎</span>
                <span class="break-all">{{ block.mediaUrl }}</span>
              </a>
              <div
                v-else
                class="flex items-center gap-2 px-3 py-2 text-gray-600"
              >
                <span data-test="funnel-preview-media-icon" aria-hidden="true">{{
                  block.type === 'VIDEO' ? '🎬' : block.type === 'AUDIO' ? '🎵' : '📎'
                }}</span>
                <span>{{ t(`funnels.editor.previewMediaType.${block.type}`) }}</span>
              </div>
              <p
                v-if="block.caption"
                data-test="funnel-preview-caption"
                class="whitespace-pre-wrap break-words px-3 py-2 text-gray-900"
              >{{ block.caption }}</p>
            </template>

            <!-- ALBUM — a grid of 2–10 items. The album caption is meaningful only on the FIRST item
                 (Decision 5) — rendered once, below the grid, as text. Each item's media is :src-bound. -->
            <template v-else-if="block.type === 'ALBUM'">
              <div class="grid grid-cols-3 gap-1 p-1">
                <div
                  v-for="(item, itemIndex) in block.items ?? []"
                  :key="itemIndex"
                  :data-test="`funnel-preview-album-item-${itemIndex}`"
                  class="aspect-square overflow-hidden rounded bg-gray-100"
                >
                  <img
                    v-if="!mediaLoadFailed[index] && isHttpUrl(item.mediaUrl)"
                    :src="item.mediaUrl"
                    :alt="t('funnels.editor.previewImageAlt')"
                    referrerpolicy="no-referrer"
                    class="h-full w-full object-cover"
                    @error="onMediaError(index)"
                  >
                  <div
                    v-else
                    class="flex h-full w-full items-center justify-center text-gray-400"
                  >
                    <span data-test="funnel-preview-media-icon" aria-hidden="true">🖼️</span>
                  </div>
                </div>
              </div>
              <p
                v-if="(block.items ?? [])[0]?.caption"
                data-test="funnel-preview-caption"
                class="whitespace-pre-wrap break-words px-3 py-2 text-gray-900"
              >{{ (block.items ?? [])[0]?.caption }}</p>
            </template>
          </div>
        </div>

        <!-- Inline keyboard — attaches to the step (the last non-album block, Decision 2), so it renders
             under the whole stack. Labels are plain text ({{ }}); url buttons are display-only here. -->
        <div
          v-if="buttons.length > 0"
          data-test="funnel-preview-buttons"
          class="mt-3 flex flex-col gap-1"
        >
          <span
            v-for="(btn, btnIndex) in buttons"
            :key="btnIndex"
            class="rounded-md border border-blue-200 bg-blue-50 px-3 py-1.5 text-center text-blue-700"
          >{{ btn.label }}</span>
        </div>
      </template>
    </template>
  </aside>
</template>
