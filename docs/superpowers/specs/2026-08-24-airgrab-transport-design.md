# AirGrab — Sub-project 1: Transport & Pairing

**Date:** 2026-08-24
**Status:** Approved design, not yet implemented
**Scope:** Sub-project 1 of 4. Foundation layer only — no gesture recognition.

---

## 1. Purpose

AirGrab is a gesture-driven bidirectional file transfer system between an
iQOO Neo 10 (OriginOS 6 / Android 16) and a Windows 11 desktop, replicating
the interaction model of Huawei's 隔空传送 (air-gesture transfer).

This document specifies **only the transport and pairing foundation**. Files
move between devices via an explicit button press. The gesture layer replaces
that button in a later sub-project.

### Why this layer first

The gesture layer is a *trigger*. Underneath it, something must reliably
discover the peer device, prove mutual identity, and move bytes without
corruption. If that foundation is unreliable, gesture failures and transport
failures become indistinguishable during debugging. This layer is also
independently useful: it is a working phone-to-PC transfer tool on its own.

### Roadmap context

| # | Sub-project | Status |
|---|---|---|
| 1 | Transport & pairing | **This document** |
| 2 | Gesture engine (MediaPipe palm/fist state machine) | Not started |
| 3 | Cross-device gesture correlation | Not started |
| 4 | Product shell (content capture, tray UX, installer) | Not started |

---

## 2. Goals and non-goals

### Goals

- Two devices on the same Wi-Fi network discover each other with zero configuration.
- A one-time human-verified pairing establishes permanent mutual trust.
- Files transfer in either direction with integrity verification.
- Transfers report real progress, not estimated progress.
- Failures are surfaced with an actionable cause, never silently.
- The Android background service survives OriginOS 6 power management.

### Non-goals for v1

Deliberately excluded. Each is cheap to add later and none is needed to
validate the foundation:

- Transfer resume after interruption
- Fan-out to multiple receivers simultaneously
- Internet / off-LAN transfers
- Transfer history and re-send
- Folder and multi-file transfers
- Peer-to-peer without an access point (Wi-Fi Direct)

---

## 3. Terminology

| Term | Meaning |
|---|---|
| **Device ID** | SHA-256 fingerprint of a device's TLS certificate. Permanent, unforgeable. |
| **Peer** | Another AirGrab device visible on the network. |
| **Trusted peer** | A peer whose Device ID is in the local trust store. |
| **Control channel** | Persistent WebSocket carrying small coordination messages. |
| **Data channel** | Per-transfer HTTPS stream carrying file bytes. |
| **Ticket** | Single-use, short-lived token authorising one data-channel upload. |
| **SAS** | Short Authentication String — the 6-digit pairing code. |

---

## 4. Architecture

Both platforms run the same five components. There is no client/server
asymmetry: every device both listens and initiates. This is what makes
bidirectional transfer free rather than double the work.

```
+------------------------------------------+
|  Identity    keypair + certificate       |
|              generated once, on disk     |
+------------------------------------------+
|  Trust       set of trusted Device IDs   |
+------------------------------------------+
|  Discovery   mDNS advertise + browse     |
+------------------------------------------+
|  Control     WSS server + client         |
+------------------------------------------+
|  Transfer    HTTPS upload endpoint       |
|              + streaming uploader        |
+------------------------------------------+
```

### Why a split control/data channel

An always-open control channel plus a per-transfer data channel is chosen
over simple request/response (LocalSend-style) for one concrete, already-known
reason: sub-project 3 requires sub-second exchange of gesture events between
devices. Re-establishing a connection per event would add unacceptable
latency. Building request/response now would require replacing the foundation
in phase 3.

Secondary benefits, which are real but were not the deciding factor: bulk
transfers cannot stall coordination messages, progress reporting and
cancellation become trivial, and peer disconnection is detected immediately
rather than on next use.

---

## 5. Components

### 5.1 Identity

On first launch a device generates a P-256 keypair and a self-signed X.509
certificate. The certificate is used for all TLS on both channels. The
device's identity is the SHA-256 fingerprint of the DER-encoded certificate.

- Common Name is the user-visible device name and is **not** trusted for
  identity — only the fingerprint is.
- Key material is stored in the Android Keystore on the phone and in a
  user-scoped app data directory on Windows.
- Regenerating identity invalidates all pairings by design.

### 5.2 Discovery

mDNS/DNS-SD, service type `_airgrab._tcp.local.`

