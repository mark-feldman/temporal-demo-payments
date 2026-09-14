import { h, render } from './vendor/preact.module.js'
import { useState, useEffect, useRef, useCallback } from './vendor/hooks.module.js'
import htm from './vendor/htm.module.js'

const html = htm.bind(h)
const api = (path, opts) => fetch(`/demo-api${path}`, {
  headers: { 'Content-Type': 'application/json' }, ...opts,
}).then(r => r.ok ? r.json() : Promise.reject(new Error(r.status)))

// ---------------------------------------------------------------------------
// Theme. Temporal Web 2.45.3 has no prefers-color-scheme rule -- it switches on
// [data-theme] only. Because Caddy puts both panes on one origin, we can read the
// iframe's own root attribute and follow it exactly.
// ---------------------------------------------------------------------------
function useTemporalTheme(iframeRef) {
  useEffect(() => {
    let observer
    let seeded = false
    const attach = () => {
      const root = iframeRef.current?.contentDocument?.documentElement
      if (!root) return false
      // On a fresh machine Temporal Web has no saved preference and renders light,
      // against our dark-first chrome. Push dark in ONCE so the two panes match out
      // of the box; after that we only follow, so flipping it live still works.
      if (!seeded) {
        seeded = true
        if (!root.getAttribute('data-theme')) root.setAttribute('data-theme', 'dark')
      }
      const apply = () =>
        document.documentElement.setAttribute('data-theme', root.getAttribute('data-theme') || 'dark')
      apply()
      observer?.disconnect()
      observer = new MutationObserver(apply)
      observer.observe(root, { attributes: true, attributeFilter: ['data-theme'] })
      return true
    }
    const f = iframeRef.current
    attach()
    f?.addEventListener('load', attach)
    return () => { f?.removeEventListener('load', attach); observer?.disconnect() }
  }, [])
}

const BADGE = {
  COMPLETED: 'badge-success', COMPENSATED: 'badge-warning',
  FAILED: 'badge-danger', CANCELLED: 'badge-danger',
  AWAITING_APPROVAL: 'badge-warning', AWAITING_BANK_CONFIRMATION: 'badge-warning',
  COMPENSATING: 'badge-warning', UNKNOWN_BANK_STATUS: 'badge-unknown',
}
const badgeFor = s => BADGE[s] ?? 'badge-running'
const isTerminal = s => ['COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN_BANK_STATUS'].includes(s)

const Eyebrow = ({ children }) => html`<div class="eyebrow mb-2">${children}</div>`

// Workers run as separate JVMs supervised by the API, so killing one is a real SIGKILL
// and the API survives to start a replacement. In-flight workflow tasks time out, and the
// new worker rebuilds state by replaying history.
function WorkerControl() {
  const [fleet, setFleet] = useState(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    const poll = () => api('/workers').then(setFleet).catch(() => setFleet(null))
    poll(); const t = setInterval(poll, 2000); return () => clearInterval(t)
  }, [])
  const act = async (path) => {
    setBusy(true)
    try { setFleet(await api(path, { method: 'POST' })) } finally { setBusy(false) }
  }
  const running = fleet?.running ?? 0
  return html`
    <div class="panel p-4 space-y-2">
      <${Eyebrow}>Workers<//>
      <div class="flex items-center gap-2 flex-wrap">
        <span class="badge ${running > 0 ? 'badge-success' : 'badge-danger'}">
          <span class="dot ${running > 0 ? '' : 'dot-pulse'}"></span>${running} running
        </span>
        ${running > 0
          ? html`<button class="btn btn-danger" disabled=${busy}
                         onClick=${() => act('/workers/kill')}>Kill a worker</button>`
          : html`<span class="mono text-xs" style="color:var(--color-state-danger)">
                   nothing is polling the task queue
                 </span>`}
        <button class="btn btn-secondary" disabled=${busy}
                onClick=${() => act(`/workers/scale?count=${running + 1}`)}>Start a worker</button>
      </div>
      ${fleet?.workers?.length > 0 && html`
        <div class="mono text-xs" style="color:var(--color-ink-muted)">
          ${fleet.workers.map(w => html`<div>worker ${w.id} · pid ${w.pid} · :${w.port}</div>`)}
        </div>`}
      ${running === 0 && html`
        <div class="text-xs" style="color:var(--color-state-warning)">
          Workflows are not progressing. Start a worker and watch them pick up where they left off.
        </div>`}
    </div>`
}

