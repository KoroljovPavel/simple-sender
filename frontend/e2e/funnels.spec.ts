import { test, expect, request as apiRequest } from '@playwright/test'

test.use({ locale: 'uk-UA' })

// ─── Prerequisites ──────────────────────────────────────────────────────────
// The golden path builds + activates a funnel against the REAL stack, so it needs a live backend AND a
// pre-seeded project whose bot is CONNECTED (activate resolves the bot for the deep-link / trigger guard).
// Those cannot be produced from the browser alone, so a per-test .env bootstrap supplies them:
//   E2E_FUNNELS_PROJECT_ID — a project owned by the login user, with a CONNECTED bot
//   E2E_FUNNELS_FUNNEL_ID  — (optional) a draft funnel in that project to open directly; when absent the
//                            golden path creates one through the UI
//   E2E_LOGIN_EMAIL / E2E_LOGIN_PASSWORD — credentials of the project owner
//   E2E_BACKEND_URL        — backend base (default http://localhost:8080)
// When the backend is down OR the bootstrap is absent, every test test.skip()s with a clear message — it
// MUST NOT fail the suite (Task 11 AC). Same real-backend + graceful-skip pattern as subscribers.spec.ts.
const BACKEND_URL = process.env.E2E_BACKEND_URL ?? 'http://localhost:8080'
const PROJECT_ID = process.env.E2E_FUNNELS_PROJECT_ID ?? ''
const SEED_FUNNEL_ID = process.env.E2E_FUNNELS_FUNNEL_ID ?? ''
const LOGIN_EMAIL = process.env.E2E_LOGIN_EMAIL ?? ''
const LOGIN_PASSWORD = process.env.E2E_LOGIN_PASSWORD ?? ''

const SKIP_BACKEND_MSG =
  'funnels.spec.ts skipped — backend not running on localhost:8080. Start with `cd backend && ./gradlew bootRun`.'
const SKIP_SEED_MSG =
  'funnels.spec.ts skipped — missing E2E bootstrap env (E2E_FUNNELS_PROJECT_ID, E2E_LOGIN_EMAIL, E2E_LOGIN_PASSWORD with a CONNECTED-bot project).'

let backendUp = false

test.beforeAll(async () => {
  // Health probe against the REAL endpoint. No Spring Actuator on the classpath (patterns.md), so
  // /actuator/health does not exist — /health is the permitAll liveness route.
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

const seedReady = !!(PROJECT_ID && LOGIN_EMAIL && LOGIN_PASSWORD)

async function login(page: import('@playwright/test').Page) {
  await page.goto('/auth/login')
  await page.waitForFunction(() => {
    const root = document.querySelector('#__nuxt') as { __vue_app__?: unknown } | null
    return !!root?.__vue_app__
  })
  await page.locator('#email').fill(LOGIN_EMAIL)
  await page.locator('#password').fill(LOGIN_PASSWORD)
  await page.locator('button[type="submit"]').click()
  await page.waitForURL(/\/(dashboard|projects)(?:\?.*)?$/)
}

// Opens an editor for a draft funnel: the seeded one if provided, else a fresh funnel created via the UI.
// Returns the funnelId taken from the resulting editor URL.
async function openDraftEditor(page: import('@playwright/test').Page): Promise<string> {
  if (SEED_FUNNEL_ID) {
    await page.goto(`/projects/${PROJECT_ID}/funnels/${SEED_FUNNEL_ID}`)
    await expect(page.locator('[data-test="funnel-canvas-host"]')).toBeVisible()
    return SEED_FUNNEL_ID
  }
  await page.goto(`/projects/${PROJECT_ID}/funnels`)
  await page.locator('[data-test="funnels-create-trigger"]').click()
  await page.locator('[data-test="funnel-name-input"]').fill(`E2E ${Date.now()}`)
  await page.locator('[data-test="funnel-create-submit"]').click()
  // CreateFunnelDialog navigates into the editor of the freshly-created draft.
  await page.waitForURL(new RegExp(`/projects/${PROJECT_ID}/funnels/[^/]+$`))
  const id = page.url().split('/').pop() as string
  await expect(page.locator('[data-test="funnel-canvas-host"]')).toBeVisible()
  return id
}

async function addSendMessage(page: import('@playwright/test').Page, text: string) {
  // STALE (18-funnel-canvas): the page-level "Add Step" button was removed — authoring is now canvas-driven
  // (palette → node → side panel). This helper still drives the old dialog flow and is only reached from
  // SKIPPED tests below; it must be migrated to the canvas palette before any test re-enabling it.
  // Dialog defaults to SEND_MESSAGE.
  await page.locator('[data-test="step-text-input"]').fill(text)
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/funnels/') && r.request().method() === 'PATCH'),
    page.locator('[data-test="step-form-submit"]').click(),
  ])
}