TXT records:

| Key | Value |
|---|---|
| `v` | Protocol version, currently `1` |
| `id` | Device ID (hex SHA-256 fingerprint) |
| `name` | User-visible device name |
| `plat` | `android` or `windows` |

Port is carried in the SRV record. Default TCP port **53421**, with automatic
fallback to an ephemeral port if occupied.

Peers are considered live while their mDNS record is unexpired and are
removed from the UI when it lapses.

**Android requirement:** mDNS requires a `WifiManager.MulticastLock` held
while browsing. Without it, discovery silently returns nothing — a failure
mode with no error message, so this is not optional.

### 5.3 Trust

A flat list of trusted Device IDs with their last-known display names,
persisted locally. A control-channel connection from an untrusted Device ID
is accepted only for the pairing exchange and is otherwise closed.

Trust is symmetric but stored independently: both devices record the other
during pairing. Removing trust on one side alone leaves a stale entry on the
other, which is acceptable — the connection will simply be refused.

### 5.4 Control channel

WebSocket over TLS at `wss://<host>:<port>/control`.

Every device runs a control server and connects out as a control client. A
connection is opened as soon as a **trusted** peer is discovered — not lazily
on first transfer — and is held open for as long as that peer remains visible.
Holding it open is the point: phase 3 gesture events cannot afford connection
setup latency. Application-level ping/pong every 15 seconds; a peer is
declared lost after 45 seconds of silence.

**Identity is established at the application layer, not by TLS.** Mutual TLS
with self-signed certificates requires rebuilding the trust store on every
pairing and cannot cleanly admit an unpaired device for its first handshake.
Instead TLS provides confidentiality only, and both sides prove possession of
their private key by signing a nonce chosen by the other.

Two rules make this secure, and both are load-bearing:

1. **A peer's fingerprint is always derived from the certificate it presented,
   never read from a message field.** An `id` in a payload is a claim an
   attacker can copy; a signature over a certificate is not.
2. **Known peers are pinned.** A trusted peer must present the exact
   certificate recorded at pairing time. A new peer is not pinned, and the SAS
   comparison covers that case instead — a machine-in-the-middle must present
   its own certificate to each side, producing two different six-digit codes.

An earlier revision of this spec specified mutual TLS and, in the
implementation plan, a one-directional signed nonce with a self-declared
server fingerprint. That scheme was insecure: an attacker could claim the
server's fingerprint and relay the client's signature onward, holding an
undetected position in the middle. The scheme above replaces it.

All messages are JSON objects with a common envelope:

```json
{ "type": "<message type>", "seq": 42, "payload": { } }
```

`seq` is a monotonic per-connection counter used to correlate replies.

### 5.5 Data channel

`POST https://<host>:<port>/transfer/<ticket>`

The request body is the raw file bytes, streamed. The receiver already knows
the filename, size, and hash from the control-channel offer, so no multipart
encoding or metadata parsing is needed.

Tickets are 256 bits of cryptographic randomness, valid for 60 seconds, and
single-use. A ticket is bound to the Device ID that was offered it; a request
presenting a valid ticket over a TLS session with a different client
certificate is rejected.

---

## 6. Protocol messages

Version 1. Both implementations must obey this list exactly; it is the single
source of truth shared between the Kotlin and Python codebases.

### Session establishment

| Type | Direction | Payload |
|---|---|---|
| `hello` | initiator to peer | `{ v, id, name, plat }` |
| `hello_ack` | peer to initiator | `{ v, id, name, plat, trusted }` |

If either side reports an incompatible `v`, the connection closes with
`error` code `version_mismatch`.

### Pairing

| Type | Direction | Payload |
|---|---|---|
| `pair_request` | initiator to peer | `{}` |
| `pair_challenge` | peer to initiator | `{ sas }` |
| `pair_confirm` | initiator to peer | `{ accepted }` |
| `pair_result` | peer to initiator | `{ accepted }` |

The SAS is computed identically and independently by both devices:

```
sas = decimal( SHA-256( sorted([fingerprint_a, fingerprint_b]) joined )[0:4] ) mod 1000000
```

rendered as six digits, zero-padded.

Because the SAS derives from both certificate fingerprints, an attacker
presenting different certificates to each side produces different codes on
each screen, and the human comparison fails. This is what the code is for; it
is not a password.

Trust is written on both sides only after both `pair_confirm` and
`pair_result` carry `accepted: true`.

### Transfer