// Temporal Web has no top-level Workers view: workers are listed on the task-queue page,
// and "Deployments" is Worker Deployments (versioning), which is a different thing.
const VIEWS = [
  { label: 'Workflows', path: '/namespaces/default/workflows',
    title: 'All workflow executions' },
  { label: 'Workers', path: '/namespaces/default/task-queues/payouts',
    title: 'Workers polling the payouts task queue, and which handlers each one registers' },
  { label: 'Schedules', path: '/namespaces/default/schedules', title: 'Schedules' },
]

const Field = ({ label, children }) => html`
  <label class="block">
    <span class="eyebrow block mb-1">${label}</span>
    ${children}
  </label>`

const Notes = ({ items }) => html`
  <div class="notes p-4 mt-auto">
    <div class="eyebrow mb-2">Notes</div>
    <ul class="text-sm space-y-1.5" style="color:var(--color-ink-secondary)">
      ${items.map(t => html`<li>· ${t}</li>`)}
    </ul>
  </div>`

// ---------------------------------------------------------------------------
// Scenarios
// ---------------------------------------------------------------------------
const SCENARIOS = [
  {
    id: 'successful', name: 'Successful payout',
    blurb: 'Validate, reserve, FX, select rail, submit, confirm, complete. The baseline every other scenario diverges from.',
    defaults: { scenario: 'successful', amountMinor: 25000, behavior: 'PASS' },
    notes: [
      'The happy path: the whole business process expressed as one workflow function.',
      'Every step appears as an event in the history on the right — no log correlation needed.',
      'Steps carry business-language summaries, so the timeline reads as a payment.',
    ],
  },
  {
    id: 'transient', name: 'Transient failure + retry',
    blurb: 'The rail times out twice, then accepts. Same idempotency key on every attempt.',
    defaults: { scenario: 'transient', amountMinor: 25000, behavior: 'FAIL_TRANSIENT', transientFailures: 2 },
    notes: [
      'There is no retry loop in the code. The policy is declarative, next to the activity.',
      'The final attempt count and the same idempotency key are both visible on the activity.',
      'Failed attempts are not separate history events — the backoff shows as the gap between ActivityTaskScheduled and ActivityTaskStarted.',
    ],
  },
  {
    id: 'permanent', name: 'Permanent failure + compensation',
    blurb: 'Non-retryable rail rejection triggers saga compensation: reserved funds released, payout marked failed.',
    defaults: { scenario: 'permanent', amountMinor: 25000, behavior: 'FAIL_PERMANENT' },
    notes: [
      'A non-retryable failure differs from retry exhaustion: Temporal stops immediately.',
      'Compensation is ordinary code in the same workflow, not a separate cleanup job.',
      'The compensation activity has no attempt cap by design — giving up would strand funds.',
    ],
  },
  {
    id: 'approval', name: 'Human approval + timeout',
    blurb: 'Amount above the threshold blocks on a signal. Approve it, reject it, or let the timer fire.',
    defaults: { scenario: 'approval', amountMinor: 250000, behavior: 'PASS' },
    notes: [
      'The workflow is waiting durably: no polling loop and no thread held open.',
      'Kill the application entirely and it is still waiting when the process returns.',
      'The deadline is a workflow timer, so the timeout path is a branch in code, not a cron job.',
      'After approval it still waits on the bank callback — the controls below follow whatever the workflow is blocked on.',
    ],
  },
  {
    id: 'unknown', name: 'Unknown bank status',
    blurb: 'The bank accepted the instruction and never confirmed. The workflow polls the bank until it gets a real answer, rather than escalating.',
    defaults: { scenario: 'unknown', amountMinor: 25000, behavior: 'ACCEPTED_NO_CALLBACK' },
    pollOutcomes: [
      { id: 'completed', label: 'Bank confirms completed', cfg: { resolvedStatus: 'COMPLETED', pollsBeforeResolution: 3 } },
      { id: 'rejected',  label: 'Bank confirms rejected',  cfg: { resolvedStatus: 'REJECTED', pollsBeforeResolution: 2 } },
      { id: 'never',     label: 'Bank never answers',      cfg: { pollingNeverResolves: true } },
    ],
    notes: [
      'The instruction was accepted but never confirmed, so the callback never arrives.',
      'Rather than escalate, the workflow polls the bank — the retry policy on the poll activity is the polling loop.',
      'Whatever the bank eventually reports drives the outcome: completed, or rejected and compensated.',
      'Only if polling is exhausted does it compensate on an unresolved status — flagged UNKNOWN_BANK_STATUS, because releasing funds on an instruction that may have settled is a policy call, not a safe default.',
    ],
  },
]

