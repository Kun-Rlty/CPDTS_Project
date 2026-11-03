import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import App from './App'
import { api, ApiError, type Invoice, type Session } from './api'

vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), api: { me: vi.fn(), logout: vi.fn(), dashboard: vi.fn(), payments: vi.fn(), invoices: vi.fn(), invoice: vi.fn(), saveInvoice: vi.fn(), invoiceAction: vi.fn(), customers: vi.fn() } }))
const company = { id: '1', code: 'JMX', displayName: 'Jeimax', role: 'FINANCE_OFFICER', permissions: ['RECORD_VIEW', 'INVOICE_MANAGE'] }
const session: Session = { user: { id: '1', email: 'test@example.test', fullName: 'User', platformAdmin: false }, companies: [company] }
const invoice: Invoice = { id: '10', invoiceNumber: 'JMX-INV-10', customerId: '20', customerName: 'Alpha', issueDate: '2026-05-01', dueDate: '2026-05-10', status: 'DRAFT', derivedStatus: 'DRAFT', totalAmount: '0.13', outstandingAmount: '0', description: null, items: [{ description: 'Service', quantity: '0.125', unitPrice: '1.00', lineTotal: '0.13' }] }
const page = { items: [invoice], totalElements: '1', totalPages: '1' }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.me).mockResolvedValue(session); vi.mocked(api.invoices).mockResolvedValue(page); vi.mocked(api.invoice).mockResolvedValue(invoice); vi.mocked(api.saveInvoice).mockResolvedValue(invoice); vi.mocked(api.invoiceAction).mockResolvedValue({ ...invoice, status: 'ISSUED' }); vi.mocked(api.customers).mockResolvedValue({ items: [{ id: '20', customerCode: 'JMX-CUST-20', displayName: 'Alpha', phone: null, email: null, address: null, notes: null, status: 'ACTIVE' }], page: '0', size: '20', totalElements: '1', totalPages: '1' }) })
afterEach(() => { cleanup(); vi.restoreAllMocks() })
function start() { const router = createMemoryRouter([{ path: '*', element: <App /> }], { initialEntries: ['/invoices'] }); render(<RouterProvider router={router} />); return router }

it('loads list and uses active company customers with exact preview', async () => {
  start(); fireEvent.click(await screen.findByRole('button', { name: 'Create draft' }))
  expect(await screen.findByRole('option', { name: 'Alpha (JMX-CUST-20)' })).toBeInTheDocument()
  expect(api.customers).toHaveBeenCalledWith('1', { page: 0, q: '', status: 'ACTIVE' }, expect.any(AbortSignal))
  fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '0.125' } })
  fireEvent.change(screen.getByLabelText('Unit price (TZS)'), { target: { value: '1.00' } })
  expect(screen.getByText(/Preview total: TZS 0.13/)).toBeInTheDocument()
})

