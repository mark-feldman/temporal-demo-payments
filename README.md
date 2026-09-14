# Payout orchestration — a Temporal demo

A local, runnable payout orchestration demo built on Temporal. One payout workflow, five
failure scenarios, a load simulator, and live worker + server metrics — all behind a single
URL, so the workflow controls and the Temporal Web UI sit side by side in one window.

**Backend:** Kotlin · Spring Boot · Temporal Java SDK 1.38.0
**Frontend:** Preact + htm, no build step for the JS · Tailwind v4 for the CSS

---

## Run it

```bash
make preflight     # verify images, tools, fonts and the JDK are all present
make start         # Temporal + Caddy/Prometheus/Grafana + backend
make seed          # six workflows in six distinct states
```

Then open **http://localhost:8080**.

```
make stop            stop everything
make reset           stop and clear ALL state, including workflow history
make test            contract tests + the client-agnostic guard
make css             recompile the stylesheet
make workers N=2     run N extra worker processes
make workers-down    stop the extras, leave the primary running
make workers-status  who is polling the task queue
```

### Scaling workers

`make workers N=2` brings the fleet to three and can be run while load is in flight:

```
Workers polling the 'payouts' task queue:
  UNVERSIONED  activity  TasksDispatchRate  55.1     # 29.8 with one worker
  Pollers:
    workflow  86860@host   now
    workflow  81911@host   now
    workflow  86856@host   now
```

Three workers means three JVMs, not three workers in one. A `WorkerFactory` keys its
workers by task queue and returns the existing one for a repeated call, so extra instances
cannot come from a single process. The script launches the same jar again with a different
`--server.port`, since the primary already holds `:8081`; nothing else differs, and each
process gets its own identity (`pid@host`) automatically.

They appear as separate rows under **Workers** on the task-queue page, and
`make workers-down` stops them without touching the primary. Temporal keeps poller entries
for a short while after a worker stops, so the count settles rather than dropping instantly.

Prometheus scrapes `:8091` and `:8092` alongside the primary, so the extra workers show up
on the dashboard individually: **Live worker instances** goes to 3, and **Worker task slots
available** gains a series per instance. Those two targets read **DOWN** whenever the fleet
is scaled to one — that means "not running", not "broken".

One query detail worth knowing if you add panels: the Temporal server exposes
`temporal_worker_task_slots_available` for its own internal system workers, so SDK panels
filter on `job="payout-demo-worker"`. It is the only metric name that overlaps.

| | |
|---|---|
| Demo | http://localhost:8080 |
| Temporal Web | http://localhost:8233 (also embedded in the right pane) |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

**Prerequisites:** Docker, the Temporal CLI, and JDK 21. The repo pins the JDK twice — a
`.mise.toml` for the shell and a Gradle toolchain for the build — because a machine default of
a newer JDK will otherwise be picked up silently, and Kotlin + Spring Boot on a brand-new JDK
is not a safe assumption.

---

## What it shows

Five scenarios on the **Demo** tab, each with its own controls and explanatory notes:

1. **Successful payout** — the baseline. Validate → reserve → FX → select rail → submit →
   confirm → complete.
2. **Transient failure + retry** — the rail times out twice, then accepts, on the same
   idempotency key. There is no retry loop in the code; the policy is declarative.
3. **Permanent failure + compensation** — a non-retryable rejection triggers saga
   compensation. The compensation activity retries indefinitely by design: giving up would
   strand the money.
4. **Human approval + timeout** — the workflow blocks durably on a signal. Kill the whole
   application and it is still waiting when the process returns.
5. **Unknown bank status** — the bank accepted an instruction and never confirmed. Rather than
   escalate, the workflow **polls the bank** until it gets a real answer and continues on its
   own. The retry policy on the poll activity *is* the polling loop, so every attempt is an
   event you can point at. Only when polling is exhausted does it compensate, flagged
   `UNKNOWN_BANK_STATUS`.

The **Metrics** tab runs the load simulator and embeds the Grafana dashboard, so traffic can
be started and observed without leaving the tab.

A **Right pane** row above the scenario controls re-points the embedded Temporal Web at
Workflows, Workers or Schedules. Worth knowing: **Temporal Web has no top-level Workers
view.** Workers are listed on the task-queue page (`/namespaces/{ns}/task-queues/{queue}`),
showing each poller's ID, build ID, last-accessed time and which handlers it registers.
The **Deployments** item in Temporal's own nav is Worker Deployments — worker versioning —
which is a different concept.

