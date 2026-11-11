import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import App from './App'
import { api, ApiError, type Dashboard, type Session } from './api'

vi.mock('./api', async importOriginal => ({ ...(await importOriginal<typeof import('./api')>()), api: { me: vi.fn(), login: vi.fn(), logout: vi.fn(), dashboard: vi.fn(), payments: vi.fn() } }))
const session: Session = { user: { id: '1', email: 'x@example.test', fullName: 'Test user', platformAdmin: false }, companies: [{ id: '1', code: 'JMX', displayName: 'Jeimax', role: 'VIEWER', permissions: ['REPORT_VIEW'] }, { id: '2', code: 'KAM', displayName: 'Kam', role: 'VIEWER', permissions: ['REPORT_VIEW'] }] }
const dashboard: Dashboard = { basis: { businessDate: '2026-05-10', zone: 'Africa/Dar_es_Salaam' }, outstandingAmount: '100.00', overdueAmount: '20.00', outstandingInvoiceCount: '1', overdueInvoiceCount: '1', pendingPaymentCount: '0', pendingPaymentAmount: '0', unallocatedCustomerCredit: '5', verifiedPaymentAmount: '5' }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.me).mockResolvedValue(session); vi.mocked(api.dashboard).mockResolvedValue(dashboard); vi.mocked(api.payments).mockResolvedValue({ items: [], totalElements: '0', totalPages: '0' }) })
afterEach(cleanup)
function start(path = '/') { return render(<RouterProvider router={createMemoryRouter([{ path: '*', element: <App /> }], { initialEntries: [path] })} />) }

describe('session and dashboard', () => {
  it('protects routes and signs in', async () => {
    vi.mocked(api.me).mockRejectedValue(new ApiError(401, 'Unauthenticated'))
    vi.mocked(api.login).mockResolvedValue(session)
    start()
    fireEvent.change(await screen.findByLabelText('Email address'), { target: { value: 'x@example.test' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('heading', { name: 'Select a company' })).toBeInTheDocument()
    expect(api.login).toHaveBeenCalledWith('x@example.test', 'password')
  })
  it('restores session, loads selected company and shows empty activity', async () => {
    start()
    fireEvent.change(await screen.findByLabelText('Company'), { target: { value: '1' } })
    expect(await screen.findByText('TZS 100.00')).toBeInTheDocument()
    expect(await screen.findByText('No payments received in this date range.')).toBeInTheDocument()
    expect(api.dashboard).toHaveBeenCalledWith('1', expect.any(AbortSignal))
  })
  it('never fetches reports without REPORT_VIEW', async () => {
    vi.mocked(api.me).mockResolvedValue({ ...session, companies: [{ ...session.companies[0], permissions: [] }] })
    start()
    expect(await screen.findByText('Dashboard access unavailable')).toBeInTheDocument()
    expect(api.dashboard).not.toHaveBeenCalled()
  })
  it('returns to sign in after a report request finds an expired session', async () => {
    vi.mocked(api.me).mockResolvedValue({ ...session, companies: [session.companies[0]] })
    vi.mocked(api.dashboard).mockRejectedValue(new ApiError(401, 'unauthenticated'))
    start()
    expect(await screen.findByText('Your session has expired. Please sign in again.')).toBeInTheDocument()
  })
  it('shows no-access state when the account has no companies', async () => {
    vi.mocked(api.me).mockResolvedValue({ ...session, companies: [] })
    start()
    expect(await screen.findByText('No company access')).toBeInTheDocument()
    expect(api.dashboard).not.toHaveBeenCalled()
  })
  it('shows loading and a forbidden state when access is revoked', async () => {
    vi.mocked(api.me).mockResolvedValue({ ...session, companies: [session.companies[0]] })
    let reject!: (error: Error) => void
    vi.mocked(api.dashboard).mockImplementation(() => new Promise((_, fail) => { reject = fail }))
    start()
    expect(await screen.findByText('Loading current balances…')).toBeInTheDocument()
    await waitFor(() => expect(reject).toBeDefined())
    reject(new ApiError(403, 'forbidden'))
    expect(await screen.findByText('You do not have access to this dashboard.')).toBeInTheDocument()
  })
  it('discards late responses after switching companies', async () => {
    let resolveFirst!: (d: Dashboard) => void
    vi.mocked(api.dashboard).mockImplementation(id => id === '1' ? new Promise(resolve => { resolveFirst = resolve }) : Promise.resolve({ ...dashboard, outstandingAmount: '777' }))
    start()
    const selector = await screen.findByLabelText('Company')
    fireEvent.change(selector, { target: { value: '1' } })
    await waitFor(() => expect(resolveFirst).toBeDefined())
    fireEvent.change(selector, { target: { value: '2' } })
    expect(await screen.findByText('TZS 777.00')).toBeInTheDocument()
    resolveFirst(dashboard)
    await waitFor(() => expect(screen.queryByText('TZS 100.00')).not.toBeInTheDocument())
  })
  it('offers retry on dashboard errors and supports logout', async () => {
    vi.mocked(api.dashboard).mockRejectedValueOnce(new Error('offline')).mockResolvedValue(dashboard)
    vi.mocked(api.logout).mockResolvedValue(undefined)
    start()
    fireEvent.change(await screen.findByLabelText('Company'), { target: { value: '1' } })
    expect(await screen.findByText('Could not load the dashboard.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('TZS 100.00')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument()
  })
})

describe('login throttling', () => {
  it('explains the temporary cooldown, disables retry and clears the password', async () => {
    vi.mocked(api.me).mockRejectedValue(new ApiError(401, 'Unauthenticated'))
    vi.mocked(api.login).mockRejectedValue(new ApiError(429, 'Too many sign-in attempts. Please try again later.', 1))
    start()
    fireEvent.change(await screen.findByLabelText('Email address'), { target: { value: 'x@example.test' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    // The UI explains the temporary pause (no lockout claim)...
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many sign-in attempts. Sign-in is temporarily paused — try again in 1 second. No account is permanently locked.')
    // ...the password is cleared and retry is disabled with a countdown...
    expect(screen.getByLabelText('Password')).toHaveValue('')
    expect(screen.getByRole('button', { name: 'Try again in 1s' })).toBeDisabled()
    expect(api.login).toHaveBeenCalledTimes(1)
    // ...and clicking the disabled control never resubmits the password.
    fireEvent.click(screen.getByRole('button', { name: 'Try again in 1s' }))
    expect(api.login).toHaveBeenCalledTimes(1)

    // After the cooldown the form is usable again (manual retry only).
    expect(await screen.findByRole('button', { name: 'Sign in' }, { timeout: 3000 })).toBeEnabled()
    expect(api.login).toHaveBeenCalledTimes(1)
    expect(screen.queryByText(/temporarily paused/)).not.toBeInTheDocument()
  })
  it('falls back to the default cooldown when Retry-After is missing', async () => {
    vi.mocked(api.me).mockRejectedValue(new ApiError(401, 'Unauthenticated'))
    vi.mocked(api.login).mockRejectedValue(new ApiError(429, 'Too many sign-in attempts. Please try again later.'))
    start()
    fireEvent.change(await screen.findByLabelText('Email address'), { target: { value: 'x@example.test' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('button', { name: 'Try again in 300s' })).toBeDisabled()
  })
})
