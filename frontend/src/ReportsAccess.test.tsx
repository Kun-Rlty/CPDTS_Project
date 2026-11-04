import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import App from './App'
import { api } from './api'

vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), api: { me: vi.fn(), report: vi.fn() } }))
afterEach(() => { cleanup(); vi.resetAllMocks() })
it('does not request reports without REPORT_VIEW', async () => {
  vi.mocked(api.me).mockResolvedValue({ user: { id: '1', email: 'user@example.test', fullName: 'User', platformAdmin: false }, companies: [{ id: '1', code: 'JMX', displayName: 'Jeimax', role: 'VIEWER', permissions: ['RECORD_VIEW'] }] })
  const router = createMemoryRouter([{ path: '*', element: <App /> }], { initialEntries: ['/reports'] })
  render(<RouterProvider router={router} />)
  expect(await screen.findByText('Reports unavailable')).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: 'Reports' })).not.toBeInTheDocument()
  expect(api.report).not.toHaveBeenCalled()
})