---

## How it fits together

```
Browser ──► Caddy :8080 ──┬─► /, /assets/*, /demo-api/*  ──► Spring Boot :8081 (host)
                          └─► everything else            ──► Temporal Web :8233 (host)

Prometheus :9090 ──┬─► temporal server :8000/metrics
                   └─► backend :8081/demo-api/actuator/prometheus  (SDK + business metrics)
Grafana :3000  ──► Prometheus
```

Only Caddy, Prometheus and Grafana are containers. Temporal and the backend run on the host,
which keeps the edit-run loop fast and makes the "kill the app and watch the workflow survive"
case a plain `kill -9`.

**Why the demo owns only three route prefixes.** Temporal Web is a SvelteKit SPA that loads
its assets from absolute paths (`/_app/immutable/...`). Mounting it under a stripped prefix
breaks it. Giving Temporal the catch-all and namespacing the demo instead means those paths
resolve unchanged — at the cost of one rule: every route this app adds must live under `/`,
`/assets/*` or `/demo-api/*`. Spring's own `/error` and `/actuator` are moved inside
`/demo-api` for exactly this reason.

---

## Things worth knowing if you extend this

**`spring.temporal.connection.target` must not be `local`.** The starter special-cases that
value and calls `WorkflowServiceStubs.newLocalServiceStubs()`, which silently discards the
metrics scope *and* the stub customizers. Spelling out `127.0.0.1:7233` takes the normal path.
This is the difference between having SDK metrics and not having them.

**Serialization is kotlinx, not Jackson.** `KotlinxJsonPayloadConverter` declares the same
`json/plain` encoding as Jackson, so it replaces Jackson in the converter chain while payloads
stay readable in Temporal Web. `EncodingKeys` is package-private in the SDK, so the wire
constants are inlined.

**Every workflow and activity boundary takes exactly one `@Serializable` data class.** This is
load-bearing, not stylistic: `PayloadConverter.toData()` only ever sees the runtime object, so
sealed types, bare generics and top-level nulls lose their type information. Model variants as
a flat data class with an enum discriminator.

**`runBlocking` belongs at the activity boundary and nowhere else.** Activity signatures stay
non-suspend because the Java SDK invokes them reflectively and cannot accept a `Continuation`.
Inside the body it is ordinary coroutine code — `delay()`, never `Thread.sleep`. None of this
touches determinism: activities are not re-executed on replay.

**Business metrics come from the API layer**, never from workflow code, where a counter would
double-count on every replay. `StatusCollector` derives them from a Temporal visibility query
over the `businessStatus` search attribute — the same query you would type into the Temporal
Web filter box.

**Workflow IDs use `REJECT_DUPLICATE`.** The default `AllowDuplicate` permits a second payout
once the first has *closed*, which is the wrong answer for a system that must not pay twice.

---

## Layout

```
backend/kotlin/          Spring Boot app: API + worker + static assets, one process
  src/main/kotlin/com/example/payouts/
    app/                 payload converter, retry profiles, Temporal config
    model/{domain,workflow,activity}/   one request/response pair per boundary
    workflow/            PayoutWorkflow + impl
    activities/          five interfaces, trivial mocks (delay, log, canned result)
    api/                 REST controllers, metrics, status collector
    simulation/          load generator
  src/css/app.css        Tailwind source  (compiled output is committed)
backend/contract/        the demo backend contract, for future SDK implementations
config/                  Caddyfile, Prometheus, Grafana provisioning + dashboard
scripts/                 start / stop / reset / seed / contract-test / client-agnostic guard
tools/tailwindcss        vendored standalone binary, no npm anywhere
```

The dashboards under `config/grafana/dashboards/_upstream-*.json` are Temporal's
**community-driven** example dashboards, kept for reference. `payout-demo.json` is the one
provisioned and embedded.

---

## Status

Single backend implementation today. `backend/contract/` describes what a second SDK
implementation would have to satisfy, and `scripts/contract-test.sh` is that contract made
executable — it drives the HTTP API only, so any future backend can be checked against it.

| Backend | SDK | Status |
|---|---|---|
| Kotlin | Temporal Java SDK | Reference implementation |
| Java, Go, TypeScript, Python, .NET | — | Not started |
