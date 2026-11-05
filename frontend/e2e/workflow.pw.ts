import { expect, test, type Page, type Route } from '@playwright/test'
import { randomUUID } from 'node:crypto'

const a = process.env.CPDTS_E2E_A, b = process.env.CPDTS_E2E_B
const password = process.env.CPDTS_E2E_PASSWORD, nonce = process.env.CPDTS_E2E_NONCE
if (!a || !b || !password || !nonce) throw new Error('Run through scripts/run_browser_tests.py; guarded test credentials are required.')

async function guard(page: Page) {
  const response = await page.request.get('/api/v1/auth/csrf')
  expect(response.headers()['x-cpdts-browser-test']).toBe(nonce)
}
async function login(page: Page, email: string) {
  await page.goto('/login')
  await page.getByLabel('Email address').fill(email)
  await page.getByLabel('Password').fill(password!)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible()
}
async function signout(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
}
async function choose(page: Page, code: string) {
  const select = page.getByLabel('Company')
  if (await select.count()) {
    const value = await select.locator('option').filter({ hasText: `(${code})` }).getAttribute('value')
    if (!value) throw new Error('Expected company is unavailable')
    await select.selectOption(value)
  }
}
async function selectCustomer(page: Page, customerId: string) {
  const select = page.getByRole('combobox', { name: 'Customer', exact: true })
  await expect(select.locator(`option[value="${customerId}"]`)).toHaveCount(1, { timeout: 20_000 })
  await select.selectOption(customerId)
}
async function getJson(page: Page, path: string) {
  return page.evaluate(async url => {
    const response = await fetch(url, { credentials: 'same-origin', cache: 'no-store' })
    if (!response.ok) throw new Error(`GET failed: ${response.status}`)
    return response.json()
  }, path)
}

