<script setup lang="ts">
import { Handle, Position, useVueFlow } from '@vue-flow/core'
import type { Button, ContentBlock, FunnelStep, FunnelTrigger } from '~/types/funnel'
import type { CanvasNodeData } from '~/composables/useFunnelCanvas'

// Custom Vue Flow node renderer for the funnel canvas (18-funnel-canvas, Task 5; redesigned in handles-redesign).
// Registered under the node-type slots of FunnelCanvas.client.vue (`#node-step` / `#node-trigger` /
// `#node-start` / `#node-note`).
//
// LIGHTWEIGHT body by design (§8.4 / Risk: performance): a PURE-FRONTEND in-card preview built from
// `props.step` ONLY (card-preview) — NO backend call, NO store, NO watchers. The expensive route-coupled
// FunnelMessagePreview (debounced backend call per step) is deliberately NOT used here. Output is text-only via
// {{ }} — never v-html (matches FunnelMessagePreview / FunnelStepForm strict no-v-html convention). A media
// thumbnail is bound via :src ONLY for a validated http(s) URL (anti-XSS — javascript:/data:/file_id fall back
// to a type label), mirroring FunnelMessagePreview's scheme guard.
//
// OUTPUT SEMANTICS (card-preview — matches the engine): a MESSAGE step's ACTIVE outputs depend on whether it
// has CALLBACK buttons:
//   • NO callback buttons → only `next` ("Далі") fires; `timeoutTargetStepId` is inert → only the `next` row.
//   • HAS callback buttons → the step WAITS: each callback button fires its own edge, OR the timeout elapses →
//     button rows + the `timeout` ("Таймаут") row; `next` is dead → no `next` row.
// SAFETY (no orphaned edges): the gating is overridden to KEEP a handle whenever its model field is already set
// (next != null / timeoutTargetStepId != null). buildEdges emits an edge ONLY for a non-null field, so this
// guarantees every drawn edge still has a visible source handle (a hidden source handle = a dangling edge).
//
// HANDLE DESIGN (handles-redesign — UX feedback):
//  • INPUT: there is NO separate input dot. The WHOLE card is the drop zone — a SINGLE node-covering target
//    Handle (`funnel-handle--card-target`) sits over the card. It is `pointer-events:none` at rest (so it
//    never swallows a node click/move) and only becomes droppable + highlighted WHILE a connection drag is in
//    progress (driven by Vue Flow's reactive `connectionStartHandle`). The covering handle carries Vue Flow's
//    `nodrag` class, so it never blocks node-body dragging.
//  • OUTPUTS: a dedicated SECTION below the header (separated by a thin divider). ONE ROW per output, each row
//    holding the short label (left-aligned) + a small MONOCHROME connector dot anchored on the card's RIGHT
//    BORDER, vertically centered to THAT row. The card GROWS IN HEIGHT with the output count — the outputs
//    have their OWN space and never overlap the header title (node-layout-fix — UX feedback).
//  • Hover affordance uses ONLY transform: scale() + box-shadow (no width/height/top/left change → no jitter).
//
// Typed OUTPUT handle IDS are UNCHANGED (the connect path depends on them): `next`, `btn:<index>` per CALLBACK
// button, `timeout`, and `entry` for the start/trigger entry. Only their POSITION/STYLE/LABELS changed.

const props = defineProps<{
  // Vue Flow passes the node id + data through the `#node-<type>` slot binding.
  id: string
  data: CanvasNodeData
  // The originating model object, threaded through `data` consumers via the parent. We read it from the
  // model arrays the parent passes alongside via props so the node body can show a localized label.
  step?: FunnelStep | null
  trigger?: FunnelTrigger | null
  noteText?: string | null
  // Broken visual state for an outgoing field that dangles (driven by the canvas).
  broken?: boolean
}>()

const { t } = useI18n()