async function addDelay(page: import('@playwright/test').Page, value: number) {
  // STALE (18-funnel-canvas): "Add Step" button removed — canvas-driven authoring now. Reached only from
  // SKIPPED tests; migrate to the canvas palette before re-enabling.
  await page.locator('[data-test="step-type-select"]').selectOption('DELAY')
  await page.locator('[data-test="step-delay-value-input"]').fill(String(value))
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/funnels/') && r.request().method() === 'PATCH'),
    page.locator('[data-test="step-form-submit"]').click(),
  ])
}

// SKIPPED (15-message-composer): relies on the removed flat-step UI — the addSendMessage helper drives
// the old SEND_MESSAGE default + [data-test="step-text-input"], neither of which exists in the composer
// editor (now MESSAGE only, with per-block [data-test="step-block-text-N"]). Needs migration to the
// composer UI before re-enabling; cannot be migrated here (requires a live :3000 + :8080 stack to
// validate the new selector contract). Kept (not deleted) as a migration anchor.
test.skip('funnels_goldenPath_buildAndActivate', async ({ page }) => {
  test.skip(!backendUp, SKIP_BACKEND_MSG)
  test.skip(!seedReady, SKIP_SEED_MSG)

  await login(page)
  await openDraftEditor(page)

  // Build: Send Message → Delay → Send Message.
  await addSendMessage(page, 'Welcome!')
  await addDelay(page, 5)
  await addSendMessage(page, 'Still here?')
  await expect(page.locator('[data-test^="funnel-step-row-"]')).toHaveCount(3)

  // Reorder: move the last step (the 2nd "Still here?" message) up one → it lands at index 1.
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/funnels/') && r.request().method() === 'PATCH'),
    page.locator('[data-test="funnel-step-move-up-2"]').click(),
  ])
  await expect(page.locator('[data-test="funnel-step-row-1"]')).toContainText('Still here?')
  // Move it back down → returns to index 2, restoring the original order.
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/funnels/') && r.request().method() === 'PATCH'),
    page.locator('[data-test="funnel-step-move-down-1"]').click(),
  ])
  await expect(page.locator('[data-test="funnel-step-row-2"]')).toContainText('Still here?')

  // Trigger value → deep-link preview + Copy button.
  await page.locator('[data-test="funnel-trigger-value-input"]').fill('ref_e2e')
  await expect(page.locator('[data-test="funnel-trigger-deeplink"]')).toContainText('?start=ref_e2e')
  await expect(page.locator('[data-test="funnel-trigger-copy"]')).toBeVisible()

  // Activate → status flips to active.
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/activate') && r.request().method() === 'POST'),
    page.locator('[data-test="funnel-activate"]').click(),
  ])
  await expect(page.locator('[data-test="funnel-status-active"]')).toBeVisible()
})

// SKIPPED (15-message-composer): the MENU step-kind and its UI were removed. This test uses
// selectOption('MENU') + [data-test="step-menu-*"] selectors and the addSendMessage helper, none of which
// exist in the composer editor (buttons now attach to the last non-album block of a MESSAGE step via the
// composer's keyboard sub-editor — Decision 2). Needs migration to the composer UI before re-enabling;
// cannot be migrated here (requires a live :3000 + :8080 stack to validate the new selector contract).
// Kept (not deleted) as a migration anchor.
test.skip('funnels_menuGoldenPath_buildAndActivate', async ({ page }) => {
  test.skip(!backendUp, SKIP_BACKEND_MSG)
  test.skip(!seedReady, SKIP_SEED_MSG)

  await login(page)
  await openDraftEditor(page)

  // First add a plain SEND_MESSAGE so the MENU has a real step to target (besides "End").
  await addSendMessage(page, 'Welcome!')

  // Add a MENU with two callback buttons: one → the SEND_MESSAGE step, one → End.
  // STALE (18-funnel-canvas): "Add Step" button removed — canvas-driven authoring now (skipped test anchor).
  await page.locator('[data-test="step-type-select"]').selectOption('MENU')
  await page.locator('[data-test="step-menu-text-input"]').fill('Pick one:')

  // Button 0 (callback → the first step). The target picker is the SearchableSelect — pick the first
  // step option (label is "1. ...") rather than End.
  await page.locator('[data-test="step-menu-button-label-0"]').fill('Continue')
  await page.locator('[data-test="step-menu-target-0-input"]').click()
  await page.locator('[data-test^="step-menu-target-0-option-"]').first().click()

  // Button 1 (callback → End sentinel).
  await page.locator('[data-test="step-menu-add-button"]').click()
  await page.locator('[data-test="step-menu-button-label-1"]').fill('Finish')
  await page.locator('[data-test="step-menu-target-1-input"]').click()
  await page.locator('[data-test="step-menu-target-1-option-__END__"]').click()

  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/funnels/') && r.request().method() === 'PATCH'),
    page.locator('[data-test="step-form-submit"]').click(),
  ])
  await expect(page.locator('[data-test^="funnel-step-row-"]')).toHaveCount(2)

  // Activate → status flips to active (graph validates: ≥1 callback, all targets resolve).
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/activate') && r.request().method() === 'POST'),
    page.locator('[data-test="funnel-activate"]').click(),
  ])
  await expect(page.locator('[data-test="funnel-status-active"]')).toBeVisible()
})

