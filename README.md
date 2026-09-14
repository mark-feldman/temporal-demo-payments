# Payout orchestration — a Temporal demo

A local, runnable payout orchestration demo built on Temporal. One payout workflow, five
scenarios, a load simulator, and live worker + server metrics — all behind a single
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
make stop            stop everything, waiting for the ports to be released
make reset           stop and clear ALL state, including workflow history
make test            contract tests, over HTTP against a running stack
make test-unit       JUnit unit suite (no stack needed)
make test-integration Spring + in-memory Temporal test server (no stack needed)
make build           compile and run both JUnit suites
make css             recompile the stylesheet
make workers N=3     scale the worker fleet to N JVMs (the cap is 10)
make worker-reload   rebuild the jar and restart the workers on it
make workers-kill    SIGKILL the most recent worker
make workers-down    stop every worker; the API survives and can start replacements
make workers-status  what is polling the task queue
```

### Scaling workers

`N` is the size of the whole fleet, not a number of extras, and it can be changed while load
is in flight. The cap is 10, which is where the Prometheus target list ends.

```
$ make workers N=3
  3 of 10 worker(s) polling the payouts task queue
    worker 1  pid 80008  :8091  alive
    worker 2  pid 81910  :8092  alive
    worker 3  pid 81916  :8093  alive
```

Three workers means three JVMs, not three workers in one. A `WorkerFactory` keys its workers
by task queue and returns the existing one for a repeated call, so extra instances cannot come
from a single process. The API supervises them and launches the same jar again on a different
`--server.port` — worker N on `8090 + N`, since the API itself holds `:8081` — and each process
gets its own identity (`pid@host`) automatically.

They appear as separate rows under **Workers** on the task-queue page. `make workers-kill`
SIGKILLs the most recent one, `make workers-down` stops them all, and the API survives either,
which is what lets it start replacements. Temporal keeps poller entries for a short while after
a worker stops, so the count settles rather than dropping instantly.

Prometheus scrapes `:8091`–`:8100` alongside the API on `:8081`, so each worker shows up on the
dashboard individually: **Live worker instances** tracks the fleet and **Worker task slots
available** gains a series per instance. Targets above the current fleet size read **DOWN** —
that means "not running", not "broken".

One query detail worth knowing if you add panels: the Temporal server exposes
`temporal_worker_task_slots_available` for its own internal system workers, so SDK panels
filter on `job="payout-demo-worker"`. It is the only metric name that overlaps.

| | |
|---|---|
| Demo | http://localhost:8080 |
| Temporal Web | http://localhost:8233 (also embedded in the right pane) |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

**Prerequisites:** Docker, JDK 21, and Temporal CLI 1.8.3 — the CLI version determines the
bundled Server and Web UI. Any JDK 21 will do: the build discovers one rather than requiring a
particular install location, and the CLI is taken from `PATH`. `make preflight` prints what it
resolved, and `make start` refuses to run on the wrong CLI or without a JDK 21.

---

## What it shows

Five scenarios on the **Demo** tab, each with its own controls and explanatory notes:

1. **Successful payout** — the baseline. Validate → reserve → FX → select rail → submit →
   confirm → complete. Rail, region and currency are fixed server-side rather than exposed as
   controls.
2. **Transient failure + retry** — the rail times out twice, then accepts, on the same
   idempotency key. There is no retry loop in the code; the policy is declarative.
3. **Permanent failure + compensation** — a non-retryable rejection triggers saga
   compensation, in reverse registration order. The compensation activities are scheduled with
   no attempt cap and a flat 5s backoff, bounded only by a one-hour schedule-to-close: giving
   up early would strand the money.
4. **Human approval + timeout** — the workflow blocks durably on a signal. Kill the whole
   application and it is still waiting when the process returns.
5. **Unknown bank status** — the bank accepted an instruction and never confirmed. Rather than
   escalate, the workflow **polls the bank** until it gets a real answer and continues on its
   own. The retry policy on the poll activity *is* the polling loop. Only when polling is
   exhausted does it compensate — reversing the instruction at the bank, releasing the
   reservation and notifying the customer — flagged `UNKNOWN_BANK_STATUS`.

   Note that activity retries do **not** write per-attempt history events. The polls are
   visible live through the pending-activity record, and afterwards as the final attempt
   number plus the gap between `ActivityTaskScheduled` and `ActivityTaskStarted`.

The **Metrics** tab runs the load simulator and embeds the Grafana dashboard, so traffic can
be started and observed without leaving the tab.

Starting a payout points the embedded pane at that run's **Timeline** tab
(`/namespaces/{ns}/workflows/{id}/{runId}/timeline`), which needs the run id — the start
response carries one, and the `?payout=` deep link carries it too so a refresh lands on the
same tab. What that pane shows is annotated rather than anonymous: the execution carries a
static summary and details, each activity a summary, and both deadline timers one too, so an
approval wait reads "Waiting for SENIOR approval" instead of a bare timer. A **Temporal UI** row
above the scenario controls re-points the pane at Workflows, Workers or Schedules.

Its **Workers** button goes to the task-queue page (`/namespaces/{ns}/task-queues/{queue}`)
rather than Temporal's own Workers view, deliberately: the task-queue page is scoped to
`payouts`, so the count is just your workers. Temporal's top-level Workers view also lists the
server's internal `temporal-sys-per-ns-tq` worker, so killing one of yours takes the count from
3 to 2 rather than 2 to 1 — avoidable confusion during the worker-recovery demo.

The top-level **Workers** view exists from Web UI 2.50. **Deployments** is a different thing
again — Worker Deployments, i.e. versioning.

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

**How the bank confirms is chosen by the amount, and that branch is versioned.** Below
`SettlementThresholds.SYNC_BELOW_MINOR` ($100) the rail answers inside the `settleWithBank`
activity: no signal, no 45s timer, `SETTLING_WITH_BANK` in the timeline. At or above it the
workflow waits durably on the callback. Scenarios 1-3 start below the threshold so they run
start to finish unattended; 4 and 5 start above it, because the durable wait is the thing they
are demonstrating. The activity answers with the scenario's `resolvedStatus` rather than rolling
for an outcome, so a demo button settles the same way every run, and the load simulator posts
its own 80/20 split to keep a mix.

Replacing one branch with another is the standard way to break replay, so
`Workflow.getVersion("inline-settlement-for-low-value", ...)` gates it and executions started
before the change find no marker and keep the wait they committed to. The version is consulted
*after* the amount test, so no marker is written on the majority path. The Java SDK does not
record `TemporalChangeVersion` itself, so the workflow upserts it by hand, which turns retiring
the patch into a query:

```bash
temporal workflow list --query 'TemporalChangeVersion IS NULL AND ExecutionStatus="Running"'
```

`src/test/resources/histories/` holds real executions recorded before a change, and
`PayoutWorkflowReplayTest` replays every one of them against the current workflow code — the
guard for a change that would break runs already in flight. Two are committed:
`pre-inline-settlement-low-value.json`, which fails without this gate, and
`approval-with-timers.json`, an approval run covering both timer outcomes, one cancelled by the
signal and one fired. The directory's own README says which shapes are worth keeping.

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

**The dev server's throughput ceiling is SQLite's journal mode.** `temporal server start-dev`
leaves the database in rollback-journal mode, where readers and writers are mutually exclusive
— the signature is a read slower than a write. `scripts/start-temporal.sh` starts it with
`--sqlite-pragma journal_mode=WAL` and raises `history.shardIOConcurrency` from its default of
1, which is what lets the load simulator sustain roughly 45–50 payouts/s rather than building a
backlog. Above that the constraint is the server retiring history tasks, not the size of the
worker fleet: scaling workers up while executor slots sit idle does not help.

**Business metrics come from the API layer**, never from workflow code, where a counter would
double-count on every replay. `StatusCollector` derives them from a Temporal visibility query
over the `businessStatus` search attribute — the same query you would type into the Temporal
Web filter box. Only the states in `FINDABLE_STATUSES` publish that attribute, so those are the
ones a query can filter on; dropping the rest changes the command stream, so it sits behind a
second version marker, `visibility-milestones-only`.

**Workflow IDs use `REJECT_DUPLICATE`.** The default `AllowDuplicate` permits a second payout
once the first has *closed*, which is the wrong answer for a system that must not pay twice.

---

## Layout

```
backend/kotlin/          Spring Boot app: API + worker + static assets, one jar
  src/main/kotlin/com/example/payouts/
    app/                 payload converter, Temporal config
    model/{domain,workflow,activity}/   one request/response pair per boundary
    workflow/            PayoutWorkflow + impl
    activities/          six interfaces, trivial mocks (delay, log, canned result)
    api/                 REST controllers, metrics, status collector
    scenario/            ScenarioStore — failure-injection config, shared with the workers
    worker/              WorkerSupervisor — forks and supervises the worker JVMs
    simulation/          load generator
  src/test/              JUnit 5 unit suite — TestWorkflowEnvironment, time skipping
    resources/histories/ exported histories, replayed against the current code
  src/integrationTest/   Spring Boot against the SDK's in-memory Temporal test server
  src/css/app.css        Tailwind source  (compiled output is committed)
