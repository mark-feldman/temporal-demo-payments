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
