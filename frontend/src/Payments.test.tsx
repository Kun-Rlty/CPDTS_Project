import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import App from './App'
import { api, ApiError, type PaymentDetail, type Session } from './api'
import { operationFor } from './paymentOperation'

vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), api: { me: vi.fn(), logout: vi.fn(), dashboard: vi.fn(), payments: vi.fn(), customers: vi.fn(), invoices: vi.fn(), paymentList: vi.fn(), paymentDetail: vi.fn(), recordPayment: vi.fn(), allocatePayment: vi.fn(), paymentAction: vi.fn() } }))
const company = { id: '1', code: 'JMX', displayName: 'Jeimax', role: 'FINANCE_OFFICER', permissions: ['RECORD_VIEW', 'PAYMENT_RECORD', 'PAYMENT_VERIFY', 'PAYMENT_REVERSE'] }
const session: Session = { user: { id: '1', email: 'user@example.test', fullName: 'User', platformAdmin: false }, companies: [company] }
const pending: PaymentDetail = { payment: { id: '10', paymentNumber: 'PAY-10', customerId: '20', customerName: 'Alpha', customerCode: null, amount: '10.00', status: 'PENDING_VERIFICATION', method: 'CASH', receivedAt: '2026-05-09T21:00:00Z', recordedBy: '2', recordedByName: 'Other user', referenceNumber: null, notes: null, allocatedAmount: '0', availableCredit: '0', reversedAt: null, reversedBy: null, reversalReason: null }, verifications: [], allocations: [] }
const verified: PaymentDetail = { ...pending, payment: { ...pending.payment, status: 'VERIFIED', availableCredit: '10.00' } }
beforeEach(() => { vi.resetAllMocks(); sessionStorage.clear(); vi.mocked(api.me).mockResolvedValue(session); vi.mocked(api.paymentList).mockResolvedValue({ items: [pending.payment], totalElements: '1', totalPages: '1' }); vi.mocked(api.paymentDetail).mockResolvedValue(pending); vi.mocked(api.paymentAction).mockResolvedValue(verified); vi.mocked(api.customers).mockResolvedValue({ items: [{ id: '20', customerCode: null, displayName: 'Alpha', phone: null, email: null, address: null, notes: null, status: 'ACTIVE' }], page: '0', size: '20', totalElements: '1', totalPages: '1' }) })
afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })
function start() { render(<RouterProvider router={createMemoryRouter([{ path: '*', element: <App /> }], { initialEntries: ['/payments'] })} />) }
async function open() { fireEvent.click(await screen.findByRole('button', { name: 'View PAY-10' }, { timeout: 5000 })); await screen.findByText('Payment details') }

it('gates reports without RECORD_VIEW and blocks self-verification', async () => {
  vi.mocked(api.paymentDetail).mockResolvedValue({ ...pending, payment: { ...pending.payment, recordedBy: '1' } })
  start(); await open()
  expect(screen.getByText('The recorder cannot verify or reject this payment.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
})

it('enforces rejection reason and displays concurrent conflicts', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.mocked(api.paymentAction).mockRejectedValue(new ApiError(409, 'payment is no longer pending'))
  start(); await open(); fireEvent.click(screen.getByRole('button', { name: 'Reject' }))
  fireEvent.click(screen.getByRole('button', { name: 'Confirm reject' }))
  expect(screen.getByText('A reason is required.')).toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Rejection reason'), { target: { value: 'Wrong amount' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirm reject' }))
  expect(await screen.findByText(/payment is no longer pending/)).toBeInTheDocument()
})

