import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, ApiError, type Company, type Customer, type CustomerFields, type CustomerPage } from './api'

type Load<T> = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; data: T }
const empty: CustomerFields = { displayName: '', phone: '', email: '', address: '', notes: '' }
const fields = (c: Customer): CustomerFields => ({ displayName: c.displayName, phone: c.phone ?? '', email: c.email ?? '', address: c.address ?? '', notes: c.notes ?? '' })
const message = (e: unknown) => e instanceof ApiError && e.status === 403 ? 'You do not have permission for this action.' : e instanceof ApiError && e.status === 400 ? e.message : 'Unable to complete the request. Please try again.'

export default function Customers({ company, onExpired, setDirty }: { company: Company; onExpired: () => void; setDirty: (value: boolean) => void }) {
  const [list, setList] = useState<Load<CustomerPage>>({ kind: 'loading' })
  const [detail, setDetail] = useState<Load<Customer> | null>(null)
  const [search, setSearch] = useState(''), [query, setQuery] = useState(''), [status, setStatus] = useState(''), [page, setPage] = useState(0), [reload, setReload] = useState(0)
  const [form, setForm] = useState<CustomerFields | null>(null), [original, setOriginal] = useState<CustomerFields | null>(null), [editId, setEditId] = useState<string | null>(null)
  const [error, setError] = useState(''), [success, setSuccess] = useState(''), [busy, setBusy] = useState(false)
  const alive = useRef(true), detailRequest = useRef<AbortController | null>(null), mutation = useRef(false)
  const canManage = company.permissions.includes('CUSTOMER_MANAGE')
  const dirty = form !== null && original !== null && JSON.stringify(form) !== JSON.stringify(original)
  useEffect(() => { setDirty(dirty) }, [dirty, setDirty])
  useEffect(() => { alive.current = true; return () => { alive.current = false; detailRequest.current?.abort() } }, [])
  useEffect(() => {
    const controller = new AbortController(); let active = true
    setList({ kind: 'loading' })
    api.customers(company.id, { page, q: query, status }, controller.signal).then(data => { if (active) setList({ kind: 'ready', data }) }).catch(e => { if (active) { if (e instanceof ApiError && e.status === 401) onExpired(); else setList({ kind: 'error', message: message(e) }) } })
    return () => { active = false; controller.abort() }
  }, [company.id, page, query, status, reload])
  const discard = () => !dirty || window.confirm('Discard unsaved customer changes?')
  function clearForm() { setForm(null); setOriginal(null); setEditId(null); setDirty(false); setError('') }
  function showDetail(id: string) {
    if (!discard()) return
    clearForm(); setSuccess(''); detailRequest.current?.abort()
    const controller = new AbortController(); detailRequest.current = controller; setDetail({ kind: 'loading' })
    api.customer(company.id, id, controller.signal).then(data => { if (alive.current && !controller.signal.aborted) setDetail({ kind: 'ready', data }) }).catch(e => { if (alive.current && !controller.signal.aborted) { if (e instanceof ApiError && e.status === 401) onExpired(); else setDetail({ kind: 'error', message: message(e) }) } })
  }
  function startForm(customer?: Customer) {
    if (!discard()) return
    detailRequest.current?.abort(); setDetail(null); setSuccess(''); setError('')
    const initial = customer ? fields(customer) : { ...empty }
    setOriginal(initial); setForm(initial); setEditId(customer?.id ?? null)
  }
  async function save(e: FormEvent) {
    e.preventDefault(); if (!form || mutation.current) return
    if (!form.displayName.trim()) { setError('Display name is required.'); return }
    if (form.email.trim() && !form.email.includes('@')) { setError('Email must contain @.'); return }
    mutation.current = true; setBusy(true); setError('')
    try {
      const customer = await api.saveCustomer(company.id, editId, form)
      if (!alive.current) return
      clearForm(); setDetail({ kind: 'ready', data: customer }); setSuccess(editId ? 'Customer updated.' : 'Customer created.'); setReload(n => n + 1)
    } catch (e) { if (alive.current) { if (e instanceof ApiError && e.status === 401) onExpired(); else setError(message(e)) } }
    finally { mutation.current = false; if (alive.current) setBusy(false) }
  }
  async function changeStatus(customer: Customer) {
    if (mutation.current || !window.confirm(`${customer.status === 'ACTIVE' ? 'Deactivate' : 'Activate'} ${customer.displayName}?`)) return
    mutation.current = true; setBusy(true); setError(''); setSuccess('')
    try {
      const updated = await api.saveCustomer(company.id, customer.id, { status: customer.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' })
      if (alive.current) { setDetail({ kind: 'ready', data: updated }); setSuccess(updated.status === 'ACTIVE' ? 'Customer activated.' : 'Customer deactivated.'); setReload(n => n + 1) }
    } catch (e) { if (alive.current) { if (e instanceof ApiError && e.status === 401) onExpired(); else setError(message(e)) } }
    finally { mutation.current = false; if (alive.current) setBusy(false) }
  }
  function filter(e: FormEvent) { e.preventDefault(); setPage(0); setQuery(search.trim()) }
  return <><div className="heading"><div><p className="eyebrow">{company.code} / CUSTOMERS</p><h1>Customers</h1><p>Customer records for {company.displayName}.</p></div>{canManage && <button onClick={() => startForm()}>Add customer</button>}</div>
    {success && <p role="status" className="success">{success}</p>}
    {form && <section className="panel customer-panel"><h2>{editId ? 'Edit customer' : 'Add customer'}</h2><p>Company: {company.displayName}. Customer codes are assigned by the system.</p><form onSubmit={save} className="customer-form">
      <label>Display name <span aria-hidden="true">*</span><input required maxLength={255} value={form.displayName} onChange={e => setForm({ ...form, displayName: e.target.value })} /></label>
      <label>Phone<input maxLength={50} value={form.phone} onChange={e => setForm({ ...form, phone: e.target.value })} /></label>
      <label>Email<input maxLength={320} value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label>
      <label>Address<input maxLength={500} value={form.address} onChange={e => setForm({ ...form, address: e.target.value })} /></label>
      <label>Notes<textarea maxLength={10000} rows={4} value={form.notes} onChange={e => setForm({ ...form, notes: e.target.value })} /></label>
      {error && <p className="error" role="alert">{error}</p>}<div className="actions"><button disabled={busy} type="submit">{busy ? 'Saving…' : 'Save customer'}</button><button className="secondary" type="button" disabled={busy} onClick={() => { if (discard()) clearForm() }}>Cancel</button></div></form></section>}
    {detail && <section className="panel customer-panel"><h2>Customer details</h2>{detail.kind === 'loading' ? <p role="status">Loading customer…</p> : detail.kind === 'error' ? <p role="alert">{detail.message}</p> : <><dl className="customer-details"><dt>Name</dt><dd>{detail.data.displayName}</dd><dt>Code</dt><dd>{detail.data.customerCode ?? '—'}</dd><dt>Status</dt><dd>{detail.data.status === 'ACTIVE' ? 'Active' : 'Inactive'}</dd><dt>Phone</dt><dd>{detail.data.phone || '—'}</dd><dt>Email</dt><dd>{detail.data.email || '—'}</dd><dt>Address</dt><dd>{detail.data.address || '—'}</dd><dt>Notes</dt><dd>{detail.data.notes || '—'}</dd></dl>{error && <p role="alert" className="error">{error}</p>}<div className="actions">{canManage && <><button onClick={() => startForm(detail.data)} disabled={busy}>Edit</button><button className="secondary" onClick={() => changeStatus(detail.data)} disabled={busy}>{detail.data.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}</button></>}<button className="secondary" onClick={() => { setDetail(null); setError('') }}>Close</button></div></>}</section>}
    <section className="panel"><form onSubmit={filter} className="customer-filters"><label>Search customers<input value={search} onChange={e => setSearch(e.target.value)} placeholder="Name, code, email or phone" /></label><label>Status<select value={status} onChange={e => { setPage(0); setStatus(e.target.value) }}><option value="">All statuses</option><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option></select></label><button type="submit">Search</button></form>
    {list.kind === 'loading' ? <p role="status">Loading customers…</p> : list.kind === 'error' ? <><p role="alert">{list.message}</p><button onClick={() => setReload(n => n + 1)}>Try again</button></> : list.data.items.length === 0 ? <p className="empty">No customers found.</p> : <><div className="table-wrap"><table><thead><tr><th>Name</th><th>Code</th><th>Phone</th><th>Status</th><th>Details</th></tr></thead><tbody>{list.data.items.map(c => <tr key={c.id}><td>{c.displayName}</td><td>{c.customerCode ?? '—'}</td><td>{c.phone ?? '—'}</td><td>{c.status === 'ACTIVE' ? 'Active' : 'Inactive'}</td><td><button className="secondary" onClick={() => showDetail(c.id)} aria-label={`View ${c.displayName}`}>View</button></td></tr>)}</tbody></table></div><div className="pagination"><span>Page {page + 1} of {list.data.totalPages} · {list.data.totalElements} customers</span><div className="actions"><button className="secondary" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</button><button className="secondary" disabled={page + 1 >= Number(list.data.totalPages)} onClick={() => setPage(p => p + 1)}>Next</button></div></div></>}
    </section></>
}
