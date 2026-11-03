import { useEffect, useState, type FormEvent } from 'react'
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { api, ApiError, formatMoney, type Company, type Dashboard, type PaymentPage, type Session } from './api'

type Load<T> = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; data: T }

export default function App() {
  const [session, setSession] = useState<Session | null | undefined>(undefined)
  const [sessionError, setSessionError] = useState('')
  const [selected, setSelected] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [expired, setExpired] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    let active = true
    setSession(undefined); setSessionError('')
    api.me().then(s => { if (active) { setSession(s); setSelected(s.companies.length === 1 ? s.companies[0].id : '') } })
      .catch(e => { if (active) { if (e instanceof ApiError && e.status === 401) setSession(null); else setSessionError('Unable to restore your session. Check your connection and try again.') } })
    return () => { active = false }
  }, [attempt])

  const onExpired = () => { setSelected(''); setSession(null); setExpired(true); navigate('/login', { replace: true }) }
  if (sessionError) return <main className="center"><div className="panel"><p role="alert">{sessionError}</p><button onClick={() => setAttempt(n => n + 1)}>Try again</button></div></main>
  if (session === undefined) return <main className="center" role="status">Checking your session…</main>
  const company = session?.companies.find(c => c.id === selected)
  return <Routes>
    <Route path="/login" element={session ? <Navigate to="/" replace /> : <Login expired={expired} onLogin={s => { setExpired(false); setSession(s); setSelected(s.companies.length === 1 ? s.companies[0].id : ''); navigate('/', { replace: true }) }} />} />
    <Route path="/" element={!session ? <Navigate to="/login" replace /> : <Shell session={session} selected={selected} select={setSelected} onLogout={async () => { try { await api.logout(); setSelected(''); setSession(null); navigate('/login', { replace: true }) } catch (e) { if (e instanceof ApiError && e.status === 401) onExpired(); else throw e } }}>
      {session.companies.length === 0 ? <section className="panel"><h2>No company access</h2><p>Your account has no active company access. Contact an administrator.</p></section> : !company ? <section className="panel"><h2>Select a company</h2><p>Choose an available company above to view its dashboard.</p></section> : !company.permissions.includes('REPORT_VIEW') ? <section className="panel"><h2>Dashboard access unavailable</h2><p>You do not have permission to view reports for {company.displayName}.</p></section> : <CompanyDashboard key={company.id} company={company} onExpired={onExpired} />}
    </Shell>} />
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes>
}

function Login({ onLogin, expired }: { onLogin: (s: Session) => void; expired: boolean }) {
  const [email, setEmail] = useState(''), [password, setPassword] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  async function submit(e: FormEvent) {
    e.preventDefault(); setError('')
    if (!email.trim() || !password) { setError('Enter your email and password.'); return }
    setBusy(true)
    try { onLogin(await api.login(email.trim(), password)); setPassword('') }
    catch (e) { setPassword(''); setError(e instanceof ApiError && e.status === 401 ? 'Email or password is incorrect.' : 'Unable to sign in. Check your details and try again.') }
    finally { setBusy(false) }
  }
  return <main className="login-page"><section className="login-card"><div className="brand"><span className="brand-mark" />CPDTS</div><p className="eyebrow">PAYMENT &amp; DEBT TRACKING</p><h1>Welcome back</h1><p>Sign in to access your company dashboard.</p>{expired && <p role="alert">Your session has expired. Please sign in again.</p>}
    <form onSubmit={submit}><label htmlFor="email">Email address</label><input id="email" type="email" autoComplete="username" value={email} onChange={e => setEmail(e.target.value)} required /><label htmlFor="password">Password</label><input id="password" type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required />{error && <p className="error" role="alert">{error}</p>}<button disabled={busy} type="submit">{busy ? 'Signing in…' : 'Sign in'}</button></form><p className="subtle">Access is managed by your administrator.</p></section></main>
}

function Shell({ session, selected, select, onLogout, children }: { session: Session; selected: string; select: (id: string) => void; onLogout: () => Promise<void>; children: React.ReactNode }) {
  const [collapsed, setCollapsed] = useState(false), [error, setError] = useState('')
  return <div className={'app-shell' + (collapsed ? ' collapsed' : '')}><aside className="sidebar"><div className="brand"><span className="brand-mark" />{!collapsed && <span>CPDTS<small>Payment &amp; Debt Tracking</small></span>}</div><button className="nav-toggle" onClick={() => setCollapsed(!collapsed)} aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'} aria-expanded={!collapsed}>☰</button><nav aria-label="Main navigation"><a className="active" href="/" aria-label="Dashboard" aria-current="page"><span aria-hidden="true">⌂</span>{!collapsed && 'Dashboard'}</a></nav><div className="sidebar-footer">{!collapsed && 'Financial operations'}</div></aside><div className="workspace"><header className="topbar"><div className="company-select">{session.companies.length > 1 ? <><label htmlFor="company">Company</label><select id="company" value={selected} onChange={e => select(e.target.value)}><option value="">Select a company</option>{session.companies.map(c => <option key={c.id} value={c.id}>{c.displayName} ({c.code})</option>)}</select></> : session.companies.length === 1 ? <span>{session.companies[0].displayName} ({session.companies[0].code})</span> : <span>No company selected</span>}</div><div className="account"><span>{session.user.fullName}</span><button className="secondary" onClick={() => { setError(''); onLogout().catch(() => setError('Unable to sign out. Please try again.')) }}>Sign out</button></div></header>{error && <p className="error" role="alert">{error}</p>}<main className="content">{children}</main></div></div>
}

