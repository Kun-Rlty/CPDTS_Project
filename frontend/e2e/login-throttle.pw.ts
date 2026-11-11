import { expect, test, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'

const password = process.env.CPDTS_E2E_PASSWORD, nonce = process.env.CPDTS_E2E_NONCE
if (!password || !nonce) throw new Error('Run through scripts/run_browser_tests.py; guarded test credentials are required.')

async function guard(page: Page) {
  const response = await page.request.get('/api/v1/auth/csrf')
  expect(response.headers()['x-cpdts-browser-test']).toBe(nonce)
}

type LoginResult = { status: number; retryAfter: string | null; body: string }

/** One real POST /api/v1/auth/login through the running application. */
async function attemptLogin(page: Page, email: string, attemptPassword: string): Promise<LoginResult> {
  return page.evaluate(async ({ email, attemptPassword }) => {
    const token = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).token
    const response = await fetch('/api/v1/auth/login', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
      body: JSON.stringify({ email, password: attemptPassword }),
    })
    return { status: response.status, retryAfter: response.headers.get('Retry-After'), body: await response.text() }
  }, { email, attemptPassword })
}

test('login throttling returns identical 401s, then 429 with Retry-After, and the UI explains the cooldown', async ({ page }) => {
  await guard(page)
  await page.goto('/login')
  // Fabricated UNKNOWN account only: never a seeded fixture account, so this
  // test cannot poison the buckets other tests rely on.
  const email = `throttle-probe-${randomUUID()}@example.test`

  // 1-5: identical invalid-credential responses below the threshold
  // (account 5 / IP 20 prototype defaults in the guarded server).
  const failures: LoginResult[] = []
  for (let i = 0; i < 5; i++) failures.push(await attemptLogin(page, email, 'definitely-not-the-password'))
  for (const result of failures) {
    expect(result.status).toBe(401)
    expect(JSON.parse(result.body).error).toBe('invalid_credentials')
  }
  expect(new Set(failures.map(result => result.body)).size).toBe(1)

  // 6th attempt: JSON 429 with a numeric delta-seconds Retry-After.
  const throttled = await attemptLogin(page, email, 'definitely-not-the-password')
  expect(throttled.status).toBe(429)
  expect(throttled.retryAfter).toMatch(/^\d+$/)
  expect(Number(throttled.retryAfter)).toBeGreaterThanOrEqual(290)
  expect(Number(throttled.retryAfter)).toBeLessThanOrEqual(300)
  expect(JSON.parse(throttled.body).error).toBe('login_throttled')

  // The UI explains the temporary pause, clears the password, disables retry
  // with a countdown, and never resubmits the password automatically.
  await page.getByLabel('Email address').fill(email)
  await page.getByLabel('Password').fill('definitely-not-the-password')
  let loginPosts = 0
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/auth/login') loginPosts++
  })
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page.getByRole('alert')).toContainText('Too many sign-in attempts. Sign-in is temporarily paused')
  await expect(page.getByRole('alert')).toContainText('No account is permanently locked.')
  await expect(page.getByLabel('Password')).toHaveValue('')
  const retryButton = page.getByRole('button', { name: /Try again in \d+s/ })
  await expect(retryButton).toBeDisabled()

  // Wait past several countdown ticks: still disabled, still exactly one
  // login POST - no automatic resubmission of the password.
  await page.waitForTimeout(2500)
  expect(loginPosts).toBe(1)
  await expect(retryButton).toBeDisabled()
  await expect(page.getByLabel('Password')).toHaveValue('')
})
