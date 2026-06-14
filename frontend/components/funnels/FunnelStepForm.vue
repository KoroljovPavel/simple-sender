<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'
import { CURRENT_DATE_TOKEN } from '~/types/funnel'
import type { BlockType, ContentBlock, DelayUnit, FunnelStep, KeyboardRow, MediaItem, StepType } from '~/types/funnel'
import type { CustomFieldDefinition, CustomFieldType, Tag } from '~/types/subscriber'
import SearchableSelect from '~/components/funnels/SearchableSelect.vue'
import { useFunnelsStore } from '~/stores/funnels'
import { storeToRefs } from 'pinia'

// Shared per-type step form used by BOTH AddStepDialog and EditStepDialog (Task 10 endorses extracting
// the common form). The picker `StepType` lives OUTSIDE the form so the computed() schema can depend on
// it (same idiom as AddCustomFieldDialog) — switching type re-runs validation against the right rules.
// All client validation MIRRORS backend FunnelService.validateSteps byte-for-byte (Decision 9/12). The
// MESSAGE composer (Task 7) is an ordered ContentBlock[] (a local reactive array, validated by hand like
// the keyboard rows): 1..10 blocks, per-type required fields, media source = http(s) URL or opaque file_id,
// album 2..10 items + type-mixing (Decision 5), the inline keyboard only on the last non-album block
// (Decision 2), over-length text/caption a soft warning (not blocking). delayValue >= 1, tagSlug
// ^[a-z0-9_-]{1,32}$, customFieldKey non-empty.
// For SET_CUSTOM_FIELD the value widget + validation additionally mirror the field's TYPE (the same
// per-type inputs the subscriber custom-fields tab uses), resolved from the project's definitions.
// siblingSteps = the OTHER steps of the funnel (threaded down from the page via Add/EditStepDialog) so a
// MESSAGE keyboard callback button can target another step by its stable id. Empty/absent otherwise.
// hideTargetPickers (canvas opt-in, 18-funnel-canvas Task 6): when true, the three target SearchableSelects
// (callback button targetStepId, timeout target, SUBSCRIBE entry) are hidden — on the canvas the EDGES own
// those targets, so the form must not double-edit them. withDefaults is REQUIRED here: Vue coerces an absent
// declared Boolean prop to `false`, but a plain optional `hideTargetPickers?: boolean` reads as `undefined`
// in the template (truthy guards still work, but withDefaults makes the false default explicit and matches
// SearchableSelect's showValue idiom). The default is `false`, so list/dialog callers are unaffected and the
// emitted FunnelStep is byte-for-byte identical — the hidden values still flow through on submit from
// `props.initial`-seeded state (menuButtons / menuTimeoutTarget / subscribeEntryStepId), never dropped/zeroed.
const props = withDefaults(
  defineProps<{
    initial?: FunnelStep | null
    submitLabel: string
    siblingSteps?: FunnelStep[]
    hideTargetPickers?: boolean
  }>(),
  { hideTargetPickers: false },
)
const emit = defineEmits<{ submit: [step: FunnelStep]; cancel: [] }>()

const { t } = useI18n()
const route = useRoute()
const projectId = computed(() => String(route.params.projectId))

const STEP_TYPES: StepType[] = [
  'MESSAGE',
  'DELAY',
  'ADD_TAG',
  'REMOVE_TAG',
  'SET_CUSTOM_FIELD',
  'EMIT_EVENT',
  'SUBSCRIBE_TO_FUNNEL',
  'SET_KEYBOARD',
  'CLEAR_KEYBOARD',
]
const PARSE_MODES = ['', 'HTML', 'MarkdownV2'] as const
const DELAY_UNITS: DelayUnit[] = ['MIN', 'HOUR', 'DAY']

// ── MESSAGE composer constants (mirror backend FunnelService.validateMessage byte-for-byte) ──────────
// Block types the author can add. The album add-block sub-picker reuses MEDIA_BLOCK_TYPES (its items each
// carry a media kind ∈ IMAGE/VIDEO/AUDIO/FILE — Decision 5).
const BLOCK_TYPES: BlockType[] = ['TEXT', 'IMAGE', 'VIDEO', 'AUDIO', 'FILE', 'ALBUM']
const MEDIA_BLOCK_TYPES: BlockType[] = ['IMAGE', 'VIDEO', 'AUDIO', 'FILE']
const MIN_BLOCKS = 1
const MAX_BLOCKS = 10
const MIN_ALBUM_ITEMS = 2
const MAX_ALBUM_ITEMS = 10
// Over-length WARN limits (Decision 3): a soft inline warning, NOT a hard block — the engine trims+WARNs
// at send time. Mirrors FunnelService.TEXT_WARN_LIMIT / CAPTION_WARN_LIMIT.
const TEXT_WARN_LIMIT = 4096
const CAPTION_WARN_LIMIT = 1024
// A media source is EITHER an http(s) URL OR an opaque Telegram file_id. Disambiguation mirrors backend
// requireMediaSource/looksLikeUrl: a value with a URI scheme prefix (^[A-Za-z][A-Za-z0-9+.-]*:) is treated
// as a URL and held to the strict http(s) rule; a bare token (no scheme) is accepted as an opaque file_id.
const URI_SCHEME_PREFIX_RE = /^[A-Za-z][A-Za-z0-9+.-]*:/

// CANONICAL — MUST match backend requireTagSlug @Pattern byte-for-byte (FunnelService).
const TAG_SLUG_RE = /^[a-z0-9_-]{1,32}$/
// CANONICAL event-name slug — MUST mirror the backend event_name @Pattern ^[A-Za-z0-9_-]{1,64}$ (Decision 4).
const EVENT_NAME_RE = /^[A-Za-z0-9_-]{1,64}$/
// requireImageUrl: lower-cased URL must start with http:// or https://.
const IMAGE_URL_RE = /^https?:\/\//i
// Native <input type="date"> emits 'YYYY-MM-DD'; mirror the prefix the backend DATE validator accepts.
const DATE_ONLY_RE = /^\d{4}-\d{2}-\d{2}/

// ── MENU constants (mirror backend validateSteps / Decision 10) ──────────────────────────────────────
// Up to 8 buttons, one per row; each label ≤64 chars (UX proxy for the server callback_data byte limit);
// every MENU needs ≥1 callback button; URL buttons must be http(s).
const MENU_MAX_BUTTONS = 8
const MENU_LABEL_MAX = 64
// Sentinel for the "End the funnel" target inside the picker ONLY. It is mapped to targetStepId=null on
// emit — an empty string "" would be a non-null id matching no step → server 422 funnel_broken_edge.
const MENU_END_TARGET = '__END__'

// ── SUBSCRIBE_TO_FUNNEL constants (Phase 5 / Decision 5) ─────────────────────────────────────────────
// Sentinel for "enter the target funnel from its first step" inside the entry-step picker ONLY. Mapped to
// targetEntryStepId=null on emit — the backend reads null as "from the start". Distinct from a real step id.
const SUBSCRIBE_ENTRY_START = '__START__'

// ── SET_KEYBOARD / CLEAR_KEYBOARD constants (16-persistent-keyboard / Decision 6) ────────────────────
// MUST mirror backend FunnelService.validateSetKeyboard/validateClearKeyboard byte-for-byte. Unlike the
// composer's TEXT_WARN_LIMIT (a soft warn), the 4096 cap here is a HARD block — the keyboard text is a
// single mandatory message that the backend rejects when blank or over the cap.
const KEYBOARD_TEXT_MAX = 4096
const KEYBOARD_MAX_ROWS = 10
const KEYBOARD_MAX_BUTTONS_PER_ROW = 4
// Button text cap = the keyword cap (64), so a button text can always be an exact keyword (user-spec).
const KEYBOARD_BUTTON_TEXT_MAX = 64

const selectedType = ref<StepType>(props.initial?.stepType ?? 'MESSAGE')

// ── MESSAGE composer block sub-editor ────────────────────────────────────────────────────────────────
// A local reactive array of content blocks (NOT vee-validate fields), validated by hand on submit — the
// same idiom as the MENU button rows. Each block is a flat editable shape mirroring ContentBlock; only the
// fields meaningful for its `type` are read on submit. Album items carry their own media kind (Decision 5).
type AlbumItemRow = { uid: number; type: BlockType; mediaUrl: string; caption: string }
type BlockRow = {
  // Stable per-row id used as the v-for :key so reordering preserves component identity (an index key
  // would re-key on move and break the teleported keyboard's fragment removal — nextSibling-of-null).
  uid: number
  type: BlockType
  text: string
  parseMode: string
  mediaUrl: string
  caption: string
  items: AlbumItemRow[]
}
let uidSeq = 0
function nextUid(): number {
  return ++uidSeq
}
function blankBlock(type: BlockType): BlockRow {
  return {
    uid: nextUid(),
    type,
    text: '',
    parseMode: '',
    mediaUrl: '',
    caption: '',
    // Albums seed with the minimum of 2 image items so the author starts from a valid shape.
    items:
      type === 'ALBUM'
        ? [blankAlbumItem(), blankAlbumItem()]
        : [],
  }
}
function blankAlbumItem(): AlbumItemRow {
  return { uid: nextUid(), type: 'IMAGE', mediaUrl: '', caption: '' }
}
// Seed from an edited MESSAGE step's blocks; otherwise one empty TEXT block so the author has a start.
function initialBlocks(): BlockRow[] {
  const existing = props.initial?.stepType === 'MESSAGE' ? props.initial?.blocks : null
  if (existing && existing.length > 0) {
    return existing.map((b) => ({
      uid: nextUid(),
      type: b.type,
      text: b.text ?? '',
      parseMode: b.parseMode ?? '',
      mediaUrl: b.mediaUrl ?? '',
      caption: b.caption ?? '',
      items:
        b.type === 'ALBUM' && b.items
          ? b.items.map((it) => ({
              uid: nextUid(),
              type: it.type,
              mediaUrl: it.mediaUrl ?? '',
              caption: it.caption ?? '',
            }))
          : [],
    }))
  }
  return [blankBlock('TEXT')]
}
const blocks = ref<BlockRow[]>(initialBlocks())
// The block-type picker for the "Add block" button.
const newBlockType = ref<BlockType>('TEXT')
// Touched on submit so per-block errors only render after the author tries to save (parity with menuTouched).
const composerTouched = ref(false)

