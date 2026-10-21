// k6 load test: simulates a "server restart" reconnect storm against the
// staging VaultEventSocket WebSocket endpoint.
//
// Context: VaultEventSocket (Android: services/VaultEventSocket.kt, iOS:
// Services/VaultEventSocket.swift) reconnects with exponential backoff +
// full jitter (ReconnectBackoff.delayForAttempt / #253) after a dropped
// connection. Per shared/api-contract.md the canonical schedule is
// exponential backoff with base 1 s and max_delay 30 s (full jitter), so
// the defaults below (BASE_DELAY_MS=500, MAX_DELAY_MS=30000) stay within
// that contract. Jitter is meant to stop many simultaneously-connected clients
// from all retrying in lockstep after an outage. This script measures
// whether that's actually true under load: N virtual clients connect, get
// dropped at once (simulating a server restart), and reconnect using either
// the real jittered schedule or a JITTER_DISABLED ablation, so the two runs
// can be compared directly.
//
// Usage:
//   k6 run -e WS_URL=wss://staging.ethos-protocol.app/ws/vault-events \
//          -e CLIENT_COUNT=500 \
//          -e JITTER_DISABLED=false \
//          load-test/websocket-reconnect-storm.js
//
// Run it twice — once with JITTER_DISABLED=false (current behavior) and
// once with JITTER_DISABLED=true (simulating pre-#253 behavior) — and
// compare the two exported summaries. See RECONNECT_STORM_LOAD_TEST.md for
// the full methodology and where to record results.

import ws from "k6/ws";
import { check, sleep } from "k6";
import { Trend, Counter } from "k6/metrics";

const WS_URL = __ENV.WS_URL || "wss://staging.ethos-protocol.app/ws/vault-events";
const CLIENT_COUNT = parseInt(__ENV.CLIENT_COUNT || "500", 10);
const JITTER_DISABLED = (__ENV.JITTER_DISABLED || "false") === "true";
const MAX_ATTEMPTS = parseInt(__ENV.MAX_ATTEMPTS || "8", 10);
// Matches the contract's base delay (shared/api-contract.md, Backoff/Jitter).
const BASE_DELAY_MS = parseInt(__ENV.BASE_DELAY_MS || "1000", 10);
const MAX_DELAY_MS = parseInt(__ENV.MAX_DELAY_MS || "30000", 10);

export const options = {
  scenarios: {
    reconnect_storm: {
      executor: "shared-iterations",
      vus: CLIENT_COUNT,
      iterations: CLIENT_COUNT,
      maxDuration: "5m",
    },
  },
};

// Mirrors ReconnectBackoff.delayForAttempt in
// android/app/src/main/java/com/ethosprotocol/services/VaultEventSocket.kt:
// exponential backoff capped at MAX_DELAY_MS (30 s per the reconciled
// shared/api-contract.md backoff/jitter section), full jitter uniformly
// sampled from [0, cappedDelay). JITTER_DISABLED reproduces the pre-#253
// behavior (always sleeping the full capped delay) for the ablation
// comparison.
function delayForAttempt(attempt) {
  const capped = Math.min(BASE_DELAY_MS * Math.pow(2, attempt), MAX_DELAY_MS);
  if (JITTER_DISABLED) return capped;
  return Math.random() * capped;
}

const reconnectLatency = new Trend("reconnect_latency_ms", true);
const reconnectAttempts = new Trend("reconnect_attempts_to_success");
const failedReconnects = new Counter("reconnects_failed_after_max_attempts");

export default function () {
  // All VUs start together to simulate every client being dropped by the
  // same server restart at the same instant.
  const disconnectedAt = Date.now();
  let attempt = 0;
  let connected = false;

  while (attempt < MAX_ATTEMPTS && !connected) {
    const delayMs = delayForAttempt(attempt);
    sleep(delayMs / 1000);

    const res = ws.connect(WS_URL, {}, function (socket) {
      socket.on("open", () => {
        connected = true;
        socket.close();
      });
      socket.on("error", () => {});
      socket.setTimeout(() => socket.close(), 5000);
    });

    attempt++;
  }

  // `connected` is set only when the socket opens, so this checks the handshake outcome.
  check(connected, { "reconnected": (ok) => ok === true });

  if (connected) {
    reconnectLatency.add(Date.now() - disconnectedAt);
    reconnectAttempts.add(attempt);
  } else {
    failedReconnects.add(1);
  }
}
