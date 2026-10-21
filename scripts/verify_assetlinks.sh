#!/usr/bin/env bash
# verify_assetlinks.sh
#
# Verifies that the Android Digital Asset Links file hosted at
# https://ethos-protocol.app/.well-known/assetlinks.json is:
#   1. Reachable (HTTP 200).
#   2. Valid JSON.
#   3. Contains a "delegate_permission/common.handle_all_urls" relation entry for this app's
#      package name and at least one of the expected SHA-256 certificate fingerprints.
#
# Usage:
#   ./verify_assetlinks.sh
#
# Environment variables (override defaults for testing against staging):
#   ASSETLINKS_URL      – full URL to assetlinks.json
#                         (default: https://ethos-protocol.app/.well-known/assetlinks.json)
#   APP_PACKAGE         – Android package name to look for
#                         (default: com.ethosprotocol)
#   CERT_FINGERPRINTS   – colon-separated list of expected SHA-256 certificate fingerprints
#                         (upper-case, colon-delimited bytes, e.g. "AB:CD:...:EF:01:CD:...:23")
#                         At least one must appear in the file.
#                         Set to empty string to skip fingerprint verification.
#   CERT_PINS_FILE      – path to the single-source certificate pin file
#                         (default: <repo>/shared/cert-pins.json).  Its
#                         "release_signing_sha256" value is always checked against the
#                         hosted assetlinks.json, so local runs and CI agree with the
#                         same source of truth used by SignatureVerifier.EXPECTED_CERT_SHA256.
#
# Exit codes:
#   0 – all checks passed
#   1 – one or more checks failed (details printed to stderr)
#
# Cross-reference: #63 (iOS AASA check), #94 (this check), #595 (single-source fingerprint).
# If #63's fetch helper is ever extracted into a shared script, consider reusing it here
# rather than duplicating the curl invocation.

set -euo pipefail

# Private work directory: parallel runs cannot read each other's files.
WORK_DIR="$(mktemp -d)"
export WORK_DIR
trap 'rm -rf "$WORK_DIR"' EXIT

ASSETLINKS_URL="${ASSETLINKS_URL:-https://ethos-protocol.app/.well-known/assetlinks.json}"
APP_PACKAGE="${APP_PACKAGE:-com.ethosprotocol}"
# Default fingerprints list is intentionally empty — CI must inject the real value via the
# CERT_FINGERPRINTS env var (or the repo secret ANDROID_CERT_SHA256).  Leaving it empty here
# disables fingerprint verification so the script is usable without secrets during development,
# while still catching "file gone / wrong package" regressions.
CERT_FINGERPRINTS="${CERT_FINGERPRINTS:-}"
# Single source of truth for the release signing certificate fingerprint.  The same value
# backs assetlinks.json, SignatureVerifier.EXPECTED_CERT_SHA256 and the Play App Signing
# comparison in CI, so a re-signed release cannot silently break App Links.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERT_PINS_FILE="${CERT_PINS_FILE:-$SCRIPT_DIR/../shared/cert-pins.json}"

FAIL=0

# ── 1. Fetch ──────────────────────────────────────────────────────────────────
echo "Fetching: $ASSETLINKS_URL"
CURL_EXIT=0
HTTP_CODE=$(curl --silent --output "$WORK_DIR/assetlinks.json" \
                 --write-out "%{http_code}" \
                 --max-time 15 \
                 --fail-with-body \
                 "$ASSETLINKS_URL" 2>"$WORK_DIR/assetlinks_err.txt") || CURL_EXIT=$?

# curl never reached the server, so there is no HTTP status to report. Naming the
# transport failure separately keeps "the domain isn't live / DNS is broken" from
# being reported as "the file is misconfigured" — they need different fixes.
if [[ "$CURL_EXIT" -ne 0 && "$HTTP_CODE" == "000" ]]; then
    case "$CURL_EXIT" in
        6)  REASON="could not resolve host — the domain has no DNS record (is it registered/live yet?)" ;;
        7)  REASON="could not connect to the host" ;;
        28) REASON="request timed out after 15s" ;;
        35|60) REASON="TLS handshake/certificate failure" ;;
        *)  REASON="curl failed with exit code $CURL_EXIT" ;;
    esac
    echo "ERROR: $ASSETLINKS_URL is unreachable: $REASON" >&2
    cat "$WORK_DIR/assetlinks_err.txt" >&2 2>/dev/null || true
    exit 1
fi

if [[ "$HTTP_CODE" != "200" ]]; then
    echo "ERROR: Expected HTTP 200, got $HTTP_CODE" >&2
    cat "$WORK_DIR/assetlinks_err.txt" >&2 2>/dev/null || true
    exit 1
fi
echo "OK: HTTP $HTTP_CODE"

# ── 2. Valid JSON ─────────────────────────────────────────────────────────────
if ! python3 -c "import json,sys; json.load(sys.stdin)" < "$WORK_DIR/assetlinks.json"; then
    echo "ERROR: assetlinks.json is not valid JSON" >&2
    FAIL=1
else
    echo "OK: valid JSON"
fi

# ── 3. Package name present ───────────────────────────────────────────────────
if python3 - "$APP_PACKAGE" <<'PY'
import json, sys
data = json.load(open(__import__("os").environ["WORK_DIR"] + "/assetlinks.json"))
pkg = sys.argv[1]
found = any(
    entry.get("target", {}).get("package_name") == pkg
    for entry in data
)
sys.exit(0 if found else 1)
PY
then
    echo "OK: package '$APP_PACKAGE' found"
