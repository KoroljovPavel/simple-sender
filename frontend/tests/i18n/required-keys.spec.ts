import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { collectKeySet } from '../../scripts/check-locales.mjs'

const localesDir = resolve(process.cwd(), 'i18n/locales')

function loadKeys(file: string): Record<string, true> {
  const raw = readFileSync(resolve(localesDir, file), 'utf8')
  return collectKeySet(JSON.parse(raw))
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
] as const

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
})