| Type | Direction | Payload |
|---|---|---|
| `offer` | sender to receiver | `{ name, size, mime, sha256 }` |
| `offer_accept` | receiver to sender | `{ ticket }` |
| `offer_reject` | receiver to sender | `{ reason }` |
| `progress` | receiver to sender | `{ received, total }` |
| `complete` | receiver to sender | `{ ok, path }` — `path` present only when `ok` is true |
| `cancel` | either | `{ reason }` |
| `error` | either | `{ code, message }` |

`progress` is emitted by the **receiver**, not the sender, at most every 250 ms.
Reporting bytes actually written to disk rather than bytes handed to the
network stack is what makes the progress bar truthful.

### Error codes

`version_mismatch`, `not_trusted`, `ticket_invalid`, `ticket_expired`,
`hash_mismatch`, `storage_full`, `storage_denied`, `transfer_aborted`,
`internal`.

---

## 7. Flows

### 7.1 Pairing

1. Both apps are open. Each lists the other under "Available devices".
2. User taps the peer on either device.
3. Initiator opens the control channel, sends `hello`, receives `hello_ack`
   with `trusted: false`.
4. Initiator sends `pair_request`.
5. Both devices compute the SAS and display the same six digits.
6. Each user confirms the codes match.
7. `pair_confirm` / `pair_result` exchange completes; both trust stores are written.

Pairing is the only point at which human interaction is required. Thereafter
devices connect silently.

### 7.2 Sending a file

1. Sender opens or reuses the control channel to a trusted peer.
2. Sender hashes the file and sends `offer`.
3. Receiver either auto-accepts (trusted peer, auto-accept enabled) or prompts.
4. Receiver allocates a ticket, opens a temporary file, replies `offer_accept`.
5. Sender streams bytes to `POST /transfer/<ticket>`.
6. Receiver writes to the temporary file, emitting `progress`.
7. On completion the receiver verifies the SHA-256 against the offer.
   - Match: file is atomically renamed into its final location; `complete` with `ok: true`.
   - Mismatch: temporary file deleted; `error` with `hash_mismatch`.

**The temporary-file-then-rename step is required, not an optimisation.** It
guarantees a file visible in the destination folder is complete and verified.
A partially written file is never observable under its real name.

### 7.3 Cancellation

Either side may send `cancel` at any point. The sender aborts the HTTP stream;
the receiver deletes the temporary file. Both return to idle.

---

## 8. Error handling

The behaviour in this table is what distinguishes a usable daily driver from
a demo. Every row is a failure a real user will hit.

| Condition | Detection | Behaviour |
|---|---|---|
| Peer on a different network | mDNS returns nothing while Wi-Fi is connected | Show "No devices found — check both are on the same Wi-Fi", not an empty list |
| Windows Firewall blocking | Listener bound but no inbound connections; firewall rule absent | Startup check with a one-click rule-creation action and an explanation |
| Android service killed by OriginOS | Service restart detected without user action | Persistent notification explains battery whitelisting, deep-links to settings |
| Peer disappears mid-transfer | Control channel ping timeout | Abort, delete temp file, report "Device went offline" |
| Destination filename collision | Stat before rename | Auto-rename to `name (2).ext`; never overwrite |
| Insufficient storage | Pre-flight check against offered size | Reject offer with `storage_full` before any bytes move |
| Hash mismatch on completion | Post-transfer verification | Delete temp file, report corruption explicitly |
| Untrusted peer connects | `hello` fingerprint not in trust store | Allow pairing messages only; refuse transfer with `not_trusted` |
| Clock skew | — | No effect. Ticket expiry uses each device's own monotonic clock, not wall time. |

---

## 9. Platform specifics

### 9.1 Android (Kotlin, minSdk 31, targetSdk 36)

**Foreground service type: `connectedDevice`.**

This is a load-bearing decision. The intuitive choice, `dataSync`, carries a
**6-hour-per-24-hour cap** introduced in Android 15; the service would be
terminated by the system each day with no user-visible cause.
`connectedDevice` accurately describes a persistent link to a paired device
and carries no such timeout.

Permissions:

| Permission | Reason |
|---|---|
| `INTERNET` | Sockets |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Network change detection |
| `CHANGE_WIFI_MULTICAST_STATE` | Multicast lock for mDNS |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Persistent service |
| `POST_NOTIFICATIONS` | Transfer status |

Received files are written via `MediaStore` into the Downloads collection,
which requires no storage permission on Android 10+ and keeps files visible
to the system file manager.