else
    echo "ERROR: package '$APP_PACKAGE' not found in assetlinks.json" >&2
    FAIL=1
fi

# ── 4. Relation present ───────────────────────────────────────────────────────
if python3 - "$APP_PACKAGE" <<'PY'
import json, sys
data = json.load(open(__import__("os").environ["WORK_DIR"] + "/assetlinks.json"))
pkg = sys.argv[1]
rel = "delegate_permission/common.handle_all_urls"
found = any(
    rel in entry.get("relation", [])
    and entry.get("target", {}).get("package_name") == pkg
    for entry in data
)
sys.exit(0 if found else 1)
PY
then
    echo "OK: 'delegate_permission/common.handle_all_urls' relation present for '$APP_PACKAGE'"
else
    echo "ERROR: required relation missing for '$APP_PACKAGE'" >&2
    FAIL=1
fi

# ── 5. Namespace is 'android_app' ─────────────────────────────────────────────
if python3 - "$APP_PACKAGE" <<'PY'
import json, sys
data = json.load(open(__import__("os").environ["WORK_DIR"] + "/assetlinks.json"))
pkg = sys.argv[1]
found = any(
    entry.get("target", {}).get("namespace") == "android_app"
    and entry.get("target", {}).get("package_name") == pkg
    for entry in data
)
sys.exit(0 if found else 1)
PY
then
    echo "OK: target namespace is 'android_app'"
else
    echo "ERROR: target namespace is not 'android_app' for '$APP_PACKAGE'" >&2
    FAIL=1
fi

# ── 6. Certificate fingerprint check (skipped when CERT_FINGERPRINTS is empty) ─
if [[ -z "$CERT_FINGERPRINTS" ]]; then
    echo "SKIP: CERT_FINGERPRINTS not set — skipping fingerprint verification"
else
    # Split colon-separated list into individual fingerprints.
    # Format of each fingerprint: "AB:CD:EF:...:01" (32 colon-delimited hex byte pairs)
    IFS=':' read -ra SEGMENTS <<< "$CERT_FINGERPRINTS"
    # Rebuild into individual fingerprints (each fingerprint is 32 hex pairs = 31 colons,
    # so every 32 SEGMENTS = one fingerprint).
    # However, passing the full list to Python is simpler and less fragile — just let Python
    # do the comparison against whatever is in the file.
    MATCH=0
    python3 - "$APP_PACKAGE" "$CERT_FINGERPRINTS" <<'PY' && MATCH=1 || true
import json, sys
data = json.load(open(__import__("os").environ["WORK_DIR"] + "/assetlinks.json"))
pkg  = sys.argv[1]
# The expected fingerprints are passed as a newline-separated list (each line is one
# 32-byte-pair, colon-delimited fingerprint).  The env-var convention allows multiple
# fingerprints separated by a newline so CI can pass both debug and release certs.
expected = set(fp.strip().upper() for fp in sys.argv[2].splitlines() if fp.strip())
found_fps = set(
    fp.upper()
    for entry in data
    if entry.get("target", {}).get("package_name") == pkg
    for fp in entry.get("target", {}).get("sha256_cert_fingerprints", [])
)
matched = expected & found_fps
if not matched:
    print(f"Expected one of: {sorted(expected)}", file=sys.stderr)
    print(f"Found in file:   {sorted(found_fps)}", file=sys.stderr)
    sys.exit(1)
PY
    if [[ "$MATCH" -eq 1 ]]; then
        echo "OK: at least one expected certificate fingerprint found"
    else
        echo "ERROR: none of the expected certificate fingerprints found in assetlinks.json" >&2
        FAIL=1
    fi
fi

# ── 7. Shared cert-pins.json fingerprint check ────────────────────────────────
# The release signing fingerprint is single-sourced in shared/cert-pins.json.  Verify the
# hosted assetlinks.json advertises exactly that fingerprint so the file, the verifier
# constant and the Play App Signing certificate cannot drift apart unnoticed.
if [[ ! -f "$CERT_PINS_FILE" ]]; then
    echo "ERROR: cert pins file not found: $CERT_PINS_FILE" >&2
    FAIL=1
else
    if python3 - "$APP_PACKAGE" "$CERT_PINS_FILE" <<'PY'
import json, sys
pkg = sys.argv[1]
with open(sys.argv[2]) as fh:
    pins = json.load(fh)
expected = (pins.get("release_signing_sha256") or "").strip().upper()
if not expected:
    print(f"ERROR: release_signing_sha256 missing/empty in {sys.argv[2]}", file=sys.stderr)
    sys.exit(2)
data = json.load(open("/tmp/assetlinks.json"))
found_fps = set(
    fp.upper()
    for entry in data
    if entry.get("target", {}).get("package_name") == pkg
    for fp in entry.get("target", {}).get("sha256_cert_fingerprints", [])
)
if expected not in found_fps:
    print(f"Expected (cert-pins.json): {expected}", file=sys.stderr)
    print(f"Found in assetlinks.json: {sorted(found_fps)}", file=sys.stderr)
    sys.exit(1)
PY
    then
        echo "OK: assetlinks.json matches shared/cert-pins.json release_signing_sha256"
    else
        echo "ERROR: assetlinks.json does not match shared/cert-pins.json release_signing_sha256" >&2
        FAIL=1
    fi
fi

# ── Summary ───────────────────────────────────────────────────────────────────
if [[ "$FAIL" -ne 0 ]]; then
    echo ""
    echo "FAILED: assetlinks.json verification failed — Android App Links (autoVerify) may break."
    exit 1
fi

echo ""
echo "PASSED: assetlinks.json looks correct for Android App Links."