it('edits only drafts and handles lifecycle conflicts', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.mocked(api.invoiceAction).mockRejectedValueOnce(new ApiError(409, 'invoice has active payment allocations and cannot be voided'))
  start(); fireEvent.click(await screen.findByRole('button', { name: 'View JMX-INV-10' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Void invoice' }))
  fireEvent.change(screen.getByLabelText('Void reason'), { target: { value: 'Correction' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirm void' }))
  expect(await screen.findByText(/invoice has active payment allocations/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Cancel void' }))
  fireEvent.click(screen.getByRole('button', { name: 'Issue invoice' }))
  expect(await screen.findByText('Invoice issued.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Edit draft' })).not.toBeInTheDocument()
})

it('enforces permissions and prevents unauthorized list requests', async () => {
  vi.mocked(api.me).mockResolvedValue({ ...session, companies: [{ ...company, permissions: [] }] })
  start(); expect(await screen.findByText('Invoice access unavailable')).toBeInTheDocument()
  expect(api.invoices).not.toHaveBeenCalled()
})

it('warns before discarding an invoice draft', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  const router = start(); fireEvent.click(await screen.findByRole('button', { name: 'Create draft' }))
  fireEvent.change(screen.getByLabelText('Issue date'), { target: { value: '2026-05-01' } })
  router.navigate('/')
  await waitFor(() => expect(confirm).toHaveBeenCalled())
  expect(screen.getByDisplayValue('2026-05-01')).toBeInTheDocument()
})

it('creates a draft using selected customer and exact date-only inputs', async () => {
  start(); fireEvent.click(await screen.findByRole('button', { name: 'Create draft' }))
  await screen.findByRole('option', { name: 'Alpha (JMX-CUST-20)' })
  fireEvent.change(screen.getByLabelText('Customer'), { target: { value: '20' } })
  fireEvent.change(screen.getByLabelText('Issue date'), { target: { value: '2026-05-01' } })
  fireEvent.change(screen.getByLabelText('Due date'), { target: { value: '2026-05-10' } })
  fireEvent.change(screen.getAllByLabelText('Description')[1], { target: { value: 'Service' } })
  fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '0.125' } })
  fireEvent.change(screen.getByLabelText('Unit price (TZS)'), { target: { value: '1.00' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save draft' }))
  expect(await screen.findByText('Draft created.')).toBeInTheDocument()
  expect(api.saveInvoice).toHaveBeenCalledWith('1', null, expect.objectContaining({ customerId: '20', issueDate: '2026-05-01', dueDate: '2026-05-10' }))
})

it('ignores stale invoices on company switch', async () => {
  let resolveOld!: (value: typeof page) => void
  vi.mocked(api.me).mockResolvedValue({ ...session, companies: [company, { ...company, id: '2', code: 'KAM', displayName: 'Kam' }] })
  vi.mocked(api.invoices).mockImplementation(id => id === '1' ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve({ ...page, items: [], totalElements: '0', totalPages: '0' }))
  start(); const selector = await screen.findByLabelText('Company')
  fireEvent.change(selector, { target: { value: '1' } })
  await waitFor(() => expect(resolveOld).toBeDefined())
  fireEvent.change(selector, { target: { value: '2' } })
  expect(await screen.findByText('No invoices found.')).toBeInTheDocument()
  resolveOld(page)
  await waitFor(() => expect(screen.queryByText('JMX-INV-10')).not.toBeInTheDocument())
})

it('edits a draft and preserves the customer on update', async () => {
  start(); fireEvent.click(await screen.findByRole('button', { name: 'View JMX-INV-10' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Edit draft' }))
  fireEvent.change(screen.getByLabelText('Due date'), { target: { value: '2026-06-01' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save draft' }))
  expect(await screen.findByText('Draft updated.')).toBeInTheDocument()
  expect(api.saveInvoice).toHaveBeenCalledWith('1', '10', expect.objectContaining({ dueDate: '2026-06-01' }))
  expect(vi.mocked(api.saveInvoice).mock.calls[0][2].customerId).toBeUndefined()
})

it('requires a void reason and prevents duplicate pending actions', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.mocked(api.invoiceAction).mockImplementation(() => new Promise(() => {}))
  start(); fireEvent.click(await screen.findByRole('button', { name: 'View JMX-INV-10' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Void invoice' }))
  fireEvent.click(screen.getByRole('button', { name: 'Confirm void' }))
  expect(screen.getByText('A void reason is required.')).toBeInTheDocument()
  expect(api.invoiceAction).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('Void reason'), { target: { value: 'Correction' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirm void' }))
  expect(screen.getByRole('button', { name: 'Confirm void' })).toBeDisabled()
  expect(api.invoiceAction).toHaveBeenCalledTimes(1)
})

it('voids an issued invoice only with a reason', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.mocked(api.invoice).mockResolvedValue({ ...invoice, status: 'ISSUED', derivedStatus: 'UNPAID', outstandingAmount: '0.13' })
  vi.mocked(api.invoiceAction).mockResolvedValue({ ...invoice, status: 'VOIDED', derivedStatus: 'VOIDED' })
  start(); fireEvent.click(await screen.findByRole('button', { name: 'View JMX-INV-10' }))
  expect(await screen.findByText('TZS 0.00')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Void invoice' }))
  fireEvent.change(screen.getByLabelText('Void reason'), { target: { value: 'Duplicate draft' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirm void' }))
  expect(await screen.findByText('Invoice voided.')).toBeInTheDocument()
  expect(api.invoiceAction).toHaveBeenCalledWith('1', '10', 'void', 'Duplicate draft')
  expect(screen.queryByRole('button', { name: 'Void invoice' })).not.toBeInTheDocument()
})
