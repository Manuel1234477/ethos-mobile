#!/usr/bin/env bash
# verify_apple_app_site_association.sh
#
# Verifies that the iOS AASA (Apple App Site Association) file hosted at
# https://ethos-protocol.app/.well-known/apple-app-site-association is:
#   1. Reachable (HTTP 200).
#   2. Valid JSON.
#   3. Contains "applinks" and "webcredentials" entries with the correct App ID.
#
# Usage:
#   ./verify_apple_app_site_association.sh
#
# Environment variables (override defaults for testing against staging):
#   AASA_URL           – full URL to apple-app-site-association
#                        (default: https://ethos-protocol.app/.well-known/apple-app-site-association)
#   TEAM_IDENTIFIER    – Apple Developer Team ID (e.g., "ABCDEFGHIJ")
#   BUNDLE_ID          – iOS app bundle ID
#                        (default: com.ethosprotocol)
#
# Exit codes:
#   0 – all checks passed
#   1 – one or more checks failed (details printed to stderr)
#
# Cross-reference: #63 (this check), #94 (Android assetlinks check)

set -euo pipefail

# Private work directory: parallel runs cannot read each other's files.
WORK_DIR="$(mktemp -d)"
export WORK_DIR
trap 'rm -rf "$WORK_DIR"' EXIT

AASA_URL="${AASA_URL:-https://ethos-protocol.app/.well-known/apple-app-site-association}"
BUNDLE_ID="${BUNDLE_ID:-com.ethosprotocol}"
TEAM_IDENTIFIER="${TEAM_IDENTIFIER:-}"

# If TEAM_IDENTIFIER is not set, derive it from the entitlements file
if [[ -z "$TEAM_IDENTIFIER" ]]; then
    if [[ -f "ios/EthosProtocol/EthosProtocol/EthosProtocol.entitlements" ]]; then
        # Extract the TeamIdentifierPrefix from the entitlements (it's in the keychain-access-groups)
        # Format: $(AppIdentifierPrefix)com.ethosprotocol.shared
        # The AppIdentifierPrefix is the Team ID + a dot
        TEAM_IDENTIFIER=$(grep -oP '\$\(AppIdentifierPrefix\)' ios/EthosProtocol/EthosProtocol/EthosProtocol.entitlements | sed 's/.$//' || true)
        if [[ -z "$TEAM_IDENTIFIER" ]]; then
            # Try to extract from CFBundleIdentifier if available
            echo "::warning::TEAM_IDENTIFIER not set and could not be derived from entitlements; skipping App ID verification"
            TEAM_IDENTIFIER=""
        fi
    fi
fi

FAIL=0

# ── 1. Fetch ──────────────────────────────────────────────────────────────────
echo "Fetching: $AASA_URL"
CURL_EXIT=0
HTTP_CODE=$(curl --silent --output "$WORK_DIR/aasa.json" \
                 --write-out "%{http_code}" \
                 --max-time 15 \
                 --fail-with-body \
                 "$AASA_URL" 2>"$WORK_DIR/aasa_err.txt") || CURL_EXIT=$?

# curl never reached the server, so there is no HTTP status to report
if [[ "$CURL_EXIT" -ne 0 && "$HTTP_CODE" == "000" ]]; then
    case "$CURL_EXIT" in
        6)  REASON="could not resolve host — the domain has no DNS record (is it registered/live yet?)" ;;
        7)  REASON="could not connect to the host" ;;
        28) REASON="request timed out after 15s" ;;
        35|60) REASON="TLS handshake/certificate failure" ;;
        *)  REASON="curl failed with exit code $CURL_EXIT" ;;
    esac
    echo "ERROR: $AASA_URL is unreachable: $REASON" >&2
    cat "$WORK_DIR/aasa_err.txt" >&2 2>/dev/null || true
    exit 1
fi

if [[ "$HTTP_CODE" != "200" ]]; then
    echo "ERROR: Expected HTTP 200, got $HTTP_CODE" >&2
    cat "$WORK_DIR/aasa_err.txt" >&2 2>/dev/null || true
    exit 1
fi
echo "OK: HTTP $HTTP_CODE"

# ── 2. Valid JSON ─────────────────────────────────────────────────────────────
if ! python3 -c "import json,sys; json.load(sys.stdin)" < "$WORK_DIR/aasa.json"; then
    echo "ERROR: apple-app-site-association is not valid JSON" >&2
    FAIL=1
else
    echo "OK: valid JSON"
fi

# ── 3. applinks section present ───────────────────────────────────────────────
if python3 - "$BUNDLE_ID" "$TEAM_IDENTIFIER" <<'PY'
import json, sys
data = json.load(open(__import__("os").environ["WORK_DIR"] + "/aasa.json"))
bundle_id = sys.argv[1]
team_id = sys.argv[2]

applinks = data.get("applinks", {})
if not applinks:
    sys.exit(1)

details = applinks.get("details", [])
for detail in details:
    # App ID format: TEAM_ID.BUNDLE_ID (e.g., ABCDEFGHIJ.com.ethosprotocol)
    app_id = detail.get("appID")
    if app_id:
        if (team_id and f"{team_id}.{bundle_id}" == app_id) or (not team_id and bundle_id in app_id):
            sys.exit(0)

sys.exit(1)
PY
then
    if [[ -n "$TEAM_IDENTIFIER" ]]; then
        echo "ERROR: applinks section missing or does not contain expected App ID ($TEAM_IDENTIFIER.$BUNDLE_ID)" >&2
    else
        echo "ERROR: applinks section missing or is empty" >&2
    fi
    FAIL=1
else
    if [[ -n "$TEAM_IDENTIFIER" ]]; then
        echo "OK: applinks section contains expected App ID ($TEAM_IDENTIFIER.$BUNDLE_ID)"
    else
        echo "OK: applinks section is present"
    fi
fi

# ── 4. webcredentials section present ─────────────────────────────────────────
if python3 - "$BUNDLE_ID" "$TEAM_IDENTIFIER" <<'PY'
import json, sys
data = json.load(open(__import__("os").environ["WORK_DIR"] + "/aasa.json"))
bundle_id = sys.argv[1]
team_id = sys.argv[2]

webcreds = data.get("webcredentials", {})
if not webcreds:
    sys.exit(1)

details = webcreds.get("details", [])
for detail in details:
    # App ID format: TEAM_ID.BUNDLE_ID
    app_id = detail.get("appID")
    if app_id:
        if (team_id and f"{team_id}.{bundle_id}" == app_id) or (not team_id and bundle_id in app_id):
            sys.exit(0)

sys.exit(1)
PY
then
    if [[ -n "$TEAM_IDENTIFIER" ]]; then
        echo "OK: webcredentials section contains expected App ID ($TEAM_IDENTIFIER.$BUNDLE_ID)"
    else
        echo "OK: webcredentials section is present"
    fi
else
    if [[ -n "$TEAM_IDENTIFIER" ]]; then
        echo "ERROR: webcredentials section missing or does not contain expected App ID ($TEAM_IDENTIFIER.$BUNDLE_ID)" >&2
    else
        echo "ERROR: webcredentials section missing or is empty" >&2
    fi
    FAIL=1
fi

# ── Summary ───────────────────────────────────────────────────────────────────
if [[ "$FAIL" -ne 0 ]]; then
    echo ""
    echo "FAILED: apple-app-site-association verification failed — Universal Links or Passkeys may break."
    exit 1
fi

echo ""
echo "PASSED: apple-app-site-association looks correct for Universal Links and Passkeys."