// A connection drag is in progress when Vue Flow has recorded a source start handle. This single shared store
// field (the parent owns the one useVueFlow instance; this resolves to the SAME store inside the provider)
// drives the whole-card droppable affordance: the card-covering target handle only captures the drop + lights
// up while connecting, so at rest it never swallows a node click or a node-body drag.
const { connectionStartHandle } = useVueFlow()
const isConnecting = computed(() => connectionStartHandle.value != null)

// CALLBACK buttons in declaration order — URL buttons carry no edge (no targetStepId), so they are excluded.
// buttonIndex is the position WITHIN this filtered list, matching useFunnelCanvas.callbackButtons() exactly
// (forward+reverse keying). URL buttons are surfaced in the preview WITHOUT a handle, never given a btn handle.
const callbackButtons = computed<Button[]>(() =>
  (props.step?.buttons ?? []).filter((b) => b.type === 'callback'),
)

const hasCallbackButtons = computed(() => callbackButtons.value.length > 0)
const isMessageStep = computed(() => props.step?.stepType === 'MESSAGE')

// `next` ("Далі") fires when the step has NO callback buttons (only a MESSAGE step can carry buttons, so every
// non-MESSAGE step always has an active `next`). KEEP it if step.next is already set so an existing next edge
// never loses its source handle (no orphaned edge — buildEdges emits e:next only for a non-null next).
const hasNextHandle = computed(
  () => !hasCallbackButtons.value || props.step?.next != null,
)

// `timeout` ("Таймаут") fires only when the step HAS callback buttons (the step parks). KEEP it if
// timeoutTargetStepId is already set so an existing timeout edge never loses its source handle.
const hasTimeoutHandle = computed(
  () => isMessageStep.value && (hasCallbackButtons.value || props.step?.timeoutTargetStepId != null),
)

// A step node carries `next` + per-button + (MESSAGE) timeout output handles.
const isStep = computed(() => props.data.kind === 'step')
// Start / trigger nodes carry the single `entry` output handle.
const isEntry = computed(() => props.data.kind === 'start' || props.data.kind === 'trigger')
const isNote = computed(() => props.data.kind === 'note')

// Truncate a long output label so the stacked row stays compact (16 chars + …).
const LABEL_MAX = 16
function truncate(label: string): string {
  return label.length > LABEL_MAX ? `${label.slice(0, LABEL_MAX)}…` : label
}

// The ordered output descriptors for this node: stable handle id + localized label. The id is UNCHANGED from
// the original scheme (next / btn:<i> / timeout / entry) — only the rendering (stacked + labeled) is new.
interface OutputHandle {
  id: string
  label: string
  testId: string
}
const outputs = computed<OutputHandle[]>(() => {
  if (isEntry.value) {
    return [{ id: 'entry', label: t('funnels.canvas.handle.entry'), testId: 'funnel-canvas-handle-entry' }]
  }
  if (!isStep.value) return []
  const list: OutputHandle[] = []
  // `next` ("Далі") only when active (no callback buttons) OR already wired (keep-if-set safety).
  if (hasNextHandle.value) {
    list.push({ id: 'next', label: t('funnels.canvas.handle.outputNext'), testId: 'funnel-canvas-handle-next' })
  }
  callbackButtons.value.forEach((btn, i) => {
    list.push({
      id: `btn:${i}`,
      label: truncate(btn.label ?? ''),
      testId: `funnel-canvas-handle-button-${i}`,
    })
  })
  if (hasTimeoutHandle.value) {
    list.push({ id: 'timeout', label: t('funnels.canvas.handle.outputTimeout'), testId: 'funnel-canvas-handle-timeout' })
  }
  return list
})

// Localized node title — type label (step / trigger) or the start / note label. Plain text via {{ }}.
const title = computed<string>(() => {
  if (props.data.kind === 'start') return t('funnels.canvas.startNode')
  if (props.data.kind === 'note') return t('funnels.canvas.note')
  if (props.data.kind === 'trigger' && props.trigger) {
    return t(`funnels.trigger.type.${props.trigger.triggerType}`)
  }
  if (props.data.kind === 'step' && props.step) {
    return t(`funnels.steps.type.${props.step.stepType}`)
  }
  return ''
})

