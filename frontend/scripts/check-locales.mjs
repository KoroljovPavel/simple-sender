import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve } from 'node:path'

const UNSAFE_KEYS = new Set(['__proto__', 'constructor', 'prototype'])

export function collectKeySet(obj, prefix = '', acc = Object.create(null)) {
  if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) return acc
  for (const key of Object.keys(obj)) {
    if (UNSAFE_KEYS.has(key)) continue
    const path = prefix ? `${prefix}.${key}` : key
    const value = obj[key]
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      collectKeySet(value, path, acc)
    } else {
      acc[path] = true
    }
  }
  return acc
}

export function compareKeySets(a, b) {
  const aKeys = Object.keys(a).sort()
  const bKeys = Object.keys(b).sort()
  const missingInB = aKeys.filter((k) => !(k in b))
  const missingInA = bKeys.filter((k) => !(k in a))
  return { missingInA, missingInB }
}

function readLocale(filePath) {
  if (!existsSync(filePath)) {
    throw new Error(`Locale file not found: ${filePath}`)
  }
  let raw
  try {
    raw = readFileSync(filePath, 'utf8')
  } catch (err) {
    throw new Error(`Cannot read ${filePath}: ${err.message}`)
  }
  try {
    return JSON.parse(raw)
  } catch (err) {
    throw new Error(`Invalid JSON in ${filePath}: ${err.message}`)
  }
}

export function checkLocales(localesDir) {
  const ukPath = resolve(localesDir, 'uk.json')
  const enPath = resolve(localesDir, 'en.json')

  const ukData = readLocale(ukPath)
  const enData = readLocale(enPath)

  const ukKeys = collectKeySet(ukData)
  const enKeys = collectKeySet(enData)
  const diff = compareKeySets(ukKeys, enKeys)

  return { ukPath, enPath, diff }
}

const isMain =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href

if (isMain) {
  const dir =
    process.env.LOCALES_DIR
      ? resolve(process.env.LOCALES_DIR)
      : fileURLToPath(new URL('../i18n/locales', import.meta.url))

  try {
    const { diff } = checkLocales(dir)
    if (diff.missingInA.length === 0 && diff.missingInB.length === 0) {
      process.exit(0)
    }
    if (diff.missingInB.length > 0) {
      console.error(`Keys present in uk.json but missing in en.json: ${diff.missingInB.join(', ')}`)
    }
    if (diff.missingInA.length > 0) {
      console.error(`Keys present in en.json but missing in uk.json: ${diff.missingInA.join(', ')}`)
    }
    process.exit(1)
  } catch (err) {
    console.error(err.message)
    process.exit(1)
  }
}
