import { expect, test, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'

const email = process.env.CPDTS_E2E_A
const password = process.env.CPDTS_E2E_PASSWORD
const nonce = process.env.CPDTS_E2E_NONCE
const backendPort = process.env.CPDTS_HTTPS_BACKEND_PORT
if (!email || !password || !nonce || !backendPort) {
  throw new Error('Run through scripts/run_browser_tests.py; the HTTPS guarded fixtures are required.')
}

async function guard(page: Page) {
  const response = await page.request.get('/api/v1/auth/csrf')
  expect(response.headers()['x-cpdts-browser-test']).toBe(nonce)
}

async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Email address').fill(email!)
  await page.getByLabel('Password').fill(password!)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible()
}

async function choose(page: Page, code: string) {
  const select = page.getByLabel('Company')
  if (await select.count()) {
    const value = await select.locator('option').filter({ hasText: `(${code})` }).getAttribute('value')
    if (!value) throw new Error('Expected company is unavailable')
    await select.selectOption(value)
  }
}

async function selectedCompany(page: Page): Promise<string> {
  return ((await page.getByLabel('Company').locator('option:checked').textContent()) ?? '').trim()
}

test('HTTPS origin: secure session, restore, deep links, company switch and CSRF', async ({ page, context }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await guard(page)
  await login(page)

  const session = (await context.cookies()).find(cookie => cookie.name === '__Host-CPDTS_SESSION')
  expect(session).toBeDefined()
  expect(session!.httpOnly).toBe(true)
  expect(session!.secure).toBe(true)
  expect(session!.sameSite).toBe('Lax')
  expect(session!.path).toBe('/')
  expect(session!.domain.startsWith('.')).toBe(false)

  // Full-page refresh restores the server-side session from the secure cookie.
  await page.reload()
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible()

  // A direct deep link is served as the SPA shell, then the route renders
  // after the company is selected. Another refresh must keep the same route.
  await page.goto('/customers')
  await expect(page).toHaveURL(/\/customers$/)
  await choose(page, 'JMX')
  await expect(page.getByRole('heading', { name: 'Customers' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Select a company' })).toBeVisible()
  await expect(page).toHaveURL(/\/customers$/)
  await choose(page, 'JMX')
  await expect(page.getByRole('heading', { name: 'Customers' })).toBeVisible()

  // Company switching stays on the same route and changes the scoped view.
  await choose(page, 'KAM')
  await expect.poll(() => selectedCompany(page)).toContain('KAM')
  await expect(page.getByRole('heading', { name: 'Customers' })).toBeVisible()
  await choose(page, 'JMX')
  await expect.poll(() => selectedCompany(page)).toContain('JMX')

  const companies = await page.evaluate(async () =>
    (await (await fetch('/api/v1/auth/companies')).json()) as { id: number; code: string }[])
  const jmx = companies.find(company => company.code === 'JMX')!.id

  // A state-changing API call without the CSRF header is rejected and leaves
  // no record, while the session itself remains valid.
  const rejectedName = `CSRF rejected ${randomUUID()}`
  const csrfMissing = await page.request.post(`/api/v1/companies/${jmx}/customers`, {
    data: { displayName: rejectedName },
  })
  expect(csrfMissing.status()).toBe(403)
  expect(csrfMissing.headers()['content-type']).toContain('application/json')
  const rejectedSearch = await page.request.get(
    `/api/v1/companies/${jmx}/customers?q=${encodeURIComponent(rejectedName)}`)
  expect(rejectedSearch.status()).toBe(200)
  expect((await rejectedSearch.json()).items).toHaveLength(0)

  // The UI uses the session CSRF token and succeeds through the same origin.
  const createdName = `HTTPS customer ${randomUUID()}`
  await page.getByRole('link', { name: 'Customers' }).click()
  await page.getByRole('button', { name: 'Add customer' }).click()
  await page.getByLabel(/Display name/).fill(createdName)
  const created = page.waitForResponse(response =>
    /\/customers$/.test(new URL(response.url()).pathname) &&
    response.request().method() === 'POST' && response.status() === 201)
  await page.getByRole('button', { name: 'Save customer' }).click()
  await created
  await expect(page.getByText('Customer created.')).toBeVisible()

  // API failures stay API responses; they are never replaced by index.html.
  const apiMissing = await page.request.get('/api/v1/this-route-does-not-exist')
  expect(apiMissing.status()).toBe(404)
  expect(apiMissing.headers()['content-type']).toContain('application/json')
  expect(await apiMissing.text()).not.toContain('<div id="root">')

  // Missing build assets are also not SPA routes.
  const assetMissing = await page.request.get('/assets/does-not-exist.js')
  expect(assetMissing.status()).toBe(404)
  expect(await assetMissing.text()).not.toContain('<div id="root">')

  const shell = await page.request.get('/')
  expect(shell.status()).toBe(200)
  expect(shell.headers()['content-type']).toContain('text/html')
  expect(shell.headers()['strict-transport-security']).toContain('max-age=')
  expect(await shell.text()).toContain('<div id="root">')

  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()

  // After logout a protected deep link falls back to sign-in.
  await page.goto('/customers')
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
})

test('forwarded headers are trusted only from the proxy boundary', async ({ page }) => {
  // Spoofed headers sent directly to the private backend are rejected.
  const spoofed = await page.request.get(`http://127.0.0.1:${backendPort}/api/v1/auth/csrf`, {
    headers: {
      'X-Forwarded-Proto': 'https',
      'X-Forwarded-For': '203.0.113.9',
      Forwarded: 'for=203.0.113.9;proto=https',
    },
  })
  expect(spoofed.status()).toBe(400)
  expect(spoofed.headers()['content-type']).toContain('application/json')
  expect(await spoofed.text()).toContain('untrusted_forwarded_headers')

  // The same client headers through the proxy are stripped/overwritten, and
  // the proxy's boundary token is accepted.
  const proxied = await page.request.get('/api/v1/auth/csrf', {
    headers: {
      'X-Forwarded-Proto': 'http',
      'X-Forwarded-For': '203.0.113.9',
      Forwarded: 'for=203.0.113.9;proto=http',
    },
  })
  expect(proxied.status()).toBe(200)
  expect(proxied.headers()['x-cpdts-browser-test']).toBe(nonce)
})