function addBlock() {
  if (blocks.value.length >= MAX_BLOCKS) return
  blocks.value.push(blankBlock(newBlockType.value))
}
function removeBlock(index: number) {
  blocks.value.splice(index, 1)
}
function moveBlock(from: number, to: number) {
  if (to < 0 || to >= blocks.value.length) return
  const arr = blocks.value
  const [moved] = arr.splice(from, 1)
  arr.splice(to, 0, moved)
}
function addAlbumItem(blockIndex: number) {
  const items = blocks.value[blockIndex].items
  if (items.length >= MAX_ALBUM_ITEMS) return
  items.push(blankAlbumItem())
}
function removeAlbumItem(blockIndex: number, itemIndex: number) {
  blocks.value[blockIndex].items.splice(itemIndex, 1)
}

// The inline keyboard attaches to the LAST block ONLY when it is non-album (Decision 2) — so the buttons
// render after the trailing content. An album as the last block hides the section (the author is hinted to
// add a text tail). This mirrors backend validateMessage, which rejects buttons on an album last block.
const buttonsAllowed = computed(
  () => blocks.value.length > 0 && blocks.value[blocks.value.length - 1].type !== 'ALBUM',
)

// Strict media-source check (mirror backend requireMediaSource). A scheme-prefixed value must be http(s);
// a bare token is an opaque file_id (accepted). Returns true when the value is a valid source.
function mediaSourceValid(raw: string): boolean {
  const v = raw.trim()
  if (!v) return false
  // A leading-whitespace original value is suspicious → treat as a URL (which then fails the strict check).
  const looksUrl = v !== raw || URI_SCHEME_PREFIX_RE.test(v)
  if (!looksUrl) return true // opaque file_id
  return IMAGE_URL_RE.test(v) // must be http(s)
}

// MENU button sub-editor: a local reactive array (not a single vee-validate field), validated by hand on
// submit. Each row keeps an internal targetStepId where the MENU_END_TARGET sentinel stands in for End.
type ButtonRow = { type: 'callback' | 'url'; label: string; targetStepId: string; url: string }
function blankButtonRow(): ButtonRow {
  return { type: 'callback', label: '', targetStepId: MENU_END_TARGET, url: '' }
}
// Pre-fill from an edited MENU step (End/null target → the sentinel), else seed one empty callback row so
// the author always has a starting point.
function initialButtonRows(): ButtonRow[] {
  const existing = props.initial?.stepType === 'MESSAGE' ? props.initial?.buttons : null
  if (existing && existing.length > 0) {
    return existing.map((b) => ({
      type: b.type === 'url' ? 'url' : 'callback',
      label: b.label ?? '',
      targetStepId: b.type === 'callback' ? (b.targetStepId ?? MENU_END_TARGET) : MENU_END_TARGET,
      url: b.url ?? '',
    }))
  }
  // A MESSAGE step's inline keyboard is OPTIONAL (Decision 2) — start with no rows so the author opts in
  // by clicking "Add button". An empty list emits no buttons (valid; no keyboard).
  return []
}
const menuButtons = ref<ButtonRow[]>(initialButtonRows())
// Per-row touched flag so an error only shows after the author tried to submit (or edited the row).
const menuTouched = ref(false)

// ── MENU optional timeout (Phase 2 / tech-spec Task 6 «опц. таймаут») ─────────────────────────────────
// All three fields are emitted ONLY when the author fills the timeout (value + unit). Left blank → unset
// → the menu waits indefinitely. The target picker reuses the same step/End options as a callback button;
// End is encoded as timeoutTargetStepId=null (same sentinel idiom). Client validation mirrors backend
// requireTimeout: when a unit is chosen the value must be a positive integer.
// Bound to a number <input>: vee-validate is not involved, so v-model writes either '' (empty) or a
// coerced number. Kept loosely typed and normalized via String() at the read sites.
const menuTimeoutValue = ref<string | number>(
  props.initial?.stepType === 'MESSAGE' && props.initial?.timeoutValue != null
    ? props.initial.timeoutValue
    : '',
)
const menuTimeoutUnit = ref<DelayUnit | ''>(
  props.initial?.stepType === 'MESSAGE' && props.initial?.timeoutUnit ? props.initial.timeoutUnit : '',
)
const menuTimeoutTarget = ref<string>(
  props.initial?.stepType === 'MESSAGE' && props.initial?.timeoutTargetStepId
    ? props.initial.timeoutTargetStepId
    : MENU_END_TARGET,
)
// The timeout is "on" once the author has entered a value OR picked a unit. While off, nothing is emitted.
const menuTimeoutValueRaw = computed(() => String(menuTimeoutValue.value ?? '').trim())
const menuTimeoutEnabled = computed(
  () => menuTimeoutValueRaw.value !== '' || menuTimeoutUnit.value !== '',
)
// Returns a localized error or null. When the timeout is engaged BOTH value (>=1 integer) and unit are
// required (mirrors backend requireTimeout's both-or-neither + value>=1 rule).
const menuTimeoutError = computed<string | null>(() => {
  if (!menuTimeoutEnabled.value) return null
  if (menuTimeoutUnit.value === '') return t('funnels.steps.validation.menuTimeoutUnitRequired')
  const raw = menuTimeoutValueRaw.value
  const n = Number(raw)
  if (raw === '' || !Number.isInteger(n) || n < 1) {
    return t('funnels.steps.validation.menuTimeoutValueMin')
  }
  return null
})

// Target options for a callback button: every OTHER step (by stable id) + the End sentinel. Steps without
// an id (not yet persisted) are skipped — they cannot be a stable target until the first save mints one.
const menuTargetOptions = computed(() => {
  const steps = props.siblingSteps ?? []
  const stepOptions = steps
    // Keep the real funnel position (index in the full array) so the label number matches the steps list,
    // THEN drop targetless steps (no id yet, or the MENU being edited itself).
    .map((s, position) => ({ s, position }))
    .filter(({ s }) => !!s.id && s.id !== props.initial?.id)
    .map(({ s, position }) => ({
      value: s.id as string,
      label: `${position + 1}. ${t(`funnels.steps.type.${s.stepType}`)}`,
    }))
  return [{ value: MENU_END_TARGET, label: t('funnels.steps.form.menuTargetEnd') }, ...stepOptions]
})

function addMenuButton() {
  if (menuButtons.value.length >= MENU_MAX_BUTTONS) return
  menuButtons.value.push(blankButtonRow())
}
function removeMenuButton(index: number) {
  menuButtons.value.splice(index, 1)
}

// Per-row validation (UX mirror of backend). Returns a localized message or null. label non-empty + ≤64;
// url buttons need an http(s) link.
function menuButtonLabelError(row: ButtonRow): string | null {
  const label = row.label.trim()
  if (!label) return t('funnels.steps.validation.menuButtonLabelRequired')
  if (label.length > MENU_LABEL_MAX) return t('funnels.steps.validation.menuButtonLabelMax')
  return null
}
function menuButtonUrlError(row: ButtonRow): string | null {
  if (row.type !== 'url') return null
  const url = row.url.trim()
  if (!url) return t('funnels.steps.validation.menuButtonUrlRequired')
  if (!IMAGE_URL_RE.test(url)) return t('funnels.steps.validation.menuButtonUrlScheme')
  return null
}
// Whether the author has engaged the inline keyboard at all. Empty list = no keyboard (valid). The MESSAGE
// step's buttons are OPTIONAL (Decision 2) — only when ≥1 row exists do the keyboard rules apply.
const hasButtons = computed(() => menuButtons.value.length > 0)
// Keyboard-level rule (mirror backend validateButtons): when buttons are present, ≥1 must be a callback so
// the subscriber can never get stuck.
const menuNeedsCallback = computed(
  () => hasButtons.value && !menuButtons.value.some((b) => b.type === 'callback'),
)
// Valid when there is no keyboard, OR the keyboard's rows + timeout all pass. The timeout error always
// applies (a half-filled timeout is invalid even without buttons, mirroring requireTimeout).
const menuButtonsValid = computed(
  () =>
    !menuTimeoutError.value &&
    (!hasButtons.value ||
      (!menuNeedsCallback.value &&
        menuButtons.value.every((b) => !menuButtonLabelError(b) && !menuButtonUrlError(b)))),
)

