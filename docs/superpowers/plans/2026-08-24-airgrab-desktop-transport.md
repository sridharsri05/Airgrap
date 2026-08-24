# AirGrab Desktop Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Windows/Python half of AirGrab — device identity, trust, discovery, pairing, and verified bidirectional file transfer — proven by two processes pairing and transferring on `127.0.0.1`.

**Architecture:** A single aiohttp server per device exposes both a persistent WebSocket control channel (`/control`) and a streaming upload endpoint (`/transfer/{ticket}`) on one TLS port. Identity is a self-signed P-256 certificate; peers authenticate by signing a nonce bound to both fingerprints. Every device is simultaneously server and client, which is what makes bidirectional transfer symmetric rather than double the work.

**Tech Stack:** Python 3.11+, `cryptography` (certs and signatures), `aiohttp` (HTTPS + WebSocket on one port), `zeroconf` (mDNS), `pystray` + `Pillow` (tray UI), `pytest` + `pytest-asyncio`.

**Spec:** `docs/superpowers/specs/2026-08-24-airgrab-transport-design.md`

## Global Constraints

- Python **3.11+**. Type hints on all public functions.
- Protocol version **1**. Sent as `v` in every `hello`; mismatch closes the connection with `version_mismatch`.
- Default TCP port **53421**, falling back to an ephemeral port if occupied.
- mDNS service type **`_airgrab._tcp.local.`**
- Device ID is the **lowercase hex SHA-256 of the DER-encoded certificate**. Never anything else.
- Tickets are **256 bits**, valid **60 seconds**, **single use**, bound to one peer fingerprint.
- Ticket expiry uses **`time.monotonic()`**, never wall-clock time.
- `progress` messages are emitted by the **receiver**, at most every **250 ms**, counting **bytes written to disk**.
- Received files are written to a temporary name and **atomically renamed only after SHA-256 verification**.
- Filename collisions auto-rename to `name (2).ext`. Never overwrite.
- TLS provides **confidentiality only** (`CERT_NONE`). Identity is proven by a **mutual signed-nonce exchange** (Task 4).
- A peer's fingerprint is **always derived from the certificate it presented**, never read from a message field. Treat any code that reads `payload["id"]` as an identity as a defect.
- Trusted peers are **pinned**: a certificate change on a paired device aborts the connection rather than re-pairing silently.

---

### Task 1: Project scaffolding and device identity

**Files:**
- Create: `desktop/pyproject.toml`
- Create: `desktop/airgrab/__init__.py`
- Create: `desktop/airgrab/identity.py`
- Create: `desktop/tests/__init__.py`
- Test: `desktop/tests/test_identity.py`
- Create: `.gitignore`

**Interfaces:**
- Consumes: nothing.
- Produces: `DeviceIdentity` with `.fingerprint: str`, `.cert_der: bytes`, `.cert_pem: bytes`, `.key_pem: bytes`, `.display_name: str`, `.sign(data: bytes) -> bytes`, and classmethod `load_or_create(directory: Path, display_name: str) -> DeviceIdentity`. Module function `fingerprint_of(cert_der: bytes) -> str`, and `verify_signature(cert_der: bytes, data: bytes, signature: bytes) -> bool`.

- [ ] **Step 1: Create the project scaffolding**

`.gitignore`:

```
__pycache__/
*.py[cod]
.pytest_cache/
.venv/
venv/
dist/
build/
*.spec
airgrab-data/
```

`desktop/pyproject.toml`:

```toml
[project]
name = "airgrab"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = [
    "cryptography>=42.0",
    "aiohttp>=3.9",
    "zeroconf>=0.131",
    "pystray>=0.19",
    "Pillow>=10.0",
]

[project.optional-dependencies]
dev = ["pytest>=8.0", "pytest-asyncio>=0.23"]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]

[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"
```

Create empty `desktop/airgrab/__init__.py` and `desktop/tests/__init__.py`.

- [ ] **Step 2: Write the failing test**

`desktop/tests/test_identity.py`:

```python
from pathlib import Path

from airgrab.identity import DeviceIdentity, fingerprint_of, verify_signature


def test_fingerprint_is_64_hex_chars(tmp_path: Path):
    ident = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    assert len(ident.fingerprint) == 64
    assert ident.fingerprint == ident.fingerprint.lower()
    int(ident.fingerprint, 16)  # raises if not hex


def test_fingerprint_matches_certificate(tmp_path: Path):
    ident = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    assert ident.fingerprint == fingerprint_of(ident.cert_der)


def test_identity_is_stable_across_reloads(tmp_path: Path):
    first = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    second = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    assert first.fingerprint == second.fingerprint
    assert first.cert_der == second.cert_der


def test_separate_directories_get_separate_identities(tmp_path: Path):
    a = DeviceIdentity.load_or_create(tmp_path / "a", "A")
    b = DeviceIdentity.load_or_create(tmp_path / "b", "B")
    assert a.fingerprint != b.fingerprint


def test_signature_round_trip(tmp_path: Path):
    ident = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    sig = ident.sign(b"hello world")
    assert verify_signature(ident.cert_der, b"hello world", sig) is True


def test_signature_rejects_tampered_data(tmp_path: Path):
    ident = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    sig = ident.sign(b"hello world")
    assert verify_signature(ident.cert_der, b"goodbye world", sig) is False


def test_signature_rejects_wrong_signer(tmp_path: Path):
    a = DeviceIdentity.load_or_create(tmp_path / "a", "A")
    b = DeviceIdentity.load_or_create(tmp_path / "b", "B")
    sig = a.sign(b"payload")
    assert verify_signature(b.cert_der, b"payload", sig) is False
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd desktop && python -m pytest tests/test_identity.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'airgrab.identity'`

- [ ] **Step 4: Write the implementation**

`desktop/airgrab/identity.py`:

```python
"""Device identity: a self-signed P-256 certificate that never changes.

The certificate's SHA-256 fingerprint IS the device's identity. The common
name is cosmetic and is never trusted for identification.
"""

from __future__ import annotations

import datetime as dt
import hashlib
from dataclasses import dataclass
from pathlib import Path

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

CERT_FILENAME = "device-cert.pem"
KEY_FILENAME = "device-key.pem"
CERT_VALIDITY_DAYS = 3650


def fingerprint_of(cert_der: bytes) -> str:
    """Lowercase hex SHA-256 of a DER-encoded certificate."""
    return hashlib.sha256(cert_der).hexdigest()


def verify_signature(cert_der: bytes, data: bytes, signature: bytes) -> bool:
    """Verify an ECDSA-SHA256 signature against a certificate's public key."""
    try:
        cert = x509.load_der_x509_certificate(cert_der)
        cert.public_key().verify(signature, data, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError, TypeError):
        return False
    return True


@dataclass(frozen=True)
class DeviceIdentity:
    display_name: str
    cert_pem: bytes
    key_pem: bytes
    cert_der: bytes
    fingerprint: str
    cert_path: Path
    key_path: Path

    @classmethod
    def load_or_create(cls, directory: Path, display_name: str) -> "DeviceIdentity":
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        cert_path = directory / CERT_FILENAME
        key_path = directory / KEY_FILENAME

        if not (cert_path.exists() and key_path.exists()):
            cert_pem, key_pem = _generate(display_name)
            key_path.write_bytes(key_pem)
            cert_path.write_bytes(cert_pem)

        cert_pem = cert_path.read_bytes()
        key_pem = key_path.read_bytes()
        cert = x509.load_pem_x509_certificate(cert_pem)
        cert_der = cert.public_bytes(serialization.Encoding.DER)

        return cls(
            display_name=display_name,
            cert_pem=cert_pem,
            key_pem=key_pem,
            cert_der=cert_der,
            fingerprint=fingerprint_of(cert_der),
            cert_path=cert_path,
            key_path=key_path,
        )

    def sign(self, data: bytes) -> bytes:
        key = serialization.load_pem_private_key(self.key_pem, password=None)
        return key.sign(data, ec.ECDSA(hashes.SHA256()))


def _generate(display_name: str) -> tuple[bytes, bytes]:
    key = ec.generate_private_key(ec.SECP256R1())
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, display_name)])
    now = dt.datetime.now(dt.timezone.utc)

    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=CERT_VALIDITY_DAYS))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    key_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    return cert.public_bytes(serialization.Encoding.PEM), key_pem
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd desktop && python -m pytest tests/test_identity.py -v`
Expected: 7 passed

- [ ] **Step 6: Commit**

```bash
git add .gitignore desktop/pyproject.toml desktop/airgrab desktop/tests
git commit -m "feat(identity): self-signed device certificate and fingerprint"
```

---

### Task 2: Trust store

**Files:**
- Create: `desktop/airgrab/trust.py`
- Test: `desktop/tests/test_trust.py`

**Interfaces:**
- Consumes: nothing from Task 1 at runtime; fingerprints are plain strings.
- Produces: `TrustedPeer` dataclass with `.fingerprint: str`, `.name: str`, `.platform: str`. `TrustStore(path: Path)` with `.is_trusted(fingerprint: str) -> bool`, `.add(fingerprint: str, name: str, platform: str) -> None`, `.remove(fingerprint: str) -> None`, `.get(fingerprint: str) -> TrustedPeer | None`, `.all() -> list[TrustedPeer]`.

- [ ] **Step 1: Write the failing test**

`desktop/tests/test_trust.py`:

```python
from pathlib import Path

from airgrab.trust import TrustStore

FP_A = "a" * 64
FP_B = "b" * 64


def test_unknown_peer_is_not_trusted(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    assert store.is_trusted(FP_A) is False


def test_added_peer_is_trusted(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    store.add(FP_A, "My Phone", "android")
    assert store.is_trusted(FP_A) is True


def test_trust_persists_across_instances(tmp_path: Path):
    path = tmp_path / "trust.json"
    TrustStore(path).add(FP_A, "My Phone", "android")
    assert TrustStore(path).is_trusted(FP_A) is True


def test_get_returns_peer_details(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    store.add(FP_A, "My Phone", "android")
    peer = store.get(FP_A)
    assert peer is not None
    assert peer.name == "My Phone"
    assert peer.platform == "android"


def test_adding_twice_updates_rather_than_duplicates(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    store.add(FP_A, "Old Name", "android")
    store.add(FP_A, "New Name", "android")
    assert len(store.all()) == 1
    assert store.get(FP_A).name == "New Name"


def test_remove_revokes_trust(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    store.add(FP_A, "My Phone", "android")
    store.remove(FP_A)
    assert store.is_trusted(FP_A) is False


def test_removing_unknown_peer_is_harmless(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    store.remove(FP_B)
    assert store.all() == []


def test_corrupt_store_is_treated_as_empty(tmp_path: Path):
    path = tmp_path / "trust.json"
    path.write_text("this is not json")
    assert TrustStore(path).all() == []
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd desktop && python -m pytest tests/test_trust.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'airgrab.trust'`

