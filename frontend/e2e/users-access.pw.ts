import { expect, test, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'

const a = process.env.CPDTS_E2E_A, b = process.env.CPDTS_E2E_B, platform = process.env.CPDTS_E2E_PLATFORM
const password = process.env.CPDTS_E2E_PASSWORD, nonce = process.env.CPDTS_E2E_NONCE
const createdEmail = process.env.CPDTS_E2E_CREATED
if (!a || !b || !platform || !password || !nonce || !createdEmail) throw new Error('Use the guarded browser test runner.')

async function login(page: Page, email: string, secret: string) {
  await page.goto('/login')
  await page.getByLabel('Email address').fill(email)
  await page.getByLabel('Password').fill(secret)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible()
}
async function logout(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
}
async function choose(page: Page, code: string) {
  const select = page.getByLabel('Company')
  if (await select.count()) {
    const value = await select.locator('option').filter({ hasText: `(${code})` }).getAttribute('value')
    if (!value) throw new Error('Test company unavailable')
    await select.selectOption(value)
  }
}

test('company user roles, status, platform attachment and access isolation', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  const guard = await page.request.get('/api/v1/auth/csrf')
  expect(guard.headers()['x-cpdts-browser-test']).toBe(nonce)
  page.on('dialog', dialog => dialog.accept())
  await login(page, a!, password!)
  const companies = await page.evaluate(async () => (await (await fetch('/api/v1/auth/companies')).json()) as { id: number; code: string }[])
  const kam = companies.find(c => c.code === 'KAM')!.id
  await choose(page, 'JMX')
  await expect(page.getByRole('link', { name: 'Users & Access' })).toHaveCount(0)
  await page.goto('/users')
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible()
  await choose(page, 'JMX')
  await expect(page.getByText('Users & Access unavailable')).toBeVisible()
  await logout(page)

  await login(page, b!, password!)
  const forbidden = await page.evaluate(async id => (await fetch(`/api/v1/companies/${id}/users`)).status, kam)
  expect(forbidden).toBe(403)
  await page.getByRole('link', { name: 'Users & Access' }).click()
  await expect(page.getByRole('heading', { name: 'Company members' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Attach existing account' })).toHaveCount(0)
  const name = `Browser Member ${randomUUID()}`
  const email = createdEmail!
  const newPassword = randomUUID()
  await page.getByRole('button', { name: 'Create user' }).click()
  const create = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Create user' }) })
  await create.getByLabel('Email').fill(email)
  await create.getByLabel('Full name').fill(name)
  await create.getByLabel('Password').fill(newPassword)
  await create.getByLabel('Membership role').selectOption('COMPANY_ADMIN')
  await expect(create.getByRole('option', { name: 'PLATFORM ADMIN' })).toHaveCount(0)
  await create.getByRole('button', { name: 'Create user' }).click()
  await expect(page.getByText('User and membership created.')).toBeVisible()
  let row = page.getByRole('row').filter({ hasText: email })
  await expect(row).toContainText('COMPANY ADMIN')
  await row.getByRole('button', { name: `Change role for ${name}` }).click()
  await page.getByLabel('New membership role').selectOption('FINANCE_OFFICER')
  await page.getByRole('button', { name: 'Save role' }).click()
  await expect(page.getByText('Membership updated.')).toBeVisible()
  row = page.getByRole('row').filter({ hasText: email })
  await expect(row).toContainText('FINANCE OFFICER')
  await row.getByRole('button', { name: `Deactivate membership for ${name}` }).click()
  await expect.poll(async () => (await page.getByRole('row').filter({ hasText: email }).innerText()).includes('INACTIVE')).toBeTruthy()
  row = page.getByRole('row').filter({ hasText: email })
  await expect(row).toContainText('ACTIVE') // Global account remains active.
  await row.getByRole('button', { name: `Activate membership for ${name}` }).click()
  await expect(page.getByRole('row').filter({ hasText: email }).getByRole('button', { name: `Deactivate membership for ${name}` })).toBeVisible()
  await page.getByRole('row').filter({ hasText: email }).getByRole('button', { name: `Change role for ${name}` }).click()
  await page.getByLabel('New membership role').selectOption('COMPANY_ADMIN')
  await page.getByRole('button', { name: 'Save role' }).click()
  await expect(page.getByRole('row').filter({ hasText: email })).toContainText('COMPANY ADMIN')
  await logout(page)

  await login(page, platform!, password!)
  await choose(page, 'KAM')
  await page.getByRole('link', { name: 'Users & Access' }).click()
  await page.getByRole('button', { name: 'Attach existing account' }).click()
  await page.getByLabel('Email', { exact: true }).fill(email)
  await expect(page.getByLabel('Password', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Attach account' }).click()
  await expect(page.getByText('Existing account attached to this company.')).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: email })).toContainText('VIEWER')
  await logout(page)

  await login(page, email, newPassword)
  await choose(page, 'JMX')
  await page.getByRole('link', { name: 'Users & Access' }).click()
  await page.getByRole('button', { name: `Deactivate membership for ${name}` }).click()
  await expect(page.getByText('Users & Access unavailable')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Company members' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Users & Access' })).toHaveCount(0)
  await logout(page)
})

test('users and access form remains usable on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const guard = await page.request.get('/api/v1/auth/csrf')
  expect(guard.headers()['x-cpdts-browser-test']).toBe(nonce)
  page.on('dialog', dialog => dialog.accept())
  await login(page, b!, password!)
  await page.getByRole('link', { name: 'Users & Access' }).click()
  await page.getByRole('button', { name: 'Create user' }).click()
  const form = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Create user' }) })
  await form.getByLabel('Email').fill('mobile@example.test')
  await expect(form.getByLabel('Password')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
  await form.getByRole('button', { name: 'Cancel' }).click()
  await expect(page.getByLabel('Password')).toHaveCount(0)
  await logout(page)
})
