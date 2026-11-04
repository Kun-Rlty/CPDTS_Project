import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, ApiError, type Company, type Member, type Session } from './api'

type Load = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; data: Member[] }
type Form = { mode: 'create' | 'attach'; email: string; fullName: string; role: string }
const roles = ['COMPANY_ADMIN', 'FINANCE_OFFICER', 'PAYMENT_VERIFIER', 'VIEWER'] as const
const roleLabel = (role: string) => role.replaceAll('_', ' ')
const errorMessage = (e: unknown) => e instanceof ApiError && e.status === 403 ? 'You do not have permission to manage users in this company.' : e instanceof ApiError && e.status === 404 ? 'This membership was not found in this company.' : e instanceof ApiError && (e.status === 400 || e.status === 409) ? e.message : 'Unable to complete the request. Check your connection and try again.'

export default function UsersAccess({ company, user, onExpired, setDirty, refreshAccess }: { company: Company; user: Session['user']; onExpired: () => void; setDirty: (value: boolean) => void; refreshAccess: (userId: string, companyId: string) => Promise<void> }) {
  const [list, setList] = useState<Load>({ kind: 'loading' }), [reload, setReload] = useState(0)
  const [form, setForm] = useState<Form | null>(null), [passwordTouched, setPasswordTouched] = useState(false)
  const [editing, setEditing] = useState<Member | null>(null), [role, setRole] = useState('')
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [success, setSuccess] = useState('')
  const passwordRef = useRef<HTMLInputElement>(null), alive = useRef(true), mutation = useRef(false)
  const dirty = !!form && (!!form.email || !!form.fullName || passwordTouched || form.role !== 'VIEWER') || !!editing && role !== editing.role
  useEffect(() => { setDirty(dirty) }, [dirty, setDirty])
  useEffect(() => { alive.current = true; return () => { alive.current = false; if (passwordRef.current) passwordRef.current.value = '' } }, [])
  useEffect(() => {
    const controller = new AbortController(); let active = true; setList({ kind: 'loading' })
    api.members(company.id, controller.signal).then(data => { if (active) setList({ kind: 'ready', data }) }).catch(e => { if (active) { if (e instanceof ApiError && e.status === 401) onExpired(); else setList({ kind: 'error', message: errorMessage(e) }) } })
    return () => { active = false; controller.abort() }
  }, [company.id, reload])
  const discard = () => !dirty || window.confirm('Discard unsaved user or membership changes?')
  function clear() { if (passwordRef.current) passwordRef.current.value = ''; setPasswordTouched(false); setForm(null); setEditing(null); setRole(''); setDirty(false); setError('') }
  function start(mode: 'create' | 'attach') { if (!discard()) return; clear(); setSuccess(''); setForm({ mode, email: '', fullName: '', role: 'VIEWER' }) }
  function edit(member: Member) { if (!discard()) return; clear(); setSuccess(''); setEditing(member); setRole(member.role) }
  async function submit(e: FormEvent) {
    e.preventDefault(); if (!form || mutation.current) return
    const password = passwordRef.current?.value ?? ''
    if (passwordRef.current) passwordRef.current.value = ''
    setPasswordTouched(false); setError('')
    if (!form.email.trim() || !form.email.includes('@') || (form.mode === 'create' && !form.fullName.trim())) { setError('Enter a valid email and required full name.'); return }
    if (form.mode === 'create' && (password.trim() === '' || password.length < 12 || new TextEncoder().encode(password).length > 72)) { setError('Password must be at least 12 characters and at most 72 UTF-8 bytes. Re-enter the password.'); return }
    mutation.current = true; setBusy(true)
    try {
      await api.createMember(company.id, form.mode === 'create' ? { email: form.email.trim(), fullName: form.fullName.trim(), password, role: form.role } : { email: form.email.trim(), role: form.role })
      if (alive.current) { const mode = form.mode; clear(); setSuccess(mode === 'create' ? 'User and membership created.' : 'Existing account attached to this company.'); setReload(n => n + 1) }
    } catch (e) { if (alive.current) { if (e instanceof ApiError && e.status === 401) onExpired(); else setError(errorMessage(e)) } }
    finally { mutation.current = false; if (alive.current) setBusy(false) }
  }
  async function update(member: Member, changes: { role?: string; status?: string }) {
    if (mutation.current) return
    const warning = changes.status === 'INACTIVE' ? `Deactivate ${member.fullName}'s membership in ${company.displayName}? They will lose access to this company; their global account is unchanged.` : changes.status === 'ACTIVE' ? `Activate ${member.fullName}'s membership in ${company.displayName}? Their global account status is unchanged.` : `Change ${member.fullName}'s role in ${company.displayName}? Permissions will change immediately.`
    if (!window.confirm(warning)) return
    mutation.current = true; setBusy(true); setError(''); setSuccess('')
    try {
      await api.updateMembership(company.id, member.userId, changes)
      if (member.userId === user.id) { if (alive.current) clear(); await refreshAccess(user.id, company.id); return }
      if (!alive.current) return
      clear()
      setSuccess('Membership updated.'); setReload(n => n + 1)
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) { if (alive.current) onExpired() }
      else if (member.userId === user.id) await refreshAccess(user.id, company.id)
      else if (alive.current) setError(errorMessage(e))
    }
    finally { mutation.current = false; if (alive.current) setBusy(false) }
  }
  return <><div className="heading"><div><p className="eyebrow">{company.code} / ADMINISTRATION</p><h1>Users &amp; Access</h1><p>Manage memberships for {company.displayName}. Account status is global and cannot be edited here.</p></div><div className="actions"><button disabled={busy} onClick={() => start('create')}>Create user</button>{user.platformAdmin && <button disabled={busy} className="secondary" onClick={() => start('attach')}>Attach existing account</button>}</div></div>
    {success && <p role="status" className="success">{success}</p>}
    {form && <section className="panel customer-panel"><h2>{form.mode === 'create' ? 'Create user' : 'Attach existing account'}</h2><p>Company: {company.displayName}. {form.mode === 'attach' ? 'Existing account credentials and profile will not be changed.' : 'A new account and active company membership will be created.'}</p><form onSubmit={submit} className="customer-form" noValidate><label>Email<input type="email" maxLength={320} required value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label>{form.mode === 'create' && <><label>Full name<input maxLength={255} required value={form.fullName} onChange={e => setForm({ ...form, fullName: e.target.value })} /></label><label>Password<input ref={passwordRef} type="password" autoComplete="new-password" required onChange={e => setPasswordTouched(!!e.target.value)} /></label><p>At least 12 characters, at most 72 UTF-8 bytes. Passwords are cleared after submission.</p></>}<label>Membership role<select value={form.role} onChange={e => setForm({ ...form, role: e.target.value })}>{roles.map(r => <option key={r} value={r}>{roleLabel(r)}</option>)}</select></label>{error && <p role="alert" className="error">{error}</p>}<div className="actions"><button type="submit" disabled={busy}>{busy ? 'Saving…' : form.mode === 'create' ? 'Create user' : 'Attach account'}</button><button className="secondary" type="button" disabled={busy} onClick={() => { if (discard()) clear() }}>Cancel</button></div></form></section>}
    {editing && <section className="panel customer-panel"><h2>Change membership role</h2><p>{editing.fullName} · {editing.email} · {company.displayName}</p><label>New membership role<select value={role} onChange={e => setRole(e.target.value)}>{roles.map(r => <option key={r} value={r}>{roleLabel(r)}</option>)}</select></label>{error && <p role="alert" className="error">{error}</p>}<div className="actions"><button disabled={busy || role === editing.role} onClick={() => update(editing, { role })}>Save role</button><button disabled={busy} className="secondary" onClick={() => { if (discard()) clear() }}>Cancel role change</button></div></section>}
    <section className="panel"><h2>Company members</h2><p>Deactivating a membership removes access only to this company; it does not disable the global account.</p>{!form && !editing && error && <p role="alert" className="error">{error}</p>}{list.kind === 'loading' ? <p role="status">Loading members…</p> : list.kind === 'error' ? <><p role="alert">{list.message}</p><button onClick={() => setReload(n => n + 1)}>Try again</button></> : list.data.length === 0 ? <p className="empty">No company members found.</p> : <div className="table-wrap"><table><thead><tr><th>Name</th><th>Email</th><th>Account status</th><th>Membership status</th><th>Role</th><th>Actions</th></tr></thead><tbody>{list.data.map(member => <tr key={member.userId}><td>{member.fullName}</td><td>{member.email}</td><td>{member.userStatus}</td><td>{member.membershipStatus}</td><td>{roleLabel(member.role)}</td><td><div className="actions"><button className="secondary" disabled={busy} aria-label={`Change role for ${member.fullName}`} onClick={() => edit(member)}>Change role</button><button className="secondary" disabled={busy} aria-label={`${member.membershipStatus === 'ACTIVE' ? 'Deactivate' : 'Activate'} membership for ${member.fullName}`} onClick={() => { if (discard()) update(member, { status: member.membershipStatus === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' }) }}>{member.membershipStatus === 'ACTIVE' ? 'Deactivate' : 'Activate'}</button></div></td></tr>)}</tbody></table></div>}</section></>
}
