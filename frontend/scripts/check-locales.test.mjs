import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { collectKeySet, compareKeySets } from './check-locales.mjs'

const SCRIPT_PATH = fileURLToPath(new URL('./check-locales.mjs', import.meta.url))

function makeFixtureDir(files) {
  const dir = mkdtempSync(join(tmpdir(), 'check-locales-'))
  for (const [name, content] of Object.entries(files)) {
    writeFileSync(join(dir, name), content)
  }
  return dir
}

function runScript(localesDir) {
  return spawnSync(process.execPath, [SCRIPT_PATH], {
    env: { ...process.env, LOCALES_DIR: localesDir },
    encoding: 'utf8',
  })
}

describe('check-locales script', () => {
  it('parse OK — matching keys → exit 0', () => {
    const dir = makeFixtureDir({
      'uk.json': JSON.stringify({ a: { b: 'x' }, c: 'y' }),
      'en.json': JSON.stringify({ a: { b: 'X' }, c: 'Y' }),
    })
    try {
      const result = runScript(dir)
      assert.equal(result.status, 0, `stderr: ${result.stderr}`)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  it('missing file → exit 1', () => {
    const dir = makeFixtureDir({
      'uk.json': JSON.stringify({ a: 'x' }),
    })
    try {
      const result = runScript(dir)
      assert.equal(result.status, 1)
      assert.match(result.stderr, /not found|missing|ENOENT|en\.json/i)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  it('invalid JSON → exit 1', () => {
    const dir = makeFixtureDir({
      'uk.json': JSON.stringify({ a: 'x' }),
      'en.json': '{ invalid json',
    })
    try {
      const result = runScript(dir)
      assert.equal(result.status, 1)
      assert.match(result.stderr, /parse|invalid|json/i)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  it('key-divergence → exit 1 with missing keys listed', () => {
    const dir = makeFixtureDir({
      'uk.json': JSON.stringify({ auth: { login: { title: 'Увійти' } } }),
      'en.json': JSON.stringify({ auth: { login: {} } }),
    })
    try {
      const result = runScript(dir)
      assert.equal(result.status, 1)
      assert.match(result.stderr, /auth\.login\.title/)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  it('prototype-pollution payload is safe — Object.prototype not polluted', () => {
    const payload = JSON.parse('{"__proto__": {"polluted": "yes"}, "real": "ok"}')
    const keys = collectKeySet(payload)
    assert.equal(({}).polluted, undefined, 'Object.prototype was polluted')
    assert.equal(keys.real, true)
    assert.equal(keys.polluted, undefined, 'unsafe key __proto__.polluted leaked into key set')
  })

  it('compareKeySets reports both sides of divergence', () => {
    const a = collectKeySet({ x: 1, y: { z: 1 } })
    const b = collectKeySet({ y: { z: 1 }, w: 1 })
    const diff = compareKeySets(a, b)
    assert.deepEqual(diff.missingInB, ['x'])
    assert.deepEqual(diff.missingInA, ['w'])
  })
})
