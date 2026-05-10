import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { collectKeySet } from '../../scripts/check-locales.mjs'

const localesDir = resolve(__dirname, '../../i18n/locales')

function loadKeys(file: string): Record<string, true> {
  const raw = readFileSync(resolve(localesDir, file), 'utf8')
  return collectKeySet(JSON.parse(raw))
}

const REQUIRED_KEYS = [
  'errors.projects.delete.confirmTypeName',
  'errors.projects.unavailable',
  'errors.projects.restore.renamedDueToConflict',
  'projects.create.limitReachedTooltip',
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
