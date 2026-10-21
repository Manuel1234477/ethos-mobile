#!/usr/bin/env bash
# Smoke-tests core flows against a staging environment. Intended to run in CI
# before a release build is cut, catching a backend/client contract mismatch
# (see shared/api-contract.md) early.
#
# Covered flows (in order):
#   1. Auth challenge endpoint  — POST /auth/challenge returns a challenge + credential IDs
#   2. Authenticated vault list — GET /vaults returns the expected response shape
#   3. Vault list pagination    — GET /vaults?limit=1 and cursor round-trip
#   4. Check-in                 — POST /vaults/{id}/checkin extends TTL with anti-replay headers
#   5. Notifications register   — POST /notifications/register accepts a push token
#
# Required env vars:
#   STAGING_API_BASE_URL    e.g. https://staging-api.ethos-protocol.app/v1
#   STAGING_SMOKE_TOKEN     long-lived JWT for a dedicated smoke-test account
#   STAGING_SMOKE_VAULT_ID  vault ID owned by that account, safe to check-in repeatedly
#
# Optional env vars:
#   STAGING_SMOKE_PUSH_TOKEN  dummy APNs/FCM token for notification registration smoke test;
#                             defaults to a clearly-fake sentinel value if not set — the
#                             server must accept registration regardless of token validity
#                             (actual push delivery is out-of-scope for smoke tests).
set -euo pipefail

# Private temp file for response bodies: parallel runs cannot overwrite each other,
# and a stale file from an earlier run cannot be printed as this run's failure body.
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

: "${STAGING_API_BASE_URL:?STAGING_API_BASE_URL is required}"
: "${STAGING_SMOKE_TOKEN:?STAGING_SMOKE_TOKEN is required}"
: "${STAGING_SMOKE_VAULT_ID:?STAGING_SMOKE_VAULT_ID is required}"

PUSH_TOKEN="${STAGING_SMOKE_PUSH_TOKEN:-smoke-test-dummy-push-token-$(openssl rand -hex 8)}"

PASS=0
FAIL=0

fail() {
  echo "SMOKE TEST FAILED: $1" >&2
  FAIL=$((FAIL + 1))
}

pass() {
  echo "OK ($1)"
  PASS=$((PASS + 1))
}

# request METHOD PATH [extra curl args...]
# Writes the response body to $BODY_FILE, returns the HTTP status code.
request() {
  local method="$1" path="$2"; shift 2
  curl -sS -o "$BODY_FILE" -w "%{http_code}" \
    -X "$method" "${STAGING_API_BASE_URL}${path}" \
    -H "Authorization: Bearer ${STAGING_SMOKE_TOKEN}" \
    "$@"
}

# request_unauthenticated METHOD PATH [extra curl args...]
# Same as request but omits the Authorization header — used for /auth/challenge
# which does not require authentication.
request_unauthenticated() {
  local method="$1" path="$2"; shift 2
  curl -sS -o "$BODY_FILE" -w "%{http_code}" \
    -X "$method" "${STAGING_API_BASE_URL}${path}" \
    "$@"
}

# request_with_headers METHOD PATH OUTPUT_HEADERS_FILE [extra curl args...]
# Writes response headers to a file for inspection (e.g. pagination cursors).
request_with_headers() {
  local method="$1" path="$2" headers_file="$3"; shift 3
  curl -sS -o "$BODY_FILE" -D "$headers_file" -w "%{http_code}" \
    -X "$method" "${STAGING_API_BASE_URL}${path}" \
    -H "Authorization: Bearer ${STAGING_SMOKE_TOKEN}" \
    "$@"
}

echo "Staging smoke tests against ${STAGING_API_BASE_URL}"
echo "======================================================"

# ── 1/5: Auth challenge ──────────────────────────────────────────────────────
# POST /auth/challenge must return 200 with a JSON body containing at least
# a "challenge" field (base64url) and an "existing_credential_ids" array.
# This exercises the auth endpoint without needing a real passkey ceremony —
# the challenge is what clients request before biometric prompt, so it failing
# would break every login attempt on staging.
echo "== 1/5: auth challenge (POST /auth/challenge) =="
status=$(request_unauthenticated POST /auth/challenge \
  -H "Content-Type: application/json" \
  -d '{}')
if [ "$status" != "200" ]; then
  fail "POST /auth/challenge returned $status: $(cat "$BODY_FILE")"
else
  if ! grep -q '"challenge"' "$BODY_FILE"; then
    fail "POST /auth/challenge response missing 'challenge' field: $(cat "$BODY_FILE")"
  elif ! grep -q '"existing_credential_ids"' "$BODY_FILE"; then
    fail "POST /auth/challenge response missing 'existing_credential_ids' field: $(cat "$BODY_FILE")"
  else
    pass "$status"
  fi
fi

# ── 2/5: Authenticated vault list ────────────────────────────────────────────
# GET /vaults must return 200 with a JSON body containing a "vaults" array.
echo "== 2/5: authenticated vault list (GET /vaults) =="
status=$(request GET /vaults)
if [ "$status" != "200" ]; then
  fail "GET /vaults returned $status: $(cat "$BODY_FILE")"
