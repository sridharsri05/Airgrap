"""Proof of key possession over an unauthenticated TLS channel.

TLS here provides confidentiality but not identity: certificates are
self-signed and no CA validates them. Each side instead proves it holds the
private key behind the fingerprint it claims, by signing a nonce chosen by the
other and bound to BOTH fingerprints. That binding is what stops a captured
signature being replayed against a different peer.

The critical rule, and the one an earlier revision of this design got wrong:
a peer's fingerprint is derived from the certificate it presented, never read
from a message field. A message field is a claim; a signature over a
certificate is proof.
"""

from __future__ import annotations

import hashlib
import secrets
from dataclasses import dataclass

from airgrab.identity import fingerprint_of, verify_signature
from airgrab.protocol import SAS_DOMAIN

NONCE_BYTES = 32


@dataclass(frozen=True)
class AuthResult:
    ok: bool
    reason: str = ""


def new_nonce() -> str:
    return secrets.token_hex(NONCE_BYTES)


def signing_payload(nonce: str, server_fp: str, client_fp: str) -> bytes:
    material = f"{SAS_DOMAIN}{nonce}{server_fp}{client_fp}".encode("utf-8")
    return hashlib.sha256(material).digest()


def verify_auth_response(
    cert_der: bytes,
    signature: bytes,
    nonce: str,
    server_fp: str,
    claimed_client_fp: str,
) -> AuthResult:
    """Server-side: authenticate the connecting client."""
    try:
        actual_fp = fingerprint_of(cert_der)
    except Exception:
        return AuthResult(False, "bad_certificate")

    payload = signing_payload(nonce, server_fp, claimed_client_fp)
    if not verify_signature(cert_der, payload, signature):
        return AuthResult(False, "bad_signature")

    if actual_fp != claimed_client_fp:
        return AuthResult(False, "identity_mismatch")

    return AuthResult(True)


def verify_server_identity(
    cert_der: bytes,
    signature: bytes,
    nonce: str,
    client_fp: str,
    pinned_fp: str | None,
) -> tuple[AuthResult, str]:
    """Client-side: authenticate the server, returning its derived fingerprint.

    The returned fingerprint comes from the certificate the server actually
    presented. It is never read from a message field, because a message field
    can simply be set to the victim's fingerprint by an attacker in the middle.

    `pinned_fp` is the fingerprint already recorded in the trust store, or None
    when this is a first-time pairing. With a pin, a mismatch is fatal. Without
    one, the caller must feed the returned fingerprint into the SAS so that a
    machine-in-the-middle shows different codes on the two screens.
    """
    try:
        derived_fp = fingerprint_of(cert_der)
    except Exception:
        return AuthResult(False, "bad_certificate"), ""

    payload = signing_payload(nonce, derived_fp, client_fp)
    if not verify_signature(cert_der, payload, signature):
        return AuthResult(False, "bad_signature"), derived_fp

    if pinned_fp is not None and derived_fp != pinned_fp:
        return AuthResult(False, "pin_mismatch"), derived_fp

    return AuthResult(True), derived_fp
