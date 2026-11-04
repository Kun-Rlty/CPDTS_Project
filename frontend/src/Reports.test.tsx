import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import Reports from './Reports'
import { api, ApiError, type ReportPage, type CustomerDebt } from './api'

vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), api: { report: vi.fn() } }))
const company = { id: '1', code: 'JMX', displayName: 'Jeimax', role: 'VIEWER', permissions: ['REPORT_VIEW'] }
const result: ReportPage<CustomerDebt> = { basis: { businessDate: '2026-11-04', observedAt: '2026-11-04T08:00:00Z', zone: 'Africa/Dar_es_Salaam', kind: 'CURRENT_BALANCE', currency: 'TZS', from: null, to: null }, items: [{ customerId: '1', customerName: 'Customer A', customerCode: 'A', customerStatus: 'ACTIVE', outstandingAmount: '1234.50', overdueAmount: '100.00', unallocatedCredit: '50.00' }], page: '0', size: '20', totalElements: '21', totalPages: '2' }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.report).mockResolvedValue(result) })
afterEach(() => cleanup())
it('shows separate balances and applies search with server pagination', async () => {
  render(<Reports company={company} onExpired={vi.fn()} />)
  expect(await screen.findByText('TZS 1,234.50')).toBeInTheDocument()
  expect(screen.getByText('TZS 50.00')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Next' }))
  await waitFor(() => expect(api.report).toHaveBeenLastCalledWith('1', 'customers', 1, expect.anything(), expect.anything()))
  fireEvent.change(screen.getByLabelText('Search'), { target: { value: 'Alice' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }))
  await waitFor(() => expect(api.report).toHaveBeenLastCalledWith('1', 'customers', 0, expect.objectContaining({ q: 'Alice' }), expect.anything()))
})
it('validates payment dates and sends supported filters', async () => {
  vi.mocked(api.report).mockImplementation((_company, kind) => Promise.resolve(kind === 'customers' ? result : { ...result, items: [] }))
  render(<Reports company={company} onExpired={vi.fn()} />)
  await screen.findByText('Customer A')
  fireEvent.change(screen.getByLabelText('Report'), { target: { value: 'payments' } })
  fireEvent.change(screen.getByLabelText('Received from (Tanzania)'), { target: { value: '2026-12-01' } })
  fireEvent.change(screen.getByLabelText('Received to (Tanzania)'), { target: { value: '2026-01-01' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }))
  expect(screen.getByRole('alert')).toHaveTextContent('from must not be after to')
  fireEvent.change(screen.getByLabelText('Received to (Tanzania)'), { target: { value: '2026-12-31' } })
  fireEvent.change(screen.getByLabelText('Method'), { target: { value: 'CASH' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }))
  await waitFor(() => expect(api.report).toHaveBeenLastCalledWith('1', 'payments', 0, expect.objectContaining({ method: 'CASH', from: '2026-12-01' }), expect.anything()))
  expect(screen.getByText(/statuses are current, not historical/i)).toBeInTheDocument()
})
it('shows forbidden and retries', async () => {
  vi.mocked(api.report).mockRejectedValueOnce(new ApiError(403, 'denied')).mockResolvedValueOnce(result)
  render(<Reports company={company} onExpired={vi.fn()} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('permission')
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByText('Customer A')).toBeInTheDocument()
})
it('ignores stale report responses on unmount', async () => {
  let finish!: (value: ReportPage<CustomerDebt>) => void
  vi.mocked(api.report).mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValueOnce({ ...result, items: [] })
  const view = render(<Reports key="1" company={company} onExpired={vi.fn()} />)
  view.rerender(<Reports key="2" company={{ ...company, id: '2' }} onExpired={vi.fn()} />)
  expect(await screen.findByText('No report results found.')).toBeInTheDocument()
  finish(result)
  await waitFor(() => expect(screen.queryByText('Customer A')).not.toBeInTheDocument())
})
