import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { collectKeySet } from '../../scripts/check-locales.mjs'

const localesDir = resolve(process.cwd(), 'i18n/locales')

function loadKeys(file: string): Record<string, true> {
  const raw = readFileSync(resolve(localesDir, file), 'utf8')
  return collectKeySet(JSON.parse(raw))
}

// collectKeySet flattens to `{ path: true }` and drops the value, so an empty
// string `""` registers as "present". For AC keys that must render visible text
// ("без порожніх ключів") we need the real leaf value to assert it is non-empty.
function loadValues(file: string): Record<string, unknown> {
  const raw = readFileSync(resolve(localesDir, file), 'utf8')
  const acc: Record<string, unknown> = Object.create(null)
  const walk = (obj: unknown, prefix: string) => {
    if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) return
    for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
      const path = prefix ? `${prefix}.${key}` : key
      if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
        walk(value, path)
      } else {
        acc[path] = value
      }
    }
  }
  walk(JSON.parse(raw), '')
  return acc
}

const REQUIRED_KEYS = [
  'errors.projects.delete.confirmTypeName',
  'errors.projects.unavailable',
  'errors.projects.restore.renamedDueToConflict',
  'projects.create.limitReachedTooltip',
  'subscribers.profile.lifecycle.subscribedAt',
  'subscribers.profile.lifecycle.unsubscribedAt',
  'subscribers.profile.lifecycle.blockedAt',
  'subscribers.profile.lifecycle.deletedAt',
  'subscribers.profile.lifecycle.lastSeenAt',
  'subscribers.profile.customFields.saved',
  // Task 9 — funnels list + create/delete (10-funnels). Section is extensible (Task 10 adds editor keys).
  'funnels.title',
  'funnels.createButton',
  'funnels.status.draft',
  'funnels.status.active',
  'funnels.status.paused',
  'funnels.filter.all',
  'funnels.emptyState.title',
  'funnels.emptyState.cta',
  'funnels.deleteConfirm.title',
  'funnels.deleteConfirm.message',
  'funnels.deleteConfirm.confirm',
  'funnels.deleteConfirm.cancel',
  'funnels.form.name',
  'funnels.form.description',
  'errors.funnels.create.422',
  'errors.funnels.create.generic',
  'errors.funnels.list.generic',
  'errors.funnels.delete.generic',
  // Task 8 — composer message preview media labels. FunnelMessagePreview.vue resolves
  // `funnels.editor.previewMediaType.${block.type}` for VIDEO/AUDIO/FILE (and IMAGE on fallback);
  // these were missing in both locales (parity check passes on symmetric absence), so guard them here.
  'funnels.editor.previewMediaType.IMAGE',
  'funnels.editor.previewMediaType.VIDEO',
  'funnels.editor.previewMediaType.AUDIO',
  'funnels.editor.previewMediaType.FILE',
  // Task 6 (17-funnel-multi-entry) — FunnelTriggersPanel.vue labels. uk/en parity, no empty values.
  'funnels.triggersPanel.mainEntry',
  'funnels.triggersPanel.mainEntryHint',
  'funnels.triggersPanel.eventTriggers',
  'funnels.triggersPanel.add',
  'funnels.triggersPanel.delete',
  'funnels.triggersPanel.confirmDelete',
  'funnels.triggersPanel.unnamedEvent',
  'funnels.triggersPanel.entryLabel',
  'funnels.triggersPanel.entryStart',
  'funnels.triggersPanel.entryPlaceholder',
  'funnels.triggersPanel.entryLoading',
  'funnels.triggersPanel.entryEmpty',
  'funnels.triggersPanel.entryNoMatches',
  'funnels.triggersPanel.entryHint',
  'funnels.triggersPanel.duplicateEventName',
  'funnels.triggersPanel.empty.title',
  'funnels.triggersPanel.empty.body',
  // 18-funnel-canvas (Tasks 5-7) — FunnelCanvas / palette / side-panel / read-only-list visible text.
  // uk/en parity + non-empty (the canvas surfaces these as labels, warnings and ARIA strings).
  'funnels.canvas.aria',
  'funnels.canvas.title',
  'funnels.canvas.listReadOnly',
  'funnels.canvas.triggersReadOnly',
  'funnels.canvas.startNode',
  'funnels.canvas.note',
  'funnels.canvas.noteEditor.label',
  'funnels.canvas.noteEditor.placeholder',
  'funnels.canvas.handle.entry',
  'funnels.canvas.handle.input',
  'funnels.canvas.handle.outputNext',
  'funnels.canvas.handle.outputTimeout',
  'funnels.canvas.exitBadge',
  'funnels.canvas.brokenEdgesLabel',
  'funnels.canvas.brokenEdge',
  'funnels.canvas.brokenEdgeMissingTarget',
  'funnels.canvas.brokenEdgeStartNotConnected',
  'funnels.canvas.palette.title',
  'funnels.canvas.palette.steps',
  'funnels.canvas.palette.other',
  'funnels.canvas.palette.trigger',
  'funnels.canvas.palette.note',
  'funnels.canvas.palette.startNode',
  'funnels.canvas.palette.startNodeExists',
  'funnels.canvas.sidePanel.title',
  'funnels.canvas.sidePanel.close',
  'funnels.canvas.sidePanel.empty',
  'funnels.canvas.delete.button',
  'funnels.canvas.delete.warning',
] as const

// AC (17-funnel-multi-entry / 18-funnel-canvas): Triggers-panel AND canvas labels must show non-empty text
// ("без порожніх ключів") — presence alone is insufficient, so these keys get an extra value
// (trimmed length > 0) assertion in both locales. The OR keeps the existing triggersPanel coverage while
// adding the new funnels.canvas.* namespace.
const NON_EMPTY_REQUIRED_KEYS = REQUIRED_KEYS.filter(
  (k) => k.startsWith('funnels.triggersPanel.') || k.startsWith('funnels.canvas.'),
)

describe('i18n AC-25 required keys', () => {
  it('AC-25 keys present in uk.json', () => {
    const keys = loadKeys('uk.json')
    for (const key of REQUIRED_KEYS) {
      expect(keys, `missing ${key} in uk.json`).toHaveProperty(key)
    }
  })

  it('AC-25 keys present in en.json', () => {
    const keys = loadKeys('en.json')
    for (const key of REQUIRED_KEYS) {
      expect(keys, `missing ${key} in en.json`).toHaveProperty(key)
    }
  })

  it('triggersPanel + canvas keys have non-empty values in uk.json', () => {
    const values = loadValues('uk.json')
    for (const key of NON_EMPTY_REQUIRED_KEYS) {
      const value = values[key]
      expect(typeof value, `${key} in uk.json must be a string`).toBe('string')
      expect(
        (value as string).trim().length,
        `empty value for ${key} in uk.json`,
      ).toBeGreaterThan(0)
    }
  })

  it('triggersPanel + canvas keys have non-empty values in en.json', () => {
    const values = loadValues('en.json')
    for (const key of NON_EMPTY_REQUIRED_KEYS) {
      const value = values[key]
      expect(typeof value, `${key} in en.json must be a string`).toBe('string')
      expect(
        (value as string).trim().length,
        `empty value for ${key} in en.json`,
      ).toBeGreaterThan(0)
    }
  })
})
