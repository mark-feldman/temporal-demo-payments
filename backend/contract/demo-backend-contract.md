# Demo backend contract

Every backend implementation exposes the same HTTP API under `/demo-api`, so the shared
frontend works against any of them without change. `scripts/contract-test.sh` is this
document made executable — it drives the HTTP surface only, never language internals.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/demo-api/health` | SDK language and version, task queue, namespace, worker status |
| `POST` | `/demo-api/payouts` | Start a payout. Returns `payoutId`, `workflowId`, `runId`, `temporalUrl` |
| `GET` | `/demo-api/payouts/{workflowId}/status` | **Must be backed by a workflow Query**, not a cache |
| `POST` | `/demo-api/payouts/{workflowId}/approval` | `{approved, approver, note}` — a Signal |
| `POST` | `/demo-api/payouts/{workflowId}/bank-status` | `{status, bankReference}` — a Signal |
| `GET` | `/demo-api/scenarios` | Current failure-injection config |
| `POST` | `/demo-api/reset` | Clear scenario config and counters. **Not** workflow history |
| `POST` | `/demo-api/simulation/start` | `{ratePerSecond, durationSeconds, maxInFlight, ...}` |
| `POST` | `/demo-api/simulation/stop` | |
| `GET` | `/demo-api/simulation/status` | Live counters, polled at 1s by the UI |
| `GET` | `/demo-api/actuator/prometheus` | SDK metrics **and** business metrics |

## Behaviour every implementation must match

1. **Business states** — the sixteen in `BusinessStatus`, surfaced through the status Query.
2. **Search attributes** — `payoutId`, `customerId`, `rail`, `region`, `amountMinor`,
   `amountBand`, `currency`, `businessStatus`, `requiresApproval`, `failureCategory`,
   `scenarioName`. Registered before any workflow starts.
3. **Workflow ID** — `payout-{scenarioName}-{payoutId}`, with a reuse policy that rejects
   duplicates for the lifetime of the payout.
4. **Idempotency key** — `{payoutId}-rail-1`, stable across every retry of the rail submission.
5. **Approval thresholds** — USD integer minor units against the FX quote's USD equivalent:
   `< 50_000` auto · `50_000…100_000` L1 · `> 100_000` senior.
6. **An absent or ambiguous bank callback must be resolved by polling, not escalation.** Poll
   the bank for the instruction's real status and continue on whatever it reports. Only when
   polling is exhausted may the payout compensate, and it must be flagged
   `failureCategory = UNKNOWN_BANK_STATUS` — releasing funds on an instruction that may have
   settled is a policy decision, and the record has to say that is what happened.
7. **Compensation must not give up** — no maximum attempts on the release activity, and it
   runs inside a detached cancellation scope so an external cancel cannot kill it.
7b. **Compensations are registered before the activity they undo**, never after, so an activity
   that dies mid-flight still has its compensation on the stack. Release by `payoutId` rather
   than a reservation id, since the id is not known until the call returns.
8. **Business metrics from the API layer**, never from workflow code.

## Failure injection

`POST /demo-api/payouts` accepts `behavior` and `transientFailures`, applied to the activity
named in `step` (default `submitToRail`):

`PASS` · `FAIL_TRANSIENT` · `FAIL_PERMANENT` · `ACCEPTED_NO_CALLBACK` · `REJECTED` ·
`COMPENSATION_FAILS`

`ACCEPTED_NO_CALLBACK` is further shaped by `pollsBeforeResolution` (how many polls report
pending), `resolvedStatus` (`COMPLETED` or `REJECTED`) and `pollingNeverResolves`.

Config is read by **activities only** — reading mutable config from workflow code is a
non-determinism bug — and persists across a backend restart, so the kill-the-app demo does not
silently reset a staged failure.