// ── MESSAGE composer hard-validation (mirror backend validateMessage byte-for-byte) ─────────────────
// Each *Error() returns a localized string or null. composerError is the array-level rule (1..10 blocks);
// blockError(block) is the per-type hard rule; albumTypeMix is folded into blockError for ALBUM.
const composerError = computed<string | null>(() => {
  if (blocks.value.length < MIN_BLOCKS) return t('funnels.steps.validation.composerEmpty')
  if (blocks.value.length > MAX_BLOCKS) return t('funnels.steps.validation.composerTooMany')
  return null
})
function blockError(block: BlockRow): string | null {
  switch (block.type) {
    case 'TEXT':
      if (!block.text.trim()) return t('funnels.steps.validation.blockTextRequired')
      return null
    case 'IMAGE':
    case 'VIDEO':
    case 'AUDIO':
    case 'FILE':
      if (!mediaSourceValid(block.mediaUrl)) return t('funnels.steps.validation.mediaUrlScheme')
      return null
    case 'ALBUM': {
      const items = block.items
      if (items.length < MIN_ALBUM_ITEMS) return t('funnels.steps.validation.albumTooFew')
      if (items.length > MAX_ALBUM_ITEMS) return t('funnels.steps.validation.albumTooMany')
      let hasVisual = false
      let hasAudio = false
      let hasFile = false
      for (let i = 0; i < items.length; i++) {
        const it = items[i]
        if (!mediaSourceValid(it.mediaUrl)) return t('funnels.steps.validation.mediaUrlScheme')
        // Decision 5: caption is meaningful only on the FIRST item.
        if (i > 0 && it.caption.trim()) return t('funnels.steps.validation.albumCaptionFirst')
        switch (it.type) {
          case 'IMAGE':
          case 'VIDEO':
            hasVisual = true
            break
          case 'AUDIO':
            hasAudio = true
            break
          case 'FILE':
            hasFile = true
            break
          default:
            return t('funnels.steps.validation.albumItemKind')
        }
      }
      // Type-mixing predicate: photo/video mix, OR all audio, OR all document — never across kinds.
      const kinds = (hasVisual ? 1 : 0) + (hasAudio ? 1 : 0) + (hasFile ? 1 : 0)
      if (kinds > 1) return t('funnels.steps.validation.albumTypeMix')
      return null
    }
    default:
      return null
  }
}
// Soft over-length warning (Decision 3) — shown inline, NEVER blocks submit.
function blockWarning(block: BlockRow): string | null {
  if (block.type === 'TEXT' && block.text.length > TEXT_WARN_LIMIT) {
    return t('funnels.steps.validation.textTooLong')
  }
  const captionOver =
    (MEDIA_BLOCK_TYPES.includes(block.type) && block.caption.length > CAPTION_WARN_LIMIT) ||
    (block.type === 'ALBUM' && (block.items[0]?.caption.length ?? 0) > CAPTION_WARN_LIMIT)
  if (captionOver) return t('funnels.steps.validation.captionTooLong')
  return null
}
// The whole composer passes when block-count + every block are valid, AND the keyboard is valid only when
// it can actually attach (last block non-album). When the last block IS an album the keyboard section is
// hidden and its rows are never emitted (buildBlock/onSubmit skip them), so stale hidden rows must NOT
// block submit — mirrors backend validateMessage, which only validates buttons it would attach.
const composerValid = computed(
  () =>
    !composerError.value &&
    blocks.value.every((b) => !blockError(b)) &&
    (!buttonsAllowed.value || menuButtonsValid.value),
)

// Project custom-field definitions feed the key select AND the value widget/validation. Lazy-loaded the
// first time SET_CUSTOM_FIELD is the active type (no fetch for funnels that never set a field).
const definitions = ref<CustomFieldDefinition[]>([])
const cfLoading = ref(false)
let cfRequested = false
async function ensureDefinitionsLoaded() {
  if (cfRequested) return
  cfRequested = true
  cfLoading.value = true
  try {
    definitions.value =
      (await useApi()<CustomFieldDefinition[]>(`/api/v1/projects/${projectId.value}/custom-fields`)) ?? []
  } catch {
    // Network/permission failure → empty list; the select shows its empty-state. No step blocked.
  } finally {
    cfLoading.value = false
  }
}
// Project tags feed the ADD_TAG / REMOVE_TAG slug picker — lazy-loaded the first time a tag step is the
// active type. The select is strict (existing tags only): an arbitrary slug would still attach to the
// subscriber but never increment its tag counter (addTag → incrementCounter has no upsert), i.e. an
// orphan tag — so the author picks from the project's managed tags.
const tags = ref<Tag[]>([])
const tagsLoading = ref(false)
let tagsRequested = false
async function ensureTagsLoaded() {
  if (tagsRequested) return
  tagsRequested = true
  tagsLoading.value = true
  try {
    tags.value = (await useApi()<Tag[]>(`/api/v1/projects/${projectId.value}/tags`)) ?? []
  } catch {
    // Network/permission failure → empty list; the select shows its empty-state. No step blocked.
  } finally {
    tagsLoading.value = false
  }
}
// ── SUBSCRIBE_TO_FUNNEL store source + lazy loader (Phase 5 / Decision 5) ─────────────────────────────
// Declared BEFORE the selectedType watch because that watch is `immediate` and may call ensureFunnelsLoaded
// during setup (when an edited SUBSCRIBE step is the initial type) — referencing it later would hit its TDZ.
// We read the project's funnels from the shared store so the picker can carry each funnel's status and
// surface a hint when the target is not `active`. NOTE: store.fetch('all') OVERWRITES the shared funnels
// list (the list page's status-filtered slice) — a deliberate trade-off (the editor specs already mock the
// store, least-friction source). Switch to a local useApi() fetch if that overwrite ever flickers a filter.
// We fetch 'all' (not 'active') on purpose — the inactive-target hint needs the non-active funnels present.
const funnelsStore = useFunnelsStore()
const { funnels: storeFunnels } = storeToRefs(funnelsStore)
// Explicit loading flag (mirrors the tag/cf loaders) so the picker shows its empty-state — not a stuck
// spinner — once the fetch settles on a project that genuinely has no other funnels.
const subscribeFunnelsLoading = ref(false)
let funnelsRequested = false
async function ensureFunnelsLoaded() {
  if (funnelsRequested) return
  funnelsRequested = true
  subscribeFunnelsLoading.value = true
  try {
    await funnelsStore.fetch('all')
  } catch {
    // Network/permission failure → the store keeps whatever it had; the picker shows its empty-state.
  } finally {
    subscribeFunnelsLoading.value = false
  }
}

watch(
  selectedType,
  (ty) => {
    if (ty === 'SET_CUSTOM_FIELD') ensureDefinitionsLoaded()
    if (ty === 'ADD_TAG' || ty === 'REMOVE_TAG') ensureTagsLoaded()
    if (ty === 'SUBSCRIBE_TO_FUNNEL') ensureFunnelsLoaded()
    // SET_KEYBOARD needs the funnels store for the per-button keyword hint (Decision 8 — containsAnyKeyword
    // mirror). CLEAR_KEYBOARD has no rows/buttons, so it needs no funnels.
    if (ty === 'SET_KEYBOARD') ensureFunnelsLoaded()
  },
  { immediate: true },
)

const cfOptions = computed(() =>
  definitions.value.map((d) => ({ value: d.name, label: d.label, hint: d.type })),
)
// Tag label is optional → fall back to the slug so every option is readable.
const tagOptions = computed(() =>
  tags.value.map((tg) => ({ value: tg.slug, label: tg.label ?? tg.slug })),
)

// ── SUBSCRIBE_TO_FUNNEL form state (Phase 5 / Decision 5) ─────────────────────────────────────────────
// (The store source + ensureFunnelsLoaded loader are declared above the selectedType watch — see there.)
// Pre-fill from an edited SUBSCRIBE step. Entry: a stored targetEntryStepId, else the "from the start"
// sentinel (which a null/absent value maps to). The checkbox mirrors endParentAfter (default false).
const subscribeTargetFunnelId = ref<string>(
  props.initial?.stepType === 'SUBSCRIBE_TO_FUNNEL' ? (props.initial.targetFunnelId ?? '') : '',
)
const subscribeEntryStepId = ref<string>(
  props.initial?.stepType === 'SUBSCRIBE_TO_FUNNEL' && props.initial.targetEntryStepId
    ? props.initial.targetEntryStepId
    : SUBSCRIBE_ENTRY_START,
)
const subscribeEndParent = ref<boolean>(
  props.initial?.stepType === 'SUBSCRIBE_TO_FUNNEL' ? Boolean(props.initial.endParentAfter) : false,
)

// Target funnel options carry the status so the hint below can read it. Self-target is allowed (cycles are
// a feature) — the currently-edited funnel may appear here, which is fine.
const subscribeTargetOptions = computed(() =>
  storeFunnels.value.map((f) => ({ value: f.id, label: f.name })),
)
// The chosen target's status drives the inactive hint (shown when it is not `active`).
const subscribeTargetStatus = computed(
  () => storeFunnels.value.find((f) => f.id === subscribeTargetFunnelId.value)?.status ?? null,
)
const subscribeTargetInactive = computed(
  () => !!subscribeTargetFunnelId.value && subscribeTargetStatus.value !== 'active',
)

// Entry-step options for the chosen target, lazily fetched (fetchOne returns the full FunnelResponse, NOT
// cached in the store). Steps without an id (not yet persisted) are skipped — no stable target. The "from
// the start" sentinel is always first.
const subscribeTargetSteps = ref<FunnelStep[]>([])
const subscribeStepsLoading = ref(false)
const subscribeEntryOptions = computed(() => {
  const stepOptions = subscribeTargetSteps.value
    .map((s, position) => ({ s, position }))
    .filter(({ s }) => !!s.id)
    .map(({ s, position }) => ({
      value: s.id as string,
      label: `${position + 1}. ${t(`funnels.steps.type.${s.stepType}`)}`,
    }))
  return [
    { value: SUBSCRIBE_ENTRY_START, label: t('funnels.steps.form.subscribeEntryStart') },
    ...stepOptions,
  ]
})

// Reload the target's steps whenever the target changes, and reset the entry pick back to "from the start"
// (step ids are unique only within one funnel, so a carried-over id would be meaningless).
watch(subscribeTargetFunnelId, async (id, prev) => {
  // Skip the reset on the seeding run (prev === undefined) so an edited step keeps its pre-filled entry.
  if (prev !== undefined && id !== prev) subscribeEntryStepId.value = SUBSCRIBE_ENTRY_START
  if (!id) {
    subscribeTargetSteps.value = []
    return
  }
  subscribeStepsLoading.value = true
  try {
    const funnel = await funnelsStore.fetchOne(id)
    subscribeTargetSteps.value = funnel.steps ?? []
  } catch {
    subscribeTargetSteps.value = [] // Failure → only the sentinel remains; the step is not blocked.
  } finally {
    subscribeStepsLoading.value = false
  }
}, { immediate: true })

// ── SET_KEYBOARD / CLEAR_KEYBOARD form state (16-persistent-keyboard / Decision 6) ────────────────────
// Both step types share the mandatory text + parse mode (plain refs, validated by hand). SET_KEYBOARD
// adds a rows sub-editor (a local reactive array of rows, each a local array of buttons — the same
// uid-keyed local-array idiom as the composer blocks / MENU rows), validated on submit via keyboardTouched.
// Seeded from props.initial when editing the matching type, else the defaults (one row, one blank button;
// persistent ON, one-time OFF).
const isKeyboardStep = (s: FunnelStep | null | undefined): boolean =>
  s?.stepType === 'SET_KEYBOARD' || s?.stepType === 'CLEAR_KEYBOARD'