const exitBadge = computed(() => props.data.exitBadge ?? null)

// ── In-card MESSAGE preview (PURE FRONTEND — card-preview) ───────────────────────────────────────────────
// http(s)-only scheme guard for the media thumbnail. Mirrors FunnelMessagePreview.isHttpUrl: rejects
// file:// / data: / javascript: AND opaque Telegram file_id tokens (no scheme). A URL that fails this guard is
// NOT bound to <img :src> (anti-XSS) — it falls back to a media-type label.
const HTTP_URL_RE = /^https?:\/\//i
function isHttpUrl(url: string | null | undefined): boolean {
  return typeof url === 'string' && HTTP_URL_RE.test(url.trim())
}

// Truncate the preview text to ~90 chars + … so the card body stays compact. Rendered via {{ }} (escaped).
const TEXT_PREVIEW_MAX = 90
const previewText = computed<string | null>(() => {
  if (!isMessageStep.value) return null
  const block = (props.step?.blocks ?? []).find((b) => b.type === 'TEXT' && (b.text ?? '').trim() !== '')
  const text = block?.text ?? ''
  if (text === '') return null
  return text.length > TEXT_PREVIEW_MAX ? `${text.slice(0, TEXT_PREVIEW_MAX)}…` : text
})

// The first MEDIA block (image/video/audio/file/album) of a MESSAGE step, for the thumbnail/icon preview.
const MEDIA_TYPES = new Set(['IMAGE', 'VIDEO', 'AUDIO', 'FILE', 'ALBUM'])
const previewMediaBlock = computed<ContentBlock | null>(() => {
  if (!isMessageStep.value) return null
  return (props.step?.blocks ?? []).find((b) => MEDIA_TYPES.has(b.type)) ?? null
})

// The thumbnail src — ONLY a validated http(s) URL (IMAGE block, or the first ALBUM item). null → icon fallback.
const previewThumbUrl = computed<string | null>(() => {
  const block = previewMediaBlock.value
  if (!block) return null
  const url = block.type === 'ALBUM' ? block.items?.[0]?.mediaUrl ?? null : block.mediaUrl ?? null
  return isHttpUrl(url) ? (url as string) : null
})

// The media block's caption (escaped via {{ }}). For ALBUM the caption lives on the first item (Decision 5).
const previewCaption = computed<string | null>(() => {
  const block = previewMediaBlock.value
  if (!block) return null
  const cap = block.type === 'ALBUM' ? block.items?.[0]?.caption ?? null : block.caption ?? null
  return cap && cap.trim() !== '' ? cap : null
})

// Localized media-type label for the icon fallback (no http(s) thumbnail). Uses the canvas preview keys.
const previewMediaLabel = computed<string | null>(() => {
  const block = previewMediaBlock.value
  if (!block) return null
  return t(`funnels.canvas.preview.mediaType.${block.type}`)
})

// URL buttons — surfaced in the preview WITHOUT a handle (they carry no edge). Distinct from callback buttons.
const urlButtons = computed<Button[]>(() =>
  (props.step?.buttons ?? []).filter((b) => b.type === 'url'),
)

// ── In-card SET_KEYBOARD / CLEAR_KEYBOARD preview (PURE FRONTEND — keyboard-preview-fix) ────────────────────
// Mirrors FunnelMessagePreview's keyboard branch, but built from props.step ONLY (no backend call). SET_KEYBOARD
// shows the mandatory text (escaped, truncated) + the reply-keyboard button labels as small chips/rows;
// CLEAR_KEYBOARD shows a short "keyboard removed" indicator. ALL user content (text, labels) via {{ }} — never
// v-html (stored-XSS guard, OWASP A03; the labels carry no browser-safe escaping).
const isSetKeyboardStep = computed(() => props.step?.stepType === 'SET_KEYBOARD')
const isClearKeyboardStep = computed(() => props.step?.stepType === 'CLEAR_KEYBOARD')
const isKeyboardStep = computed(() => isSetKeyboardStep.value || isClearKeyboardStep.value)