OriginOS 6 additionally requires the user to grant battery-optimisation
exemption and autostart permission manually. These cannot be requested
programmatically on vivo builds; the app must detect the likely-killed state
and guide the user to the correct settings screen.

**Build note:** MediaPipe and any other native dependency must be
16 KB-page-size aligned for Android 15+. Pin a verified version at project
setup rather than discovering this at packaging time. This affects
sub-project 2 but the constraint is recorded here.

### 9.2 Windows (Python 3.11+)

- System tray application; no main window in normal operation.
- Packaged with PyInstaller into a single signed executable.
- Firewall rule created on first run via an elevated helper, once.
- Autostart via a registry `Run` entry, user-scoped, toggleable in settings.
- Received files land in a configurable folder, defaulting to `Downloads/AirGrab`.

---

## 10. Project structure

```
airgrab/
+-- docs/superpowers/specs/          design documents
+-- protocol/
|   +-- PROTOCOL.md                  section 6, authoritative
|   +-- vectors/                     shared test fixtures (SAS, hashes, envelopes)
+-- desktop/
|   +-- airgrab/
|   |   +-- identity.py              keypair, certificate, fingerprint
|   |   +-- trust.py                 trust store
|   |   +-- discovery.py             mDNS advertise + browse
|   |   +-- control.py               WSS server + client, message dispatch
|   |   +-- transfer.py              upload + receive endpoint
|   |   +-- config.py                settings persistence
|   |   +-- ui/                      tray, pairing dialog, progress
|   +-- tests/
+-- android/
    +-- app/src/main/java/com/airgrab/
        +-- identity/
        +-- trust/
        +-- discovery/
        +-- control/
        +-- transfer/
        +-- service/                 connectedDevice foreground service
        +-- ui/
```

`protocol/` is authoritative. Two independently written implementations drift
without a single shared definition, and the resulting incompatibilities
surface as unexplainable runtime failures rather than build errors. Shared
test vectors let each side verify conformance without the other present.

Each module has one responsibility and a defined interface. `control.py` does
not know what a file is; `transfer.py` does not know what a peer is.

---

## 11. Testing strategy

The project owner has elected to defer execution; the code will be written
before it is run. That raises rather than lowers the value of the following,
which should be written alongside the implementation:

- **Protocol conformance tests** on both sides against `protocol/vectors/` —
  SAS derivation, envelope encoding, hash computation. These catch cross-language
  drift without needing two devices.
- **Loopback integration test** on the desktop side: one process pairs with and
  transfers to another on `127.0.0.1`. Exercises the full flow with no phone.
- **Failure injection**: truncated uploads, wrong hashes, expired tickets,
  untrusted fingerprints. Each error-handling row in section 8 gets a test.
- **Manual verification checklist** for the phone, to be run on the first day
  hardware is available.

Until executed, all code is to be treated as complete but unverified.

---

## 12. Decisions log

| Decision | Alternative rejected | Reason |
|---|---|---|
| Split control/data channels | LocalSend-style request/response | Phase 3 gesture events need a persistent low-latency channel |
| Self-signed certs + TOFU | CA, or pre-shared key | No infrastructure; fingerprint pinning is stronger than a shared secret |
| SAS from both fingerprints | User-typed password | Detects MITM; nothing to remember or leak |
| `connectedDevice` FGS type | `dataSync` | `dataSync` is capped at 6h/24h since Android 15 |
| Receiver reports progress | Sender reports progress | Sender only knows bytes handed to the OS, not bytes written |
| Temp file then atomic rename | Direct write | Guarantees no observable partial file |
| Python on desktop | .NET, Electron | Best MediaPipe support for phase 2; readable and modifiable by the project owner |
| No resume in v1 | Chunked resumable upload | Significant complexity; LAN transfers rarely interrupt |

---

## 13. Settled defaults

Recorded here so implementation has no discretion:

- **Display name** defaults to the machine hostname on Windows and the device
  model on Android. User-editable in settings; changing it does not affect
  identity, which is the certificate fingerprint.
- **Auto-accept from trusted peers is ON by default**, with a settings toggle.
  This is a two-device personal setup where a confirmation prompt on every
  transfer would be friction without security benefit — pairing already
  established consent, and untrusted peers cannot reach this path at all.
- **Transfer size limit:** none. Pre-flight storage check in section 8 is the
  only gate.

No open questions remain. This spec is implementable as written.
