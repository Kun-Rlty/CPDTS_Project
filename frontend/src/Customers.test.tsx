import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import App from './App'
import { api, ApiError, type Customer, type Session } from './api'

vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), api: { me: vi.fn(), login: vi.fn(), logout: vi.fn(), dashboard: vi.fn(), payments: vi.fn(), customers: vi.fn(), customer: vi.fn(), saveCustomer: vi.fn() } }))
const customer: Customer = { id: '10', customerCode: 'JMX-CUST-00010', displayName: 'Alpha', phone: null, email: null, address: null, notes: null, status: 'ACTIVE' }
const session: Session = { user: { id: '1', email: 'test@example.test', fullName: 'User', platformAdmin: false }, companies: [{ id: '1', code: 'JMX', displayName: 'Jeimax', role: 'FINANCE_OFFICER', permissions: ['RECORD_VIEW', 'CUSTOMER_MANAGE'] }, { id: '2', code: 'KAM', displayName: 'Kam', role: 'VIEWER', permissions: ['RECORD_VIEW'] }] }
const page = { items: [customer], page: '0', size: '20', totalElements: '21', totalPages: '2' }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.me).mockResolvedValue(session); vi.mocked(api.customers).mockResolvedValue(page); vi.mocked(api.customer).mockResolvedValue(customer); vi.mocked(api.saveCustomer).mockResolvedValue(customer) })
afterEach(() => { cleanup(); vi.restoreAllMocks() })
function start() { const router = createMemoryRouter([{ path: '*', element: <App /> }], { initialEntries: ['/customers'] }); render(<RouterProvider router={router} />); return router }
async function choose() { fireEvent.change(await screen.findByLabelText('Company'), { target: { value: '1' } }); await screen.findByRole('heading', { name: 'Customers' }) }

describe('customer management', () => {
  it('loads company-scoped search, status and paginated results', async () => {
    start(); await choose(); await screen.findByText('Alpha')
    fireEvent.change(screen.getByLabelText('Search customers'), { target: { value: 'Acme' } }); fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(api.customers).toHaveBeenLastCalledWith('1', { page: 0, q: 'Acme', status: '' }, expect.any(AbortSignal)))
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'ACTIVE' } })
    await waitFor(() => expect(api.customers).toHaveBeenLastCalledWith('1', { page: 0, q: 'Acme', status: 'ACTIVE' }, expect.any(AbortSignal)))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(api.customers).toHaveBeenLastCalledWith('1', { page: 1, q: 'Acme', status: 'ACTIVE' }, expect.any(AbortSignal)))
  })
  it('creates and reports backend validation errors without resubmitting', async () => {
    vi.mocked(api.saveCustomer).mockRejectedValueOnce(new ApiError(400, 'email must contain @')).mockResolvedValue(customer)
    start(); await choose(); fireEvent.click(screen.getByRole('button', { name: 'Add customer' }))
    fireEvent.change(screen.getByLabelText(/Display name/), { target: { value: 'Alpha' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save customer' }))
    expect(await screen.findByText('email must contain @')).toBeInTheDocument()
    expect(api.saveCustomer).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Save customer' }))
    expect(await screen.findByText('Customer created.')).toBeInTheDocument()
  })
  it('loads details, edits, and deactivates with confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    start(); await choose(); fireEvent.click(await screen.findByRole('button', { name: 'View Alpha' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    fireEvent.change(screen.getByLabelText(/Display name/), { target: { value: 'Alpha Ltd' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save customer' }))
    expect(await screen.findByText('Customer updated.')).toBeInTheDocument()
    vi.mocked(api.saveCustomer).mockResolvedValue({ ...customer, status: 'INACTIVE' })
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    expect(await screen.findByText('Customer deactivated.')).toBeInTheDocument()
    expect(api.saveCustomer).toHaveBeenLastCalledWith('1', '10', { status: 'INACTIVE' })
    vi.mocked(api.saveCustomer).mockResolvedValue(customer)
    fireEvent.click(screen.getByRole('button', { name: 'Activate' }))
    expect(await screen.findByText('Customer activated.')).toBeInTheDocument()
    expect(api.saveCustomer).toHaveBeenLastCalledWith('1', '10', { status: 'ACTIVE' })
  })
  it('blocks unauthorized reads and hides mutations for read-only users', async () => {
    start(); await choose(); fireEvent.change(screen.getByLabelText('Company'), { target: { value: '2' } })
    await waitFor(() => expect(api.customers).toHaveBeenLastCalledWith('2', expect.anything(), expect.any(AbortSignal)))
    expect(screen.queryByRole('button', { name: 'Add customer' })).not.toBeInTheDocument()
  })
  it('does not query customers when RECORD_VIEW is absent', async () => {
    vi.mocked(api.me).mockResolvedValue({ ...session, companies: [{ ...session.companies[0], permissions: [] }] })
    start()
    expect(await screen.findByText('Customer access unavailable')).toBeInTheDocument()
    expect(api.customers).not.toHaveBeenCalled()
  })
  it('warns before leaving a dirty form through browser history', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const router = start(); await choose()
    fireEvent.click(screen.getByRole('button', { name: 'Add customer' }))
    fireEvent.change(screen.getByLabelText(/Display name/), { target: { value: 'Unsaved' } })
    router.navigate('/')
    await waitFor(() => expect(confirm).toHaveBeenCalled())
    expect(screen.getByDisplayValue('Unsaved')).toBeInTheDocument()
  })
  it('prevents repeated submissions while the save is pending', async () => {
    vi.mocked(api.saveCustomer).mockImplementation(() => new Promise(() => {}))
    start(); await choose(); fireEvent.click(screen.getByRole('button', { name: 'Add customer' }))
    fireEvent.change(screen.getByLabelText(/Display name/), { target: { value: 'Alpha' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save customer' }))
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled()
    expect(api.saveCustomer).toHaveBeenCalledTimes(1)
  })
  it('warns on company change and ignores stale results', async () => {
    let resolveOld!: (value: typeof page) => void
    vi.mocked(api.customers).mockImplementation(id => id === '1' ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve({ ...page, items: [] }))
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    start(); await choose(); fireEvent.click(screen.getByRole('button', { name: 'Add customer' }))
    fireEvent.change(screen.getByLabelText(/Display name/), { target: { value: 'Unsaved' } })
    fireEvent.change(screen.getByLabelText('Company'), { target: { value: '2' } })
    expect(confirm).toHaveBeenCalled(); expect(screen.getByDisplayValue('Unsaved')).toBeInTheDocument()
    confirm.mockReturnValue(true); fireEvent.change(screen.getByLabelText('Company'), { target: { value: '2' } })
    expect(await screen.findByText('No customers found.')).toBeInTheDocument()
    resolveOld(page)
    await waitFor(() => expect(screen.queryByText('Alpha')).not.toBeInTheDocument())
    expect(screen.queryByDisplayValue('Unsaved')).not.toBeInTheDocument()
  })
})
