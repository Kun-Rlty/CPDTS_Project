import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import App from './App'
import { api, ApiError, type Member, type Session } from './api'

vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), api: { me: vi.fn(), logout: vi.fn(), members: vi.fn(), createMember: vi.fn(), updateMembership: vi.fn() } }))
const company = { id: '1', code: 'JMX', displayName: 'Jeimax', role: 'COMPANY_ADMIN', permissions: ['USER_MANAGE', 'RECORD_VIEW'] }
const session: Session = { user: { id: '1', email: 'manager@example.test', fullName: 'Manager', platformAdmin: false }, companies: [company] }
const member: Member = { userId: '2', fullName: 'Member', email: 'member@example.test', userStatus: 'ACTIVE', membershipStatus: 'ACTIVE', role: 'VIEWER' }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.me).mockResolvedValue(session); vi.mocked(api.members).mockResolvedValue([member]); vi.mocked(api.createMember).mockResolvedValue(member); vi.mocked(api.updateMembership).mockResolvedValue(member) })
afterEach(() => { cleanup(); vi.restoreAllMocks() })
function start() { const router = createMemoryRouter([{ path: '*', element: <App /> }], { initialEntries: ['/users'] }); render(<RouterProvider router={router} />); return router }

it('shows separate account and membership states and restricts attachment', async () => {
  start(); expect(await screen.findByText('member@example.test')).toBeInTheDocument()
  expect(screen.getByText('Account status')).toBeInTheDocument()
  expect(screen.getByText('Membership status')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Attach existing account' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Create user' }))
  expect(screen.queryByRole('option', { name: 'PLATFORM ADMIN' })).not.toBeInTheDocument()
})

it('clears password after validation and backend conflict without persisting it', async () => {
  vi.mocked(api.createMember).mockRejectedValue(new ApiError(409, 'a user with this email already exists'))
  start(); fireEvent.click(await screen.findByRole('button', { name: 'Create user' }))
  const form = screen.getByRole('heading', { name: 'Create user' }).closest('section')!
  fireEvent.change(within(form).getByLabelText('Email'), { target: { value: 'new@example.test' } })
  fireEvent.change(within(form).getByLabelText('Full name'), { target: { value: 'New User' } })
  const password = within(form).getByLabelText('Password') as HTMLInputElement
  fireEvent.change(password, { target: { value: 'short' } })
  fireEvent.click(within(form).getByRole('button', { name: 'Create user' }))
  expect(screen.getByText(/at least 12 characters/)).toBeInTheDocument()
  expect(password.value).toBe('')
  fireEvent.change(password, { target: { value: '界'.repeat(25) } })
  fireEvent.click(within(form).getByRole('button', { name: 'Create user' }))
  expect(screen.getByRole('alert')).toHaveTextContent('at most 72 UTF-8 bytes')
  expect(password.value).toBe('')
  fireEvent.change(password, { target: { value: 'test-only-password-123' } })
  fireEvent.click(within(form).getByRole('button', { name: 'Create user' }))
  expect(await screen.findByText('a user with this email already exists')).toBeInTheDocument()
  expect(password.value).toBe('')
  expect(JSON.stringify(sessionStorage)).not.toContain('test-only-password-123')
  expect(JSON.stringify(localStorage)).not.toContain('test-only-password-123')
})

it('prevents repeated submissions while creation is pending', async () => {
  vi.mocked(api.createMember).mockImplementation(() => new Promise(() => {}))
  start(); fireEvent.click(await screen.findByRole('button', { name: 'Create user' }))
  const form = screen.getByRole('heading', { name: 'Create user' }).closest('section')!
  fireEvent.change(within(form).getByLabelText('Email'), { target: { value: 'pending@example.test' } })
  fireEvent.change(within(form).getByLabelText('Full name'), { target: { value: 'Pending User' } })
  fireEvent.change(within(form).getByLabelText('Password'), { target: { value: 'test-only-password-123' } })
  fireEvent.click(within(form).getByRole('button', { name: 'Create user' }))
  expect(within(form).getByRole('button', { name: 'Saving…' })).toBeDisabled()
  expect(api.createMember).toHaveBeenCalledTimes(1)
  expect((within(form).getByLabelText('Password') as HTMLInputElement).value).toBe('')
})

