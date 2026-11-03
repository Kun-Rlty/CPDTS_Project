import { parse } from 'lossless-json'

// JSON numeric tokens are kept as strings, including aggregate NUMERIC values
// larger than Number.MAX_SAFE_INTEGER. No financial amount passes through Number.
export type Company = { id: string; code: string; displayName: string; role: string | null; permissions: string[] }
export type Session = { user: { id: string; email: string; fullName: string; platformAdmin: boolean }; companies: Company[] }
export type Dashboard = { basis: { businessDate: string; zone: string }; outstandingAmount: string; overdueAmount: string; outstandingInvoiceCount: string; overdueInvoiceCount: string; pendingPaymentCount: string; pendingPaymentAmount: string; unallocatedCustomerCredit: string; verifiedPaymentAmount: string }
export type Payment = { id: string; paymentNumber: string; customerName: string; amount: string; status: string; method: string; receivedAt: string }
export type PaymentPage = { items: Payment[]; totalElements: string; totalPages: string }
export type Customer = { id: string; customerCode: string | null; displayName: string; phone: string | null; email: string | null; address: string | null; notes: string | null; status: 'ACTIVE' | 'INACTIVE' }
export type CustomerFields = { displayName: string; phone: string; email: string; address: string; notes: string }
export type CustomerPage = { items: Customer[]; page: string; size: string; totalElements: string; totalPages: string }

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch('/api/v1' + path, { ...init, credentials: 'same-origin', cache: 'no-store' })
  const text = await response.text()
  const data = text ? parse(text, undefined, value => value) as Record<string, unknown> : null
  if (!response.ok) throw new ApiError(response.status, typeof data?.message === 'string' ? data.message : 'Request failed')
  return data as T
}

async function csrf(): Promise<string> { return (await request<{ token: string }>('/auth/csrf')).token }
export const api = {
  me: () => request<Session>('/auth/me'),
  login: async (email: string, password: string) => {
    const token = await csrf()
    const result = await request<Session>('/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: JSON.stringify({ email, password }) })
    await csrf()
    return result
  },
  logout: async () => {
    const token = await csrf()
    await request('/auth/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': token } })
  },
  dashboard: (id: string, signal: AbortSignal) => request<Dashboard>(`/companies/${encodeURIComponent(id)}/dashboard`, { signal }),
  payments: (id: string, from: string, to: string, signal: AbortSignal) => {
    const query = new URLSearchParams({ from, to, size: '10' })
    return request<PaymentPage>(`/companies/${encodeURIComponent(id)}/reports/payments?${query}`, { signal })
  },
  customers: (id: string, query: { page: number; q: string; status: string }, signal: AbortSignal) => {
    const params = new URLSearchParams({ page: String(query.page), size: '20', q: query.q, status: query.status })
    return request<CustomerPage>(`/companies/${encodeURIComponent(id)}/customers?${params}`, { signal })
  },
  customer: (company: string, id: string, signal: AbortSignal) => request<Customer>(`/companies/${encodeURIComponent(company)}/customers/${encodeURIComponent(id)}`, { signal }),
  saveCustomer: async (company: string, id: string | null, body: CustomerFields | { status: string }) => {
    const token = await csrf()
    return request<Customer>(`/companies/${encodeURIComponent(company)}/customers${id ? '/' + encodeURIComponent(id) : ''}`, {
      method: id ? 'PATCH' : 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: JSON.stringify(body),
    })
  },
}

export function formatMoney(value: string): string {
  if (!/^\d+(?:\.\d{1,2})?$/.test(value)) return 'Amount unavailable'
  const [integer, fraction = ''] = value.split('.')
  return `TZS ${integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${fraction.padEnd(2, '0')}`
}
