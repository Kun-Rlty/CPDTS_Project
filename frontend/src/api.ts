import { LosslessNumber, parse, stringify } from 'lossless-json'

// JSON numeric tokens are kept as strings, including aggregate NUMERIC values
// larger than Number.MAX_SAFE_INTEGER. No financial amount passes through Number.
export type Company = { id: string; code: string; displayName: string; role: string | null; permissions: string[] }
export type Session = { user: { id: string; email: string; fullName: string; platformAdmin: boolean }; companies: Company[] }
export type Dashboard = { basis: { businessDate: string; zone: string }; outstandingAmount: string; overdueAmount: string; outstandingInvoiceCount: string; overdueInvoiceCount: string; pendingPaymentCount: string; pendingPaymentAmount: string; unallocatedCustomerCredit: string; verifiedPaymentAmount: string }
export type Payment = { id: string; paymentNumber: string; customerName: string; amount: string; status: string; method: string; receivedAt: string }
export type PaymentPage = { items: Payment[]; totalElements: string; totalPages: string }
export type PaymentRecord = Payment & { customerId: string; customerCode: string | null; recordedBy: string; recordedByName: string | null; referenceNumber: string | null; notes: string | null; allocatedAmount: string; availableCredit: string; reversedAt: string | null; reversedBy: string | null; reversalReason: string | null }
export type PaymentDetail = { payment: PaymentRecord; verifications: { id: string; decision: string; reason: string | null; verifierName: string | null; createdAt: string }[]; allocations: { id: string; invoiceId: string; invoiceNumber: string; amount: string; status: string; createdAt: string; reversedAt: string | null; reversalReason: string | null }[] }
export type RecordPayment = { customerId: string; amount: string; method: string; receivedAt: string; referenceNumber: string; notes: string }
export type AllocationLine = { invoiceId: string; amount: string }
export type Customer = { id: string; customerCode: string | null; displayName: string; phone: string | null; email: string | null; address: string | null; notes: string | null; status: 'ACTIVE' | 'INACTIVE' }
export type CustomerFields = { displayName: string; phone: string; email: string; address: string; notes: string }
export type CustomerPage = { items: Customer[]; page: string; size: string; totalElements: string; totalPages: string }
export type InvoiceItem = { description: string; quantity: string; unitPrice: string; lineTotal?: string }
export type Invoice = { id: string; invoiceNumber: string; customerId: string; customerName: string; issueDate: string; dueDate: string; status: 'DRAFT' | 'ISSUED' | 'VOIDED'; derivedStatus: string; totalAmount: string; outstandingAmount: string; description: string | null; items?: InvoiceItem[] }
export type InvoicePage = { items: Invoice[]; totalElements: string; totalPages: string }
export type InvoiceFields = { issueDate: string; dueDate: string; description: string; items: InvoiceItem[]; customerId?: string }

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
  invoices: (company: string, query: { page: number; q: string; status: string; customerId: string }, signal: AbortSignal) => {
    const params = new URLSearchParams({ page: String(query.page), size: '20', q: query.q, status: query.status })
    if (query.customerId) params.set('customerId', query.customerId)
    return request<InvoicePage>(`/companies/${encodeURIComponent(company)}/invoices?${params}`, { signal })
  },
  invoice: (company: string, id: string, signal: AbortSignal) => request<Invoice>(`/companies/${encodeURIComponent(company)}/invoices/${encodeURIComponent(id)}`, { signal }),
  saveInvoice: async (company: string, id: string | null, body: InvoiceFields) => {
    const token = await csrf()
    const number = (value: string) => new LosslessNumber(value.trim().replace(/^0+(?=\d)/, ''))
    const payload = { ...body, ...(body.customerId ? { customerId: number(body.customerId) } : {}), items: body.items.map(item => ({ description: item.description, quantity: number(item.quantity), unitPrice: number(item.unitPrice) })) }
    return request<Invoice>(`/companies/${encodeURIComponent(company)}/invoices${id ? '/' + encodeURIComponent(id) : ''}`, { method: id ? 'PATCH' : 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: stringify(payload) })
  },
  invoiceAction: async (company: string, id: string, action: 'issue' | 'void', reason?: string) => {
    const token = await csrf()
    return request<Invoice>(`/companies/${encodeURIComponent(company)}/invoices/${encodeURIComponent(id)}/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, ...(action === 'void' ? { body: JSON.stringify({ reason }) } : {}) })
  },
  paymentList: (company: string, query: { page: number; q: string; status: string; customerId: string }, signal: AbortSignal) => {
    const params = new URLSearchParams({ page: String(query.page), size: '20', q: query.q, status: query.status })
    if (query.customerId) params.set('customerId', query.customerId)
    return request<PaymentPage>(`/companies/${encodeURIComponent(company)}/payments?${params}`, { signal })
  },
  paymentDetail: (company: string, id: string, signal: AbortSignal) => request<PaymentDetail>(`/companies/${encodeURIComponent(company)}/payments/${encodeURIComponent(id)}`, { signal }),
  recordPayment: async (company: string, body: RecordPayment, key: string) => {
    const token = await csrf()
    const number = (value: string) => new LosslessNumber(value.trim().replace(/^0+(?=\d)/, ''))
    return request<PaymentDetail>(`/companies/${encodeURIComponent(company)}/payments`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token, 'Idempotency-Key': key }, body: stringify({ ...body, customerId: number(body.customerId), amount: number(body.amount) }) })
  },
  allocatePayment: async (company: string, id: string, lines: AllocationLine[], key: string) => {
    const token = await csrf()
    const number = (value: string) => new LosslessNumber(value.trim().replace(/^0+(?=\d)/, ''))
    return request<PaymentDetail>(`/companies/${encodeURIComponent(company)}/payments/${encodeURIComponent(id)}/allocations`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token, 'Idempotency-Key': key }, body: stringify({ allocations: lines.map(line => ({ invoiceId: number(line.invoiceId), amount: number(line.amount) })) }) })
  },
  paymentAction: async (company: string, id: string, action: 'verify' | 'reject' | 'reverse', text: string) => {
    const token = await csrf()
    return request<PaymentDetail>(`/companies/${encodeURIComponent(company)}/payments/${encodeURIComponent(id)}/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: JSON.stringify(action === 'verify' ? { notes: text } : { reason: text }) })
  },
}

export function formatMoney(value: string): string {
  if (!/^\d+(?:\.\d{1,2})?$/.test(value)) return 'Amount unavailable'
  const [integer, fraction = ''] = value.split('.')
  return `TZS ${integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${fraction.padEnd(2, '0')}`
}