it('changes role and deactivates membership with confirmation', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  start(); fireEvent.click(await screen.findByRole('button', { name: 'Change role for Member' }))
  fireEvent.change(screen.getByLabelText('New membership role'), { target: { value: 'FINANCE_OFFICER' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save role' }))
  await waitFor(() => expect(api.updateMembership).toHaveBeenCalledWith('1', '2', { role: 'FINANCE_OFFICER' }))
  await screen.findByText('Membership updated.')
  fireEvent.click(screen.getByRole('button', { name: 'Deactivate membership for Member' }))
  await waitFor(() => expect(api.updateMembership).toHaveBeenCalledWith('1', '2', { status: 'INACTIVE' }))
  expect(confirm).toHaveBeenCalledWith(expect.stringContaining('global account is unchanged'))
})

it('immediately refreshes access after changing the current membership', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.mocked(api.members).mockResolvedValue([{ ...member, userId: '1', fullName: 'Manager', role: 'COMPANY_ADMIN' }])
  vi.mocked(api.me).mockResolvedValueOnce(session).mockResolvedValueOnce({ ...session, companies: [] })
  start(); fireEvent.click(await screen.findByRole('button', { name: 'Deactivate membership for Manager' }))
  expect(await screen.findByText('No company access')).toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: 'Company members' })).not.toBeInTheDocument()
})

it('does not overwrite another company screen when a delayed self-change completes', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  const other = { ...company, id: '2', code: 'KAM', displayName: 'Kam' }
  const both = { ...session, companies: [company, other] }
  vi.mocked(api.me).mockResolvedValueOnce(both).mockResolvedValueOnce({ ...session, companies: [other] })
  vi.mocked(api.members).mockImplementation(id => Promise.resolve(id === '1' ? [{ ...member, userId: '1', fullName: 'Manager', role: 'COMPANY_ADMIN' }] : []))
  let finish!: (value: Member) => void
  vi.mocked(api.updateMembership).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  start(); const selector = await screen.findByLabelText('Company')
  fireEvent.change(selector, { target: { value: '1' } })
  fireEvent.click(await screen.findByRole('button', { name: 'Deactivate membership for Manager' }))
  fireEvent.change(selector, { target: { value: '2' } })
  fireEvent.click(await screen.findByRole('button', { name: 'Create user' }))
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'unsaved@example.test' } })
  finish(member)
  await waitFor(() => expect(api.me).toHaveBeenCalledTimes(2))
  expect(screen.getByDisplayValue('unsaved@example.test')).toBeInTheDocument()
  expect(selector).toHaveValue('2')
})

it('lets platform admins attach existing accounts without password or profile', async () => {
  vi.mocked(api.me).mockResolvedValue({ ...session, user: { ...session.user, platformAdmin: true } })
  start(); fireEvent.click(await screen.findByRole('button', { name: 'Attach existing account' }))
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'member@example.test' } })
  expect(screen.queryByLabelText('Password')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Attach account' }))
  await waitFor(() => expect(api.createMember).toHaveBeenCalledWith('1', { email: 'member@example.test', role: 'VIEWER' }))
})

it('denies unauthorized routes without requesting members', async () => {
  vi.mocked(api.me).mockResolvedValue({ ...session, companies: [{ ...company, permissions: ['RECORD_VIEW'] }] })
  start(); expect(await screen.findByText('Users & Access unavailable')).toBeInTheDocument()
  expect(api.members).not.toHaveBeenCalled()
})

it('discards late company responses and protects unsaved edits on switching', async () => {
  let resolveOld!: (members: Member[]) => void
  vi.mocked(api.me).mockResolvedValue({ ...session, companies: [company, { ...company, id: '2', code: 'KAM', displayName: 'Kam' }] })
  vi.mocked(api.members).mockImplementation(id => id === '1' ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve([]))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  start(); const selector = await screen.findByLabelText('Company')
  fireEvent.change(selector, { target: { value: '1' } })
  fireEvent.click(await screen.findByRole('button', { name: 'Create user' }))
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'unsaved@example.test' } })
  fireEvent.change(selector, { target: { value: '2' } })
  expect(confirm).toHaveBeenCalled(); expect(screen.getByDisplayValue('unsaved@example.test')).toBeInTheDocument()
  confirm.mockReturnValue(true); fireEvent.change(selector, { target: { value: '2' } })
  expect(await screen.findByText('No company members found.')).toBeInTheDocument()
  resolveOld([member]); await waitFor(() => expect(screen.queryByText('member@example.test')).not.toBeInTheDocument())
})
