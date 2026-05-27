import { test, expect, request as apiRequest } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { seedStartUpdate } from './helpers/webhook-fixture'

test.use({ locale: 'uk-UA' })

// ─── Prerequisites ──────────────────────────────────────────────────────────
// This golden-path spec drives the FULL 09-subscribers epic end-to-end, so it needs a live backend
// AND a pre-seeded project whose bot is CONNECTED (the webhook handler verifies the bot + secret before
// it will register a subscriber). Those cannot be produced from the browser alone, so they are supplied
// by a per-test .env bootstrap:
//   E2E_SUBSCRIBERS_PROJECT_ID  — a fresh project owned by the login user, with a CONNECTED bot
//   E2E_WEBHOOK_SECRET          — that bot's plaintext webhook secret (X-Telegram-Bot-Api-Secret-Token)
//   E2E_LOGIN_EMAIL / E2E_LOGIN_PASSWORD — credentials of the project owner
//   E2E_BACKEND_URL             — backend base (default http://localhost:8080)
// When the backend is down OR the bootstrap is absent, the spec test.skip()s with a clear message — it
// MUST NOT fail the suite (Task 11 AC).
const BACKEND_URL = process.env.E2E_BACKEND_URL ?? 'http://localhost:8080'
const PROJECT_ID = process.env.E2E_SUBSCRIBERS_PROJECT_ID ?? ''
const WEBHOOK_SECRET = process.env.E2E_WEBHOOK_SECRET ?? ''
const LOGIN_EMAIL = process.env.E2E_LOGIN_EMAIL ?? ''
const LOGIN_PASSWORD = process.env.E2E_LOGIN_PASSWORD ?? ''

const SKIP_BACKEND_MSG =
  'subscribers.spec.ts skipped — backend not running on localhost:8080. Start with `cd backend && ./gradlew bootRun`.'
const SKIP_SEED_MSG =
  'subscribers.spec.ts skipped — missing E2E bootstrap env (E2E_SUBSCRIBERS_PROJECT_ID, E2E_WEBHOOK_SECRET, E2E_LOGIN_EMAIL, E2E_LOGIN_PASSWORD with a CONNECTED-bot project).'

let backendUp = false

test.beforeAll(async () => {
  // Health probe against the REAL endpoint. This project has no Spring Actuator on the classpath
  // (patterns.md), so /actuator/health does not exist — /health is the permitAll liveness route.
  try {
    const ctx = await apiRequest.newContext()
    const resp = await ctx.get(`${BACKEND_URL}/health`, { timeout: 3000 })
    backendUp = resp.ok()
    await ctx.dispose()
  } catch {
    backendUp = false
  }
  if (!backendUp) console.log(SKIP_BACKEND_MSG)
})

const seedReady = !!(PROJECT_ID && WEBHOOK_SECRET && LOGIN_EMAIL && LOGIN_PASSWORD)