test('guarded real company payment lifecycle and uncertain allocation replay', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await guard(page)
  page.on('dialog', dialog => dialog.accept())
  await login(page, a!)
  await choose(page, 'JMX')
  await page.reload()
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible()
  await choose(page, 'JMX')

  const name = `Browser ${randomUUID()}`
  await page.getByRole('link', { name: 'Customers' }).click()
  await page.getByRole('button', { name: 'Add customer' }).click()
  await page.getByLabel(/Display name/).fill(name)
  const customerResponse = page.waitForResponse(r => /\/customers$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST' && r.status() === 201)
  await page.getByRole('button', { name: 'Save customer' }).click()
  const customer = await (await customerResponse).json()
  await expect(page.getByText('Customer created.')).toBeVisible()

  await page.getByRole('button', { name: 'Edit', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Edit customer' })).toBeVisible()
  await page.getByLabel('Phone').fill('+255 700 000 001')
  await page.getByLabel('Notes').fill('Edited in the guarded browser workflow')
  const editResponse = page.waitForResponse(r => /\/customers\/\d+$/.test(new URL(r.url()).pathname) && r.request().method() === 'PATCH' && r.status() === 200)
  await page.getByRole('button', { name: 'Save customer' }).click()
  await editResponse
  await expect(page.getByText('Customer updated.')).toBeVisible()
  await expect(page.getByText('+255 700 000 001', { exact: true })).toBeVisible()

  await choose(page, 'KAM')
  await page.getByLabel('Search customers').fill(name)
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByText('No customers found.')).toBeVisible()
  await expect(page.getByText(name, { exact: true })).toHaveCount(0)
  await choose(page, 'JMX')

  await page.getByRole('link', { name: 'Invoices' }).click()
  await page.getByRole('button', { name: 'Create draft' }).click()
  await page.getByLabel('Search customers').fill(name)
  await page.getByRole('button', { name: 'Find customers' }).click()
  await selectCustomer(page, String(customer.id))
  await page.getByLabel('Issue date').fill('2100-12-31')
  await page.getByLabel('Due date').fill('2100-12-31')
  await page.getByRole('group', { name: 'Item 1' }).getByLabel('Description').fill('Browser workflow')
  await page.getByLabel('Quantity').fill('1')
  await page.getByLabel('Unit price (TZS)').fill('100000.00')
  const invoiceResponse = page.waitForResponse(r => /\/invoices$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST' && r.status() === 201)
  await page.getByRole('button', { name: 'Save draft' }).click()
  const invoice = await (await invoiceResponse).json()
  await page.getByRole('button', { name: 'Edit draft' }).click()
  await expect(page.getByRole('heading', { name: 'Edit draft' })).toBeVisible()
  await page.getByRole('group', { name: 'Item 1' }).getByLabel('Description').fill('Browser workflow edited item')
  const draftResponse = page.waitForResponse(r => /\/invoices\/\d+$/.test(new URL(r.url()).pathname) && r.request().method() === 'PATCH' && r.status() === 200)
  await page.getByRole('button', { name: 'Save draft' }).click()
  await draftResponse
  await expect(page.getByText('Draft updated.')).toBeVisible()
  await expect(page.getByText('Browser workflow edited item')).toBeVisible()
  await page.getByRole('button', { name: 'Issue invoice' }).click()
  await expect(page.getByText('Invoice issued.')).toBeVisible()

  await page.getByRole('link', { name: 'Payments' }).click()
  await page.getByRole('button', { name: 'Record payment' }).click()
  await page.getByLabel('Search customers').fill(name)
  await page.getByRole('button', { name: 'Find customers' }).click()
  await selectCustomer(page, String(customer.id))
  await page.getByLabel('Amount (TZS)').fill('120000.00')
  await page.getByLabel('Received date (Tanzania)').fill('2026-05-10')
  const paymentResponse = page.waitForResponse(r => /\/payments$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST' && r.status() === 201)
  await page.locator('form').filter({ has: page.getByLabel('Amount (TZS)') }).getByRole('button', { name: 'Record payment' }).click()
  const payment = (await (await paymentResponse).json()).payment
  await expect(page.getByText('The recorder cannot verify or reject this payment.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Verify', exact: true })).toHaveCount(0)
  const companies = await getJson(page, '/api/v1/auth/companies')
  const jmx = companies.find((company: { code: string }) => company.code === 'JMX').id
  const verifyPath = `/api/v1/companies/${jmx}/payments/${payment.id}/verify`
  const selfStatus = await page.evaluate(async path => {
    const token = (await (await fetch('/api/v1/auth/csrf')).json()).token
    return (await fetch(path, { method: 'POST', credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': token, 'Content-Type': 'application/json' }, body: '{}' })).status
  }, verifyPath)
  expect(selfStatus).toBe(403)
  await signout(page)
  await page.goto('/payments')
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()

  await login(page, b!)
  await page.getByRole('link', { name: 'Payments' }).click()
  await page.getByLabel('Search payments').fill(payment.paymentNumber)
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await page.getByRole('button', { name: `View ${payment.paymentNumber}` }).click()
  await page.getByRole('button', { name: 'Verify', exact: true }).click()
  await page.getByRole('button', { name: 'Confirm verify' }).click()
  await expect(page.getByText('Payment decision recorded.')).toBeVisible()
  await signout(page)

  await login(page, a!)
  await choose(page, 'JMX')
  await page.getByRole('link', { name: 'Payments' }).click()
  await page.getByLabel('Search payments').fill(payment.paymentNumber)
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await page.getByRole('button', { name: `View ${payment.paymentNumber}` }).click()
  await page.getByRole('button', { name: 'Allocate', exact: true }).click()
  await page.getByLabel(`Allocate to ${invoice.invoiceNumber}`).fill('100000.00')
  let key = '', body = ''
  const drop = async (route: Route) => {
    key = route.request().headers()['idempotency-key']; body = route.request().postData()!
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    await route.abort('failed')
  }
  await page.route('**/allocations', drop)
  await page.getByRole('button', { name: 'Allocate payment' }).click()
  await expect(page.getByText(/outcome may be uncertain/)).toBeVisible()
  await page.unroute('**/allocations', drop)
  await choose(page, 'KAM')
  await expect(page.getByRole('button', { name: 'Retry saved allocation' })).toHaveCount(0)
  const kam = companies.find((company: { code: string }) => company.code === 'KAM').id
  const foreignCompanyStatus = await page.evaluate(async path => (await fetch(path, { credentials: 'same-origin' })).status,
    `/api/v1/companies/${kam}/payments/${payment.id}`)
  expect(foreignCompanyStatus).toBe(404)
  await signout(page)

  await login(page, b!)
  await page.getByRole('link', { name: 'Payments' }).click()
  await page.getByLabel('Search payments').fill(payment.paymentNumber)
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await page.getByRole('button', { name: `View ${payment.paymentNumber}` }).click()
  await expect(page.getByRole('button', { name: 'Retry saved allocation' })).toHaveCount(0)
  const allocPath = `/api/v1/companies/${jmx}/payments/${payment.id}/allocations`
  const foreignStatus = await page.evaluate(async ({ path, key, body }) => {
    const token = (await (await fetch('/api/v1/auth/csrf')).json()).token
    return (await fetch(path, { method: 'POST', credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': token, 'Idempotency-Key': key, 'Content-Type': 'application/json' }, body })).status
  }, { path: allocPath, key, body })
  expect(foreignStatus).toBe(403)
  await signout(page)

  await login(page, a!)
  await choose(page, 'JMX')
  await page.getByRole('link', { name: 'Payments' }).click()
  await page.getByLabel('Search payments').fill(payment.paymentNumber)
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await page.getByRole('button', { name: `View ${payment.paymentNumber}` }).click()
  await page.getByRole('button', { name: 'Retry saved allocation' }).click()
  await expect(page.getByText('Allocations recorded.')).toBeVisible()
  const paid = await getJson(page, `/api/v1/companies/${jmx}/invoices/${invoice.id}`)
  const allocated = await getJson(page, `/api/v1/companies/${jmx}/payments/${payment.id}`)
  expect(paid.outstandingAmount).toBe(0)
  expect(allocated.payment.availableCredit).toBe(20000)
  expect(allocated.allocations).toHaveLength(1)
  expect(allocated.allocations[0].amount).toBe(100000)
  const customerAfterAllocation = await getJson(page, `/api/v1/companies/${jmx}/reports/debt/customers?q=${encodeURIComponent(name)}`)
  expect(customerAfterAllocation.items).toHaveLength(1)
  expect(customerAfterAllocation.items[0].unallocatedCredit).toBe(20000)
  await signout(page)

  await login(page, b!)
  await page.getByRole('link', { name: 'Payments' }).click()
  await page.getByLabel('Search payments').fill(payment.paymentNumber)
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await page.getByRole('button', { name: `View ${payment.paymentNumber}` }).click()
  await page.getByRole('button', { name: 'Reverse payment' }).click()
  await page.getByLabel('Reversal reason').fill('Browser integration test')
  await page.getByRole('button', { name: 'Confirm reverse' }).click()
  await expect(page.getByText('Payment decision recorded.')).toBeVisible()
  const restored = await getJson(page, `/api/v1/companies/${jmx}/invoices/${invoice.id}`)
  const reversed = await getJson(page, `/api/v1/companies/${jmx}/payments/${payment.id}`)
  expect(restored.outstandingAmount).toBe(100000)
  expect(reversed.payment.availableCredit).toBe(0)
  expect(reversed.allocations).toHaveLength(1)
  expect(reversed.allocations[0].status).toBe('REVERSED')
  const customerAfterReversal = await getJson(page, `/api/v1/companies/${jmx}/reports/debt/customers?q=${encodeURIComponent(name)}`)
  expect(customerAfterReversal.items).toHaveLength(1)
  expect(customerAfterReversal.items[0].unallocatedCredit).toBe(0)
  expect(customer.id).toBe(invoice.customerId)
})

test('mobile navigation and company-scoped screens fit the viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await guard(page)
  page.on('dialog', dialog => dialog.accept())
  await login(page, a!)
  await choose(page, 'JMX')
  await page.getByRole('link', { name: 'Customers' }).click()
  await page.getByRole('button', { name: 'Add customer' }).click()
  await page.getByLabel(/Display name/).fill('Mobile test draft')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
  await page.getByRole('button', { name: 'Cancel' }).click()
  await page.getByRole('link', { name: 'Invoices' }).click()
  await page.getByRole('button', { name: 'Create draft' }).click()
  await page.getByLabel('Issue date').fill('2026-05-10')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
  await page.getByRole('button', { name: 'Cancel' }).click()
  await page.getByRole('link', { name: 'Payments' }).click()
  await page.getByRole('button', { name: 'Record payment' }).click()
  await page.getByLabel('Amount (TZS)').fill('1.00')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
  await page.getByRole('button', { name: 'Cancel' }).click()
  await signout(page)
})
