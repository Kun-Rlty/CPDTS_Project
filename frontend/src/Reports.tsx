import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError, formatMoney, type Company, type CustomerDebt, type Invoice, type PaymentRecord, type ReportFilters, type ReportKind, type ReportPage } from './api'

type Row = CustomerDebt | Invoice | PaymentRecord
type Load = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; reportKind: ReportKind; data: ReportPage<Row> }
const empty = (): ReportFilters => ({ q: '', customerId: '', overdueOnly: '', status: '', method: '', from: '', to: '' })
const labels: Record<ReportKind, string> = { customers: 'Customer debt', invoices: 'Outstanding invoices', payments: 'Payment register' }
const text = (s: string) => s.replaceAll('_', ' ')
const tanzania = (s: string) => new Intl.DateTimeFormat('en-GB', { timeZone: 'Africa/Dar_es_Salaam', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(s))

export default function Reports({ company, onExpired }: { company: Company; onExpired: () => void }) {
  const [kind, setKind] = useState<ReportKind>('customers'), [draft, setDraft] = useState(empty), [filters, setFilters] = useState(empty)
  const [page, setPage] = useState(0), [retry, setRetry] = useState(0), [error, setError] = useState('')
  const [load, setLoad] = useState<Load>({ kind: 'loading' })
  useEffect(() => {
    const controller = new AbortController(); let active = true
    setLoad({ kind: 'loading' })
    api.report(company.id, kind, page, filters, controller.signal).then(data => { if (active) setLoad({ kind: 'ready', reportKind: kind, data }) }).catch(e => {
      if (!active) return
      if (e instanceof ApiError && e.status === 401) onExpired()
      else setLoad({ kind: 'error', message: e instanceof ApiError && e.status === 403 ? 'You do not have permission to view reports for this company.' : 'Unable to load this report. Please try again.' })
    })
    return () => { active = false; controller.abort() }
  }, [company.id, kind, page, filters, retry])
  function apply(e: FormEvent) {
    e.preventDefault()
    if (draft.customerId && (!/^[1-9]\d*$/.test(draft.customerId) || BigInt(draft.customerId) > 9223372036854775807n)) { setError('Customer ID must be a positive integer.'); return }
    if ([draft.from, draft.to].some(d => d && (d < '2000-01-01' || d > '2100-12-31')) || (draft.from && draft.to && draft.from > draft.to)) { setError('Choose valid Tanzania dates between 2000 and 2100; from must not be after to.'); return }
    setError(''); setPage(0); setFilters({ ...draft, q: draft.q.trim() })
  }
  function changeKind(value: ReportKind) { setKind(value); setDraft(empty()); setFilters(empty()); setPage(0); setError('') }
  return <><div className="heading"><div><p className="eyebrow">{company.code} / REPORTS</p><h1>Reports</h1><p>Company: {company.displayName}</p></div></div>
    <section className="panel"><label>Report<select value={kind} onChange={e => changeKind(e.target.value as ReportKind)}>{(Object.keys(labels) as ReportKind[]).map(k => <option value={k} key={k}>{labels[k]}</option>)}</select></label>
      <h2>{labels[kind]}</h2>{kind === 'payments' ? <p>Received-date activity in Tanzania business dates. Payment statuses are current, not historical.</p> : <p>Current balances, not historical balances. Gross debt and verified unallocated credit are separate; overdue means due before the current Tanzania business date.</p>}
      <form className="customer-filters" onSubmit={apply} noValidate><label>Search<input value={draft.q} onChange={e => setDraft({ ...draft, q: e.target.value })} /></label>
        {kind !== 'customers' && <label>Customer ID<input inputMode="numeric" value={draft.customerId} onChange={e => setDraft({ ...draft, customerId: e.target.value })} /></label>}
        {kind === 'invoices' && <label>Invoice scope<select value={draft.overdueOnly} onChange={e => setDraft({ ...draft, overdueOnly: e.target.value })}><option value="">All outstanding</option><option value="true">Overdue only</option></select></label>}
        {kind === 'payments' && <><label>Current payment status<select value={draft.status} onChange={e => setDraft({ ...draft, status: e.target.value })}><option value="">All statuses</option>{['PENDING_VERIFICATION', 'VERIFIED', 'REJECTED', 'REVERSED'].map(v => <option key={v} value={v}>{text(v)}</option>)}</select></label><label>Method<select value={draft.method} onChange={e => setDraft({ ...draft, method: e.target.value })}><option value="">All methods</option>{['CASH', 'BANK_TRANSFER', 'MOBILE_MONEY'].map(v => <option key={v} value={v}>{text(v)}</option>)}</select></label><label>Received from (Tanzania)<input type="date" min="2000-01-01" max="2100-12-31" value={draft.from} onChange={e => setDraft({ ...draft, from: e.target.value })} /></label><label>Received to (Tanzania)<input type="date" min="2000-01-01" max="2100-12-31" value={draft.to} onChange={e => setDraft({ ...draft, to: e.target.value })} /></label></>}
        <button type="submit">Apply filters</button></form>{error && <p role="alert" className="error">{error}</p>}
      {(load.kind === 'loading' || load.kind === 'ready' && load.reportKind !== kind) ? <p role="status">Loading report…</p> : load.kind === 'error' ? <><p role="alert">{load.message}</p><button onClick={() => setRetry(n => n + 1)}>Try again</button></> : <><p>Current Tanzania business date: {load.data.basis.businessDate} · Observed: {tanzania(load.data.basis.observedAt)} Tanzania</p>{load.data.items.length === 0 ? <p className="empty">No report results found.</p> : <div className="table-wrap"><table><caption>{labels[kind]} · {company.displayName}</caption><thead>{kind === 'customers' ? <tr><th>Customer</th><th>Code</th><th>Account status</th><th>Gross outstanding debt</th><th>Overdue debt</th><th>Verified unallocated credit</th></tr> : kind === 'invoices' ? <tr><th>Invoice</th><th>Customer</th><th>Due date</th><th>Payment state</th><th>Outstanding debt</th></tr> : <tr><th>Payment</th><th>Customer</th><th>Received (Tanzania)</th><th>Method</th><th>Current status</th><th>Amount</th></tr>}</thead><tbody>{load.data.items.map(row => kind === 'customers' ? ((c: CustomerDebt) => <tr key={c.customerId}><td>{c.customerName}</td><td>{c.customerCode || '—'}</td><td>{c.customerStatus}</td><td>{formatMoney(c.outstandingAmount)}</td><td>{formatMoney(c.overdueAmount)}</td><td>{formatMoney(c.unallocatedCredit)}</td></tr>)(row as CustomerDebt) : kind === 'invoices' ? ((i: Invoice) => <tr key={i.id}><td>{i.invoiceNumber}</td><td>{i.customerName}</td><td>{i.dueDate}</td><td>{text(i.derivedStatus)}</td><td>{formatMoney(i.outstandingAmount)}</td></tr>)(row as Invoice) : ((p: PaymentRecord) => <tr key={p.id}><td>{p.paymentNumber}</td><td>{p.customerName}</td><td>{tanzania(p.receivedAt)}</td><td>{text(p.method)}</td><td>{text(p.status)}</td><td>{formatMoney(p.amount)}</td></tr>)(row as PaymentRecord))}</tbody></table></div>}
        <div className="pagination"><span>{load.data.totalElements} results · Page {page + 1} of {load.data.totalPages}</span><div className="actions"><button className="secondary" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</button><button className="secondary" disabled={page + 1 >= Number(load.data.totalPages)} onClick={() => setPage(p => p + 1)}>Next</button></div></div></>}
    </section></>
}