// The keyboard step's mandatory text, truncated + escaped via {{ }} (same cap as the MESSAGE preview text).
const keyboardPreviewText = computed<string | null>(() => {
  if (!isKeyboardStep.value) return null
  const text = props.step?.keyboardText ?? ''
  if (text.trim() === '') return null
  return text.length > TEXT_PREVIEW_MAX ? `${text.slice(0, TEXT_PREVIEW_MAX)}…` : text
})

// SET_KEYBOARD button-label rows for the chip mock — each row's button texts, truncated + escaped via {{ }}.
// Empty rows / empty labels are dropped so a half-built draft renders nothing rather than blank chips.
const keyboardPreviewRows = computed<string[][]>(() => {
  if (!isSetKeyboardStep.value) return []
  return (props.step?.keyboardRows ?? [])
    .map((row) =>
      (row?.buttons ?? [])
        .map((b) => (b?.text ?? '').trim())
        .filter((label) => label !== '')
        .map((label) => truncate(label)),
    )
    .filter((row) => row.length > 0)
})
</script>

<template>
  <div
    data-test="funnel-canvas-node"
    :data-node-kind="data.kind"
    :data-node-id="id"
    :class="[
      'funnel-canvas-node',
      { 'funnel-canvas-node--broken': broken, 'funnel-canvas-node--droppable': isConnecting && !isEntry && !isNote },
    ]"
  >
    <!-- INPUT = the WHOLE card. A single node-covering target Handle (NO separate input dot). At rest it is
         pointer-events:none (never swallows a click / node-body drag); while a connection drag is in progress
         it becomes droppable + the card lights up. Start/trigger entry nodes are pure sources, and a note has
         no edges — so neither gets a target handle.
         The handle carries a STABLE id "in" (connect-precision): with ConnectionMode.Strict an unnamed target
         handle competed ambiguously with always-on source dots near a card edge; a real, named target handle is
         the single unambiguous drop. The connect path still resolves the destination by NODE id (conn.target),
         so targetHandle being "in" instead of null is inert — handleConnect/resolveEdgeRef never read it. -->
    <Handle
      v-if="!isEntry && !isNote"
      id="in"
      class="funnel-handle funnel-handle--card-target"
      data-test="funnel-canvas-handle-target"
      data-handle-id="in"
      type="target"
      :position="Position.Left"
      :title="t('funnels.canvas.handle.input')"
    />

    <!-- HEADER: the localized type / title on its OWN full-width line. Nothing is positioned over it, so the
         title is never covered or truncated (node-layout-fix). Lightweight by design — NO FunnelMessagePreview
         here (perf, §8.4). -->
    <div class="funnel-canvas-node__header" data-test="funnel-canvas-node-header">
      <span class="funnel-canvas-node__title" data-test="funnel-canvas-node-title">{{ title }}</span>
    </div>

    <!-- IN-CARD MESSAGE PREVIEW (card-preview) — PURE FRONTEND, built from props.step ONLY (no backend/store).
         ALL user content is rendered text-only via {{ }} (escaped) — NEVER v-html (stored-XSS guard, OWASP A03).
         The media thumbnail uses :src ONLY for a validated http(s) URL; any other scheme falls back to a label.
         URL buttons are shown here WITHOUT a handle (callback buttons get handle rows in the outputs section). -->
    <div
      v-if="isStep && (previewText || previewMediaBlock || urlButtons.length > 0)"
      class="funnel-canvas-node__preview"
      data-test="funnel-canvas-node-preview"
    >
      <p
        v-if="previewText"
        class="funnel-canvas-node__preview-text"
        data-test="funnel-canvas-preview-text"
      >{{ previewText }}</p>

      <template v-if="previewMediaBlock">
        <!-- http(s) thumbnail only (scheme-validated); else a neutral media-type label. -->
        <img
          v-if="previewThumbUrl"
          :src="previewThumbUrl"
          :alt="previewMediaLabel ?? ''"
          referrerpolicy="no-referrer"
          class="funnel-canvas-node__preview-thumb"
          data-test="funnel-canvas-preview-thumb"
        >
        <span
          v-else
          class="funnel-canvas-node__preview-media-fallback"
          data-test="funnel-canvas-preview-media-fallback"
        >{{ previewMediaLabel }}</span>
        <p
          v-if="previewCaption"
          class="funnel-canvas-node__preview-text"
          data-test="funnel-canvas-preview-caption"
        >{{ previewCaption }}</p>
      </template>

      <!-- URL buttons — display-only chips, NO output handle (they carry no edge). -->
      <span
        v-for="(btn, i) in urlButtons"
        :key="`url:${i}`"
        class="funnel-canvas-node__preview-url-button"
        data-test="funnel-canvas-preview-url-button"
      >{{ t('funnels.canvas.preview.urlButton') }} · {{ truncate(btn.label ?? '') }}</span>
    </div>

    <!-- IN-CARD KEYBOARD PREVIEW (keyboard-preview-fix) — PURE FRONTEND, built from props.step ONLY.
         SET_KEYBOARD: the mandatory text (escaped, truncated) + the reply-keyboard button labels as chip rows.
         CLEAR_KEYBOARD: a short "keyboard removed" indicator. ALL user content via {{ }} (escaped) — NEVER
         v-html (stored-XSS guard, OWASP A03; labels carry no browser-safe escaping). Matches the MESSAGE
         in-card preview styling. -->
    <div
      v-if="isStep && isSetKeyboardStep && (keyboardPreviewText || keyboardPreviewRows.length > 0)"
      class="funnel-canvas-node__preview"
      data-test="funnel-canvas-node-keyboard-preview"
    >
      <p
        v-if="keyboardPreviewText"
        class="funnel-canvas-node__preview-text"
        data-test="funnel-canvas-preview-keyboard-text"
      >{{ keyboardPreviewText }}</p>

      <div
        v-if="keyboardPreviewRows.length > 0"
        class="funnel-canvas-node__preview-keyboard"
        data-test="funnel-canvas-preview-keyboard"
      >
        <div
          v-for="(row, rowIndex) in keyboardPreviewRows"
          :key="rowIndex"
          class="funnel-canvas-node__preview-keyboard-row"
          :data-test="`funnel-canvas-preview-keyboard-row-${rowIndex}`"
        >
          <span
            v-for="(label, labelIndex) in row"
            :key="labelIndex"
            class="funnel-canvas-node__preview-keyboard-key"
            data-test="funnel-canvas-preview-keyboard-key"
          >{{ label }}</span>
        </div>
      </div>
    </div>

    <!-- CLEAR_KEYBOARD — a compact "keyboard removed" indicator (no text/rows to show). -->
    <div
      v-if="isStep && isClearKeyboardStep"
      class="funnel-canvas-node__preview"
      data-test="funnel-canvas-node-keyboard-preview"
    >
      <span
        class="funnel-canvas-node__preview-keyboard-cleared"
        data-test="funnel-canvas-preview-keyboard-cleared"
      >{{ t('funnels.canvas.preview.clearKeyboard') }}</span>
    </div>

    <!-- Note body — author free text. Rendered text-only via {{ }}, NEVER v-html (Decision 7 stored-XSS
         guard, mirrors FunnelMessagePreview/FunnelStepForm). An injection payload shows as escaped text. -->
    <span
      v-if="isNote && noteText"
      data-test="funnel-canvas-note-text"
      class="funnel-canvas-node__note-text"
    >{{ noteText }}</span>

    <!-- Cross-funnel SUBSCRIBE exit badge (Decision 11) — surfaced on the node, NOT as an outgoing edge. -->
    <span
      v-if="exitBadge"
      data-test="funnel-canvas-exit-badge"
      class="funnel-canvas-node__exit-badge"
    >{{ t('funnels.canvas.exitBadge') }}</span>

    <!-- OUTPUTS SECTION: separated from the header by a thin divider; ONE ROW per output. Each row reserves its
         own vertical space (the card grows in height with the output count → no overlap with the header). The
         label is left-aligned inside the card; the monochrome connector dot (the source Handle) is anchored on
         the card's RIGHT BORDER, vertically centered to its row, so the edge renders from the dot. Each id maps
         to a mapping-layer EdgeRef field via the @connect handler (ids UNCHANGED). -->
    <div v-if="outputs.length > 0" class="funnel-canvas-node__outputs" data-test="funnel-canvas-node-outputs">
      <div
        v-for="out in outputs"
        :key="out.id"
        class="funnel-output-row"
        data-test="funnel-canvas-output-row"
      >
        <span class="funnel-output-row__label" data-test="funnel-canvas-output-label">{{ out.label }}</span>
        <Handle
          :id="out.id"
          class="funnel-handle funnel-handle--output"
          :data-test="out.testId"
          :data-handle-id="out.id"
          type="source"
          :position="Position.Right"
          :title="out.label"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.funnel-canvas-node {
  position: relative;
  min-width: 180px;
  border: 1px solid #cbd5e1;
  border-radius: 0.375rem;
  background: #ffffff;
  /* No card padding: each SECTION owns its own padding so the output dot can sit flush on the right border
     (right:0 of a row == the card's border). The card grows in height with the number of output rows. */
  padding: 0;
  font-size: 0.875rem;
}

