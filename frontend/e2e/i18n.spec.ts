import { test, expect } from '@playwright/test'

test.use({ locale: 'uk-UA' })

test('switches locale on auth/login page', async ({ page }) => {
  await page.goto('/auth/login')
  await expect(page).toHaveURL(/\/auth\/login(?:\?.*)?$/)
  await expect(page.locator('html')).toHaveAttribute('lang', 'uk')
  await expect(page.getByRole('button', { name: 'Увійти' })).toBeVisible()
  // Wait for client-side hydration so the LangSwitcher click handler is wired up.
  await page.waitForLoadState('networkidle')

  await page.locator('[data-test="lang-en"]').click()
  await expect.poll(() => page.url(), { timeout: 10_000 }).toMatch(/\/en\/auth\/login/)
  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()

  await page.getByLabel('Email').fill('not-an-email')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.locator('[data-test="email-error"]')).toHaveText('Invalid email format')
})
