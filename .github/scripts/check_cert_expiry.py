#!/usr/bin/env python3
"""check_cert_expiry.py

#273 — Certificate-pin expiry monitor.

Connects to the live API endpoint, retrieves the full TLS certificate chain,
and checks whether any certificate whose SPKI hash matches a pinned value is
within WARN_DAYS of expiry.

Pin sources (in priority order for each platform):
  iOS  : TLS_PUBLIC_KEY_PINS array in Info.plist files passed via --ios-plist.
  Android: ETHOS_CERT_PINS environment variable (comma-separated Base64 digests),
           falling back to the DEFAULT_PINS / PLACEHOLDER_PINS literals in the
           source file passed via --android-source.
  Merged : the union of all pins across both platforms.

Output:
  Emits GitHub Actions ::warning:: / ::error:: annotations.
  Writes a JSON report to cert-expiry-report.json in the working directory.

Exit codes:
  0 — All pinned certs expire more than WARN_DAYS from today (or the API host
      was unreachable and no certificates could be checked — treated as a non-
      blocking condition since the monitor should not gate normal CI).
  1 — At least one pinned cert expires within WARN_DAYS.

Usage:
  check_cert_expiry.py \\
    --host api.ethos-protocol.app --port 443 \\
    --warn-days 90 \\
    --ios-plist ios/EthosProtocol/EthosProtocol/Info.plist \\
    --ios-plist ios/EthosProtocol/TTLWidget/Info.plist \\
    --android-source android/app/src/main/java/com/ethosprotocol/api/CertificatePinning.kt
"""

from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import json
import os
import plistlib
import re
import socket
import ssl
import struct
import sys
import time
from pathlib import Path
from typing import Optional

# Critical threshold: a ::error:: annotation is emitted in addition to the warning.
CRITICAL_DAYS = 14

# ── Pin extraction ─────────────────────────────────────────────────────────────

def load_ios_pins(plist_paths: list[str]) -> set[str]:
    """Returns the union of TLS_PUBLIC_KEY_PINS arrays from all provided plists."""
    pins: set[str] = set()
    for path in plist_paths:
        p = Path(path)
        if not p.is_file():
            print(f"::warning::iOS Info.plist not found: {path} (skipping)")
            continue
        try:
            with p.open("rb") as f:
                data = plistlib.load(f)
            raw = data.get("TLS_PUBLIC_KEY_PINS", [])
            if isinstance(raw, list):
                pins.update(s for s in raw if isinstance(s, str) and s)
        except Exception as exc:
            print(f"::warning::Could not read {path}: {exc}")
    return pins


_PINS_BLOCK = re.compile(
    r"(?:DEFAULT_PINS|PLACEHOLDER_PINS)[^=]*=\s*setOf\((.*?)\)", re.DOTALL)
_QUOTED = re.compile(r'"([^"]*)"')
_STRING_OR_COMMENT = re.compile(r'"[^"\n]*"|//[^\n]*')
_PIN_PATTERN = re.compile(r"^[A-Za-z0-9+/]{43}=$")


def _is_placeholder(pin: str) -> bool:
    if "PLACEHOLDER" in pin.upper():
        return True
    if not _PIN_PATTERN.match(pin):
        return True
    body = pin.rstrip("=")
    if len(set(body)) == 1:
        return True
    try:
        decoded = base64.b64decode(pin, validate=True)
        return len(set(decoded)) == 1
    except Exception:
        return True


def load_android_pins(source_path: str) -> set[str]:
    """Returns Android pins from ETHOS_CERT_PINS env var or the source file."""
    env_pins = os.environ.get("ETHOS_CERT_PINS", "")
    if env_pins.strip():
        pins = {p.strip() for p in env_pins.split(",") if p.strip()}
        return {p for p in pins if not _is_placeholder(p)}

    p = Path(source_path)
    if not p.is_file():
        print(f"::warning::Android CertificatePinning.kt not found: {source_path} (skipping)")
        return set()
    text = _STRING_OR_COMMENT.sub(
        lambda m: m.group(0) if m.group(0).startswith('"') else "", p.read_text(encoding="utf-8"))
    match = _PINS_BLOCK.search(text)
    if not match:
        return set()
    return {pin for pin in _QUOTED.findall(match.group(1))
            if pin and not _is_placeholder(pin)}