// ---------------------------------------------------------------------------
function StatusCard({ status, error }) {
  if (error) return html`
    <div class="panel p-4" style="border-color:var(--color-state-danger)">
      <${Eyebrow}>Live status<//>
      <div class="badge badge-danger"><span class="dot"></span>Backend not responding</div>
      <p class="text-sm mt-3" style="color:var(--color-ink-secondary)">
        The demo backend is down. The workflow on the right is unaffected.
      </p>
    </div>`
  if (!status) return html`
    <div class="panel p-4" style="min-height:150px">
      <${Eyebrow}>Live status<//>
      <div class="badge badge-idle">No payout running</div>
    </div>`
  return html`
    <div class="panel p-4" style="min-height:150px">
      <${Eyebrow}>Live status<//>
      <div class="badge ${badgeFor(status.status)}">
        <span class="dot ${isTerminal(status.status) ? '' : 'dot-pulse'}"></span>${status.status}
      </div>
      <div class="mono text-xs mt-3" style="color:var(--color-ink-secondary)">${status.currentStep}</div>
      <div class="grid grid-cols-2 gap-2 mt-3 text-xs mono" style="color:var(--color-ink-muted)">
        <div>payout <span style="color:var(--color-ink-primary)">${status.payoutId}</span></div>
        <div>attempt <span style="color:var(--color-ink-primary)">${status.railAttempts || '—'}</span></div>
        ${status.approvalTier !== 'NONE' && html`<div>tier <span style="color:var(--color-ink-primary)">${status.approvalTier}</span></div>`}
        ${status.bankReference && html`<div>ref <span style="color:var(--color-ink-primary)">${status.bankReference}</span></div>`}
      </div>
    </div>`
}