- [ ] **Step 3: Write the implementation**

`desktop/airgrab/trust.py`:

```python
"""The list of devices this device has paired with.

A corrupt or unreadable store is treated as empty rather than fatal: the
worst outcome is re-pairing, and refusing to start is a far worse experience
than asking for six digits again.
"""

from __future__ import annotations

import json
import os
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class TrustedPeer:
    fingerprint: str
    name: str
    platform: str


class TrustStore:
    def __init__(self, path: Path) -> None:
        self._path = Path(path)
        self._peers: dict[str, TrustedPeer] = {}
        self._load()

    def is_trusted(self, fingerprint: str) -> bool:
        return fingerprint in self._peers

    def get(self, fingerprint: str) -> TrustedPeer | None:
        return self._peers.get(fingerprint)

    def all(self) -> list[TrustedPeer]:
        return list(self._peers.values())

    def add(self, fingerprint: str, name: str, platform: str) -> None:
        self._peers[fingerprint] = TrustedPeer(fingerprint, name, platform)
        self._save()

    def remove(self, fingerprint: str) -> None:
        if self._peers.pop(fingerprint, None) is not None:
            self._save()

    def _load(self) -> None:
        if not self._path.exists():
            return
        try:
            raw = json.loads(self._path.read_text(encoding="utf-8"))
            for entry in raw.get("peers", []):
                peer = TrustedPeer(
                    fingerprint=entry["fingerprint"],
                    name=entry.get("name", "Unknown"),
                    platform=entry.get("platform", "unknown"),
                )
                self._peers[peer.fingerprint] = peer
        except (json.JSONDecodeError, KeyError, TypeError, AttributeError, OSError):
            self._peers = {}

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"peers": [asdict(p) for p in self._peers.values()]}
        fd, tmp = tempfile.mkstemp(dir=str(self._path.parent), suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(payload, handle, indent=2)
            os.replace(tmp, self._path)
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd desktop && python -m pytest tests/test_trust.py -v`
Expected: 8 passed

- [ ] **Step 5: Commit**

```bash
git add desktop/airgrab/trust.py desktop/tests/test_trust.py
git commit -m "feat(trust): persistent trusted-peer store with atomic writes"
```

---

### Task 3: Protocol envelope and SAS derivation

**Files:**
- Create: `protocol/PROTOCOL.md`
- Create: `protocol/vectors/sas.json`
- Create: `desktop/airgrab/protocol.py`
- Test: `desktop/tests/test_protocol.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `PROTOCOL_VERSION: int`, `MessageType` (string constants `HELLO`, `HELLO_ACK`, `PAIR_REQUEST`, `PAIR_CHALLENGE`, `PAIR_CONFIRM`, `PAIR_RESULT`, `OFFER`, `OFFER_ACCEPT`, `OFFER_REJECT`, `PROGRESS`, `COMPLETE`, `CANCEL`, `ERROR`, `AUTH_CHALLENGE`, `AUTH_RESPONSE`, `PING`, `PONG`), `ErrorCode` constants, `Envelope` dataclass, `encode(type: str, seq: int, payload: dict) -> str`, `decode(raw: str) -> Envelope`, `compute_sas(fingerprint_a: str, fingerprint_b: str) -> str`, and `ProtocolError`.

- [ ] **Step 1: Write the shared test vectors**

`protocol/vectors/sas.json` — these values are computed by the algorithm in `PROTOCOL.md` and must produce identical results in Python and Kotlin. Generate them in Step 4 once `compute_sas` exists, then paste them here.

```json
{
  "_comment": "Populated in Step 4. Both implementations must reproduce these.",
  "cases": []
}
```

`protocol/PROTOCOL.md`:

```markdown
# AirGrab Protocol v1

Authoritative definition. Both the Python and Kotlin implementations obey
this document. Where code and this document disagree, this document wins.

## Envelope

Every control-channel message is a UTF-8 JSON object:

    { "type": "<string>", "seq": <int>, "payload": { ... } }

`seq` is a monotonic counter, per connection, per direction, starting at 1.
A reply carries the `seq` of the message it answers inside its payload as
`re`, when a correlation is needed.

Unknown `type` values MUST be ignored, not treated as errors. This is what
allows v1 and a future v2 to coexist during a rollout.

## Message types

hello, hello_ack, auth_challenge, auth_response, pair_request,
pair_challenge, pair_confirm, pair_result, offer, offer_accept,
offer_reject, progress, complete, cancel, error, ping, pong

## Error codes

version_mismatch, not_trusted, auth_failed, ticket_invalid, ticket_expired,
hash_mismatch, storage_full, storage_denied, transfer_aborted, internal

## Device ID

Lowercase hex SHA-256 of the DER-encoded device certificate.

## SAS (pairing code)

    joined  = concat(sort([fingerprint_a, fingerprint_b]))   # ASCII sort
    digest  = SHA-256(joined UTF-8 bytes)
    value   = int.from_bytes(digest[0:4], "big") mod 1000000
    sas     = value rendered as 6 decimal digits, zero-padded

Sorting makes the result independent of who initiated. Deriving it from both
fingerprints is what makes it detect a machine-in-the-middle: an attacker
holding a different certificate to each side produces a different code on
each screen.

## Authentication

TLS provides confidentiality only; certificates are not validated by a CA.
Identity is proven at the application layer, **in both directions**.

    signing_payload(nonce, server_fp, client_fp) =
        SHA-256( "airgrab-auth-v1" || nonce_hex || server_fp || client_fp )

all parts UTF-8 encoded and concatenated in that order.

Exchange:

1. Client sends `hello` with `{ v, id, name, plat, nonce }` where `nonce` is
   32 random bytes hex-encoded. `id` is the fingerprint the client claims.
2. Server replies `hello_ack` with `{ v, id, name, plat, trusted, cert, sig }`
   where `cert` is the server's DER certificate hex-encoded and `sig` is the
   server's signature over `signing_payload(client_nonce, server_fp,
   client_claimed_fp)`, with `server_fp` derived from the server's own
   certificate.
3. **The client derives the server's fingerprint from the presented
   certificate — never from any field in the message** — and verifies `sig`
   against that certificate. It then either:
   - **pins**: if the peer is already in the trust store, the derived
     fingerprint MUST equal the stored one, or the connection is aborted; or
   - **pairs**: the derived fingerprint is what feeds the SAS computation.
4. Server sends `auth_challenge` with `{ nonce }`.
5. Client replies `auth_response` with `{ cert, sig }`, signing
   `signing_payload(server_nonce, derived_server_fp, own_fp)`.
6. Server verifies the signature against the presented certificate and
   checks that certificate's fingerprint equals the `id` claimed in `hello`.

**Why every part of this is load-bearing.** An earlier draft had the server
simply *state* its fingerprint in a message and had only the client
authenticate. That is not secure: a machine-in-the-middle could claim the real
server's fingerprint, forward the client's signature to the real server, and
sit undetected on both connections. Deriving the peer fingerprint from the
presented certificate — combined with pinning for known peers and SAS
comparison for new ones — is what closes that hole. A machine-in-the-middle
must present its own certificate, which produces either a pin mismatch or two
different six-digit codes on the two screens.
```

- [ ] **Step 2: Write the failing test**

`desktop/tests/test_protocol.py`:

```python
import json
from pathlib import Path

import pytest

from airgrab.protocol import (
    PROTOCOL_VERSION,
    Envelope,
    MessageType,
    ProtocolError,
    compute_sas,
    decode,
    encode,
)

FP_A = "a" * 64
FP_B = "b" * 64


def test_protocol_version_is_one():
    assert PROTOCOL_VERSION == 1


def test_encode_decode_round_trip():
    raw = encode(MessageType.HELLO, 1, {"id": FP_A})
    env = decode(raw)
    assert env.type == MessageType.HELLO
    assert env.seq == 1
    assert env.payload == {"id": FP_A}


def test_encode_produces_json_with_expected_keys():
    raw = json.loads(encode(MessageType.PING, 7, {}))
    assert set(raw.keys()) == {"type", "seq", "payload"}


def test_decode_rejects_non_json():
    with pytest.raises(ProtocolError):
        decode("not json at all")


def test_decode_rejects_missing_type():
    with pytest.raises(ProtocolError):
        decode(json.dumps({"seq": 1, "payload": {}}))


def test_decode_rejects_non_integer_seq():
    with pytest.raises(ProtocolError):
        decode(json.dumps({"type": "ping", "seq": "one", "payload": {}}))


def test_decode_defaults_missing_payload_to_empty():
    env = decode(json.dumps({"type": "ping", "seq": 1}))
    assert env.payload == {}


def test_sas_is_six_digits():
    sas = compute_sas(FP_A, FP_B)
    assert len(sas) == 6
    assert sas.isdigit()


def test_sas_is_order_independent():
    assert compute_sas(FP_A, FP_B) == compute_sas(FP_B, FP_A)


def test_sas_differs_for_different_peers():
    assert compute_sas(FP_A, FP_B) != compute_sas(FP_A, "c" * 64)


def test_sas_matches_shared_vectors():
    vectors = json.loads(
        (Path(__file__).parents[2] / "protocol" / "vectors" / "sas.json").read_text()
    )
    assert vectors["cases"], "vectors file must not be empty"
    for case in vectors["cases"]:
        assert compute_sas(case["a"], case["b"]) == case["sas"]
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd desktop && python -m pytest tests/test_protocol.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'airgrab.protocol'`

- [ ] **Step 4: Write the implementation**

`desktop/airgrab/protocol.py`:

```python
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
```

- [ ] **Step 5: Generate the shared test vectors**

Run this once and paste the output into `protocol/vectors/sas.json`:

```bash
cd desktop && python -c "
import json
from airgrab.protocol import compute_sas
pairs = [('a'*64,'b'*64), ('0'*64,'f'*64), ('deadbeef'*8,'cafebabe'*8), ('1'*64,'1'*64)]
print(json.dumps({'cases':[{'a':a,'b':b,'sas':compute_sas(a,b)} for a,b in pairs]}, indent=2))
"
```

Replace the whole contents of `protocol/vectors/sas.json` with that output. The Kotlin implementation will later be tested against this exact file — that is how cross-language drift gets caught without two devices in the room.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd desktop && python -m pytest tests/test_protocol.py -v`
Expected: 11 passed

- [ ] **Step 7: Commit**

```bash
git add protocol desktop/airgrab/protocol.py desktop/tests/test_protocol.py
git commit -m "feat(protocol): v1 envelope, message types, SAS derivation, shared vectors"
```

---

### Task 4: Authentication challenge

**Files:**
- Create: `desktop/airgrab/auth.py`
- Test: `desktop/tests/test_auth.py`

**Interfaces:**
- Consumes: `airgrab.identity.DeviceIdentity`, `verify_signature`, `fingerprint_of`.
- Produces: `new_nonce() -> str` (64 hex chars), `signing_payload(nonce: str, server_fp: str, client_fp: str) -> bytes`, `AuthResult` dataclass with `.ok: bool` and `.reason: str`, `verify_auth_response(cert_der: bytes, signature: bytes, nonce: str, server_fp: str, claimed_client_fp: str) -> AuthResult`, and `verify_server_identity(cert_der: bytes, signature: bytes, nonce: str, client_fp: str, pinned_fp: str | None) -> tuple[AuthResult, str]` returning the fingerprint **derived from the certificate**.

- [ ] **Step 1: Write the failing test**

`desktop/tests/test_auth.py`:

```python
from pathlib import Path

from airgrab.auth import (
    new_nonce,
    signing_payload,
    verify_auth_response,
    verify_server_identity,
)
from airgrab.identity import DeviceIdentity


def _pair(tmp_path: Path):
    server = DeviceIdentity.load_or_create(tmp_path / "server", "SERVER")
    client = DeviceIdentity.load_or_create(tmp_path / "client", "CLIENT")
    return server, client


def test_nonce_is_64_hex_chars_and_unique():
    a, b = new_nonce(), new_nonce()
    assert len(a) == 64 and len(b) == 64
    int(a, 16)
    assert a != b


def test_valid_response_is_accepted(tmp_path: Path):
    server, client = _pair(tmp_path)
    nonce = new_nonce()
    sig = client.sign(signing_payload(nonce, server.fingerprint, client.fingerprint))
    result = verify_auth_response(
        client.cert_der, sig, nonce, server.fingerprint, client.fingerprint
    )
    assert result.ok is True


def test_wrong_nonce_is_rejected(tmp_path: Path):
    server, client = _pair(tmp_path)
    sig = client.sign(
        signing_payload(new_nonce(), server.fingerprint, client.fingerprint)
    )
    result = verify_auth_response(
        client.cert_der, sig, new_nonce(), server.fingerprint, client.fingerprint
    )
    assert result.ok is False
    assert result.reason == "bad_signature"


def test_signature_from_another_connection_is_rejected(tmp_path: Path):
    """A signature bound to a different server fingerprint must not replay."""
    server, client = _pair(tmp_path)
    other_server_fp = "9" * 64
    nonce = new_nonce()
    sig = client.sign(signing_payload(nonce, other_server_fp, client.fingerprint))
    result = verify_auth_response(
        client.cert_der, sig, nonce, server.fingerprint, client.fingerprint
    )
    assert result.ok is False
    assert result.reason == "bad_signature"


def test_certificate_not_matching_claimed_id_is_rejected(tmp_path: Path):
    """Presenting a valid signature while claiming someone else's ID fails."""
    server, client = _pair(tmp_path)
    nonce = new_nonce()
    lie = "7" * 64
    sig = client.sign(signing_payload(nonce, server.fingerprint, lie))
    result = verify_auth_response(client.cert_der, sig, nonce, server.fingerprint, lie)
    assert result.ok is False
    assert result.reason == "identity_mismatch"


def test_garbage_certificate_is_rejected(tmp_path: Path):
    server, client = _pair(tmp_path)
    nonce = new_nonce()
    sig = client.sign(signing_payload(nonce, server.fingerprint, client.fingerprint))
    result = verify_auth_response(
        b"not a certificate", sig, nonce, server.fingerprint, client.fingerprint
    )
    assert result.ok is False


# --- server-side identity, the half that stops machine-in-the-middle ---


def test_server_identity_is_derived_from_certificate_not_claimed(tmp_path: Path):
    server, client = _pair(tmp_path)
    nonce = new_nonce()
    sig = server.sign(signing_payload(nonce, server.fingerprint, client.fingerprint))
    result, derived = verify_server_identity(
        server.cert_der, sig, nonce, client.fingerprint, pinned_fp=None
    )
    assert result.ok is True
    assert derived == server.fingerprint


def test_pinned_server_fingerprint_must_match(tmp_path: Path):
    server, client = _pair(tmp_path)
    nonce = new_nonce()
    sig = server.sign(signing_payload(nonce, server.fingerprint, client.fingerprint))
    result, _ = verify_server_identity(
        server.cert_der, sig, nonce, client.fingerprint, pinned_fp="9" * 64
    )
    assert result.ok is False
    assert result.reason == "pin_mismatch"


def test_impostor_cannot_impersonate_a_pinned_server(tmp_path: Path):
    """The core MITM case: attacker has its own key, claims the real server."""
    real, client = _pair(tmp_path)
    attacker = DeviceIdentity.load_or_create(tmp_path / "attacker", "MITM")
    nonce = new_nonce()
    # Attacker signs correctly with ITS key, and cannot do otherwise.
    sig = attacker.sign(signing_payload(nonce, attacker.fingerprint, client.fingerprint))
    result, derived = verify_server_identity(
        attacker.cert_der, sig, nonce, client.fingerprint, pinned_fp=real.fingerprint
    )
    assert result.ok is False
    assert result.reason == "pin_mismatch"
    assert derived == attacker.fingerprint


def test_impostor_cannot_forge_a_signature_for_a_stolen_certificate(tmp_path: Path):
    """Attacker presents the real server's certificate but lacks its key."""
    real, client = _pair(tmp_path)
    attacker = DeviceIdentity.load_or_create(tmp_path / "attacker", "MITM")
    nonce = new_nonce()
    sig = attacker.sign(signing_payload(nonce, real.fingerprint, client.fingerprint))
    result, _ = verify_server_identity(
        real.cert_der, sig, nonce, client.fingerprint, pinned_fp=real.fingerprint
    )
    assert result.ok is False
    assert result.reason == "bad_signature"


def test_mitm_produces_a_different_pairing_code_on_each_side(tmp_path: Path):
    """During pairing there is no pin, so SAS mismatch is the defence."""
    from airgrab.protocol import compute_sas

    real, client = _pair(tmp_path)
    attacker = DeviceIdentity.load_or_create(tmp_path / "attacker", "MITM")

    client_side_sas = compute_sas(client.fingerprint, attacker.fingerprint)
    server_side_sas = compute_sas(real.fingerprint, attacker.fingerprint)
    honest_sas = compute_sas(client.fingerprint, real.fingerprint)

    assert client_side_sas != server_side_sas
    assert client_side_sas != honest_sas
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd desktop && python -m pytest tests/test_auth.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'airgrab.auth'`

- [ ] **Step 3: Write the implementation**

`desktop/airgrab/auth.py`:

```python
"""Proof of key possession over an unauthenticated TLS channel.

TLS here provides confidentiality but not identity: certificates are
self-signed and no CA validates them. The connecting device instead proves it
holds the private key behind the fingerprint it claims, by signing a nonce
bound to BOTH fingerprints. That binding is what stops a captured signature
being replayed against a different peer.
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
    """Authenticate the server, returning the fingerprint DERIVED from its cert.

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
```

Note the ordering: the signature is checked before the identity comparison, so a garbage certificate fails at `bad_signature` rather than leaking which check ran first. Both are rejections; the distinction only matters for logs.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd desktop && python -m pytest tests/test_auth.py -v`
Expected: 11 passed

- [ ] **Step 5: Commit**

```bash
git add desktop/airgrab/auth.py desktop/tests/test_auth.py
git commit -m "feat(auth): signed-nonce peer authentication bound to both fingerprints"
```

---

### Task 5: Ticket store and file receiver

**Files:**
- Create: `desktop/airgrab/transfer.py`
- Test: `desktop/tests/test_transfer.py`

**Interfaces:**
- Consumes: `airgrab.protocol.ErrorCode`.
- Produces: `TicketStore` with `.issue(peer_fp: str, expected_sha256: str, size: int) -> str`, `.redeem(ticket: str, peer_fp: str) -> Ticket`, raising `TicketError(code)`. `Ticket` dataclass with `.value`, `.peer_fp`, `.expected_sha256`, `.size`. `unique_destination(directory: Path, filename: str) -> Path`. `async receive_to_file(chunks, destination: Path, expected_sha256: str, progress) -> Path` raising `TransferError(code)`. `hash_file(path: Path) -> str`.

- [ ] **Step 1: Write the failing test**

`desktop/tests/test_transfer.py`:

