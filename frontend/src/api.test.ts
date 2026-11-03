import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, formatMoney } from './api'

afterEach(() => vi.unstubAllGlobals())
describe('API client', () => {
  it('obtains CSRF before login, sends cookies and refreshes the token', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response('{"token":"before"}', { status: 200 }))
      .mockResolvedValueOnce(new Response('{"user":{},"companies":[]}', { status: 200 }))
      .mockResolvedValueOnce(new Response('{"token":"after"}', { status: 200 }))
    vi.stubGlobal('fetch', fetcher)
    await api.login('user@example.test', 'not-a-real-password')
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(fetcher.mock.calls[1][1]).toMatchObject({ credentials: 'same-origin', method: 'POST', headers: { 'X-XSRF-TOKEN': 'before' } })
    expect(fetcher.mock.calls[2][0]).toBe('/api/v1/auth/csrf')
  })
  it('uses a fresh CSRF token for logout', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response('{"token":"fresh"}', { status: 200 }))
      .mockResolvedValueOnce(new Response('{"ok":true}', { status: 200 }))
    vi.stubGlobal('fetch', fetcher)
    await api.logout()
    expect(fetcher.mock.calls[1][1]).toMatchObject({ method: 'POST', headers: { 'X-XSRF-TOKEN': 'fresh' } })
  })
  it('sends customer mutations once with the session CSRF token', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response('{"token":"fresh"}', { status: 200 }))
      .mockResolvedValueOnce(new Response('{"id":12}', { status: 200 }))
    vi.stubGlobal('fetch', fetcher)
    await api.saveCustomer('1', '12', { status: 'INACTIVE' })
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(fetcher.mock.calls[1][0]).toBe('/api/v1/companies/1/customers/12')
    expect(fetcher.mock.calls[1][1]).toMatchObject({ method: 'PATCH', credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': 'fresh' } })
  })
  it('sends exact invoice decimal tokens without floating point conversion', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response('{"token":"fresh"}', { status: 200 }))
      .mockResolvedValueOnce(new Response('{"id":12}', { status: 200 }))
    vi.stubGlobal('fetch', fetcher)
    await api.saveInvoice('1', null, { customerId: '20', issueDate: '2026-01-01', dueDate: '2026-01-01', description: '', items: [{ description: 'Line', quantity: '0.125', unitPrice: '999999999999.99' }] })
    const body = fetcher.mock.calls[1][1].body as string
    expect(body).toContain('"quantity":0.125')
    expect(body).toContain('"unitPrice":999999999999.99')
    expect(fetcher.mock.calls[1][1].headers['X-XSRF-TOKEN']).toBe('fresh')
  })
  it('formats exact large decimal values without floating point', () => {
    expect(formatMoney('999999999999999999.12')).toBe('TZS 999,999,999,999,999,999.12')
  })
  it('preserves large numeric JSON tokens before formatting', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"outstandingAmount":999999999999999999.12}', { status: 200 })))
    const data = await api.dashboard('1', new AbortController().signal)
    expect(formatMoney(data.outstandingAmount)).toBe('TZS 999,999,999,999,999,999.12')
  })
})
