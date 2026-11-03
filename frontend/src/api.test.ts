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
  it('formats exact large decimal values without floating point', () => {
    expect(formatMoney('999999999999999999.12')).toBe('TZS 999,999,999,999,999,999.12')
  })
  it('preserves large numeric JSON tokens before formatting', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"outstandingAmount":999999999999999999.12}', { status: 200 })))
    const data = await api.dashboard('1', new AbortController().signal)
    expect(formatMoney(data.outstandingAmount)).toBe('TZS 999,999,999,999,999,999.12')
  })
})