```python
import hashlib
from pathlib import Path

import pytest

from airgrab.transfer import (
    TicketError,
    TicketStore,
    TransferError,
    hash_file,
    receive_to_file,
    unique_destination,
)

FP_A = "a" * 64
FP_B = "b" * 64


async def _chunks(*blobs: bytes):
    for blob in blobs:
        yield blob


def test_issued_ticket_can_be_redeemed_once():
    store = TicketStore()
    ticket = store.issue(FP_A, "0" * 64, 10)
    assert store.redeem(ticket, FP_A).peer_fp == FP_A
    with pytest.raises(TicketError) as exc:
        store.redeem(ticket, FP_A)
    assert exc.value.code == "ticket_invalid"


def test_ticket_is_bound_to_one_peer():
    store = TicketStore()
    ticket = store.issue(FP_A, "0" * 64, 10)
    with pytest.raises(TicketError) as exc:
        store.redeem(ticket, FP_B)
    assert exc.value.code == "ticket_invalid"


def test_unknown_ticket_is_rejected():
    store = TicketStore()
    with pytest.raises(TicketError):
        store.redeem("nope", FP_A)


def test_expired_ticket_is_rejected():
    clock = iter([100.0, 100.0, 161.0])
    store = TicketStore(clock=lambda: next(clock))
    ticket = store.issue(FP_A, "0" * 64, 10)
    with pytest.raises(TicketError) as exc:
        store.redeem(ticket, FP_A)
    assert exc.value.code == "ticket_expired"


def test_unique_destination_avoids_collision(tmp_path: Path):
    (tmp_path / "photo.jpg").write_bytes(b"x")
    assert unique_destination(tmp_path, "photo.jpg").name == "photo (2).jpg"


def test_unique_destination_keeps_counting(tmp_path: Path):
    (tmp_path / "photo.jpg").write_bytes(b"x")
    (tmp_path / "photo (2).jpg").write_bytes(b"x")
    assert unique_destination(tmp_path, "photo.jpg").name == "photo (3).jpg"


def test_unique_destination_passes_through_when_free(tmp_path: Path):
    assert unique_destination(tmp_path, "photo.jpg").name == "photo.jpg"


def test_unique_destination_strips_path_separators(tmp_path: Path):
    result = unique_destination(tmp_path, "../../evil.txt")
    assert result.parent == tmp_path
    assert result.name == "evil.txt"


async def test_receive_writes_verified_file(tmp_path: Path):
    data = b"hello world"
    digest = hashlib.sha256(data).hexdigest()
    dest = tmp_path / "out.bin"
    seen: list[int] = []

    result = await receive_to_file(
        _chunks(b"hello ", b"world"), dest, digest, seen.append
    )

    assert result == dest
    assert dest.read_bytes() == data
    assert seen and seen[-1] == len(data)


async def test_receive_rejects_corrupt_data_and_leaves_nothing(tmp_path: Path):
    dest = tmp_path / "out.bin"
    with pytest.raises(TransferError) as exc:
        await receive_to_file(_chunks(b"tampered"), dest, "0" * 64, lambda n: None)
    assert exc.value.code == "hash_mismatch"
    assert not dest.exists()
    assert list(tmp_path.iterdir()) == []


async def test_receive_leaves_no_partial_file_on_stream_error(tmp_path: Path):
    async def exploding():
        yield b"partial"
        raise ConnectionResetError("peer vanished")

    dest = tmp_path / "out.bin"
    with pytest.raises(TransferError) as exc:
        await receive_to_file(exploding(), dest, "0" * 64, lambda n: None)
    assert exc.value.code == "transfer_aborted"
    assert list(tmp_path.iterdir()) == []


def test_hash_file_matches_hashlib(tmp_path: Path):
    path = tmp_path / "f.bin"
    path.write_bytes(b"some content")
    assert hash_file(path) == hashlib.sha256(b"some content").hexdigest()
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd desktop && python -m pytest tests/test_transfer.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'airgrab.transfer'`

- [ ] **Step 3: Write the implementation**

`desktop/airgrab/transfer.py`:

```python
"""Ticket issuance and verified file reception.

Two invariants this module exists to guarantee:

1. A file visible under its real name is complete and hash-verified. Bytes
   land in a temporary file and are renamed only after verification, so a
   partial or corrupt transfer is never observable as a real file.
2. A ticket authorises exactly one upload, from exactly one peer, for at most
   sixty seconds.
"""

from __future__ import annotations

import hashlib
import os
import secrets
import time
from dataclasses import dataclass
from pathlib import Path
from typing import AsyncIterator, Callable

from airgrab.protocol import ErrorCode

TICKET_TTL_SECONDS = 60
TICKET_BYTES = 32
READ_CHUNK = 256 * 1024


class TicketError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class TransferError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class Ticket:
    value: str
    peer_fp: str
    expected_sha256: str
    size: int
    issued_at: float


class TicketStore:
    """Single-use, peer-bound, time-limited upload authorisations.

    Expiry uses a monotonic clock so that a system clock change mid-transfer
    cannot expire a live ticket or resurrect a dead one.
    """

    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self._tickets: dict[str, Ticket] = {}

    def issue(self, peer_fp: str, expected_sha256: str, size: int) -> str:
        value = secrets.token_hex(TICKET_BYTES)
        self._tickets[value] = Ticket(
            value=value,
            peer_fp=peer_fp,
            expected_sha256=expected_sha256,
            size=size,
            issued_at=self._clock(),
        )
        return value

    def redeem(self, ticket: str, peer_fp: str) -> Ticket:
        found = self._tickets.get(ticket)
        if found is None or not secrets.compare_digest(found.peer_fp, peer_fp):
            raise TicketError(ErrorCode.TICKET_INVALID)

        del self._tickets[ticket]

        if self._clock() - found.issued_at > TICKET_TTL_SECONDS:
            raise TicketError(ErrorCode.TICKET_EXPIRED)

        return found

    def discard(self, ticket: str) -> None:
        self._tickets.pop(ticket, None)


def unique_destination(directory: Path, filename: str) -> Path:
    """Resolve a safe, non-colliding path inside `directory`.

    The basename is taken deliberately: a peer-supplied name containing path
    separators must never escape the destination folder.
    """
    directory = Path(directory)
    safe = os.path.basename(filename.replace("\\", "/")) or "received"
    candidate = directory / safe
    if not candidate.exists():
        return candidate

    stem = candidate.stem
    suffix = candidate.suffix
    counter = 2
    while True:
        candidate = directory / f"{stem} ({counter}){suffix}"
        if not candidate.exists():
            return candidate
        counter += 1


async def receive_to_file(
    chunks: AsyncIterator[bytes],
    destination: Path,
    expected_sha256: str,
    progress: Callable[[int], None],
) -> Path:
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp_path = destination.with_name(f".{destination.name}.airgrab-part")

    digest = hashlib.sha256()
    written = 0

    try:
        with open(temp_path, "wb") as handle:
            async for chunk in chunks:
                handle.write(chunk)
                digest.update(chunk)
                written += len(chunk)
                progress(written)
            handle.flush()
            os.fsync(handle.fileno())
    except OSError as exc:
        temp_path.unlink(missing_ok=True)
        code = (
            ErrorCode.STORAGE_FULL
            if getattr(exc, "errno", None) == 28
            else ErrorCode.STORAGE_DENIED
        )
        raise TransferError(code) from exc
    except BaseException as exc:
        temp_path.unlink(missing_ok=True)
        raise TransferError(ErrorCode.TRANSFER_ABORTED) from exc

    if digest.hexdigest() != expected_sha256:
        temp_path.unlink(missing_ok=True)
        raise TransferError(ErrorCode.HASH_MISMATCH)

    os.replace(temp_path, destination)
    return destination


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            block = handle.read(READ_CHUNK)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd desktop && python -m pytest tests/test_transfer.py -v`
Expected: 12 passed

- [ ] **Step 5: Commit**

```bash
git add desktop/airgrab/transfer.py desktop/tests/test_transfer.py
git commit -m "feat(transfer): peer-bound tickets and atomically verified file reception"
```

---

### Task 6: Control channel — server and client

**Files:**
- Create: `desktop/airgrab/node.py`
- Test: `desktop/tests/test_node.py`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: `NodeConfig` dataclass with `.data_dir: Path`, `.download_dir: Path`, `.display_name: str`, `.port: int`, `.auto_accept: bool`. `Node` with `async start()`, `async stop()`, `.port: int`, `.identity`, `.trust`, `async pair_with(host: str, port: int, on_sas) -> bool`, `async send_file(host: str, port: int, path: Path, on_progress=None) -> bool`, and `.on_incoming_file: Callable[[Path], None] | None`.

- [ ] **Step 1: Write the failing test**

`desktop/tests/test_node.py`:

```python
import hashlib
from pathlib import Path

import pytest

from airgrab.node import Node, NodeConfig


def _config(root: Path, name: str, auto_accept: bool = True) -> NodeConfig:
    return NodeConfig(
        data_dir=root / name,
        download_dir=root / name / "downloads",
        display_name=name,
        port=0,
        auto_accept=auto_accept,
    )


async def _running(root: Path, name: str, **kw):
    node = Node(_config(root, name, **kw))
    await node.start()
    return node


async def test_two_nodes_have_distinct_identities(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    try:
        assert a.identity.fingerprint != b.identity.fingerprint
    finally:
        await a.stop()
        await b.stop()


async def test_pairing_shows_matching_code_and_grants_mutual_trust(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    codes: list[str] = []
    try:
        ok = await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: (codes.append(s), True)[1])
        assert ok is True
        assert len(codes) == 1 and len(codes[0]) == 6
        assert a.trust.is_trusted(b.identity.fingerprint)
        assert b.trust.is_trusted(a.identity.fingerprint)
    finally:
        await a.stop()
        await b.stop()


async def test_declining_the_code_leaves_both_untrusted(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    try:
        ok = await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: False)
        assert ok is False
        assert not a.trust.is_trusted(b.identity.fingerprint)
        assert not b.trust.is_trusted(a.identity.fingerprint)
    finally:
        await a.stop()
        await b.stop()


async def test_transfer_to_untrusted_peer_is_refused(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "f.txt"
    payload.write_bytes(b"data")
    try:
        assert await a.send_file("127.0.0.1", b.port, payload) is False
    finally:
        await a.stop()
        await b.stop()


async def test_paired_nodes_transfer_a_file(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "photo.jpg"
    payload.write_bytes(b"pretend jpeg bytes" * 1000)
    landed: list[Path] = []
    b.on_incoming_file = landed.append
    try:
        await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: True)
        assert await a.send_file("127.0.0.1", b.port, payload) is True
        assert len(landed) == 1
        assert landed[0].read_bytes() == payload.read_bytes()
        assert landed[0].name == "photo.jpg"
    finally:
        await a.stop()
        await b.stop()


async def test_progress_is_reported_and_ends_at_file_size(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "big.bin"
    payload.write_bytes(b"x" * (2 * 1024 * 1024))
    seen: list[tuple[int, int]] = []
    try:
        await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: True)
        await a.send_file("127.0.0.1", b.port, payload, on_progress=lambda r, t: seen.append((r, t)))
        assert seen
        assert seen[-1] == (payload.stat().st_size, payload.stat().st_size)
    finally:
        await a.stop()
        await b.stop()


async def test_collision_is_renamed_not_overwritten(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "note.txt"
    payload.write_bytes(b"second version")
    (b.config.download_dir).mkdir(parents=True, exist_ok=True)
    (b.config.download_dir / "note.txt").write_bytes(b"first version")
    landed: list[Path] = []
    b.on_incoming_file = landed.append
    try:
        await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: True)
        await a.send_file("127.0.0.1", b.port, payload)
        assert landed[0].name == "note (2).txt"
        assert (b.config.download_dir / "note.txt").read_bytes() == b"first version"
    finally:
        await a.stop()
        await b.stop()


async def test_received_file_hash_matches_source(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "data.bin"
    payload.write_bytes(bytes(range(256)) * 500)
    landed: list[Path] = []
    b.on_incoming_file = landed.append
    try:
        await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: True)
        await a.send_file("127.0.0.1", b.port, payload)
        expected = hashlib.sha256(payload.read_bytes()).hexdigest()
        assert hashlib.sha256(landed[0].read_bytes()).hexdigest() == expected
    finally:
        await a.stop()
        await b.stop()
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd desktop && python -m pytest tests/test_node.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'airgrab.node'`