function CompanyDashboard({ company, onExpired }: { company: Company; onExpired: () => void }) {
  const [dashboard, setDashboard] = useState<Load<Dashboard>>({ kind: 'loading' })
  const [activity, setActivity] = useState<Load<PaymentPage>>({ kind: 'loading' })
  const [from, setFrom] = useState(''), [to, setTo] = useState(''), [range, setRange] = useState({ from: '', to: '' }), [error, setError] = useState(''), [retry, setRetry] = useState(0)
  useEffect(() => {
    const controller = new AbortController(); let active = true
    setDashboard({ kind: 'loading' })
    api.dashboard(company.id, controller.signal).then(data => { if (active) { setDashboard({ kind: 'ready', data }); setFrom(data.basis.businessDate); setTo(data.basis.businessDate); setRange({ from: data.basis.businessDate, to: data.basis.businessDate }) } }).catch(e => { if (active) { if (e instanceof ApiError && e.status === 401) onExpired(); else setDashboard({ kind: 'error', message: e instanceof ApiError && e.status === 403 ? 'You do not have access to this dashboard.' : 'Could not load the dashboard.' }) } })
    return () => { active = false; controller.abort() }
  }, [company.id, retry])
  useEffect(() => {
    if (!range.from || !range.to) return
    const controller = new AbortController(); let active = true; setActivity({ kind: 'loading' })
    api.payments(company.id, range.from, range.to, controller.signal).then(data => { if (active) setActivity({ kind: 'ready', data }) }).catch(e => { if (active) { if (e instanceof ApiError && e.status === 401) onExpired(); else setActivity({ kind: 'error', message: e instanceof ApiError && e.status === 403 ? 'You do not have access to payment activity.' : 'Could not load payment activity.' }) } })
    return () => { active = false; controller.abort() }
  }, [company.id, range, retry])
  function apply(e: FormEvent) { e.preventDefault(); if (!from || !to || from > to) { setError('Choose a valid date range.'); return } setError(''); setRange({ from, to }) }
  return <><div className="heading"><div><p className="eyebrow">{company.code} / OVERVIEW</p><h1>Dashboard</h1><p>A clear view of your company’s receivables.</p></div>{dashboard.kind === 'ready' && <div className="date-chip">{dashboard.data.basis.businessDate} · Tanzania</div>}</div>
    {dashboard.kind === 'loading' ? <p role="status">Loading current balances…</p> : dashboard.kind === 'error' ? <section className="panel"><p role="alert">{dashboard.message}</p><button onClick={() => setRetry(n => n + 1)}>Try again</button></section> : <><div className="section-heading"><div><h2>Current balances</h2><p>Current position · {company.displayName} · TZS</p></div></div><div className="metrics"><Metric label="Outstanding debt" amount={dashboard.data.outstandingAmount} detail={`${dashboard.data.outstandingInvoiceCount} outstanding invoices`} /><Metric label="Overdue debt" amount={dashboard.data.overdueAmount} detail={`${dashboard.data.overdueInvoiceCount} overdue invoices`} accent /><Metric label="Customer credit" amount={dashboard.data.unallocatedCustomerCredit} detail="Verified and unallocated" /><Metric label="Pending verification" amount={dashboard.data.pendingPaymentAmount} detail={`${dashboard.data.pendingPaymentCount} payments awaiting review`} accent /></div><p className="note">Gross debt and verified credit are shown separately. Overdue means due before the current Tanzania business date.</p>
    <section className="panel activity"><div className="section-heading"><div><h2>Payment activity</h2><p>Payments received in the selected Tanzania business dates. Statuses are current, not historical.</p></div></div><form className="date-form" onSubmit={apply}><label>From<input type="date" value={from} onChange={e => setFrom(e.target.value)} min="2000-01-01" max="2100-12-31" required /></label><label>To<input type="date" value={to} onChange={e => setTo(e.target.value)} min="2000-01-01" max="2100-12-31" required /></label><button type="submit">Apply dates</button></form>{error && <p role="alert" className="error">{error}</p>}
    {activity.kind === 'loading' ? <p role="status">Loading payment activity…</p> : activity.kind === 'error' ? <><p role="alert">{activity.message}</p><button onClick={() => setRetry(n => n + 1)}>Try again</button></> : activity.data.items.length === 0 ? <p className="empty">No payments received in this date range.</p> : <><p className="note">Showing up to 10 of {activity.data.totalElements} payments received in this period.</p><div className="table-wrap"><table><thead><tr><th>Payment</th><th>Customer</th><th>Method</th><th>Status</th><th>Amount</th></tr></thead><tbody>{activity.data.items.map(p => <tr key={p.id}><td>{p.paymentNumber}</td><td>{p.customerName}</td><td>{p.method.replaceAll('_', ' ')}</td><td><span className={'status ' + p.status.toLowerCase()}>{p.status.replaceAll('_', ' ')}</span></td><td>{formatMoney(p.amount)}</td></tr>)}</tbody></table></div></>}</section></>}
  </>
}

function Metric({ label, amount, detail, accent = false }: { label: string; amount: string; detail: string; accent?: boolean }) {
  return <article className={'metric' + (accent ? ' metric-accent' : '')}><p>{label}</p><strong>{formatMoney(amount)}</strong><span>{detail}</span></article>
}