function ScenarioPanel({ scenario, onStarted, current, status, statusError }) {
  const [amount, setAmount] = useState(scenario.defaults.amountMinor)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState('')
  const [pollOutcome, setPollOutcome] = useState('completed')

  const start = async () => {
    setBusy(true); setNote('')
    try {
      const r = await api('/payouts', {
        method: 'POST',
        body: JSON.stringify({
          ...scenario.defaults,
          ...(scenario.pollOutcomes?.find(o => o.id === pollOutcome)?.cfg ?? {}),
          amountMinor: Number(amount),
        }),
      })
      onStarted(r)
    } catch (e) { setNote(`Could not start: ${e.message}`) } finally { setBusy(false) }
  }

  // What the workflow is currently blocked on, straight from the Query.
  const waitingFor =
    status?.status === 'AWAITING_APPROVAL' ? 'approval'
    : status?.status === 'AWAITING_BANK_CONFIRMATION' ? 'bank'
    : 'none'

  const signal = async (path, body, label) => {
    try {
      await api(`/payouts/${current.workflowId}${path}`, { method: 'POST', body: JSON.stringify(body) })
      setNote(`${label} sent — signals are fire-and-forget, watch the status above.`)
    } catch (e) { setNote(`Failed: ${e.message}`) }
  }

  return html`
    <div class="flex flex-col gap-4 h-full">
      <div>
        <h2 class="text-xl mb-1">${scenario.name}</h2>
        <p class="text-sm" style="color:var(--color-ink-secondary)">${scenario.blurb}</p>
      </div>

      <div class="panel p-4 space-y-3">
        <${Eyebrow}>Scenario controls<//>
        <!-- Rail, region and currency are supplied by the server defaults. They were controls
             once, but none of them changed a branch, a timeout or an outcome, and two inert
             dropdowns are worse than none in a demo that argues everything on screen is real. -->
        <${Field} label="Amount (minor units, USD)">
          <input class="input" type="number" value=${amount} onInput=${e => setAmount(e.target.value)} />
        <//>
        ${scenario.pollOutcomes && html`
          <${Field} label="When the workflow polls, the bank eventually...">
            <select class="input" value=${pollOutcome} onChange=${e => setPollOutcome(e.target.value)}>
              ${scenario.pollOutcomes.map(o => html`<option value=${o.id}>${o.label}</option>`)}
            </select>
          <//>`}
        <button class="btn btn-primary w-full" disabled=${busy} onClick=${start}>
          ${busy ? 'Starting…' : 'Start payout'}
        </button>
      </div>

      ${current && html`
        <div class="panel p-4 space-y-2">
          <${Eyebrow}>Interact with this execution<//>
          <!-- Controls follow the live status rather than the scenario, so a signal button
               is offered exactly when the workflow is actually blocked waiting for it. The
               approval path still needs a bank callback afterwards. -->
          <div class="flex flex-wrap gap-2">
            ${waitingFor === 'approval' && html`
              <button class="btn btn-secondary" onClick=${() => signal('/approval', { approved: true, approver: 'ops-1' }, 'Approval')}>Approve</button>
              <button class="btn btn-secondary" onClick=${() => signal('/approval', { approved: false, approver: 'ops-1' }, 'Rejection')}>Reject</button>`}
            ${waitingFor === 'bank' && html`
              <button class="btn btn-secondary" onClick=${() => signal('/bank-status', { status: 'COMPLETED', bankReference: '' }, 'Bank COMPLETED')}>Bank: completed</button>
              <button class="btn btn-secondary" onClick=${() => signal('/bank-status', { status: 'REJECTED', bankReference: '' }, 'Bank REJECTED')}>Bank: rejected</button>
              <button class="btn btn-secondary" onClick=${() => signal('/bank-status', { status: 'UNKNOWN', bankReference: '' }, 'Bank UNKNOWN')}>Bank: unknown</button>`}
            ${waitingFor === 'none' && html`
              <span class="mono text-xs" style="color:var(--color-ink-muted)">
                ${status ? `${status.status} — nothing to signal` : 'starting…'}
              </span>`}
          </div>
          <div class="mono text-xs" style="color:var(--color-ink-muted)">${current.workflowId}</div>
          ${note && html`<div class="text-xs" style="color:var(--color-state-warning)">${note}</div>`}
        </div>`}

      <${StatusCard} status=${status} error=${statusError} />
      <${WorkerControl} />
      <${Notes} items=${scenario.notes} />
    </div>`
}

// ---------------------------------------------------------------------------
function SimulationStrip() {
  const [status, setStatus] = useState(null)
  const [advanced, setAdvanced] = useState(false)
  const [preset, setPreset] = useState('safe')
  const PRESETS = {
    safe: { ratePerSecond: 5, durationSeconds: 300, maxInFlight: 250 },
    load: { ratePerSecond: 25, durationSeconds: 600, maxInFlight: 250 },
  }
  useEffect(() => {
    const t = setInterval(() => api('/simulation/status').then(setStatus).catch(() => {}), 1000)
    return () => clearInterval(t)
  }, [])
  const running = status?.state === 'running'
  const start = () => api('/simulation/start', { method: 'POST', body: JSON.stringify(PRESETS[preset]) }).then(setStatus)
  const stop = () => api('/simulation/stop', { method: 'POST' }).then(setStatus)

  const N = ({ label, value }) => html`
    <span class="mono text-xs">
      <span style="color:var(--color-ink-muted)">${label}</span>
      <span style="color:var(--color-ink-primary)"> ${value ?? 0}</span>
    </span>`

  return html`
    <div class="panel p-4 mb-4 sticky top-0 z-10" style="background:var(--color-surface-panel)">
      <div class="flex items-center justify-between mb-3">
        <div class="flex items-center gap-3">
          <span class="eyebrow" style="margin:0">Simulation</span>
          <span class="badge ${running ? 'badge-running' : 'badge-idle'}">
            <span class="dot ${running ? 'dot-pulse' : ''}"></span>${status?.state ?? 'idle'}
          </span>
        </div>
        <button class="btn btn-ghost text-xs" onClick=${() => setAdvanced(!advanced)}>
          ${advanced ? '▾ Advanced' : '▸ Advanced'}
        </button>
      </div>

      <div class="flex items-center gap-3 flex-wrap mb-3">
        ${Object.entries(PRESETS).map(([k, v]) => html`
          <label class="flex items-center gap-2 mono text-xs cursor-pointer">
            <input type="radio" name="preset" checked=${preset === k} onChange=${() => setPreset(k)} />
            <span style="color:${preset === k ? 'var(--color-ink-primary)' : 'var(--color-ink-secondary)'}">
              ${k === 'safe' ? 'Safe' : 'Load'} ${v.ratePerSecond}/s · ${v.durationSeconds / 60} min
            </span>
          </label>`)}
        ${running
          ? html`<button class="btn btn-danger ml-auto" onClick=${stop}>■ STOP</button>`
          : html`<button class="btn btn-primary ml-auto" onClick=${start}>▸ Start simulation</button>`}
      </div>

      <div class="flex flex-wrap gap-x-5 gap-y-1 items-center">
        <${N} label="started" value=${status?.started} />
        <span class="mono text-xs">
          <span style="color:var(--color-ink-muted)">in-flight</span>
          <span style="color:var(--color-ink-primary)"> ${status?.inFlight ?? 0}/${status?.maxInFlight ?? 250}</span>
        </span>
        <${N} label="completed" value=${status?.completed} />
        <${N} label="failed" value=${status?.failed} />
        <${N} label="compensated" value=${status?.compensated} />
        <${N} label="awaiting approval" value=${status?.awaitingApproval} />
        <${N} label="unknown" value=${status?.unknown} />
        <${N} label="retries" value=${status?.retries} />
        <span class="mono text-xs ml-auto" style="color:var(--color-ink-muted)">⟳ live, 1s</span>
      </div>

      ${advanced && html`
        <div class="mt-3 pt-3 text-xs mono" style="border-top:1px solid var(--color-line-subtle);color:var(--color-ink-secondary)">
          Scenario mix 35% success · 25% transient-retry · 15% permanent+compensation ·
          15% approval · 10% unknown. Rails and regions even. Customers 1–500.
          <div class="mt-1" style="color:var(--color-ink-muted)">
            The strip reads the API directly every second; the graphs below are Prometheus
            sampling every 10s. Watch them converge when the load stops.
          </div>
        </div>`}
    </div>`
}

function MetricsTab() {
  return html`
    <div class="flex flex-col h-full p-4 overflow-auto">
      <${SimulationStrip} />
      <div class="panel flex-1 overflow-hidden flex flex-col">
        <div class="px-4 py-2 flex items-center justify-between"
             style="border-bottom:1px solid var(--color-line-subtle)">
          <span class="eyebrow" style="margin:0">Worker and server metrics</span>
          <span class="mono text-xs" style="color:var(--color-ink-muted)">Prometheus · 10s</span>
        </div>
        <iframe class="flex-1 w-full border-0"
                src="http://localhost:3000/d/payout-demo/payout-demo?kiosk&theme=dark&refresh=5s"></iframe>
      </div>
    </div>`
}

// ---------------------------------------------------------------------------
function App() {
  // Hash deep-link: #metrics opens the metrics tab directly.
  const [tab, setTab] = useState(location.hash === '#metrics' ? 'metrics' : 'demo')
  const [scenarioId, setScenarioId] = useState(() => {
    const id = new URLSearchParams(location.search).get('payout') ?? ''
    const name = id.split('-')[1]
    return SCENARIOS.some(s => s.id === name) ? name : 'successful'
  })
  // ?payout=<workflowId> attaches to an existing run, so a page refresh mid-demo does
  // not lose the workflow you were watching.
  const [current, setCurrent] = useState(() => {
    const id = new URLSearchParams(location.search).get('payout')
    return id ? { workflowId: id, temporalUrl: `/namespaces/default/workflows/${id}` } : null
  })
  const [status, setStatus] = useState(null)
  const [statusError, setStatusError] = useState(false)
  const [health, setHealth] = useState(null)
  const iframeRef = useRef(null)
  useTemporalTheme(iframeRef)

  useEffect(() => {
    const poll = () => api('/health').then(h => { setHealth(h); setStatusError(false) })
                        .catch(() => { setHealth(null); setStatusError(true) })
    poll(); const t = setInterval(poll, 3000); return () => clearInterval(t)
  }, [])

  useEffect(() => {
    if (!current) return
    const poll = () => api(`/payouts/${current.workflowId}/status`)
      .then(s => { setStatus(s); setStatusError(false) })
      .catch(() => setStatusError(true))
    poll(); const t = setInterval(poll, 1000); return () => clearInterval(t)
  }, [current])

  const showInPane = useCallback(path => {
    if (iframeRef.current) iframeRef.current.src = path
  }, [])

  const onStarted = useCallback(r => {
    setCurrent(r); setStatus(null)
    history.replaceState(null, '', `?payout=${encodeURIComponent(r.workflowId)}${location.hash}`)
    if (iframeRef.current) iframeRef.current.src = r.temporalUrl
  }, [])

  const scenario = SCENARIOS.find(s => s.id === scenarioId)

  return html`
    <div class="flex flex-col h-full">
      <header class="flex items-center gap-6 px-5 py-3"
              style="border-bottom:1px solid var(--color-line-subtle)">
        <h1 class="text-lg" style="letter-spacing:-0.02em">Payout orchestration</h1>
        <nav class="flex gap-1">
          ${[['demo', 'Demo'], ['metrics', 'Metrics']].map(([k, label]) => html`
            <button class="btn ${tab === k ? 'btn-secondary' : 'btn-ghost'} uppercase"
                    style="font-size:.75rem;letter-spacing:.08em;padding:.45rem 1rem"
                    onClick=${() => { setTab(k); location.hash = k }}>${label}</button>`)}
        </nav>
        <div class="ml-auto mono text-xs flex items-center gap-2"
             style="color:var(--color-ink-secondary)">
          ${health
            ? html`<span class="dot" style="color:var(--color-state-success)"></span>
                   Kotlin · ${health.sdk} · ${health.taskQueue} · ${health.namespace} · worker ${health.worker}`
            : html`<span class="dot" style="color:var(--color-state-danger)"></span>
                   <span style="color:var(--color-state-danger)">backend unreachable</span>`}
        </div>
      </header>

      <main class="flex-1 min-h-0">
        <!-- Both panes stay mounted for the life of the page: remounting would reload
             Temporal Web and cold-start Grafana every time you switch. -->
        <div class="h-full ${tab === 'demo' ? 'flex' : 'hidden'}">
          <aside class="flex flex-col gap-3 p-4 overflow-auto"
                 style="width:32%;min-width:360px;border-right:1px solid var(--color-line-subtle)">
            <div class="flex flex-wrap gap-1">
              ${SCENARIOS.map((s, i) => html`
                <button class="btn ${scenarioId === s.id ? 'btn-secondary' : 'btn-ghost'}"
                        style="font-size:.75rem;padding:.35rem .7rem"
                        onClick=${() => setScenarioId(s.id)}>${i + 1}</button>`)}
            </div>
            <div class="flex flex-wrap items-center gap-1"
                 style="border-bottom:1px solid var(--color-line-subtle);padding-bottom:.6rem">
              <span class="eyebrow" style="margin:0 .4rem 0 0">Temporal UI</span>
              ${VIEWS.map(v => html`
                <button class="btn btn-ghost" title=${v.title}
                        style="font-size:.7rem;padding:.3rem .6rem"
                        onClick=${() => showInPane(v.path)}>${v.label}</button>`)}
            </div>
            <${ScenarioPanel} scenario=${scenario} onStarted=${onStarted}
                              current=${current} status=${status} statusError=${statusError} />
          </aside>
          <section class="flex-1 min-w-0">
            <iframe ref=${iframeRef} class="w-full h-full border-0"
                    src="/namespaces/default/workflows"></iframe>
          </section>
        </div>
        <div class="h-full ${tab === 'metrics' ? 'block' : 'hidden'}"><${MetricsTab} /></div>
      </main>
    </div>`
}

render(html`<${App} />`, document.getElementById('root'))