it('allows another authorized user to verify a pending payment', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  start(); await open(); fireEvent.click(screen.getByRole('button', { name: 'Verify' }))
  fireEvent.change(screen.getByLabelText('Verification note (optional)'), { target: { value: 'Reviewed' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirm verify' }))
  expect(await screen.findByText('Payment decision recorded.')).toBeInTheDocument()
  expect(api.paymentAction).toHaveBeenCalledWith('1', '10', 'verify', 'Reviewed')
})

it('enforces exact allocation limits and handles reversal', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.mocked(api.paymentDetail).mockResolvedValue(verified)
  vi.mocked(api.invoices).mockResolvedValue({ items: [{ id: '30', invoiceNumber: 'INV-30', customerId: '20', customerName: 'Alpha', issueDate: '2026-01-01', dueDate: '2026-01-02', status: 'ISSUED', derivedStatus: 'UNPAID', totalAmount: '5.00', outstandingAmount: '5.00', description: null }], totalElements: '1', totalPages: '1' })
  start(); await open(); fireEvent.click(screen.getByRole('button', { name: 'Allocate' }))
  const input = await screen.findByLabelText('Allocate to INV-30')
  fireEvent.change(input, { target: { value: '5.01' } }); fireEvent.click(screen.getByRole('button', { name: 'Allocate payment' }))
  expect(screen.getByText(/Enter positive amounts within/)).toBeInTheDocument()
  expect(api.allocatePayment).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Cancel allocation' }))
  fireEvent.click(screen.getByRole('button', { name: 'Reverse payment' }))
  fireEvent.change(screen.getByLabelText('Reversal reason'), { target: { value: 'Correction' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirm reverse' }))
  await waitFor(() => expect(api.paymentAction).toHaveBeenCalledWith('1', '10', 'reverse', 'Correction'))
})

it('reuses the create key and exact payload after a network failure', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.mocked(api.recordPayment).mockRejectedValueOnce(new TypeError('network')).mockResolvedValue(pending)
  start(); fireEvent.click(await screen.findByRole('button', { name: 'Record payment' }))
  await screen.findByRole('option', { name: 'Alpha (ACTIVE)' })
  fireEvent.change(screen.getByLabelText('Customer'), { target: { value: '20' } })
  fireEvent.change(screen.getByLabelText('Amount (TZS)'), { target: { value: '10.00' } })
  fireEvent.change(screen.getByLabelText('Received date (Tanzania)'), { target: { value: '2026-05-10' } })
  fireEvent.click(screen.getAllByRole('button', { name: /^Record payment$/ })[1])
  expect(await screen.findByText(/outcome may be uncertain/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Retry saved request' }))
  expect(await screen.findByText('Payment recorded. Awaiting manual verification.')).toBeInTheDocument()
  expect(vi.mocked(api.recordPayment).mock.calls[0]).toEqual(vi.mocked(api.recordPayment).mock.calls[1])
})

it('replays an uncertain allocation even when current credit is exhausted', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  const lines = [{ invoiceId: '30', amount: '10.00' }]
  const original = await operationFor('user.1.company.1.payment.10.allocate', JSON.stringify({ allocations: lines }))
  vi.mocked(api.paymentDetail).mockResolvedValue({ ...verified, payment: { ...verified.payment, availableCredit: '0.00' } })
  vi.mocked(api.allocatePayment).mockResolvedValue(verified)
  start(); await open()
  fireEvent.click(screen.getByRole('button', { name: 'Retry saved allocation' }))
  await waitFor(() => expect(api.allocatePayment).toHaveBeenCalledWith('1', '10', lines, original.key))
  expect(await screen.findByText('Allocations recorded.')).toBeInTheDocument()
})

it('allocates multiple invoices and reuses the key and payload after a network failure', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.mocked(api.paymentDetail).mockResolvedValue(verified)
  const makeInvoice = (id: string, number: string) => ({ id, invoiceNumber: number, customerId: '20', customerName: 'Alpha', issueDate: '2026-01-01', dueDate: '2026-01-02', status: 'ISSUED' as const, derivedStatus: 'UNPAID', totalAmount: '6.00', outstandingAmount: '6.00', description: null })
  vi.mocked(api.invoices).mockResolvedValue({ items: [makeInvoice('30', 'INV-30'), makeInvoice('31', 'INV-31')], totalElements: '2', totalPages: '1' })
  vi.mocked(api.allocatePayment).mockRejectedValueOnce(new TypeError('network')).mockResolvedValue(verified)
  start(); await open(); fireEvent.click(screen.getByRole('button', { name: 'Allocate' }))
  fireEvent.change(await screen.findByLabelText('Allocate to INV-30'), { target: { value: '4.25' } })
  fireEvent.change(screen.getByLabelText('Allocate to INV-31'), { target: { value: '5.75' } })
  fireEvent.click(screen.getByRole('button', { name: 'Allocate payment' }))
  expect(await screen.findByText(/outcome may be uncertain/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Retry saved allocation' }))
  expect(await screen.findByText('Allocations recorded.')).toBeInTheDocument()
  expect(vi.mocked(api.allocatePayment).mock.calls[0]).toEqual(vi.mocked(api.allocatePayment).mock.calls[1])
})

it('does not request payments without RECORD_VIEW', async () => {
  vi.mocked(api.me).mockResolvedValue({ ...session, companies: [{ ...company, permissions: [] }] })
  start(); expect(await screen.findByText('Payment access unavailable')).toBeInTheDocument()
  expect(api.paymentList).not.toHaveBeenCalled()
})

it('does not offer mutation actions to a read-only user', async () => {
  vi.mocked(api.me).mockResolvedValue({ ...session, companies: [{ ...company, permissions: ['RECORD_VIEW'] }] })
  start(); await open()
  expect(screen.queryByRole('button', { name: 'Record payment' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Reverse payment' })).not.toBeInTheDocument()
})

it('ignores a late response after switching companies', async () => {
  let resolveOld!: (value: { items: typeof pending.payment[]; totalElements: string; totalPages: string }) => void
  vi.mocked(api.me).mockResolvedValue({ ...session, companies: [company, { ...company, id: '2', code: 'KAM', displayName: 'Kam' }] })
  vi.mocked(api.paymentList).mockImplementation(id => id === '1' ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve({ items: [], totalElements: '0', totalPages: '0' }))
  start(); const selector = await screen.findByLabelText('Company')
  fireEvent.change(selector, { target: { value: '1' } })
  await waitFor(() => expect(resolveOld).toBeDefined())
  fireEvent.change(selector, { target: { value: '2' } })
  expect(await screen.findByText('No payments found.')).toBeInTheDocument()
  resolveOld({ items: [pending.payment], totalElements: '1', totalPages: '1' })
  await waitFor(() => expect(screen.queryByText('PAY-10')).not.toBeInTheDocument())
})
