# Recorded event histories

`PayoutWorkflowReplayTest` replays every `*.json` file in this directory against the **current**
`PayoutWorkflowImpl`. If the directory is empty that test is skipped, not failed.

The other replay cases in that class generate their history in-process, so they can only ever
prove the workflow is self-consistent *right now*. Files dropped here are the stronger guard:
they were produced by an earlier version of the workflow, so replaying them catches a change
that would break executions already in flight — a reordered activity, a new call inserted
before an existing one, a timer that moved.

Export one from the running stack:

```bash
temporal workflow show \
  --workflow-id payout-successful-po-000123 \
  --output json > backend/kotlin/src/test/resources/histories/happy-path.json
```

Worth keeping one per distinct shape rather than many of the same: a completed run, a
compensated run, an approval run (timers and signals), and a polling run.

A failure here is a **non-determinism error**, and it is the good kind of failure — in
production the same mismatch does not fail the workflow, it blocks it, and the workflow sits
there until someone redeploys compatible code.

## What is committed here, and why

`approval-with-timers.json` — a real 64-event approval run of a **$2,500** payout, exported
before the deadline timers carried summaries. It is the "timers and signals" shape the list
above asks for, and it covers both timer outcomes in one history: the approval timer is
**cancelled** because the `approve` signal arrived first, and the bank timer **fires** because
no callback came, so `PollBankStatus` takes over. There is also a `getVersion` marker and five
visibility upserts in it.

Unlike the file below, it does not fail against the unpatched code, because there is no patch
it belongs to: the summaries on those timers are user metadata, which rides alongside the
command rather than forming part of it. It was recorded by the code that had no summaries and
replays clean against the code that has them, which is what says the change was safe for the
executions that were in flight when it shipped. It is kept as the forward guard for this
shape: a change that reorders the two waits, moves a timer, or folds one back into
`await(timeout, ...)` would break replay here and nowhere else in the suite.

`pre-inline-settlement-low-value.json` — a real 53-event run of a **$10–$99** payout, exported
from the running stack *before* the bank confirmation was split by amount. It took the old
path: submit to the rail, park on `AWAITING_BANK_CONFIRMATION`, consume the callback, complete.
There is no `SettleWithBank` anywhere in it.

It is the guard for the `inline-settlement-for-low-value` patch, and it earns its place because
it fails without it. Replayed against the branch *unpatched*:

```
NonDeterministicException: [TMPRL1100] Failure handling event 40 of type
'EVENT_TYPE_ACTIVITY_TASK_SCHEDULED' during replay. Event 40 of type
EVENT_TYPE_ACTIVITY_TASK_SCHEDULED does not match command type
COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES
```

With the patch, `Workflow.getVersion` finds no marker in this history, returns `DEFAULT_VERSION`,
and the old branch runs — so it replays clean. Delete this file and the patch can be removed by
accident without anything noticing. Keep it until the patch itself is removed, which is the
point at which no execution recorded before the change can still exist.