elif ! grep -q '"vaults"' "$BODY_FILE"; then
  fail "GET /vaults response missing 'vaults' field: $(cat "$BODY_FILE")"
else
  pass "$status"
fi

# ── 3/5: Vault list pagination ───────────────────────────────────────────────
# GET /vaults?limit=1 must return 200. When there are more vaults than the limit
# the response must include an X-Next-Cursor header (per api-contract.md §Pagination).
# We don't assert has_more=true here because the smoke account may have exactly one
# vault — we only verify the cursor mechanism works when the response says there is one.
echo "== 3/5: vault list pagination (GET /vaults?limit=1) =="
status=$(request_with_headers GET "/vaults?limit=1" /tmp/smoke_headers.txt)
if [ "$status" != "200" ]; then
  fail "GET /vaults?limit=1 returned $status: $(cat "$BODY_FILE")"
else
  pass "$status"
  # If the server signals more pages exist, verify the cursor round-trip works.
  NEXT_CURSOR=$(grep -i '^x-next-cursor:' /tmp/smoke_headers.txt | awk '{print $2}' | tr -d '\r\n' || true)
  if [ -n "$NEXT_CURSOR" ]; then
    echo "   ↳ X-Next-Cursor present — verifying cursor round-trip =="
    status2=$(request GET "/vaults?limit=1&cursor=${NEXT_CURSOR}")
    if [ "$status2" != "200" ]; then
      fail "GET /vaults?limit=1&cursor=... returned $status2: $(cat "$BODY_FILE")"
    elif ! grep -q '"vaults"' "$BODY_FILE"; then
      fail "Paginated GET /vaults response missing 'vaults' field: $(cat "$BODY_FILE")"
    else
      echo "   ↳ cursor round-trip OK ($status2)"
    fi
  else
    echo "   ↳ X-Next-Cursor absent — only one page of vaults (OK)"
  fi
fi

# ── 4/5: Check-in ────────────────────────────────────────────────────────────
# POST /vaults/{id}/checkin with required anti-replay headers (X-Nonce, X-Timestamp
# per api-contract.md §Anti-Replay Protection) must return 200.
echo "== 4/5: check-in (POST /vaults/${STAGING_SMOKE_VAULT_ID}/checkin) =="
# A check-in extends the vault's TTL, which other staging tests may depend on. It runs
# only when STAGING_SMOKE_ALLOW_CHECKIN=1 is set for a vault created for this purpose.
if [ "${STAGING_SMOKE_ALLOW_CHECKIN:-0}" != "1" ]; then
  echo "SKIP: set STAGING_SMOKE_ALLOW_CHECKIN=1 to run check-in against a dedicated vault"
else
  nonce=$(openssl rand -hex 32)
  timestamp=$(date +%s)
  status=$(request POST "/vaults/${STAGING_SMOKE_VAULT_ID}/checkin" \
    -H "Content-Type: application/json" \
    -H "X-Nonce: ${nonce}" \
    -H "X-Timestamp: ${timestamp}" \
    -d '{}')
  if [ "$status" != "200" ]; then
    fail "POST /vaults/${STAGING_SMOKE_VAULT_ID}/checkin returned $status: $(cat $BODY_FILE)"
  else
    pass "$status"
  fi
fi

# ── 5/5: Push notification registration ──────────────────────────────────────
# POST /notifications/register with a push token must return 200 or 204.
# The server is not expected to validate that the token is a real APNs/FCM token —
# it only needs to accept and store it. This catches API contract drift in the
# notifications endpoint (e.g. a changed required field) before it hits production.
echo "== 5/5: push notification registration (POST /notifications/register) =="
for platform in ${SMOKE_PLATFORMS:-ios android}; do
  nonce=$(openssl rand -hex 32)
  timestamp=$(date +%s)
  status=$(request POST /notifications/register \
    -H "Content-Type: application/json" \
    -H "X-Nonce: ${nonce}" \
    -H "X-Timestamp: ${timestamp}" \
    -d "{\"token\": \"${PUSH_TOKEN}\", \"platform\": \"${platform}\"}")
  if [ "$status" != "200" ] && [ "$status" != "204" ]; then
    fail "POST /notifications/register ($platform) returned $status: $(cat $BODY_FILE)"
  else
    pass "$status ($platform)"
  fi

  # Remove the dummy registration so staging does not accumulate device records.
  nonce=$(openssl rand -hex 32)
  timestamp=$(date +%s)
  status=$(request DELETE /notifications/register \
    -H "Content-Type: application/json" \
    -H "X-Nonce: ${nonce}" \
    -H "X-Timestamp: ${timestamp}" \
    -d "{\"token\": \"${PUSH_TOKEN}\"}")
  if [ "$status" != "200" ] && [ "$status" != "204" ]; then
    fail "DELETE /notifications/register ($platform) returned $status: $(cat $BODY_FILE)"
  else
    pass "$status (unregister $platform)"
  fi
done

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "======================================================"
echo "Results: ${PASS} passed, ${FAIL} failed"
if [ "$FAIL" -gt 0 ]; then
  echo "STAGING SMOKE TEST SUITE FAILED" >&2
  exit 1
fi
echo "All smoke tests passed against ${STAGING_API_BASE_URL}"