backend/contract/        the demo backend contract, for future SDK implementations
config/                  Caddyfile, Prometheus, Grafana provisioning + dashboard
scripts/                 start / stop / reset / seed / contract-test / scale-workers,
                         plus java-home + temporal-bin (tool resolution)
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

Alongside it, `make build` runs two JUnit 5 suites that need nothing running: a unit suite on
`TestWorkflowEnvironment` with time skipping (which is how the 30s approval and 45s bank
deadlines get tested at all), and an integration suite that boots the whole Spring context
against the SDK's in-memory test server. Those are Java-SDK-specific and deliberately not part
of the cross-SDK contract.

One test in the unit suite looks out of place and is not: every backend test supplies its own
request, so nothing covered the request the *UI* posts. `DemoScenarioDefaultsTest` reads the
shipped `app.js`, reconstructs every body the five buttons can send, and checks each one
against the production constants — amounts against `ApprovalThresholds`, every JSON key against
`StartPayoutBody`, and the injected failure counts against the smallest retry cap the workflow
can draw. Each of those fails silently in the browser: unknown JSON properties are dropped
rather than rejected, so a stale key returns 200 and the scenario runs on defaults.

`PayoutWorkflowReplayTest` is the other guard worth knowing about. It generates histories
in-process, and also replays any `temporal workflow show --output json` export left in
`src/test/resources/histories/`, which is how a change that would break in-flight executions
gets caught before it ships.

| Backend | SDK | Status |
|---|---|---|
| Kotlin | Temporal Java SDK | Reference implementation |
| Java, Go, TypeScript, Python, .NET | — | Not started |