.funnel-canvas-node--broken {
  border-color: #f87171;
  box-shadow: 0 0 0 2px rgba(248, 113, 113, 0.35);
}

/* Whole-card drop affordance: while a connection drag is in progress every connectable card lights up as a
   droppable target. Box-shadow + background only — no geometry change (no reflow/jitter). */
.funnel-canvas-node--droppable {
  border-color: #6366f1;
  background: #eef2ff;
  box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.45);
}

/* ── Header ───────────────────────────────────────────────────────────────────────────────────────────────
   The title on its own full-width line. Nothing overlaps it, so it is never covered or truncated. */
.funnel-canvas-node__header {
  padding: 0.5rem 0.75rem;
}

.funnel-canvas-node__title {
  font-weight: 600;
  color: #374151;
}

.funnel-canvas-node__note-text {
  display: block;
  padding: 0 0.75rem 0.5rem;
  white-space: pre-wrap;
  color: #475569;
}

.funnel-canvas-node__exit-badge {
  display: inline-block;
  margin: 0 0.75rem 0.5rem;
  border-radius: 0.25rem;
  background: #fef3c7;
  padding: 0.05rem 0.4rem;
  font-size: 0.75rem;
  color: #92400e;
}

/* ── In-card MESSAGE preview (card-preview) ───────────────────────────────────────────────────────────────
   A compact, cheap body rendered from props.step ONLY. Text/captions/url-button labels are {{ }} (escaped);
   the thumbnail is :src-bound only for a validated http(s) URL (else a neutral media-type label). */