- [ ] **Step 3: Write the implementation**

`desktop/airgrab/node.py`:

```python
"""One AirGrab device: server and client in a single object.

Both sides of every exchange live here because the protocol is symmetric —
a device that can only receive is half an implementation, and keeping the two
halves adjacent is what stops them drifting apart.

The control channel is a WebSocket at /control; file bytes travel separately
to /transfer/{ticket} on the same TLS port. Small coordination messages
therefore cannot be stalled behind a multi-gigabyte upload.
"""

from __future__ import annotations

import asyncio
import contextlib
import ssl
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import aiohttp
from aiohttp import web

from airgrab import auth
from airgrab.identity import DeviceIdentity
from airgrab.protocol import (
    PROTOCOL_VERSION,
    Envelope,
    ErrorCode,
    MessageType,
    ProtocolError,
    compute_sas,
    decode,
    encode,
)
from airgrab.transfer import (
    TicketError,
    TicketStore,
    TransferError,
    hash_file,
    receive_to_file,
    unique_destination,
)
from airgrab.trust import TrustStore

PLATFORM = "windows"
PROGRESS_INTERVAL_SECONDS = 0.25
UPLOAD_CHUNK = 256 * 1024


@dataclass
class NodeConfig:
    data_dir: Path
    download_dir: Path
    display_name: str
    port: int = 53421
    auto_accept: bool = True
    trust_filename: str = "trust.json"


@dataclass
class _Session:
    """Per-connection state on the server side."""

    peer_fp: str = ""
    peer_name: str = "Unknown"
    peer_platform: str = "unknown"
    nonce: str = ""
    authenticated: bool = False
    pending_sas: str = ""
    seq: int = field(default=0)

    def next_seq(self) -> int:
        self.seq += 1
        return self.seq


class Node:
    def __init__(self, config: NodeConfig) -> None:
        self.config = config
        self.identity = DeviceIdentity.load_or_create(
            config.data_dir, config.display_name
        )
        self.trust = TrustStore(config.data_dir / config.trust_filename)
        self.tickets = TicketStore()
        self.on_incoming_file: Callable[[Path], None] | None = None
        self.on_pair_request: Callable[[str, str], bool] | None = None

        self._runner: web.AppRunner | None = None
        self._site: web.TCPSite | None = None
        self._port = config.port

    @property
    def port(self) -> int:
        return self._port

    # ---------------------------------------------------------------- server

    async def start(self) -> None:
        app = web.Application(client_max_size=0)
        app.router.add_get("/control", self._handle_control)
        app.router.add_post("/transfer/{ticket}", self._handle_upload)

        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_context.load_cert_chain(
            certfile=str(self.identity.cert_path), keyfile=str(self.identity.key_path)
        )

        self._runner = web.AppRunner(app)
        await self._runner.setup()
        self._site = web.TCPSite(
            self._runner, "0.0.0.0", self.config.port, ssl_context=ssl_context
        )
        await self._site.start()

        sockets = self._site._server.sockets  # type: ignore[union-attr]
        self._port = sockets[0].getsockname()[1]

    async def stop(self) -> None:
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
            self._site = None

    async def _handle_control(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=15)
        await ws.prepare(request)
        session = _Session()

        async for message in ws:
            if message.type is not aiohttp.WSMsgType.TEXT:
                continue
            try:
                envelope = decode(message.data)
            except ProtocolError:
                continue
            try:
                await self._dispatch(ws, session, envelope)
            except Exception:
                await self._send(ws, session, MessageType.ERROR,
                                 {"code": ErrorCode.INTERNAL, "message": "server error"})
                break

        return ws

    async def _send(self, ws, session: _Session, type: str, payload: dict) -> None:
        await ws.send_str(encode(type, session.next_seq(), payload))

    async def _dispatch(self, ws, session: _Session, env: Envelope) -> None:
        payload = env.payload

        if env.type == MessageType.HELLO:
            if payload.get("v") != PROTOCOL_VERSION:
                await self._send(ws, session, MessageType.ERROR,
                                 {"code": ErrorCode.VERSION_MISMATCH, "message": "protocol version"})
                await ws.close()
                return
            session.peer_fp = str(payload.get("id", ""))
            session.peer_name = str(payload.get("name", "Unknown"))
            session.peer_platform = str(payload.get("plat", "unknown"))
            session.nonce = auth.new_nonce()
            client_nonce = str(payload.get("nonce", ""))
            # Prove possession of our own key over the client's nonce, so the
            # client can pin us. Without this the client has no way to tell us
            # apart from a machine-in-the-middle.
            server_sig = self.identity.sign(
                auth.signing_payload(
                    client_nonce, self.identity.fingerprint, session.peer_fp
                )
            )
            await self._send(ws, session, MessageType.HELLO_ACK, {
                "v": PROTOCOL_VERSION,
                "id": self.identity.fingerprint,
                "name": self.identity.display_name,
                "plat": PLATFORM,
                "trusted": self.trust.is_trusted(session.peer_fp),
                "cert": self.identity.cert_der.hex(),
                "sig": server_sig.hex(),
            })
            await self._send(ws, session, MessageType.AUTH_CHALLENGE,
                             {"nonce": session.nonce})
            return

        if env.type == MessageType.AUTH_RESPONSE:
            result = auth.verify_auth_response(
                cert_der=bytes.fromhex(str(payload.get("cert", ""))),
                signature=bytes.fromhex(str(payload.get("signature", ""))),
                nonce=session.nonce,
                server_fp=self.identity.fingerprint,
                claimed_client_fp=session.peer_fp,
            )
            session.authenticated = result.ok
            if not result.ok:
                await self._send(ws, session, MessageType.ERROR,
                                 {"code": ErrorCode.AUTH_FAILED, "message": result.reason})
                await ws.close()
            return

        if not session.authenticated:
            await self._send(ws, session, MessageType.ERROR,
                             {"code": ErrorCode.AUTH_FAILED, "message": "not authenticated"})
            await ws.close()
            return

        if env.type == MessageType.PAIR_REQUEST:
            session.pending_sas = compute_sas(self.identity.fingerprint, session.peer_fp)
            accepted = True
            if self.on_pair_request is not None:
                accepted = bool(self.on_pair_request(session.pending_sas, session.peer_name))
            session.pending_sas = session.pending_sas if accepted else ""
            await self._send(ws, session, MessageType.PAIR_CHALLENGE,
                             {"sas": compute_sas(self.identity.fingerprint, session.peer_fp)})
            return

        if env.type == MessageType.PAIR_CONFIRM:
            accepted = bool(payload.get("accepted")) and session.pending_sas != ""
            if accepted:
                self.trust.add(session.peer_fp, session.peer_name, session.peer_platform)
            await self._send(ws, session, MessageType.PAIR_RESULT, {"accepted": accepted})
            return

        if env.type == MessageType.OFFER:
            if not self.trust.is_trusted(session.peer_fp):
                await self._send(ws, session, MessageType.OFFER_REJECT,
                                 {"reason": ErrorCode.NOT_TRUSTED})
                return
            if not self.config.auto_accept:
                await self._send(ws, session, MessageType.OFFER_REJECT,
                                 {"reason": ErrorCode.TRANSFER_ABORTED})
                return
            ticket = self.tickets.issue(
                session.peer_fp,
                str(payload.get("sha256", "")),
                int(payload.get("size", 0)),
            )
            self._pending_names[ticket] = str(payload.get("name", "received"))
            self._pending_sockets[ticket] = (ws, session)
            await self._send(ws, session, MessageType.OFFER_ACCEPT, {"ticket": ticket})
            return

        if env.type == MessageType.PING:
            await self._send(ws, session, MessageType.PONG, {})
            return

    _pending_names: dict[str, str] = {}
    _pending_sockets: dict = {}

    async def _handle_upload(self, request: web.Request) -> web.Response:
        ticket_value = request.match_info["ticket"]
        entry = self._pending_sockets.get(ticket_value)
        if entry is None:
            return web.json_response({"code": ErrorCode.TICKET_INVALID}, status=403)
        ws, session = entry

        try:
            ticket = self.tickets.redeem(ticket_value, session.peer_fp)
        except TicketError as exc:
            self._cleanup_ticket(ticket_value)
            return web.json_response({"code": exc.code}, status=403)

        filename = self._pending_names.pop(ticket_value, "received")
        destination = unique_destination(self.config.download_dir, filename)

        last_report = 0.0

        def report(received: int) -> None:
            nonlocal last_report
            now = time.monotonic()
            if now - last_report < PROGRESS_INTERVAL_SECONDS and received != ticket.size:
                return
            last_report = now
            asyncio.create_task(
                self._send(ws, session, MessageType.PROGRESS,
                           {"received": received, "total": ticket.size})
            )

        async def stream():
            while True:
                chunk = await request.content.readany()
                if not chunk:
                    break
                yield chunk

        try:
            path = await receive_to_file(
                stream(), destination, ticket.expected_sha256, report
            )
        except TransferError as exc:
            self._cleanup_ticket(ticket_value)
            with contextlib.suppress(Exception):
                await self._send(ws, session, MessageType.COMPLETE, {"ok": False})
            return web.json_response({"code": exc.code}, status=400)

        self._cleanup_ticket(ticket_value)
        with contextlib.suppress(Exception):
            await self._send(ws, session, MessageType.COMPLETE,
                             {"ok": True, "path": str(path)})
        if self.on_incoming_file is not None:
            self.on_incoming_file(path)
        return web.json_response({"ok": True})

    def _cleanup_ticket(self, ticket_value: str) -> None:
        self.tickets.discard(ticket_value)
        self._pending_names.pop(ticket_value, None)
        self._pending_sockets.pop(ticket_value, None)

    # ---------------------------------------------------------------- client

    def _client_ssl(self) -> ssl.SSLContext:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        return context

    @contextlib.asynccontextmanager
    async def _connect(self, host: str, port: int):
        connector = aiohttp.TCPConnector(ssl=self._client_ssl())
        async with aiohttp.ClientSession(connector=connector) as http:
            async with http.ws_connect(f"https://{host}:{port}/control") as ws:
                seq = {"n": 0}

                async def send(type: str, payload: dict) -> None:
                    seq["n"] += 1
                    await ws.send_str(encode(type, seq["n"], payload))

                async def recv(expected: str | None = None) -> Envelope:
                    async for message in ws:
                        if message.type is not aiohttp.WSMsgType.TEXT:
                            continue
                        env = decode(message.data)
                        if expected is None or env.type in (expected, MessageType.ERROR):
                            return env
                    raise ConnectionError("control channel closed")

                client_nonce = auth.new_nonce()
                await send(MessageType.HELLO, {
                    "v": PROTOCOL_VERSION,
                    "id": self.identity.fingerprint,
                    "name": self.identity.display_name,
                    "plat": PLATFORM,
                    "nonce": client_nonce,
                })
                ack = await recv(MessageType.HELLO_ACK)
                if ack.type == MessageType.ERROR:
                    raise ConnectionError(ack.payload.get("code", "error"))

                # Authenticate the SERVER before revealing anything further.
                # peer_fp comes from the presented certificate, never from
                # ack.payload["id"] — that field is attacker-controlled.
                pinned = ack.payload.get("id")
                pinned_fp = pinned if self.trust.is_trusted(str(pinned)) else None
                server_result, peer_fp = auth.verify_server_identity(
                    cert_der=bytes.fromhex(str(ack.payload.get("cert", ""))),
                    signature=bytes.fromhex(str(ack.payload.get("sig", ""))),
                    nonce=client_nonce,
                    client_fp=self.identity.fingerprint,
                    pinned_fp=str(pinned_fp) if pinned_fp is not None else None,
                )
                if not server_result.ok:
                    raise ConnectionError(f"server authentication failed: {server_result.reason}")

                challenge = await recv(MessageType.AUTH_CHALLENGE)
                nonce = str(challenge.payload.get("nonce", ""))
                signature = self.identity.sign(
                    auth.signing_payload(nonce, peer_fp, self.identity.fingerprint)
                )
                await send(MessageType.AUTH_RESPONSE, {
                    "cert": self.identity.cert_der.hex(),
                    "signature": signature.hex(),
                })

                yield http, ws, send, recv, peer_fp, ack.payload

    async def pair_with(
        self, host: str, port: int, on_sas: Callable[[str], bool]
    ) -> bool:
        async with self._connect(host, port) as (_http, _ws, send, recv, peer_fp, ack):
            await send(MessageType.PAIR_REQUEST, {})
            challenge = await recv(MessageType.PAIR_CHALLENGE)
            if challenge.type == MessageType.ERROR:
                return False

            sas = str(challenge.payload.get("sas", ""))
            if sas != compute_sas(self.identity.fingerprint, peer_fp):
                return False

            accepted = bool(on_sas(sas))
            await send(MessageType.PAIR_CONFIRM, {"accepted": accepted})
            result = await recv(MessageType.PAIR_RESULT)
            granted = accepted and bool(result.payload.get("accepted"))

            if granted:
                self.trust.add(peer_fp, str(ack.get("name", "Unknown")),
                               str(ack.get("plat", "unknown")))
            return granted

    async def send_file(
        self,
        host: str,
        port: int,
        path: Path,
        on_progress: Callable[[int, int], None] | None = None,
    ) -> bool:
        path = Path(path)
        size = path.stat().st_size
        digest = hash_file(path)

        async with self._connect(host, port) as (http, _ws, send, recv, _fp, _ack):
            await send(MessageType.OFFER, {
                "name": path.name,
                "size": size,
                "mime": "application/octet-stream",
                "sha256": digest,
            })
            reply = await recv(MessageType.OFFER_ACCEPT)
            if reply.type != MessageType.OFFER_ACCEPT:
                return False
            ticket = str(reply.payload.get("ticket", ""))

            async def body():
                with open(path, "rb") as handle:
                    while True:
                        chunk = handle.read(UPLOAD_CHUNK)
                        if not chunk:
                            break
                        yield chunk

            upload = await http.post(
                f"https://{host}:{port}/transfer/{ticket}", data=body()
            )
            if upload.status != 200:
                return False

            while True:
                env = await recv()
                if env.type == MessageType.PROGRESS and on_progress is not None:
                    on_progress(int(env.payload.get("received", 0)),
                                int(env.payload.get("total", size)))
                elif env.type == MessageType.COMPLETE:
                    return bool(env.payload.get("ok"))
                elif env.type == MessageType.ERROR:
                    return False
```