const keyboardText = ref<string>(isKeyboardStep(props.initial) ? (props.initial?.keyboardText ?? '') : '')
const keyboardParseMode = ref<string>(
  isKeyboardStep(props.initial) ? (props.initial?.keyboardParseMode ?? '') : '',
)

type KeyboardButtonRow = { uid: number; text: string }
type KeyboardRowRow = { uid: number; buttons: KeyboardButtonRow[] }
function blankKeyboardButton(): KeyboardButtonRow {
  return { uid: nextUid(), text: '' }
}
function blankKeyboardRow(): KeyboardRowRow {
  return { uid: nextUid(), buttons: [blankKeyboardButton()] }
}
// Seed from an edited SET_KEYBOARD step's rows; otherwise one row with one blank button so the author has
// a starting point (1..10 × 1..4 is mandatory — there is no "empty keyboard" valid state, unlike the
// optional inline keyboard).
function initialKeyboardRows(): KeyboardRowRow[] {
  const existing = props.initial?.stepType === 'SET_KEYBOARD' ? props.initial?.keyboardRows : null
  if (existing && existing.length > 0) {
    return existing.map((r) => ({
      uid: nextUid(),
      buttons:
        r.buttons && r.buttons.length > 0
          ? r.buttons.map((b) => ({ uid: nextUid(), text: b.text ?? '' }))
          : [blankKeyboardButton()],
    }))
  }
  return [blankKeyboardRow()]
}
const keyboardRows = ref<KeyboardRowRow[]>(initialKeyboardRows())
// Keyboard behaviour is a single 3-way choice (mutually exclusive) that maps to Telegram's two independent
// boolean fields `is_persistent` / `one_time_keyboard`. A radio removes the contradictory true+true combo
// the two old checkboxes allowed. Default 'persistent' on a NEW step. When editing, derive the mode from the
// STORED booleans: persistent WINS on a contradictory true+true seed (matches Telegram client behaviour).
// Nullish-coalesce only the genuinely-absent fields so a stored explicit `false` is not clobbered.
type KeyboardMode = 'persistent' | 'normal' | 'oneTime'
const keyboardMode = ref<KeyboardMode>(
  props.initial?.stepType === 'SET_KEYBOARD'
    ? (props.initial?.isPersistent ?? true)
      ? 'persistent'
      : (props.initial?.oneTimeKeyboard ?? false)
        ? 'oneTime'
        : 'normal'
    : 'persistent',
)
// Touched on submit so per-field errors only render after the author tries to save (parity with the rest).
const keyboardTouched = ref(false)

function addKeyboardRow() {
  if (keyboardRows.value.length >= KEYBOARD_MAX_ROWS) return
  keyboardRows.value.push(blankKeyboardRow())
}
function removeKeyboardRow(index: number) {
  keyboardRows.value.splice(index, 1)
}
function addKeyboardButton(rowIndex: number) {
  const buttons = keyboardRows.value[rowIndex].buttons
  if (buttons.length >= KEYBOARD_MAX_BUTTONS_PER_ROW) return
  buttons.push(blankKeyboardButton())
}
function removeKeyboardButton(rowIndex: number, buttonIndex: number) {
  keyboardRows.value[rowIndex].buttons.splice(buttonIndex, 1)
}

// Mandatory text: non-blank and ≤4096 (HARD block — Decision 6, NOT the composer's soft warn). The cap is
// checked against the RAW length, mirroring backend requireKeyboardText (text.length(), not trimmed).
const keyboardTextError = computed<string | null>(() => {
  if (!keyboardText.value.trim()) return t('funnels.steps.validation.keyboardTextRequired')
  if (keyboardText.value.length > KEYBOARD_TEXT_MAX) return t('funnels.steps.validation.keyboardTextMax')
  return null
})
// Per-button rule: non-blank and ≤64. Returns a localized message or null (mirror of menuButtonLabelError).
function keyboardButtonError(button: KeyboardButtonRow): string | null {
  const text = button.text.trim()
  if (!text) return t('funnels.steps.validation.keyboardButtonRequired')
  if (text.length > KEYBOARD_BUTTON_TEXT_MAX) return t('funnels.steps.validation.keyboardButtonMax')
  return null
}
// Keyboard-level duplicate rule: no two trimmed button texts repeat across the WHOLE keyboard (Set-based,
// new logic — inline keyboards allow dup labels, reply-keyboard buttons must be unique). Blank texts are
// skipped here (the per-button required rule already blocks them) so a duplicate-of-blank is not reported.
const keyboardHasDuplicate = computed<boolean>(() => {
  const seen = new Set<string>()
  for (const row of keyboardRows.value) {
    for (const button of row.buttons) {
      const text = button.text.trim()
      if (!text) continue
      if (seen.has(text)) return true
      seen.add(text)
    }
  }
  return false
})
// The whole SET_KEYBOARD form passes when text + every button are valid, there are no duplicates, and the
// row/button caps hold (the add controls already disable at the caps, so over-cap is unreachable via UI —
// but a seeded edit could exceed them, so guard here too). CLEAR_KEYBOARD validates text only.
const keyboardValid = computed<boolean>(() => {
  if (keyboardTextError.value) return false
  if (selectedType.value === 'CLEAR_KEYBOARD') return true
  if (keyboardRows.value.length < 1 || keyboardRows.value.length > KEYBOARD_MAX_ROWS) return false
  for (const row of keyboardRows.value) {
    if (row.buttons.length < 1 || row.buttons.length > KEYBOARD_MAX_BUTTONS_PER_ROW) return false
    if (row.buttons.some((b) => keyboardButtonError(b))) return false
  }
  return !keyboardHasDuplicate.value
})

// Non-blocking keyword hint (Decision 8): warn next to a button whose lowercased text contains NO keyword
// of any active keyword funnel — a client-side mirror of backend containsAnyKeyword. Data comes from the
// shared funnels store (storeFunnels, loaded by ensureFunnelsLoaded). Advisory only — never gates submit.
// A blank button shows no hint (the required-text error covers it). `keywords` may be null on non-keyword
// funnels — guarded. keywords arrive already lowercase-normalized (FunnelTriggerSettings.addKeyword).
function keyboardButtonHint(button: KeyboardButtonRow): boolean {
  const text = button.text.trim().toLowerCase()
  if (!text) return false
  // Phase 8 (17-funnel-multi-entry): a funnel now carries a LIST of triggers; scan each keyword trigger's
  // keywords (the flat f.triggerType/f.keywords trio is gone).
  const matched = storeFunnels.value.some(
    (f) =>
      f.status === 'active' &&
      (f.triggers ?? []).some(
        (tr) =>
          tr.triggerType === 'keyword' &&
          (tr.keywords ?? []).some((kw) => kw && text.includes(kw)),
      ),
  )
  return !matched
}

// Plain ref (NOT a computed over customFieldKey): the validation `schema` below is evaluated by useForm
// during setup, BEFORE defineField creates customFieldKey — a computed that read customFieldKey there
// would hit its temporal dead zone. A watcher keeps this in sync once the form fields exist (below).
const selectedFieldType = ref<CustomFieldType | null>(null)

// Per-field-type value rule (UX mirror of backend CustomFieldValueValidator — the funnel save endpoint
// does NOT type-check the value, so this is the only place the author is guided before execution).
function cfValueSchema(type: CustomFieldType | null): z.ZodTypeAny {
  switch (type) {
    case 'NUMBER':
      return z
        .any()
        .refine(
          (v) => v === '' || v === null || v === undefined || Number.isFinite(Number(v)),
          t('funnels.steps.validation.customFieldValueNumber'),
        )
    case 'DATE':
      return z
        .any()
        .refine(
          (v) =>
            v === '' || v === null || v === undefined || v === CURRENT_DATE_TOKEN || DATE_ONLY_RE.test(String(v)),
          t('funnels.steps.validation.customFieldValueDate'),
        )
    case 'STRING':
      return z
        .any()
        .refine(
          (v) => v === null || v === undefined || String(v).trim().length <= 1024,
          t('funnels.steps.validation.customFieldValueStringMax'),
        )
    default: // BOOLEAN (checkbox is always valid) or type not yet resolved
      return z.any()
  }
}

// One schema computed over selectedType: only the active type's fields are validated; the rest fall back
// to z.any() so a stale value from another branch never blocks submit.
function schemaFor(type: StepType): z.ZodTypeAny {
  // MESSAGE composer blocks are a LOCAL reactive array validated by hand (composerValid), NOT vee-validate
  // fields — so the schema carries no message-body fields here (mirrors the MENU button rows idiom).
  const delayValue =
    type === 'DELAY'
      ? z.coerce
          .number({ invalid_type_error: t('funnels.steps.validation.delayMin') })
          .int(t('funnels.steps.validation.delayMin'))
          .min(1, t('funnels.steps.validation.delayMin'))
      : z.any()
  const tagSlug =
    type === 'ADD_TAG' || type === 'REMOVE_TAG'
      ? z.string().trim().regex(TAG_SLUG_RE, t('funnels.steps.validation.tagPattern'))
      : z.any()
  const customFieldKey =
    type === 'SET_CUSTOM_FIELD'
      ? z.string().trim().min(1, t('funnels.steps.validation.customFieldKeyRequired'))
      : z.any()
  const customFieldValue =
    type === 'SET_CUSTOM_FIELD' ? cfValueSchema(selectedFieldType.value) : z.any()
  const eventName =
    type === 'EMIT_EVENT'
      ? z.string().trim().regex(EVENT_NAME_RE, t('funnels.steps.validation.eventNamePattern'))
      : z.any()
  return z.object({
    delayValue,
    delayUnit: z.any(),
    tagSlug,
    customFieldKey,
    customFieldValue,
    eventName,
  })
}