.funnel-canvas-node__preview {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  padding: 0 0.75rem 0.5rem;
}

.funnel-canvas-node__preview-text {
  margin: 0;
  overflow: hidden;
  font-size: 0.75rem;
  line-height: 1.35;
  color: #475569;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.funnel-canvas-node__preview-thumb {
  max-height: 64px;
  width: auto;
  max-width: 100%;
  border-radius: 0.25rem;
  object-fit: contain;
}

.funnel-canvas-node__preview-media-fallback {
  display: inline-block;
  align-self: flex-start;
  border: 1px dashed #cbd5e1;
  border-radius: 0.25rem;
  padding: 0.1rem 0.4rem;
  font-size: 0.75rem;
  color: #64748b;
}

.funnel-canvas-node__preview-url-button {
  align-self: flex-start;
  border: 1px solid #bfdbfe;
  border-radius: 0.25rem;
  background: #eff6ff;
  padding: 0.05rem 0.4rem;
  font-size: 0.7rem;
  color: #1d4ed8;
}

/* ── In-card keyboard preview (keyboard-preview-fix) ──────────────────────────────────────────────────────
   SET_KEYBOARD reply-keyboard mock: neutral-gray chip rows under the text, mirroring the Telegram bottom
   keyboard. CLEAR_KEYBOARD: a small neutral "keyboard removed" indicator. Labels are {{ }} (escaped). */