- [ ] **Step 4: Add the certificate-pinning regression test**

Append to `desktop/tests/test_node.py`:

```python
async def test_paired_peer_that_changes_certificate_is_refused(tmp_path: Path):
    """After pairing, a device presenting a different key must be rejected.

    This is the machine-in-the-middle case at the node level: the attacker
    controls the address but not the private key. Deleting the identity files
    and restarting simulates exactly that.
    """
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "f.txt"
    payload.write_bytes(b"data")
    try:
        await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: True)
        assert await a.send_file("127.0.0.1", b.port, payload) is True

        # B is replaced by an impostor at the same address.
        await b.stop()
        for stale in (tmp_path / "B").glob("device-*.pem"):
            stale.unlink()
        impostor = await _running(tmp_path, "B")
        try:
            with pytest.raises(Exception):
                await a.send_file("127.0.0.1", impostor.port, payload)
        finally:
            await impostor.stop()
    finally:
        await a.stop()
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd desktop && python -m pytest tests/test_node.py -v`
Expected: 9 passed

If `test_progress_is_reported_and_ends_at_file_size` is flaky, the cause is the fire-and-forget `asyncio.create_task` in `report`; make the final progress report awaited synchronously before `receive_to_file` returns rather than loosening the assertion.

- [ ] **Step 6: Commit**

```bash
git add desktop/airgrab/node.py desktop/tests/test_node.py
git commit -m "feat(node): control channel, mutual authentication, pinning, and file transfer"
```

---

### Task 7: mDNS discovery

**Files:**
- Create: `desktop/airgrab/discovery.py`
- Test: `desktop/tests/test_discovery.py`

**Interfaces:**
- Consumes: `airgrab.protocol.PROTOCOL_VERSION`.
- Produces: `SERVICE_TYPE: str`, `DiscoveredPeer` dataclass with `.fingerprint`, `.name`, `.platform`, `.host`, `.port`. `Advertiser(fingerprint, name, platform, port)` with `.start()` / `.stop()`. `Browser(on_found, on_lost, ignore_fingerprint)` with `.start()` / `.stop()` / `.peers()`.

- [ ] **Step 1: Write the failing test**

`desktop/tests/test_discovery.py`:

```python
import time

from airgrab.discovery import SERVICE_TYPE, Advertiser, Browser

FP = "a" * 64


def test_service_type_matches_spec():
    assert SERVICE_TYPE == "_airgrab._tcp.local."


def test_advertised_node_is_discovered():
    found = []
    advertiser = Advertiser(FP, "TEST-PC", "windows", 53421)
    browser = Browser(on_found=found.append, on_lost=lambda fp: None,
                      ignore_fingerprint="ignored")
    advertiser.start()
    browser.start()
    try:
        deadline = time.time() + 10
        while time.time() < deadline and not found:
            time.sleep(0.2)
        assert found, "advertised service was not discovered within 10s"
        assert found[0].fingerprint == FP
        assert found[0].name == "TEST-PC"
        assert found[0].port == 53421
    finally:
        browser.stop()
        advertiser.stop()


def test_own_advertisement_is_ignored():
    found = []
    advertiser = Advertiser(FP, "TEST-PC", "windows", 53421)
    browser = Browser(on_found=found.append, on_lost=lambda fp: None,
                      ignore_fingerprint=FP)
    advertiser.start()
    browser.start()
    try:
        time.sleep(3)
        assert found == []
    finally:
        browser.stop()
        advertiser.stop()
```

These two tests use real multicast on the loopback interface and are the only
tests in the suite that touch the network stack. If a corporate firewall blocks
mDNS they will fail for environmental reasons; mark them `@pytest.mark.network`
rather than deleting them.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd desktop && python -m pytest tests/test_discovery.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'airgrab.discovery'`

- [ ] **Step 3: Write the implementation**

`desktop/airgrab/discovery.py`:

```python
"""Zero-configuration peer discovery over mDNS.

Devices advertise their fingerprint, name, platform and port; the fingerprint
in the TXT record is a hint for the UI only. It is never trusted — identity is
established by the signed-nonce exchange in auth.py once a connection opens.
"""

from __future__ import annotations

import socket
from dataclasses import dataclass
from typing import Callable

from zeroconf import ServiceBrowser, ServiceInfo, ServiceListener, Zeroconf

from airgrab.protocol import PROTOCOL_VERSION

SERVICE_TYPE = "_airgrab._tcp.local."


@dataclass(frozen=True)
class DiscoveredPeer:
    fingerprint: str
    name: str
    platform: str
    host: str
    port: int