const schema = computed(() => toTypedSchema(schemaFor(selectedType.value)))

const { defineField, handleSubmit, errors } = useForm({
  validationSchema: schema,
  initialValues: {
    delayValue: props.initial?.delayValue ?? 1,
    delayUnit: (props.initial?.delayUnit as DelayUnit | undefined) ?? 'MIN',
    tagSlug: props.initial?.tagSlug ?? '',
    customFieldKey: props.initial?.customFieldKey ?? '',
    customFieldValue: props.initial?.customFieldValue ?? '',
    eventName: props.initial?.eventName ?? '',
  },
})
const [delayValue, delayValueAttrs] = defineField('delayValue')
const [delayUnit, delayUnitAttrs] = defineField('delayUnit')
// tagSlug is bound to SearchableSelect via v-model (no vee-validate v-bind attrs — the combobox owns its
// input); validation runs on model update like the other fields.
const [tagSlug] = defineField('tagSlug')
// customFieldKey is bound to SearchableSelect via v-model; customFieldValue to a per-type widget.
// Neither needs the vee-validate v-bind attrs (the widgets own their input element); validation runs on
// model update like every other field.
const [customFieldKey] = defineField('customFieldKey')
const [customFieldValue] = defineField('customFieldValue')
const [eventName, eventNameAttrs] = defineField('eventName')

// Resolve the selected field's type from the loaded definitions — drives the value widget + validation.
watch(
  [definitions, customFieldKey],
  () => {
    selectedFieldType.value =
      definitions.value.find((d) => d.name === customFieldKey.value)?.type ?? null
  },
  { immediate: true },
)

// DATE "current date" affordance: the toggle simply writes the @now sentinel into the value (which the
// engine resolves at execution time) and the date input hides — no separate state to keep in sync.
const useCurrentDate = computed({
  get: () => customFieldValue.value === CURRENT_DATE_TOKEN,
  set: (on: boolean) => {
    customFieldValue.value = on ? CURRENT_DATE_TOKEN : ''
  },
})

// Switching to a different field clears the old value — a date typed for a DATE field is meaningless once
// the user repoints the step at a NUMBER field, etc. (skips the very first, seeding assignment).
watch(customFieldKey, (next, prev) => {
  if (prev !== undefined && next !== prev) customFieldValue.value = ''
})

// On EDIT the stored value must be re-shaped into the widget's representation, but the type is only known
// once definitions load — reconcile exactly once when the type first resolves.
let reconciled = false
watch(
  selectedFieldType,
  (type) => {
    if (reconciled) return
    if (props.initial?.stepType !== 'SET_CUSTOM_FIELD') {
      reconciled = true
      return
    }
    if (!type) return
    reconciled = true
    const raw = props.initial?.customFieldValue
    if (raw === CURRENT_DATE_TOKEN) {
      customFieldValue.value = CURRENT_DATE_TOKEN
    } else if (raw === null || raw === undefined) {
      customFieldValue.value = type === 'BOOLEAN' ? false : ''
    } else if (type === 'DATE') {
      customFieldValue.value = isoToDateInput(raw) // ISO date-time → 'YYYY-MM-DD' for the date input
    } else if (type === 'BOOLEAN') {
      customFieldValue.value = Boolean(raw)
    } else {
      customFieldValue.value = raw
    }
  },
  { immediate: true },
)

function blankToNull(v: unknown): string | null {
  const s = typeof v === 'string' ? v.trim() : v
  return s === '' || s === null || s === undefined ? null : (s as string)
}

// Convert an editable BlockRow to the persisted ContentBlock: trim values, blank→null for optional fields,
// and carry ONLY the fields meaningful for the block's type (the server ignores the rest, but a clean block
// keeps the steps array readable and never leaks a stale value from a switched type).
function buildBlock(row: BlockRow): ContentBlock {
  switch (row.type) {
    case 'TEXT':
      return { type: 'TEXT', text: row.text.trim(), parseMode: blankToNull(row.parseMode) }
    case 'ALBUM':
      return {
        type: 'ALBUM',
        parseMode: blankToNull(row.parseMode),
        items: row.items.map(
          (it, i): MediaItem => ({
            type: it.type,
            mediaUrl: it.mediaUrl.trim(),
            // Decision 5: a caption is meaningful only on the FIRST item; drop it everywhere else.
            caption: i === 0 ? blankToNull(it.caption) : null,
          }),
        ),
      }
    default: // IMAGE / VIDEO / AUDIO / FILE
      return {
        type: row.type,
        mediaUrl: row.mediaUrl.trim(),
        caption: blankToNull(row.caption),
        parseMode: blankToNull(row.parseMode),
      }
  }
}

// Coerce the flat UI model to the persisted custom-field value by the resolved field type, so the engine's
// per-type validator accepts it at execution: number/boolean as-is, DATE → ISO (or the @now sentinel).
function customFieldValueForSubmit(raw: unknown): unknown {
  const type = selectedFieldType.value
  if (type === 'DATE' && raw === CURRENT_DATE_TOKEN) return CURRENT_DATE_TOKEN
  if (type === 'NUMBER') return raw === '' || raw === null || raw === undefined ? null : Number(raw)
  if (type === 'DATE') return dateInputToIso(raw)
  if (type === 'BOOLEAN') return Boolean(raw)
  return blankToNull(raw) // STRING or type not resolved
}

// Narrow the flat model to ONLY the active type's persisted fields — the server ignores the rest, but
// sending a clean step keeps the array readable and avoids leaking a stale value from a switched branch.
const onSubmit = handleSubmit((values) => {
  const type = selectedType.value
  let step: FunnelStep
  switch (type) {
    case 'MESSAGE': {
      // Blocks + keyboard are local reactive arrays (not vee-validate fields), validated by hand. Mark
      // touched so inline errors render, then block the emit if anything is invalid (mirrors validateMessage).
      composerTouched.value = true
      menuTouched.value = true
      if (!composerValid.value) return
      step = {
        stepType: type,
        blocks: blocks.value.map((b) => buildBlock(b)),
        // Preserve the server-minted graph fields so a re-save / reorder keeps stable ids + edges.
        id: props.initial?.id ?? undefined,
        next: props.initial?.next ?? undefined,
      }
      // Buttons + timeout attach ONLY when the last block is non-album (Decision 2) AND the author added a
      // keyboard. Otherwise they are omitted entirely (no keyboard → the step ends/continues normally).
      if (buttonsAllowed.value && hasButtons.value) {
        step.buttons = menuButtons.value.map((b) => ({
          type: b.type,
          label: b.label.trim(),
          // End → targetStepId null (NOT "" — an empty string is a non-null id matching no step → server
          // 422 funnel_broken_edge). URL buttons carry no target.
          targetStepId:
            b.type === 'callback' ? (b.targetStepId === MENU_END_TARGET ? null : b.targetStepId) : null,
          url: b.type === 'url' ? b.url.trim() : null,
        }))
        // Optional timeout: emit the three fields ONLY when engaged. End → timeoutTargetStepId null.
        if (menuTimeoutEnabled.value) {
          step.timeoutValue = Number(menuTimeoutValueRaw.value)
          step.timeoutUnit = menuTimeoutUnit.value as DelayUnit
          step.timeoutTargetStepId =
            menuTimeoutTarget.value === MENU_END_TARGET ? null : menuTimeoutTarget.value
        }
      }
      break
    }
    case 'DELAY':
      step = { stepType: type, delayValue: Number(values.delayValue), delayUnit: values.delayUnit as DelayUnit }
      break
    case 'ADD_TAG':
    case 'REMOVE_TAG':
      step = { stepType: type, tagSlug: (values.tagSlug as string).trim() }
      break
    case 'EMIT_EVENT':
      step = { stepType: type, eventName: (values.eventName as string).trim() }
      break
    case 'SET_CUSTOM_FIELD':
      step = {
        stepType: type,
        customFieldKey: (values.customFieldKey as string).trim(),
        customFieldValue: customFieldValueForSubmit(values.customFieldValue),
      }
      break
    case 'SUBSCRIBE_TO_FUNNEL':
      // Cross-funnel enroll (Decision 5): the entry "from the start" sentinel → targetEntryStepId null.
      // No client-side block here — the backend owns target_required/_not_found/_inactive validation and
      // returns the funnel_subscribe_* 422 codes, mapped inline by the editor page.
      step = {
        stepType: type,
        targetFunnelId: blankToNull(subscribeTargetFunnelId.value),
        targetEntryStepId:
          subscribeEntryStepId.value === SUBSCRIBE_ENTRY_START ? null : subscribeEntryStepId.value,
        endParentAfter: subscribeEndParent.value,
      }
      break
    case 'SET_KEYBOARD': {
      // Text + rows are hand-validated local state (not vee-validate fields). Mark touched so inline errors
      // render, then block the emit if anything is invalid (mirrors validateSetKeyboard byte-for-byte).
      keyboardTouched.value = true
      if (!keyboardValid.value) return
      step = {
        stepType: type,
        keyboardText: keyboardText.value.trim(),
        keyboardParseMode: blankToNull(keyboardParseMode.value),
        // Emit trimmed button texts as the typed KeyboardRow[] shape.
        keyboardRows: keyboardRows.value.map(
          (r): KeyboardRow => ({ buttons: r.buttons.map((b) => ({ text: b.text.trim() })) }),
        ),
        // Map the 3-way mode back to Telegram's two independent booleans.
        isPersistent: keyboardMode.value === 'persistent',
        oneTimeKeyboard: keyboardMode.value === 'oneTime',
        id: props.initial?.id ?? undefined,
        next: props.initial?.next ?? undefined,
      }
      break
    }
    case 'CLEAR_KEYBOARD': {
      keyboardTouched.value = true
      if (!keyboardValid.value) return
      // CLEAR_KEYBOARD carries ONLY text + parse mode — NEVER keyboardRows/isPersistent/oneTimeKeyboard.
      // The backend strictly rejects those on this type (Decision 6 symmetric rejection) → a sloppy emit
      // would 422 on save.
      step = {
        stepType: type,
        keyboardText: keyboardText.value.trim(),
        keyboardParseMode: blankToNull(keyboardParseMode.value),
        id: props.initial?.id ?? undefined,
        next: props.initial?.next ?? undefined,
      }
      break
    }
  }
  // Carry the server-minted graph/layout fields the form does NOT edit through unchanged so a side-panel
  // field edit never drops them (MAJOR-1 — 18-funnel-canvas audit). canvasPosition is the node's manually
  // placed coordinate; like id/next it is owned by the canvas, not this form, and must survive a re-emit.
  // Omitted when absent so the emitted step stays byte-identical for the list/dialog callers (Decision 10).
  if (props.initial?.canvasPosition != null) step.canvasPosition = props.initial.canvasPosition
  emit('submit', step)
})
</script>