.funnel-canvas-node__preview-keyboard {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.funnel-canvas-node__preview-keyboard-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.2rem;
}

.funnel-canvas-node__preview-keyboard-key {
  flex: 1 1 auto;
  border: 1px solid #d1d5db;
  border-radius: 0.25rem;
  background: #f3f4f6;
  padding: 0.1rem 0.4rem;
  font-size: 0.7rem;
  text-align: center;
  color: #374151;
}

.funnel-canvas-node__preview-keyboard-cleared {
  align-self: flex-start;
  border: 1px dashed #cbd5e1;
  border-radius: 0.25rem;
  padding: 0.1rem 0.4rem;
  font-size: 0.75rem;
  color: #64748b;
}

/* ── Outputs section ──────────────────────────────────────────────────────────────────────────────────────
   A dedicated block below the header, separated by a thin divider. ONE ROW per output, stacked in normal flow
   so the card GROWS IN HEIGHT with the output count (the outputs have their OWN space — no overlap with the
   header). The label is left-aligned inside the card; the connector dot is absolutely anchored on the card's
   RIGHT BORDER (right:0 of the row) and vertically centered to its row, so the edge renders from the dot. */
.funnel-canvas-node__outputs {
  border-top: 1px solid #e2e8f0;
}

.funnel-output-row {
  position: relative; /* the dot anchors to THIS row → it lines up with this row's label on the right border */
  display: flex;
  align-items: center;
  padding: 0.3125rem 0.75rem;
}

.funnel-output-row + .funnel-output-row {
  border-top: 1px solid #f1f5f9; /* light separator between stacked rows */
}

.funnel-output-row__label {
  white-space: nowrap;
  font-size: 0.75rem;
  line-height: 1.3;
  color: #475569;
}

/* ── Connection handles (handles-redesign) ───────────────────────────────────────────────────────────────
   MONOCHROME: every handle uses one neutral/accent color (no per-kind color → no confusion, no next/timeout
   color flicker). :deep() is required: <Handle> renders its own .vue-flow__handle child element outside this
   component's scoped-style hash, so the class we pass through is only reachable via :deep(). */
.funnel-canvas-node :deep(.funnel-handle--output) {
  /* Anchored to its OWN row on the card's RIGHT BORDER, vertically centered to the row → the dot lines up with
     this row's label. translateX centers the circle ON the border (half straddles outside) so edges render
     cleanly from the dot. */
  position: absolute;
  top: 50%;
  right: 0;
  width: 12px;
  height: 12px;
  border: 2px solid #ffffff;
  border-radius: 9999px;
  background: #6366f1; /* single monochrome accent for ALL outputs */
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.25);
  cursor: crosshair;
  pointer-events: all;
  transform: translate(50%, -50%);
  transition:
    transform 0.1s ease,
    box-shadow 0.1s ease;
}

/* Grab affordance: scale + shadow ONLY — never width/height/top/right → no layout reflow, no jitter. The base
   border-anchoring translate is preserved so the dot scales in place on the right border (no jump). */
.funnel-canvas-node :deep(.funnel-handle--output:hover),
.funnel-canvas-node :deep(.funnel-handle--output.vue-flow__handle-connecting) {
  transform: translate(50%, -50%) scale(1.4);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.35);
}

/* Whole-card target: a transparent handle covering the entire node. `nodrag` (Vue Flow default on handles)
   keeps node-body dragging working; pointer-events:none at rest keeps node clicks working. It only captures
   the drop WHILE a connection is in progress (the parent toggles the droppable card state via .--droppable). */
.funnel-canvas-node :deep(.funnel-handle--card-target) {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  transform: none;
  border: none;
  border-radius: 0.375rem;
  background: transparent;
  pointer-events: none;
}
.funnel-canvas-node--droppable :deep(.funnel-handle--card-target) {
  pointer-events: all;
}
</style>