class Advertiser:
    def __init__(self, fingerprint: str, name: str, platform: str, port: int) -> None:
        self._zeroconf: Zeroconf | None = None
        self._info = ServiceInfo(
            SERVICE_TYPE,
            f"{fingerprint[:16]}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(_local_address())],
            port=port,
            properties={
                "v": str(PROTOCOL_VERSION),
                "id": fingerprint,
                "name": name,
                "plat": platform,
            },
        )

    def start(self) -> None:
        self._zeroconf = Zeroconf()
        self._zeroconf.register_service(self._info)

    def stop(self) -> None:
        if self._zeroconf is not None:
            self._zeroconf.unregister_service(self._info)
            self._zeroconf.close()
            self._zeroconf = None


class Browser(ServiceListener):
    def __init__(
        self,
        on_found: Callable[[DiscoveredPeer], None],
        on_lost: Callable[[str], None],
        ignore_fingerprint: str,
    ) -> None:
        self._on_found = on_found
        self._on_lost = on_lost
        self._ignore = ignore_fingerprint
        self._peers: dict[str, DiscoveredPeer] = {}
        self._zeroconf: Zeroconf | None = None
        self._browser: ServiceBrowser | None = None

    def start(self) -> None:
        self._zeroconf = Zeroconf()
        self._browser = ServiceBrowser(self._zeroconf, SERVICE_TYPE, self)

    def stop(self) -> None:
        if self._zeroconf is not None:
            self._zeroconf.close()
            self._zeroconf = None
            self._browser = None

    def peers(self) -> list[DiscoveredPeer]:
        return list(self._peers.values())

    def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        info = zc.get_service_info(type_, name)
        if info is None:
            return
        props = {
            key.decode(): value.decode()
            for key, value in (info.properties or {}).items()
            if key is not None and value is not None
        }
        fingerprint = props.get("id", "")
        if not fingerprint or fingerprint == self._ignore:
            return
        addresses = info.parsed_addresses()
        if not addresses:
            return
        peer = DiscoveredPeer(
            fingerprint=fingerprint,
            name=props.get("name", "Unknown"),
            platform=props.get("plat", "unknown"),
            host=addresses[0],
            port=info.port or 0,
        )
        self._peers[name] = peer
        self._on_found(peer)

    def update_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        self.add_service(zc, type_, name)

    def remove_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        peer = self._peers.pop(name, None)
        if peer is not None:
            self._on_lost(peer.fingerprint)


def _local_address() -> str:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()
```

`_local_address` opens a UDP socket to a public address to learn which local
interface would be used. No packet is sent — UDP `connect` only sets a default
route — so this works with no internet connection.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd desktop && python -m pytest tests/test_discovery.py -v`
Expected: 3 passed

- [ ] **Step 5: Commit**

```bash
git add desktop/airgrab/discovery.py desktop/tests/test_discovery.py
git commit -m "feat(discovery): mDNS advertisement and browsing"
```

---

### Task 8: Loopback milestone — full end-to-end flow

**Files:**
- Test: `desktop/tests/test_loopback.py`

**Interfaces:**
- Consumes: `Node`, `NodeConfig`, `Advertiser`, `Browser`.
- Produces: nothing. This task adds no production code — it is the gate that proves Tasks 1–7 form a working system.

- [ ] **Step 1: Write the end-to-end test**

`desktop/tests/test_loopback.py`:

```python
"""The milestone: two independent devices, discovered, paired, transferring.

Everything before this task is scaffolding. If this passes, the desktop half
of AirGrab works, and the Android app has a proven peer to be written against.
"""

import asyncio
import hashlib
from pathlib import Path

from airgrab.node import Node, NodeConfig


def _config(root: Path, name: str) -> NodeConfig:
    return NodeConfig(
        data_dir=root / name,
        download_dir=root / name / "downloads",
        display_name=name,
        port=0,
        auto_accept=True,
    )


async def test_full_lifecycle_pair_then_transfer_both_directions(tmp_path: Path):
    pc = Node(_config(tmp_path, "DESKTOP"))
    phone = Node(_config(tmp_path, "PHONE-SIM"))
    await pc.start()
    await phone.start()

    to_phone: list[Path] = []
    to_pc: list[Path] = []
    phone.on_incoming_file = to_phone.append
    pc.on_incoming_file = to_pc.append

    codes: list[str] = []

    try:
        # 1. Pairing: one exchange grants mutual trust.
        paired = await pc.pair_with(
            "127.0.0.1", phone.port, on_sas=lambda s: (codes.append(s), True)[1]
        )
        assert paired is True
        assert len(codes[0]) == 6
        assert pc.trust.is_trusted(phone.identity.fingerprint)
        assert phone.trust.is_trusted(pc.identity.fingerprint)

        # 2. PC to phone.
        outgoing = tmp_path / "report.pdf"
        outgoing.write_bytes(b"%PDF-1.4 pretend" * 5000)
        assert await pc.send_file("127.0.0.1", phone.port, outgoing) is True
        assert len(to_phone) == 1
        assert to_phone[0].read_bytes() == outgoing.read_bytes()

        # 3. Phone to PC, on the same pairing, no re-pairing needed.
        incoming = tmp_path / "photo.jpg"
        incoming.write_bytes(bytes(range(256)) * 4000)
        assert await phone.send_file("127.0.0.1", pc.port, incoming) is True
        assert len(to_pc) == 1
        assert hashlib.sha256(to_pc[0].read_bytes()).hexdigest() == \
            hashlib.sha256(incoming.read_bytes()).hexdigest()

    finally:
        await pc.stop()
        await phone.stop()


async def test_stranger_cannot_transfer_without_pairing(tmp_path: Path):
    pc = Node(_config(tmp_path, "DESKTOP"))
    stranger = Node(_config(tmp_path, "STRANGER"))
    await pc.start()
    await stranger.start()

    landed: list[Path] = []
    pc.on_incoming_file = landed.append

    payload = tmp_path / "malware.exe"
    payload.write_bytes(b"definitely not malware")

    try:
        assert await stranger.send_file("127.0.0.1", pc.port, payload) is False
        assert landed == []
    finally:
        await pc.stop()
        await stranger.stop()


async def test_concurrent_transfers_do_not_corrupt_each_other(tmp_path: Path):
    pc = Node(_config(tmp_path, "DESKTOP"))
    phone = Node(_config(tmp_path, "PHONE-SIM"))
    await pc.start()
    await phone.start()

    landed: list[Path] = []
    phone.on_incoming_file = landed.append

    try:
        await pc.pair_with("127.0.0.1", phone.port, on_sas=lambda s: True)

        files = []
        for index in range(4):
            path = tmp_path / f"file{index}.bin"
            path.write_bytes(bytes([index]) * (200_000 + index))
            files.append(path)

        results = await asyncio.gather(
            *(pc.send_file("127.0.0.1", phone.port, f) for f in files)
        )
        assert all(results)
        assert len(landed) == 4

        by_name = {p.name: p for p in landed}
        for original in files:
            assert by_name[original.name].read_bytes() == original.read_bytes()

    finally:
        await pc.stop()
        await phone.stop()
```

- [ ] **Step 2: Run the milestone test**

Run: `cd desktop && python -m pytest tests/test_loopback.py -v`
Expected: 3 passed

If `test_concurrent_transfers_do_not_corrupt_each_other` fails, the cause is
almost certainly the module-level `_pending_names` / `_pending_sockets`
dictionaries in `node.py` being shared across instances. Move them into
`__init__` as instance attributes. This is a real defect the test exists to
catch, not a test problem.

- [ ] **Step 3: Run the whole suite**

Run: `cd desktop && python -m pytest -v`
Expected: all tests pass across all seven test files.

- [ ] **Step 4: Commit**

```bash
git add desktop/tests/test_loopback.py
git commit -m "test(loopback): end-to-end pairing and bidirectional transfer milestone"
```

---

### Task 9: Configuration, Windows integration, and tray UI

**Files:**
- Create: `desktop/airgrab/config.py`
- Create: `desktop/airgrab/windows.py`
- Create: `desktop/airgrab/app.py`
- Create: `desktop/airgrab/ui/__init__.py`
- Create: `desktop/airgrab/ui/tray.py`
- Test: `desktop/tests/test_config.py`

**Interfaces:**
- Consumes: `Node`, `NodeConfig`, `Advertiser`, `Browser`, `DiscoveredPeer`.
- Produces: `Settings` dataclass with `.display_name`, `.download_dir`, `.auto_accept`, `.autostart`, `.port`; `load_settings(path) -> Settings`; `save_settings(path, settings) -> None`; `default_data_dir() -> Path`; `ensure_firewall_rule() -> bool`; `set_autostart(enabled: bool) -> None`; `AirGrabApp` with `.run()`.

- [ ] **Step 1: Write the failing config test**

`desktop/tests/test_config.py`:

```python
from pathlib import Path

from airgrab.config import Settings, load_settings, save_settings


def test_missing_file_yields_documented_defaults(tmp_path: Path):
    settings = load_settings(tmp_path / "settings.json")
    assert settings.auto_accept is True
    assert settings.port == 53421
    assert settings.display_name


def test_settings_round_trip(tmp_path: Path):
    path = tmp_path / "settings.json"
    original = Settings(
        display_name="MY-PC",
        download_dir=tmp_path / "drop",
        auto_accept=False,
        autostart=True,
        port=51000,
    )
    save_settings(path, original)
    assert load_settings(path) == original


def test_corrupt_settings_fall_back_to_defaults(tmp_path: Path):
    path = tmp_path / "settings.json"
    path.write_text("{ not valid json")
    assert load_settings(path).port == 53421
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd desktop && python -m pytest tests/test_config.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'airgrab.config'`

- [ ] **Step 3: Write the configuration module**

`desktop/airgrab/config.py`:

