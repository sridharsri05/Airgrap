"""Wire format and pairing-code derivation.

This module is deliberately dependency-free and side-effect-free so it can be
tested against protocol/vectors/ without a network, and so the Kotlin port has
an unambiguous reference.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from typing import Any

PROTOCOL_VERSION = 1
SAS_DOMAIN = "airgrab-auth-v1"


class MessageType:
    HELLO = "hello"
    HELLO_ACK = "hello_ack"
    AUTH_CHALLENGE = "auth_challenge"
    AUTH_RESPONSE = "auth_response"
    PAIR_REQUEST = "pair_request"
    PAIR_CHALLENGE = "pair_challenge"
    PAIR_CONFIRM = "pair_confirm"
    PAIR_RESULT = "pair_result"
    OFFER = "offer"
    OFFER_ACCEPT = "offer_accept"
    OFFER_REJECT = "offer_reject"
    PROGRESS = "progress"
    COMPLETE = "complete"
    CANCEL = "cancel"
    ERROR = "error"
    PING = "ping"
    PONG = "pong"


class ErrorCode:
    VERSION_MISMATCH = "version_mismatch"
    NOT_TRUSTED = "not_trusted"
    AUTH_FAILED = "auth_failed"
    TICKET_INVALID = "ticket_invalid"
    TICKET_EXPIRED = "ticket_expired"
    HASH_MISMATCH = "hash_mismatch"
    STORAGE_FULL = "storage_full"
    STORAGE_DENIED = "storage_denied"
    TRANSFER_ABORTED = "transfer_aborted"
    INTERNAL = "internal"


class ProtocolError(Exception):
    """A message that cannot be parsed as a valid v1 envelope."""


@dataclass(frozen=True)
class Envelope:
    type: str
    seq: int
    payload: dict[str, Any] = field(default_factory=dict)


def encode(type: str, seq: int, payload: dict[str, Any] | None = None) -> str:
    return json.dumps(
        {"type": type, "seq": seq, "payload": payload or {}},
        separators=(",", ":"),
        ensure_ascii=False,
    )


def decode(raw: str) -> Envelope:
    try:
        obj = json.loads(raw)
    except (json.JSONDecodeError, TypeError) as exc:
        raise ProtocolError("message is not valid JSON") from exc

    if not isinstance(obj, dict):
        raise ProtocolError("message is not a JSON object")

    msg_type = obj.get("type")
    if not isinstance(msg_type, str) or not msg_type:
        raise ProtocolError("message has no type")

    seq = obj.get("seq", 0)
    if not isinstance(seq, int) or isinstance(seq, bool):
        raise ProtocolError("seq must be an integer")

    payload = obj.get("payload", {})
    if payload is None:
        payload = {}
    if not isinstance(payload, dict):
        raise ProtocolError("payload must be an object")

    return Envelope(type=msg_type, seq=seq, payload=payload)


def compute_sas(fingerprint_a: str, fingerprint_b: str) -> str:
    """The six-digit pairing code, identical on both devices."""
    joined = "".join(sorted([fingerprint_a, fingerprint_b]))
    digest = hashlib.sha256(joined.encode("utf-8")).digest()
    value = int.from_bytes(digest[0:4], "big") % 1_000_000
    return f"{value:06d}"
