import { test, expect } from '@playwright/test'

// Golden-path E2E for the 05-projects feature.
//
// Scope (single test block, no per-locale variants — locale switching is
// covered by e2e/i18n.spec.ts):
//   register → dashboard empty-state → create #1 → selector populated →
//   create #2 → switch via dropdown → settings rename →
//   selector live-update (no page.reload) → soft-delete via type-the-name
//   modal → /projects "Recently deleted" → restore → both projects active.
//
// Selectors use the data-test contract actually shipped by Wave 3 tasks
// 6/7/8/9 (see work/05-projects/decisions.md task 10 for the naming map).
// Locale defaults to uk (Nuxt i18n prefix_except_default); URL regexes
// tolerate either /dashboard or /en/dashboard so the spec is locale-agnostic.

test('golden path: register → create → switch → rename → soft-delete → restore', async ({ page }) => {
  // Random suffix on top of Date.now() guards against collisions when the
  // spec runs more than once per millisecond (Playwright retries, parallel
  // workers). A 409 on register would abort the rest of the flow.
  const stamp = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const email = `e2e-projects-${stamp}@test.local`
  const password = 'Test1234!'
  const project1Name = `Acme ${stamp}`
  const project2Name = `Brand X ${stamp}`
  const renamedName = `${project1Name} Renamed`

  // Step 1: register fresh user. Email is randomized per run so the spec is
  // idempotent under playwright.config.ts `reuseExistingServer: !CI`.
  await page.goto('/auth/register')

  // Hydration gate. In dev mode SSR HTML is interactive before Vue mounts;
  // a submit click before @submit.prevent attaches fires a native HTML GET
  // (/auth/register?email=...&password=...) and the redirect to /dashboard
  // never happens — this is the AC-28 race that Task 14 caught. We wait for
  // Vue's root app instance to report mounted before any interaction.
  // Subsequent navigations are client-side (Vue Router), so no further gate
  // is needed in this spec. `networkidle` is intentionally NOT used because
  // Nuxt's HMR WebSocket keeps the channel busy in dev mode.
  await page.waitForFunction(() => {
    const root = document.querySelector('#__nuxt') as (Element & { __vue_app__?: { _instance?: { isMounted?: boolean } } }) | null
    return root?.__vue_app__?._instance?.isMounted === true
  })

  await page.locator('#email').fill(email)
  await page.locator('#password').fill(password)
  await page.locator('#confirmPassword').fill(password)
  await page.locator('button[type="submit"]').click()

  // Step 2: auto-login redirects to /dashboard with the inline empty-state CTA
  // (AC-18). The CTA is visible only when isLoaded && projects.length === 0,
  // so its visibility implicitly proves the projects store finished its first
  // fetch.
  await page.waitForURL(/\/dashboard(?:\?.*)?$/)
  const emptyState = page.locator('[data-test="dashboard-empty-state"]')
  const emptyCta = page.locator('[data-test="dashboard-empty-state-cta"]')
  await expect(emptyCta).toBeVisible()
  await emptyCta.click()

  // Step 3: create the first project. Timezone defaults to the browser zone;
  // description is left blank.
  await page.waitForURL(/\/projects\/new(?:\?.*)?$/)
  await page.locator('[data-test="project-name-input"]').fill(project1Name)
  await page.locator('[data-test="project-submit"]').click()

  // Step 4: redirect to /dashboard with the topbar selector populated (AC-19).
  // Also assert the empty-state container disappeared — guards the
  // AC-18 → AC-19 transition (a regression that keeps showing the empty
  // CTA on a non-empty dashboard would otherwise slip through).
  await page.waitForURL(/\/dashboard(?:\?.*)?$/)
  const selectorTrigger = page.locator('[data-test="project-selector-trigger"]')
  await expect(emptyState).toBeHidden()
  await expect(selectorTrigger).toContainText(project1Name)

  // Step 5: open the selector and create a second project via the dropdown
  // "+ Create new project" entry.
  await selectorTrigger.click()
  await page.locator('[data-test="project-selector-create"]').click()
  await page.waitForURL(/\/projects\/new(?:\?.*)?$/)
  await page.locator('[data-test="project-name-input"]').fill(project2Name)
  await page.locator('[data-test="project-submit"]').click()
  await page.waitForURL(/\/dashboard(?:\?.*)?$/)
  // After create, the new project becomes current (sortByCreatedAtDesc).
  await expect(selectorTrigger).toContainText(project2Name)

  // Step 6: open the selector — both projects visible — switch back to #1.
  // AC-21: switching updates the in-memory store; the trigger reflects it.
  await selectorTrigger.click()
  const items = page.locator('[data-test^="project-selector-item-"]')
  await expect(items).toHaveCount(2)
  await items.filter({ hasText: project1Name }).click()
  await expect(selectorTrigger).toContainText(project1Name)

  // Step 7: navigate to the current project's settings via the dropdown link.
  await selectorTrigger.click()
  await page.locator('[data-test="project-selector-settings"]').click()
  await page.waitForURL(/\/projects\/[^/]+\/settings(?:\?.*)?$/)

  // Step 8: rename → submit → selector live-updates WITHOUT page.reload
  // (AC-22a, the critical reactive path).
  await page.locator('[data-test="settings-name-input"]').fill(renamedName)
  await page.locator('[data-test="settings-submit"]').click()
  await expect(page.locator('[data-test="settings-saved"]')).toBeVisible()
  await expect(selectorTrigger).toContainText(renamedName)

  // Step 9: danger-zone delete with type-the-name modal (AC-22d).
  await page.locator('[data-test="delete-project-open"]').click()
  await expect(page.locator('[data-test="delete-project-modal"]')).toBeVisible()

  const confirmButton = page.locator('[data-test="delete-project-confirm"]')
  const confirmInput = page.locator('[data-test="delete-project-name-input"]')

  // Empty input → disabled.
  await expect(confirmButton).toBeDisabled()
  // Partial match → still disabled.
  await confirmInput.fill(renamedName.slice(0, -1))
  await expect(confirmButton).toBeDisabled()
  // Exact match → enabled; confirm.
  await confirmInput.fill(renamedName)
  await expect(confirmButton).toBeEnabled()
  await confirmButton.click()

  // settings.vue redirects to /projects when at least one active project
  // remains (project2 is still active).
  await page.waitForURL(/\/projects(?:\?.*)?$/)

  // Step 10: /projects shows the "Recently deleted" section with the deleted
  // project (AC-23). The soft-deleted project must NOT appear in the topbar
  // selector anymore.
  const deletedRow = page.locator('[data-test^="deleted-row-"]', { hasText: renamedName })
  await expect(page.locator('[data-test="recently-deleted-section"]')).toBeVisible()
  await expect(deletedRow).toBeVisible()

  await selectorTrigger.click()
  await expect(items).toHaveCount(1)
  await expect(items).toContainText(project2Name)
  // Close the dropdown before clicking the restore button below.
  await page.keyboard.press('Escape')

  // Step 11: restore → project returns to Active and reappears in the topbar
  // selector (AC-19/AC-21 path end-to-end).
  await deletedRow.locator('[data-test^="restore-button-"]').click()
  await expect(deletedRow).toBeHidden()

  await selectorTrigger.click()
  await expect(items).toHaveCount(2)
  await expect(items.filter({ hasText: renamedName })).toBeVisible()
})