```python
"""User settings, with defaults matching spec section 13.

A corrupt settings file falls back to defaults rather than refusing to start:
the cost of a wrong setting is an inconvenience, the cost of not launching is
a broken product.
"""

from __future__ import annotations

import json
import os
import platform
from dataclasses import dataclass, replace
from pathlib import Path

DEFAULT_PORT = 53421


@dataclass(frozen=True)
class Settings:
    display_name: str
    download_dir: Path
    auto_accept: bool = True
    autostart: bool = False
    port: int = DEFAULT_PORT


def default_data_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA") or str(Path.home() / ".local" / "share")
    return Path(base) / "AirGrab"


def _defaults() -> Settings:
    return Settings(
        display_name=platform.node() or "Windows PC",
        download_dir=Path.home() / "Downloads" / "AirGrab",
        auto_accept=True,
        autostart=False,
        port=DEFAULT_PORT,
    )


def load_settings(path: Path) -> Settings:
    defaults = _defaults()
    path = Path(path)
    if not path.exists():
        return defaults
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        return replace(
            defaults,
            display_name=str(raw.get("display_name", defaults.display_name)),
            download_dir=Path(raw.get("download_dir", str(defaults.download_dir))),
            auto_accept=bool(raw.get("auto_accept", defaults.auto_accept)),
            autostart=bool(raw.get("autostart", defaults.autostart)),
            port=int(raw.get("port", defaults.port)),
        )
    except (json.JSONDecodeError, TypeError, ValueError, OSError):
        return defaults


def save_settings(path: Path, settings: Settings) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "display_name": settings.display_name,
                "download_dir": str(settings.download_dir),
                "auto_accept": settings.auto_accept,
                "autostart": settings.autostart,
                "port": settings.port,
            },
            indent=2,
        ),
        encoding="utf-8",
    )
```

- [ ] **Step 4: Run the config tests to verify they pass**

Run: `cd desktop && python -m pytest tests/test_config.py -v`
Expected: 3 passed

- [ ] **Step 5: Write the Windows integration module**

`desktop/airgrab/windows.py`:

```python
"""Windows-specific integration: firewall and autostart.

Spec section 8 requires that a blocked firewall be detected and fixable in one
click rather than presenting as "no devices found". Rule creation needs
elevation, so it is attempted once and its failure is reported, never hidden.
"""

from __future__ import annotations

import subprocess
import sys
import winreg
from pathlib import Path

RULE_NAME = "AirGrab"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
_NO_WINDOW = 0x08000000


def firewall_rule_exists() -> bool:
    result = subprocess.run(
        ["netsh", "advfirewall", "firewall", "show", "rule", f"name={RULE_NAME}"],
        capture_output=True,
        text=True,
        creationflags=_NO_WINDOW,
    )
    return result.returncode == 0


def ensure_firewall_rule(port: int) -> bool:
    """Create the inbound rule. Returns False if elevation was refused."""
    if firewall_rule_exists():
        return True
    result = subprocess.run(
        [
            "netsh", "advfirewall", "firewall", "add", "rule",
            f"name={RULE_NAME}", "dir=in", "action=allow",
            "protocol=TCP", f"localport={port}",
        ],
        capture_output=True,
        text=True,
        creationflags=_NO_WINDOW,
    )
    return result.returncode == 0


def set_autostart(enabled: bool) -> None:
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            target = f'"{Path(sys.executable).resolve()}"'
            winreg.SetValueEx(key, RULE_NAME, 0, winreg.REG_SZ, target)
        else:
            try:
                winreg.DeleteValue(key, RULE_NAME)
            except FileNotFoundError:
                pass
```

- [ ] **Step 6: Write the tray UI and application entry point**

`desktop/airgrab/ui/__init__.py` — empty file.

`desktop/airgrab/ui/tray.py`:

```python
"""System tray presence.

The app has no main window in normal operation: it is a background service
with a menu. Everything the user needs day to day — who is connected, where
files land, whether it is working — has to be legible from the tray.
"""

from __future__ import annotations

from typing import Callable

import pystray
from PIL import Image, ImageDraw


def _icon_image(connected: bool) -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    fill = (46, 160, 67, 255) if connected else (110, 118, 129, 255)
    draw.ellipse((8, 8, 56, 56), fill=fill)
    draw.polygon([(32, 18), (44, 34), (36, 34), (36, 46), (28, 46), (28, 34), (20, 34)],
                 fill=(255, 255, 255, 255))
    return image


class Tray:
    def __init__(
        self,
        on_open_downloads: Callable[[], None],
        on_pair: Callable[[], None],
        on_quit: Callable[[], None],
    ) -> None:
        self._status = "Starting..."
        self._icon = pystray.Icon(
            "airgrab",
            _icon_image(False),
            "AirGrab",
            menu=pystray.Menu(
                pystray.MenuItem(lambda item: self._status, None, enabled=False),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Pair a device...", lambda: on_pair()),
                pystray.MenuItem("Open received files", lambda: on_open_downloads()),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Quit", lambda: on_quit()),
            ),
        )

    def set_status(self, text: str, connected: bool) -> None:
        self._status = text
        self._icon.icon = _icon_image(connected)
        self._icon.title = f"AirGrab — {text}"
        self._icon.update_menu()

    def notify(self, message: str) -> None:
        self._icon.notify(message, "AirGrab")

    def run(self) -> None:
        self._icon.run()

    def stop(self) -> None:
        self._icon.stop()
```

`desktop/airgrab/app.py`:

```python
"""Application entry point: wires the node, discovery, and tray together."""

from __future__ import annotations

import asyncio
import os
import threading
from pathlib import Path

from airgrab.config import default_data_dir, load_settings
from airgrab.discovery import Advertiser, Browser, DiscoveredPeer
from airgrab.node import Node, NodeConfig
from airgrab.ui.tray import Tray
from airgrab.windows import ensure_firewall_rule


class AirGrabApp:
    def __init__(self) -> None:
        self._data_dir = default_data_dir()
        self._settings = load_settings(self._data_dir / "settings.json")
        self._peers: dict[str, DiscoveredPeer] = {}
        self._loop = asyncio.new_event_loop()

        self._node = Node(
            NodeConfig(
                data_dir=self._data_dir,
                download_dir=self._settings.download_dir,
                display_name=self._settings.display_name,
                port=self._settings.port,
                auto_accept=self._settings.auto_accept,
            )
        )
        self._node.on_incoming_file = self._on_file_received

        self._tray = Tray(
            on_open_downloads=self._open_downloads,
            on_pair=self._show_pairing_hint,
            on_quit=self.stop,
        )
        self._advertiser: Advertiser | None = None
        self._browser: Browser | None = None

    def run(self) -> None:
        threading.Thread(target=self._run_loop, daemon=True).start()
        self._tray.run()

    def _run_loop(self) -> None:
        asyncio.set_event_loop(self._loop)
        self._loop.run_until_complete(self._start_services())
        self._loop.run_forever()

    async def _start_services(self) -> None:
        await self._node.start()

        if not ensure_firewall_rule(self._node.port):
            self._tray.set_status(
                "Firewall is blocking AirGrab — run once as administrator", False
            )
        else:
            self._tray.set_status("Waiting for devices", False)

        self._advertiser = Advertiser(
            self._node.identity.fingerprint,
            self._settings.display_name,
            "windows",
            self._node.port,
        )
        self._advertiser.start()

        self._browser = Browser(
            on_found=self._on_peer_found,
            on_lost=self._on_peer_lost,
            ignore_fingerprint=self._node.identity.fingerprint,
        )
        self._browser.start()

    def _on_peer_found(self, peer: DiscoveredPeer) -> None:
        self._peers[peer.fingerprint] = peer
        trusted = self._node.trust.is_trusted(peer.fingerprint)
        label = f"Connected to {peer.name}" if trusted else f"{peer.name} found — not paired"
        self._tray.set_status(label, trusted)

    def _on_peer_lost(self, fingerprint: str) -> None:
        self._peers.pop(fingerprint, None)
        if not self._peers:
            self._tray.set_status("No devices found — check both are on the same Wi-Fi", False)

    def _on_file_received(self, path: Path) -> None:
        self._tray.notify(f"Received {path.name}")

    def _open_downloads(self) -> None:
        self._settings.download_dir.mkdir(parents=True, exist_ok=True)
        os.startfile(str(self._settings.download_dir))

    def _show_pairing_hint(self) -> None:
        if not self._peers:
            self._tray.notify("No devices found. Make sure both are on the same Wi-Fi.")
            return
        peer = next(iter(self._peers.values()))
        future = asyncio.run_coroutine_threadsafe(
            self._node.pair_with(peer.host, peer.port, on_sas=self._confirm_sas),
            self._loop,
        )
        future.add_done_callback(
            lambda f: self._tray.notify(
                f"Paired with {peer.name}" if f.result() else "Pairing cancelled"
            )
        )

    def _confirm_sas(self, sas: str) -> bool:
        import tkinter
        from tkinter import messagebox

        root = tkinter.Tk()
        root.withdraw()
        answer = messagebox.askyesno(
            "AirGrab pairing",
            f"Does the other device show this code?\n\n        {sas}\n\n"
            "Only accept if the codes match exactly.",
        )
        root.destroy()
        return bool(answer)

    def stop(self) -> None:
        if self._browser is not None:
            self._browser.stop()
        if self._advertiser is not None:
            self._advertiser.stop()
        asyncio.run_coroutine_threadsafe(self._node.stop(), self._loop)
        self._tray.stop()


def main() -> None:
    AirGrabApp().run()


if __name__ == "__main__":
    main()
```

- [ ] **Step 7: Run the whole suite once more**

Run: `cd desktop && python -m pytest -v`
Expected: all tests pass. The tray and app modules have no automated tests — they are thin wiring over tested components, and testing `pystray` meaningfully requires a desktop session.

- [ ] **Step 8: Commit**

```bash
git add desktop/airgrab/config.py desktop/airgrab/windows.py desktop/airgrab/app.py desktop/airgrab/ui desktop/tests/test_config.py
git commit -m "feat(app): settings, firewall and autostart integration, tray UI"
```

---

## What this plan does not cover

Recorded so the omissions are deliberate rather than forgotten:

- **The Android application.** A separate plan, written after this one is proven, translating Tasks 1–6 into Kotlin against `protocol/PROTOCOL.md` and `protocol/vectors/`.
- **PyInstaller packaging and code signing.** Belongs with the Android work, when there is a complete product to package.
- **Everything in spec section 2's non-goals** — resume, fan-out, off-LAN, history, folders.
- **The gesture layer.** Sub-projects 2 and 3.

## Verification status

The project owner has elected to defer execution. Every task above includes
its run commands and expected output; none has been executed. Until the suite
in Task 8 has actually run and passed, this code is **complete but
unverified**, and no claim that AirGrab works should be made on its behalf.

The desktop half needs only Python — no phone, no Android Studio, no cable —
so running Task 8 is cheap the moment there is appetite for it.