test('subscribers_goldenPath_endToEnd', async ({ page, request }) => {
  test.skip(!backendUp, SKIP_BACKEND_MSG)
  test.skip(!seedReady, SKIP_SEED_MSG)

  const subscribersPath = `/projects/${PROJECT_ID}/subscribers`

  // ── Step 1: login → /subscribers (empty state) ──────────────────────────────
  await page.goto('/auth/login')
  await page.waitForFunction(() => {
    const root = document.querySelector('#__nuxt') as { __vue_app__?: unknown } | null
    return !!root?.__vue_app__
  })
  await page.locator('#email').fill(LOGIN_EMAIL)
  await page.locator('#password').fill(LOGIN_PASSWORD)
  await page.locator('button[type="submit"]').click()
  await page.waitForURL(/\/(dashboard|projects)(?:\?.*)?$/)

  await page.goto(subscribersPath)
  // Fresh seed project → empty state. (The bootstrap owns a project with no subscribers yet.)
  await expect(page.locator('[data-test="subscribers-empty"]')).toBeVisible()

  // ── Step 2: seed two subscribers via fake webhook → one row visible ─────────
  const PETRO = { telegramUserId: 12345, chatId: 12345, firstName: 'Petro' }
  const IVANNA = { telegramUserId: 67890, chatId: 67890, firstName: 'Ivanna' }
  await seedStartUpdate(request, BACKEND_URL, PROJECT_ID, WEBHOOK_SECRET, PETRO)
  await seedStartUpdate(request, BACKEND_URL, PROJECT_ID, WEBHOOK_SECRET, IVANNA)

  await page.goto(subscribersPath) // re-mount → store.loadFirstPage refetches
  const rows = page.locator('[data-test^="subscribers-row-"]')
  await expect.poll(() => rows.count(), { timeout: 10_000 }).toBeGreaterThanOrEqual(2)

  // ── Step 3: search "Iva" → exactly one row (text-index match) ───────────────
  await page.locator('[data-test="subscribers-filter-search"]').fill('Iva')
  await expect.poll(() => rows.count(), { timeout: 10_000 }).toBe(1)

  // ── Step 4: open profile → Tabs rendered ────────────────────────────────────
  await rows.first().click()
  await page.waitForURL(new RegExp(`/projects/${PROJECT_ID}/subscribers/[^/]+`))
  await expect(page.locator('[data-test="subscriber-tab-tags"]')).toBeVisible()
  await expect(page.locator('[data-test="subscriber-tab-custom-fields"]')).toBeVisible()
  await expect(page.locator('[data-test="subscriber-tab-history"]')).toBeVisible()
  const profileUrl = page.url()

  // ── Step 5: add tag "vip" → chip; /tags counter = 1 ─────────────────────────
  await page.locator('[data-test="subscriber-tab-tags"]').click()
  await page.locator('[data-test="subscriber-tags-combobox"]').fill('vip')
  await page.locator('[data-test="subscriber-tag-add"]').click()
  await expect(page.locator('[data-test="subscriber-tag-chip-vip"]')).toBeVisible()

  await page.goto(`/projects/${PROJECT_ID}/tags`)
  const vipRow = page.locator('[data-test="tag-row-vip"]')
  await expect(vipRow).toBeVisible()
  await expect(vipRow).toContainText('1')

  // ── Step 6: add custom field city=string; set per-subscriber value "Київ" ────
  await page.goto(`/projects/${PROJECT_ID}/custom-fields`)
  await page.locator('[data-test="custom-fields-add-trigger"]').click()
  await page.locator('[data-test="custom-field-name-input"]').fill('city')
  await page.locator('[data-test="custom-field-label-input"]').fill('City')
  // type select defaults to STRING.
  await page.locator('[data-test="custom-field-add-submit"]').click()
  await expect(page.locator('[data-test="custom-field-row-city"]')).toBeVisible()

  await page.goto(profileUrl)
  await page.locator('[data-test="subscriber-tab-custom-fields"]').click()
  await page.locator('[data-test="subscriber-custom-field-city-input"]').fill('Київ')
  // Await the PATCH so a save failure fails HERE (clear locus) rather than masquerading as a CSV-parse
  // failure in step 7.
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/custom-fields') && r.request().method() === 'PATCH'),
    page.locator('[data-test="subscriber-custom-field-city-save"]').click(),
  ])

  // ── Step 7: Export CSV → poll Recent exports until DONE → download + parse ───
  await page.goto(subscribersPath)
  await page.locator('[data-test="subscribers-export-trigger"]').click()
  await page.locator('[data-test="export-submit"]').click()

  // No auto-poll in RecentExportsDialog (Decision 8): re-open the dialog until a DONE row exposes a
  // Download link. CSV is tiny (2 rows), so 30s is generous; the loop times out cleanly.
  const deadline = Date.now() + 30_000
  const downloadLink = page.locator('[data-test^="recent-export-download-"]').first()
  const dialog = page.locator('[role="dialog"]')
  // eslint-disable-next-line no-constant-condition
  while (true) {
    // Reopen idempotently: only click the trigger when the dialog is actually closed, so a click never
    // toggles an already-open dialog shut (would otherwise misattribute a toggle flake to a stuck export).
    if ((await dialog.count()) === 0) {
      await page.locator('[data-test="subscribers-recent-exports-trigger"]').click()
    }
    if ((await downloadLink.count()) && (await downloadLink.isVisible())) break
    await page.keyboard.press('Escape') // close dialog
    await expect(dialog).toHaveCount(0)
    if (Date.now() > deadline) {
      throw new Error(
        'export did not reach DONE within 30s — Recent exports never showed a Download link (export likely stuck PENDING/RUNNING)',
      )
    }
    await page.waitForTimeout(1500)
  }

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    downloadLink.click(),
  ])
  const filePath = await download.path()
  expect(filePath).toBeTruthy()
  const csv = await readFile(filePath as string, 'utf8')
  expect(csv.charCodeAt(0)).toBe(0xfeff) // UTF-8 BOM
  expect(csv).toContain('custom_fields') // header column
  expect(csv).toContain('Київ') // Cyrillic value survives the round-trip

  // ── Step 8: manual unsubscribe → status badge flips within 1s ───────────────
  // Task 10's manual-unsubscribe is a direct action (no confirm modal).
  await page.goto(profileUrl)
  await page.locator('[data-test="subscriber-manual-unsubscribe"]').click()
  await expect
    .poll(() => page.locator('[data-test="subscriber-status-badge"]').getAttribute('data-status'), {
      timeout: 1000,
    })
    .toBe('unsubscribed')
})