<template>
  <form data-test="step-form" class="space-y-3" novalidate @submit.prevent="onSubmit">
    <div>
      <label for="step-type" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.typeLabel') }}</label>
      <select
        id="step-type"
        v-model="selectedType"
        data-test="step-type-select"
        class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
      >
        <option v-for="ty in STEP_TYPES" :key="ty" :value="ty">{{ t(`funnels.steps.type.${ty}`) }}</option>
      </select>
    </div>

    <!-- MESSAGE composer: an ordered list of content blocks (each → one Telegram message). The author adds
         blocks via the type picker, reorders / removes them, and (optionally) attaches an inline keyboard to
         the LAST non-album block. All labels/text render via {{ }} interpolation (never v-html — XSS). -->
    <template v-if="selectedType === 'MESSAGE'">
      <div data-test="step-composer" class="space-y-3">
        <!-- Block-type picker + Add block -->
        <div class="flex items-end gap-2">
          <div class="flex-1">
            <label for="step-block-type" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.blockType') }}</label>
            <select
              id="step-block-type"
              v-model="newBlockType"
              data-test="step-block-type-picker"
              class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option v-for="bt in BLOCK_TYPES" :key="bt" :value="bt">{{ t(`funnels.steps.blockType.${bt}`) }}</option>
            </select>
          </div>
          <button
            type="button"
            data-test="step-add-block"
            :disabled="blocks.length >= MAX_BLOCKS"
            class="rounded-md border px-3 py-2 text-sm hover:bg-gray-50 disabled:opacity-40"
            @click="addBlock"
          >
            {{ t('funnels.steps.form.addBlock') }}
          </button>
        </div>

        <p v-if="composerTouched && composerError" data-test="step-composer-error" class="text-sm text-red-600">
          {{ composerError }}
        </p>

        <!-- Block rows -->
        <div
          v-for="(block, index) in blocks"
          :key="block.uid"
          :data-test="`step-block-${index}`"
          class="space-y-2 rounded-md border p-3"
        >
          <div class="flex items-center justify-between">
            <span class="text-xs font-medium uppercase text-gray-500">{{ t(`funnels.steps.blockType.${block.type}`) }}</span>
            <div class="flex gap-1">
              <button
                type="button"
                :data-test="`step-block-move-up-${index}`"
                :disabled="index === 0"
                :aria-label="t('funnels.steps.form.blockMoveUp')"
                class="rounded-md border px-2 py-1 text-sm hover:bg-gray-50 disabled:opacity-40"
                @click="moveBlock(index, index - 1)"
              >
                ↑
              </button>
              <button
                type="button"
                :data-test="`step-block-move-down-${index}`"
                :disabled="index === blocks.length - 1"
                :aria-label="t('funnels.steps.form.blockMoveDown')"
                class="rounded-md border px-2 py-1 text-sm hover:bg-gray-50 disabled:opacity-40"
                @click="moveBlock(index, index + 1)"
              >
                ↓
              </button>
              <button
                type="button"
                :data-test="`step-block-remove-${index}`"
                :aria-label="t('funnels.steps.form.blockRemove')"
                class="rounded-md border border-red-300 px-2.5 py-1 text-sm text-red-700 hover:bg-red-50"
                @click="removeBlock(index)"
              >
                ×
              </button>
            </div>
          </div>

          <!-- TEXT block -->
          <template v-if="block.type === 'TEXT'">
            <textarea
              v-model="block.text"
              :data-test="`step-block-text-${index}`"
              rows="3"
              :aria-label="t('funnels.steps.form.text')"
              class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <select
              v-model="block.parseMode"
              :data-test="`step-block-parsemode-${index}`"
              :aria-label="t('funnels.steps.form.parseMode')"
              class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option v-for="pm in PARSE_MODES" :key="pm" :value="pm">
                {{ pm === '' ? t('funnels.steps.form.parseModeNone') : pm }}
              </option>
            </select>
          </template>

          <!-- IMAGE / VIDEO / AUDIO / FILE block -->
          <template v-else-if="block.type !== 'ALBUM'">
            <input
              v-model="block.mediaUrl"
              :data-test="`step-block-media-url-${index}`"
              type="text"
              autocomplete="off"
              :placeholder="t('funnels.steps.form.mediaUrl')"
              :aria-label="t('funnels.steps.form.mediaUrl')"
              class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <input
              v-model="block.caption"
              :data-test="`step-block-caption-${index}`"
              type="text"
              :placeholder="t('funnels.steps.form.caption')"
              :aria-label="t('funnels.steps.form.caption')"
              class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <select
              v-model="block.parseMode"
              :data-test="`step-block-parsemode-${index}`"
              :aria-label="t('funnels.steps.form.parseMode')"
              class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option v-for="pm in PARSE_MODES" :key="pm" :value="pm">
                {{ pm === '' ? t('funnels.steps.form.parseModeNone') : pm }}
              </option>
            </select>
          </template>

          <!-- ALBUM block: 2..10 media items, each with its own media kind. Caption only on the first. -->
          <template v-else>
            <div class="mb-1 flex items-center justify-between">
              <span class="text-sm font-medium">{{ t('funnels.steps.form.albumItems') }}</span>
              <button
                type="button"
                :data-test="`step-album-add-item-${index}`"
                :disabled="block.items.length >= MAX_ALBUM_ITEMS"
                class="rounded-md border px-2 py-1 text-xs hover:bg-gray-50 disabled:opacity-40"
                @click="addAlbumItem(index)"
              >
                {{ t('funnels.steps.form.albumAddItem') }}
              </button>
            </div>
            <div
              v-for="(item, j) in block.items"
              :key="item.uid"
              :data-test="`step-album-item-${index}-${j}`"
              class="mb-2 space-y-2 rounded-md border border-dashed p-2"
            >
              <div class="flex gap-2">
                <select
                  v-model="item.type"
                  :data-test="`step-album-item-type-${index}-${j}`"
                  :aria-label="t('funnels.steps.form.albumItemType')"
                  class="rounded-md border px-2 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                >
                  <option v-for="mt in MEDIA_BLOCK_TYPES" :key="mt" :value="mt">{{ t(`funnels.steps.blockType.${mt}`) }}</option>
                </select>
                <input
                  v-model="item.mediaUrl"
                  :data-test="`step-album-item-url-${index}-${j}`"
                  type="text"
                  autocomplete="off"
                  :placeholder="t('funnels.steps.form.mediaUrl')"
                  :aria-label="t('funnels.steps.form.mediaUrl')"
                  class="flex-1 rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                <button
                  type="button"
                  :data-test="`step-album-remove-item-${index}-${j}`"
                  :aria-label="t('funnels.steps.form.albumRemoveItem')"
                  class="rounded-md border border-red-300 px-2.5 py-1 text-sm text-red-700 hover:bg-red-50"
                  @click="removeAlbumItem(index, j)"
                >
                  ×
                </button>
              </div>
              <!-- Decision 5: caption is meaningful only on the FIRST item. -->
              <input
                v-if="j === 0"
                v-model="item.caption"
                :data-test="`step-album-item-caption-${index}-${j}`"
                type="text"
                :placeholder="t('funnels.steps.form.caption')"
                :aria-label="t('funnels.steps.form.caption')"
                class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          </template>

          <p v-if="composerTouched && blockError(block)" :data-test="`step-block-error-${index}`" class="text-sm text-red-600">
            {{ blockError(block) }}
          </p>
          <p v-if="blockWarning(block)" :data-test="`step-block-warn-${index}`" class="text-sm text-amber-600">
            {{ blockWarning(block) }}
          </p>
        </div>

        <!-- Inline keyboard: attaches to the LAST non-album block only (Decision 2). Hidden when the last
             block is an album — the author is hinted to add a text tail to carry the buttons. -->
        <template v-if="buttonsAllowed">
          <!-- Button sub-editor: one row per inline-keyboard button. Labels render via {{ }} interpolation
               (never v-html) — author-entered plain text must not be parsed as markup (XSS). -->
          <div data-test="step-menu-buttons" class="rounded-md border border-blue-200 bg-blue-50/40 p-3">
            <div class="mb-1 flex items-center justify-between">
              <label class="block text-sm font-medium">{{ t('funnels.steps.form.menuButtons') }}</label>
              <button
                type="button"
                data-test="step-menu-add-button"
                :disabled="menuButtons.length >= MENU_MAX_BUTTONS"
                class="rounded-md border px-2 py-1 text-xs hover:bg-gray-50 disabled:opacity-40"
                @click="addMenuButton"
              >
                {{ t('funnels.steps.form.menuAddButton') }}
              </button>
            </div>
            <p class="mb-2 text-xs text-gray-500">{{ t('funnels.steps.form.menuButtonsHint') }}</p>

            <div
              v-for="(row, index) in menuButtons"
              :key="index"
              :data-test="`step-menu-button-row-${index}`"
              class="mb-2 space-y-2 rounded-md border p-2"
            >
              <div class="flex gap-2">
                <select
                  v-model="row.type"
                  :data-test="`step-menu-button-type-${index}`"
                  :aria-label="t('funnels.steps.form.menuButtonType')"
                  class="rounded-md border px-2 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                >
                  <option value="callback">{{ t('funnels.steps.form.menuButtonTypeCallback') }}</option>
                  <option value="url">{{ t('funnels.steps.form.menuButtonTypeUrl') }}</option>
                </select>
                <input
                  v-model="row.label"
                  :data-test="`step-menu-button-label-${index}`"
                  type="text"
                  :placeholder="t('funnels.steps.form.menuButtonLabel')"
                  :aria-label="t('funnels.steps.form.menuButtonLabel')"
                  class="flex-1 rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                <button
                  type="button"
                  :data-test="`step-menu-button-remove-${index}`"
                  :aria-label="t('funnels.steps.form.menuRemoveButton')"
                  class="rounded-md border border-red-300 px-2.5 py-1 text-sm text-red-700 hover:bg-red-50"
                  @click="removeMenuButton(index)"
                >
                  ×
                </button>
              </div>

              <p
                v-if="menuTouched && menuButtonLabelError(row)"
                :data-test="`step-menu-button-label-error-${index}`"
                class="text-sm text-red-600"
              >
                {{ menuButtonLabelError(row) }}
              </p>

              <!-- The branch selector keys ONLY on row.type so a callback button always stays in the callback
                   branch. The canvas-edge owner (hideTargetPickers) hides JUST the inner target picker — it
                   must NOT push a callback button into the v-else URL branch (that would corrupt the form).
                   When hidden, row.targetStepId is preserved unchanged and still flows through on submit. -->
              <template v-if="row.type === 'callback'">
                <SearchableSelect
                  v-if="!hideTargetPickers"
                  v-model="row.targetStepId"
                  :options="menuTargetOptions"
                  :show-value="false"
                  :test-prefix="`step-menu-target-${index}`"
                  :placeholder="t('funnels.steps.form.menuTargetPlaceholder')"
                  :loading-text="t('funnels.steps.form.menuTargetLoading')"
                  :empty-text="t('funnels.steps.form.menuTargetEmpty')"
                  :no-matches-text="t('funnels.steps.form.menuTargetNoMatches')"
                />
              </template>

              <!-- URL: an http(s) link (the funnel does not advance on click). -->
              <template v-else>
                <input
                  v-model="row.url"
                  :data-test="`step-menu-button-url-${index}`"
                  type="text"
                  autocomplete="off"
                  :placeholder="t('funnels.steps.form.menuButtonUrl')"
                  :aria-label="t('funnels.steps.form.menuButtonUrl')"
                  class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                <p
                  v-if="menuTouched && menuButtonUrlError(row)"
                  :data-test="`step-menu-button-url-error-${index}`"
                  class="text-sm text-red-600"
                >
                  {{ menuButtonUrlError(row) }}
                </p>
              </template>
            </div>

            <p v-if="menuTouched && menuNeedsCallback" data-test="step-menu-error" class="mt-1 text-sm text-red-600">
              {{ t('funnels.steps.validation.menuNeedsCallback') }}
            </p>

            <!-- Optional timeout: leave blank to wait indefinitely. Filling value + unit emits the timeout
                 edge; the target picker reuses the callback step/End options (End → timeoutTargetStepId null). -->
            <div data-test="step-menu-timeout" class="mt-3">
              <label class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.menuTimeout') }}</label>
              <p class="mb-2 text-xs text-gray-500">{{ t('funnels.steps.form.menuTimeoutHint') }}</p>
              <div class="flex gap-2">
                <div class="flex-1">
                  <input
                    v-model="menuTimeoutValue"
                    data-test="step-menu-timeout-value"
                    type="number"
                    min="1"
                    :placeholder="t('funnels.steps.form.menuTimeoutValue')"
                    :aria-label="t('funnels.steps.form.menuTimeoutValue')"
                    class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                <div class="flex-1">
                  <select
                    v-model="menuTimeoutUnit"
                    data-test="step-menu-timeout-unit"
                    :aria-label="t('funnels.steps.form.menuTimeoutUnit')"
                    class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  >
                    <option value="">{{ t('funnels.steps.form.menuTimeoutUnitNone') }}</option>
                    <option v-for="u in DELAY_UNITS" :key="u" :value="u">{{ t(`funnels.steps.unit.${u}`) }}</option>
                  </select>
                </div>
              </div>
              <div v-if="menuTimeoutEnabled && !hideTargetPickers" class="mt-2">
                <label class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.menuTimeoutTarget') }}</label>
                <SearchableSelect
                  v-model="menuTimeoutTarget"
                  :options="menuTargetOptions"
                  :show-value="false"
                  test-prefix="step-menu-timeout-target"
                  :placeholder="t('funnels.steps.form.menuTargetPlaceholder')"
                  :loading-text="t('funnels.steps.form.menuTargetLoading')"
                  :empty-text="t('funnels.steps.form.menuTargetEmpty')"
                  :no-matches-text="t('funnels.steps.form.menuTargetNoMatches')"
                />
              </div>
              <p
                v-if="menuTouched && menuTimeoutError"
                data-test="step-menu-timeout-error"
                class="mt-1 text-sm text-red-600"
              >
                {{ menuTimeoutError }}
              </p>
            </div>
          </div>
        </template>
        <p v-else data-test="step-composer-album-tail-hint" class="text-xs text-gray-500">
          {{ t('funnels.steps.form.albumTailHint') }}
        </p>
      </div>
    </template>

    <!-- DELAY -->
    <template v-else-if="selectedType === 'DELAY'">
      <div class="flex gap-2">
        <div class="flex-1">
          <label for="step-delay-value" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.delayValue') }}</label>
          <input
            id="step-delay-value"
            v-model="delayValue"
            v-bind="delayValueAttrs"
            data-test="step-delay-value-input"
            type="number"
            min="1"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
        </div>
        <div class="flex-1">
          <label for="step-delay-unit" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.delayUnit') }}</label>
          <select
            id="step-delay-unit"
            v-model="delayUnit"
            v-bind="delayUnitAttrs"
            data-test="step-delay-unit-select"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option v-for="u in DELAY_UNITS" :key="u" :value="u">{{ t(`funnels.steps.unit.${u}`) }}</option>
          </select>
        </div>
      </div>
      <p v-if="errors.delayValue" data-test="step-delay-error" class="mt-1 text-sm text-red-600">{{ errors.delayValue }}</p>
    </template>

    <!-- ADD_TAG / REMOVE_TAG -->
    <template v-else-if="selectedType === 'ADD_TAG' || selectedType === 'REMOVE_TAG'">
      <div>
        <label for="step-tag" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.tagSlug') }}</label>
        <SearchableSelect
          id="step-tag"
          v-model="tagSlug"
          :options="tagOptions"
          :loading="tagsLoading"
          :invalid="!!errors.tagSlug"
          test-prefix="step-tag"
          :placeholder="t('funnels.steps.form.tagPlaceholder')"
          :loading-text="t('funnels.steps.form.tagLoading')"
          :empty-text="t('funnels.steps.form.tagEmpty')"
          :no-matches-text="t('funnels.steps.form.tagNoMatches')"
        />
        <p v-if="errors.tagSlug" data-test="step-tag-error" class="mt-1 text-sm text-red-600">{{ errors.tagSlug }}</p>
      </div>
    </template>

    <!-- SET_CUSTOM_FIELD -->
    <template v-else-if="selectedType === 'SET_CUSTOM_FIELD'">
      <div>
        <label for="step-cf-key" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.customFieldKey') }}</label>
        <SearchableSelect
          id="step-cf-key"
          v-model="customFieldKey"
          :options="cfOptions"
          :loading="cfLoading"
          :invalid="!!errors.customFieldKey"
          test-prefix="step-cf-key"
          :placeholder="t('funnels.steps.form.customFieldKeyPlaceholder')"
          :loading-text="t('funnels.steps.form.customFieldKeyLoading')"
          :empty-text="t('funnels.steps.form.customFieldKeyEmpty')"
          :no-matches-text="t('funnels.steps.form.customFieldKeyNoMatches')"
        />
        <p v-if="errors.customFieldKey" data-test="step-cf-key-error" class="mt-1 text-sm text-red-600">{{ errors.customFieldKey }}</p>
      </div>
      <div>
        <label for="step-cf-value" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.customFieldValue') }}</label>

        <!-- DATE: optional "current date" (resolves at execution) OR an explicit date. -->
        <template v-if="selectedFieldType === 'DATE'">
          <label class="mb-2 flex items-center gap-2 text-sm">
            <input
              v-model="useCurrentDate"
              data-test="step-cf-current-date"
              type="checkbox"
              class="h-4 w-4 rounded border-gray-300"
            />
            {{ t('funnels.steps.form.customFieldCurrentDate') }}
          </label>
          <input
            v-if="!useCurrentDate"
            id="step-cf-value"
            v-model="customFieldValue"
            data-test="step-cf-value-input"
            type="date"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
        </template>

        <!-- BOOLEAN: checkbox. -->
        <label
          v-else-if="selectedFieldType === 'BOOLEAN'"
          class="inline-flex items-center gap-2 text-sm"
        >
          <input
            v-model="customFieldValue"
            data-test="step-cf-value-input"
            type="checkbox"
            class="h-4 w-4 rounded border-gray-300"
          />
          {{ t('funnels.steps.form.customFieldValueBool') }}
        </label>

        <!-- NUMBER -->
        <input
          v-else-if="selectedFieldType === 'NUMBER'"
          id="step-cf-value"
          v-model="customFieldValue"
          data-test="step-cf-value-input"
          type="number"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />

        <!-- STRING (or type not yet resolved) -->
        <input
          v-else
          id="step-cf-value"
          v-model="customFieldValue"
          data-test="step-cf-value-input"
          type="text"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />

        <p v-if="errors.customFieldValue" data-test="step-cf-value-error" class="mt-1 text-sm text-red-600">{{ errors.customFieldValue }}</p>
      </div>
    </template>

    <!-- EMIT_EVENT: dispatch an event into the shared `event` namespace (Decision 4). Slug-validated. -->
    <template v-else-if="selectedType === 'EMIT_EVENT'">
      <div>
        <label for="step-event-name" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.eventName') }}</label>
        <input
          id="step-event-name"
          v-model="eventName"
          v-bind="eventNameAttrs"
          data-test="step-event-name-input"
          type="text"
          autocomplete="off"
          :placeholder="t('funnels.steps.form.eventNamePlaceholder')"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p class="mt-1 text-xs text-gray-500">{{ t('funnels.steps.form.eventNameHint') }}</p>
        <p v-if="errors.eventName" data-test="step-event-name-error" class="mt-1 text-sm text-red-600">{{ errors.eventName }}</p>
      </div>
    </template>

    <!-- SUBSCRIBE_TO_FUNNEL: enroll the subscriber into ANOTHER funnel of the project (Phase 5, Decision 5).
         Target picker carries each funnel's status → inline hint when the target is not `active`. Entry
         picker = a step of the target (or "from the start" sentinel). End-parent checkbox ends THIS funnel. -->
    <template v-else-if="selectedType === 'SUBSCRIBE_TO_FUNNEL'">
      <div>
        <label class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.subscribeTarget') }}</label>
        <SearchableSelect
          v-model="subscribeTargetFunnelId"
          :options="subscribeTargetOptions"
          :loading="subscribeFunnelsLoading"
          test-prefix="step-subscribe-target"
          :placeholder="t('funnels.steps.form.subscribeTargetPlaceholder')"
          :loading-text="t('funnels.steps.form.subscribeTargetLoading')"
          :empty-text="t('funnels.steps.form.subscribeTargetEmpty')"
          :no-matches-text="t('funnels.steps.form.subscribeTargetNoMatches')"
        />
        <p
          v-if="subscribeTargetInactive"
          data-test="step-subscribe-inactive-hint"
          class="mt-1 text-sm text-amber-600"
        >
          {{ t('funnels.steps.form.subscribeInactiveHint') }}
        </p>
      </div>

      <!-- SUBSCRIBE entry picker — hidden on the canvas (hideTargetPickers); the value is preserved unchanged
           and still flows through on submit. The cross-funnel target is rendered as a node exit badge there. -->
      <div v-if="!hideTargetPickers">
        <label class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.subscribeEntry') }}</label>
        <SearchableSelect
          v-model="subscribeEntryStepId"
          :options="subscribeEntryOptions"
          :loading="subscribeStepsLoading"
          :show-value="false"
          test-prefix="step-subscribe-entry"
          :placeholder="t('funnels.steps.form.subscribeEntryPlaceholder')"
          :loading-text="t('funnels.steps.form.subscribeEntryLoading')"
          :empty-text="t('funnels.steps.form.subscribeEntryEmpty')"
          :no-matches-text="t('funnels.steps.form.subscribeEntryNoMatches')"
        />
      </div>

      <div>
        <label class="inline-flex items-center gap-2 text-sm">
          <input
            v-model="subscribeEndParent"
            data-test="step-subscribe-end-parent"
            type="checkbox"
            class="h-4 w-4 rounded border-gray-300"
          />
          {{ t('funnels.steps.form.subscribeEndParent') }}
        </label>
        <p data-test="step-subscribe-return-hint" class="mt-1 text-xs text-gray-500">
          {{ t('funnels.steps.form.subscribeReturnHint') }}
        </p>
      </div>
    </template>

    <!-- SET_KEYBOARD / CLEAR_KEYBOARD: a mandatory text message + (SET only) a reply-keyboard rows editor
         (16-persistent-keyboard). Both share the text + parse mode. SET adds the rows sub-editor, the two
         checkboxes, and the per-button keyword hint. All text via {{ }} interpolation (never v-html — XSS). -->
    <template v-else-if="selectedType === 'SET_KEYBOARD' || selectedType === 'CLEAR_KEYBOARD'">
      <div>
        <label for="step-keyboard-text" class="block text-sm font-medium mb-1">{{ t('funnels.steps.form.keyboardText') }}</label>
        <textarea
          id="step-keyboard-text"
          v-model="keyboardText"
          data-test="step-keyboard-text"
          rows="3"
          :aria-label="t('funnels.steps.form.keyboardText')"
          class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <select
          v-model="keyboardParseMode"
          data-test="step-keyboard-parsemode"
          :aria-label="t('funnels.steps.form.parseMode')"
          class="mt-2 w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          <option v-for="pm in PARSE_MODES" :key="pm" :value="pm">
            {{ pm === '' ? t('funnels.steps.form.parseModeNone') : pm }}
          </option>
        </select>
        <p v-if="keyboardTouched && keyboardTextError" data-test="step-keyboard-text-error" class="mt-1 text-sm text-red-600">
          {{ keyboardTextError }}
        </p>
      </div>

      <!-- SET_KEYBOARD only: the reply-keyboard rows sub-editor (1..10 rows × 1..4 buttons) + checkboxes. -->
      <template v-if="selectedType === 'SET_KEYBOARD'">
        <div data-test="step-keyboard-rows" class="rounded-md border border-blue-200 bg-blue-50/40 p-3">
          <div class="mb-1 flex items-center justify-between">
            <label class="block text-sm font-medium">{{ t('funnels.steps.form.keyboardRows') }}</label>
            <button
              type="button"
              data-test="step-keyboard-add-row"
              :disabled="keyboardRows.length >= KEYBOARD_MAX_ROWS"
              class="rounded-md border px-2 py-1 text-xs hover:bg-gray-50 disabled:opacity-40"
              @click="addKeyboardRow"
            >
              {{ t('funnels.steps.form.keyboardAddRow') }}
            </button>
          </div>
          <p class="mb-2 text-xs text-gray-500">{{ t('funnels.steps.form.keyboardRowsHint') }}</p>

          <div
            v-for="(row, r) in keyboardRows"
            :key="row.uid"
            :data-test="`step-keyboard-row-${r}`"
            class="mb-2 space-y-2 rounded-md border p-2"
          >
            <div class="flex items-center justify-between">
              <span class="text-xs font-medium uppercase text-gray-500">{{ t('funnels.steps.form.keyboardRow', { n: r + 1 }) }}</span>
              <div class="flex gap-1">
                <button
                  type="button"
                  :data-test="`step-keyboard-add-button-${r}`"
                  :disabled="row.buttons.length >= KEYBOARD_MAX_BUTTONS_PER_ROW"
                  class="rounded-md border px-2 py-1 text-xs hover:bg-gray-50 disabled:opacity-40"
                  @click="addKeyboardButton(r)"
                >
                  {{ t('funnels.steps.form.keyboardAddButton') }}
                </button>
                <button
                  type="button"
                  :data-test="`step-keyboard-remove-row-${r}`"
                  :disabled="keyboardRows.length <= 1"
                  :aria-label="t('funnels.steps.form.keyboardRemoveRow')"
                  class="rounded-md border border-red-300 px-2.5 py-1 text-sm text-red-700 hover:bg-red-50 disabled:opacity-40"
                  @click="removeKeyboardRow(r)"
                >
                  ×
                </button>
              </div>
            </div>

            <div
              v-for="(button, c) in row.buttons"
              :key="button.uid"
              :data-test="`step-keyboard-button-cell-${r}-${c}`"
              class="space-y-1"
            >
              <div class="flex gap-2">
                <input
                  v-model="button.text"
                  :data-test="`step-keyboard-button-${r}-${c}`"
                  type="text"
                  autocomplete="off"
                  :placeholder="t('funnels.steps.form.keyboardButton')"
                  :aria-label="t('funnels.steps.form.keyboardButton')"
                  class="flex-1 rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                <button
                  type="button"
                  :data-test="`step-keyboard-remove-button-${r}-${c}`"
                  :disabled="row.buttons.length <= 1"
                  :aria-label="t('funnels.steps.form.keyboardRemoveButton')"
                  class="rounded-md border border-red-300 px-2.5 py-1 text-sm text-red-700 hover:bg-red-50 disabled:opacity-40"
                  @click="removeKeyboardButton(r, c)"
                >
                  ×
                </button>
              </div>
              <p
                v-if="keyboardTouched && keyboardButtonError(button)"
                :data-test="`step-keyboard-button-error-${r}-${c}`"
                class="text-sm text-red-600"
              >
                {{ keyboardButtonError(button) }}
              </p>
              <!-- Non-blocking amber keyword hint (Decision 8) — advisory, never blocks submit. -->
              <p
                v-if="keyboardButtonHint(button)"
                :data-test="`step-keyboard-hint-${r}-${c}`"
                class="text-sm text-amber-600"
              >
                {{ t('funnels.steps.form.keyboardKeywordHint') }}
              </p>
            </div>
          </div>

          <p v-if="keyboardTouched && keyboardHasDuplicate" data-test="step-keyboard-duplicate-error" class="mt-1 text-sm text-red-600">
            {{ t('funnels.steps.validation.keyboardDuplicate') }}
          </p>
        </div>

        <fieldset class="space-y-2">
          <legend class="text-sm font-medium text-gray-700">
            {{ t('funnels.steps.form.keyboardModeLabel') }}
          </legend>
          <label
            v-for="mode in (['persistent', 'normal', 'oneTime'] as const)"
            :key="mode"
            class="flex items-start gap-2 text-sm"
          >
            <input
              v-model="keyboardMode"
              :value="mode"
              :data-test="`step-keyboard-mode-${mode}`"
              type="radio"
              name="keyboard-mode"
              class="mt-0.5 h-4 w-4 border-gray-300"
            />
            <span>
              {{ t(`funnels.steps.form.keyboardMode_${mode}`) }}
              <span class="block text-xs text-gray-500">
                {{ t(`funnels.steps.form.keyboardMode_${mode}_hint`) }}
              </span>
            </span>
          </label>
        </fieldset>
      </template>
    </template>

    <div class="flex justify-end gap-2 pt-2">
      <button
        type="button"
        data-test="step-form-cancel"
        class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
        @click="emit('cancel')"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        type="submit"
        data-test="step-form-submit"
        class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
      >
        {{ props.submitLabel }}
      </button>
    </div>
  </form>
</template>
