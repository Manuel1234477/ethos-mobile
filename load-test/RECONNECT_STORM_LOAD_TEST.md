# WebSocket Reconnect-Storm Load Test

**Tracking**: Testing & Quality issue "Load-test WebSocket reconnect storms"
(references #253 — backoff jitter for `VaultEventSocket` reconnects).

## Why this exists

`VaultEventSocket` (Android: `services/VaultEventSocket.kt`, iOS:
`Services/VaultEventSocket.swift`) reconnects after a dropped connection
using `ReconnectBackoff.delayForAttempt`: exponential backoff (base 1 s,
max 30 s), capped, with full jitter — this shipped as #253. These are the
canonical values, matching `shared/api-contract.md` §Connection lifecycle /
§Backoff/Jitter and the defaults in `load-test/websocket-reconnect-storm.js`.
A server restart is the scenario that
backoff+jitter exists for: every currently-connected client is disconnected
at the same instant, and without jitter they would all retry on the same
clock tick, turning a routine restart into a self-inflicted thundering-herd
outage right as the server comes back up. This has not been load-tested
against a staging environment, so the jitter's effectiveness at realistic
client counts is currently unverified — it looks correct by inspection but
that's not the same as measuring it under load.

## What this adds

- `load-test/websocket-reconnect-storm.js` — a [k6](https://k6.io) script
  that simulates `CLIENT_COUNT` clients all disconnecting simultaneously
  (a "server restart") and reconnecting via the same
  exponential-backoff-with-full-jitter formula used in
  `ReconnectBackoff.delayForAttempt`. It supports a `JITTER_DISABLED` flag
  that reproduces the pre-#253 always-sleep-the-full-delay behavior, so the
  jittered and unjittered cases can be run back-to-back against the same
  staging endpoint and compared directly.

## Running it

Requires [k6](https://k6.io/docs/get-started/installation/) and a reachable
staging WebSocket endpoint (see `E2E_API_BASE_URL` used elsewhere in this
repo's `.github/workflows/e2e-cross-platform.yml` for the equivalent staging
convention).

```bash
# With jitter (current, post-#253, behavior)
k6 run \
  -e WS_URL=wss://staging.ethos-protocol.app/ws/vault-events \
  -e CLIENT_COUNT=500 \
  -e JITTER_DISABLED=false \
  load-test/websocket-reconnect-storm.js

# Without jitter (ablation — reproduces pre-#253 behavior)
k6 run \
  -e WS_URL=wss://staging.ethos-protocol.app/ws/vault-events \
  -e CLIENT_COUNT=500 \
  -e JITTER_DISABLED=true \
  load-test/websocket-reconnect-storm.js
```

Recommended client counts to test at: 50, 500, and 5000 — to see whether the
jittered/unjittered gap only matters past some fleet-size threshold, or is
significant even at moderate scale.

## What to measure

The script exports three custom metrics via k6's summary output:

- `reconnect_latency_ms` — wall-clock time from simulated disconnect to
  successful reconnect, per client. Compare the p50/p95/p99 between the
  jittered and unjittered runs.
- `reconnect_attempts_to_success` — how many attempts each client needed.
  A spike here under `JITTER_DISABLED=true` indicates synchronized retries
  are colliding and getting rejected/timing out.
- `reconnects_failed_after_max_attempts` — clients that exhausted
  `MAX_ATTEMPTS` without reconnecting. Any nonzero count here at production
  fleet size is a signal the server couldn't absorb the herd.

Also watch the staging server's own metrics during the run (CPU, connection
accept rate, error rate) — the client-side latency numbers alone don't show
server-side cost, and the entire point of jitter is to reduce that cost.

## Recording findings

Once both runs have been executed against staging, record results here:

| Date | Client count | Jitter | p50 reconnect (ms) | p95 reconnect (ms) | Failed reconnects | Server CPU peak | Notes |
|------|--------------|--------|---------------------|---------------------|--------------------|------------------|-------|
| _(no run recorded yet)_ | | | | | | | |

If the unjittered run shows materially worse server load or a higher
failure rate at realistic fleet sizes, that's the evidence needed to
prioritize any further backoff tuning (e.g. raising `MAX_DELAY_MS`, adding
per-client startup jitter independent of the reconnect jitter, or
rate-limiting reconnect acceptance server-side during a restart window).