# ── Certificate chain retrieval ────────────────────────────────────────────────

FETCH_ATTEMPTS = 3
FETCH_BACKOFF_SECONDS = 5


def fetch_cert_chain(host: str, port: int) -> list[bytes]:
    """Returns raw DER-encoded certificates from the TLS handshake with host:port."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE  # We only need the chain for expiry, not trust.
    try:
        with socket.create_connection((host, port), timeout=15) as sock:
            with ctx.wrap_socket(sock, server_hostname=host) as ssock:
                # get_unverified_chain requires Python 3.10+; fall back to getpeercert.
                if hasattr(ssock, "get_unverified_chain"):
                    return [ssl.DER_cert_to_PEM_cert(c) for c in ssock.get_unverified_chain() or []]
                der = ssock.getpeercert(binary_form=True)
                return [der] if der else []
    except Exception as exc:
        print(f"::warning::Could not connect to {host}:{port} — {exc}")
        return []


def der_to_bytes(cert_data) -> bytes:
    """Normalises a cert to raw bytes (handles DER or PEM strings)."""
    if isinstance(cert_data, bytes):
        return cert_data
    # PEM string from get_unverified_chain
    if isinstance(cert_data, str) and "-----BEGIN" in cert_data:
        b64 = "".join(cert_data.splitlines()[1:-1])
        return base64.b64decode(b64)
    return cert_data

# ── SPKI extraction ────────────────────────────────────────────────────────────

# Known SPKI OID + header prefixes for common key types, as used by
# CertificatePinning.swift / CertificatePinning.kt.
_EC_P256_SPKI_PREFIX = bytes([
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE,
    0x3D, 0x02, 0x01, 0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D,
    0x03, 0x01, 0x07, 0x03, 0x42, 0x00
])
_RSA_SPKI_PREFIX = bytes([
    0x30, 0x82, 0x01, 0x22, 0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86,
    0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00, 0x03,
    0x82, 0x01, 0x0F, 0x00
])


def _asn1_length(data: bytes, pos: int) -> tuple[int, int]:
    """Decodes an ASN.1 length at `pos`; returns (length, bytes_consumed)."""
    first = data[pos]
    if first < 0x80:
        return first, 1
    n = first & 0x7F
    length = int.from_bytes(data[pos + 1: pos + 1 + n], "big")
    return length, 1 + n


def extract_spki_from_der(cert_der: bytes) -> Optional[bytes]:
    """
    Extracts the SubjectPublicKeyInfo bytes from a DER-encoded X.509 certificate
    using a minimal ASN.1 walk.  Returns None on parse failure.
    """
    try:
        # Certificate  ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
        pos = 0
        if cert_der[pos] != 0x30:
            return None
        pos += 1
        _, ll = _asn1_length(cert_der, pos)
        pos += ll  # skip outer SEQUENCE length

        # tbsCertificate  ::= SEQUENCE { ... }
        if cert_der[pos] != 0x30:
            return None
        pos += 1
        tbs_len, ll = _asn1_length(cert_der, pos)
        pos += ll
        tbs_start = pos
        tbs_end = pos + tbs_len

        # Inside tbsCertificate: version[0], serialNumber, signature, issuer,
        # validity, subject, **subjectPublicKeyInfo**, ...
        # Walk each element until we find the SEQUENCE with key OID bytes.
        inner_pos = tbs_start
        while inner_pos < tbs_end:
            tag = cert_der[inner_pos]
            inner_pos += 1
            elem_len, ll = _asn1_length(cert_der, inner_pos)
            inner_pos += ll
            elem_end = inner_pos + elem_len

            # subjectPublicKeyInfo is a SEQUENCE (0x30) whose first bytes contain
            # an AlgorithmIdentifier SEQUENCE.  Identify it by looking for EC or
            # RSA OID bytes within the first 32 bytes of the element.
            if tag == 0x30 and elem_len > 4:
                candidate = cert_der[inner_pos:inner_pos + 32]
                is_ec = b'\x2a\x86\x48\xce\x3d' in candidate   # OID 1.2.840.10045
                is_rsa = b'\x2a\x86\x48\x86\xf7\x0d\x01\x01' in candidate  # OID 1.2.840.113549.1.1
                if is_ec or is_rsa:
                    return cert_der[inner_pos - ll - 1: elem_end]

            inner_pos = elem_end
        return None
    except Exception:
        return None


def spki_sha256_base64(cert_der: bytes) -> Optional[str]:
    spki = extract_spki_from_der(cert_der)
    if spki is None:
        return None
    digest = hashlib.sha256(spki).digest()
    return base64.b64encode(digest).decode()

# ── Expiry extraction ──────────────────────────────────────────────────────────

def extract_not_after(cert_der: bytes) -> Optional[datetime.datetime]:
    """
    Parses the NotAfter date from a DER X.509 certificate.
    Uses the ssl module to avoid a cryptography/pyOpenSSL dependency.
    """
    try:
        # Python's ssl.DER_cert_to_PEM_cert + ssl.cert_time_to_seconds route.
        pem = ssl.DER_cert_to_PEM_cert(cert_der)
        # Wrap in a temporary SSLContext to get the parsed cert dict.
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        # load_verify_locations is the public API for loading PEM into a context.
        ctx.load_verify_locations(cadata=pem)
        # There's no direct "parse this cert" API in ssl; use pyopenssl if present,
        # otherwise fall back to manual ASN.1 for the Validity SEQUENCE.
        try:
            from OpenSSL import crypto  # type: ignore
            x509 = crypto.load_certificate(crypto.FILETYPE_ASN1, cert_der)
            raw = x509.get_notAfter()
            if raw:
                # Format: b'YYYYMMDDHHMMSSZ'
                return datetime.datetime.strptime(raw.decode(), "%Y%m%d%H%M%SZ").replace(
                    tzinfo=datetime.timezone.utc)
        except ImportError:
            pass
        # Fallback: parse Validity SEQUENCE manually.
        return _parse_not_after_der(cert_der)
    except Exception:
        return None


def _parse_not_after_der(cert_der: bytes) -> Optional[datetime.datetime]:
    """
    Minimal DER parser for the Validity.notAfter field.
    Validity ::= SEQUENCE { notBefore Time, notAfter Time }
    Time ::= CHOICE { utcTime UTCTime, generalTime GeneralizedTime }
    """
    try:
        # Walk to tbsCertificate → Validity
        pos = 2  # skip outer SEQUENCE tag + length (approximate; works for most certs)
        # Re-parse length properly
        pos = 1
        _, ll = _asn1_length(cert_der, pos)
        pos += ll  # outer SEQUENCE
        pos += 1   # tbsCertificate SEQUENCE tag
        _, ll = _asn1_length(cert_der, pos)
        pos += ll  # tbs length field
        tbs_start = pos
        # Skip version [0] EXPLICIT, serialNumber INTEGER, signature SEQUENCE,
        # issuer SEQUENCE — find Validity SEQUENCE by walking tags.
        inner = tbs_start
        seq_count = 0
        while inner < len(cert_der):
            tag = cert_der[inner]
            inner += 1
            elem_len, ll = _asn1_length(cert_der, inner)
            inner += ll
            if tag == 0x30:
                seq_count += 1
                if seq_count == 4:  # Validity is the 4th SEQUENCE inside tbsCertificate
                    # Parse notBefore, then notAfter
                    v = inner
                    for _ in range(2):  # skip notBefore
                        t_tag = cert_der[v]
                        v += 1
                        t_len, tll = _asn1_length(cert_der, v)
                        v += tll
                        raw = cert_der[v:v + t_len].decode("ascii")
                        v += t_len
                        if _ == 1:  # notAfter
                            if t_tag == 0x17:  # UTCTime
                                dt = datetime.datetime.strptime(raw, "%y%m%d%H%M%SZ")
                                year = dt.year
                                if year < 2050:
                                    year = dt.year + (2000 if dt.year < 70 else 1900)
                                    dt = dt.replace(year=year)
                                return dt.replace(tzinfo=datetime.timezone.utc)
                            elif t_tag == 0x18:  # GeneralizedTime
                                return datetime.datetime.strptime(raw, "%Y%m%d%H%M%SZ").replace(
                                    tzinfo=datetime.timezone.utc)
                    return None
            inner += elem_len
        return None
    except Exception:
        return None

# ── Main logic ─────────────────────────────────────────────────────────────────

def run(host: str, port: int, warn_days: int,
        ios_plists: list[str], android_source: str) -> int:
    ios_pins = load_ios_pins(ios_plists)
    android_pins = load_android_pins(android_source)
    all_pins = ios_pins | android_pins

    if not all_pins:
        print("::error::No pinned certificate hashes found in configured sources — "
              "cannot check expiry. Is ETHOS_CERT_PINS set or CertificatePinning.kt updated?")
        return 2

    print(f"Checking certificate expiry for {host}:{port}")
    print(f"Pinned hashes ({len(all_pins)}): {', '.join(sorted(all_pins))}")

    raw_chain = []
    for attempt in range(1, FETCH_ATTEMPTS + 1):
        try:
            raw_chain = fetch_cert_chain(host, port)
        except Exception as exc:  # a raising fetch counts as a failed attempt
            print(f"::warning::Fetch attempt {attempt} raised: {exc}")
            raw_chain = []
        if raw_chain:
            break
        if attempt < FETCH_ATTEMPTS:
            time.sleep(FETCH_BACKOFF_SECONDS * attempt)
    if not raw_chain:
        print(f"::error::Could not fetch the certificate chain from {host}:{port} after "
              f"{FETCH_ATTEMPTS} attempts, so expiry is unchecked. Treat as an incident.")
        return 2

    today = datetime.datetime.now(datetime.timezone.utc)
    results = []
    exit_code = 0

    for i, raw in enumerate(raw_chain):
        der = der_to_bytes(raw)
        pin = spki_sha256_base64(der)
        if pin is None:
            continue
        not_after = extract_not_after(der)
        result = {
            "chain_index": i,
            "spki_sha256": pin,
            "is_pinned": pin in all_pins,
            "not_after": not_after.isoformat() if not_after else None,
            "days_until_expiry": None,
            "status": "unknown",
        }

        if pin in all_pins and not_after:
            delta = (not_after - today).days
            result["days_until_expiry"] = delta
            if delta <= CRITICAL_DAYS:
                result["status"] = "critical"
                print(f"::error::CRITICAL: Pinned certificate (chain[{i}], SPKI={pin[:16]}…) "
                      f"expires in {delta} days ({not_after.date()}) — "
                      f"immediate rotation required! See docs/cert-pin-rotation-runbook.md")
                exit_code = 1
            elif delta <= warn_days:
                result["status"] = "warning"
                print(f"::warning::Pinned certificate (chain[{i}], SPKI={pin[:16]}…) "
                      f"expires in {delta} days ({not_after.date()}) — "
                      f"begin rotation process. See docs/cert-pin-rotation-runbook.md")
                exit_code = 1
            else:
                result["status"] = "ok"
                print(f"OK: Pinned certificate (chain[{i}], SPKI={pin[:16]}…) "
                      f"expires in {delta} days ({not_after.date()}) — no action needed.")
        elif pin not in all_pins:
            result["status"] = "not_pinned"

        results.append(result)

    report = {
        "generated_at": today.isoformat(),
        "host": host,
        "port": port,
        "warn_days": warn_days,
        "certificates": results,
    }
    with open("cert-expiry-report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    print(f"Report written to cert-expiry-report.json")

    return exit_code


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="API hostname to connect to")
    parser.add_argument("--port", type=int, default=443, help="TCP port (default: 443)")
    parser.add_argument("--warn-days", type=int, default=90,
                        help="Emit a warning when a pinned cert expires within this many days (default: 90)")
    parser.add_argument("--ios-plist", action="append", dest="ios_plists", default=[],
                        metavar="PATH", help="iOS Info.plist path (may be repeated)")
    parser.add_argument("--android-source", default="",
                        help="Path to CertificatePinning.kt (source of the compiled-in Android pins)")
    args = parser.parse_args(argv)
    return run(args.host, args.port, args.warn_days, args.ios_plists, args.android_source)


if __name__ == "__main__":
    sys.exit(main())
