# AirGrab — Build Progress

Updated as each layer lands. A layer counts as done when its tests pass and
it is committed, not when the code is written.

## Desktop (Windows, Python)

| Layer | State |
|-------|-------|
| Identity, trust store, protocol | done |
| Transport: control channel + file transfer | done |
| Discovery over Wi-Fi | done |
| Camera + hand tracking | done |
| Gesture state machine + coordinator | done |
| Tray app, settings, packaging | done |

Verified: two processes transferred 5MB over the real network with matching
SHA-256; a gesture-driven transfer moved 2,048,000 bytes; the packaged exe
launches and listens on 0.0.0.0:53421.

## Phone (Android, Kotlin)

| Layer | State |
|-------|-------|
| Protocol, trust store, pairing codes | done |
| Gesture state machine + coordinator | done |
| Authentication | done |
| Agreement with the desktop (byte-level) | done |
| File transfer + tickets | done |
| TLS transport foundation | done |
| Device identity | done |
| Connection layer (control channel + transfer endpoint) | done |
| Finding the PC on Wi-Fi | done |
| Background service | done |
| Camera + hand tracking | done |
| Gesture-to-transfer wiring | next |
| Screen | status screen only |

Verified on loopback: two nodes with real certificates paired, showed the
same six digits on both sides, and transferred 700,000 bytes with a matching
SHA-256. An unpaired device was refused; naming the wrong device aborted.

## Waiting on a handset

These cannot be closed without the phone plugged in.

- Whether Netty actually runs a TLS server on Android. The APK builds and
  packages, which is not the same claim. `TlsProbe` logs the verdict.
- Whether the app survives vivo's background process management.
- Whether the phone and the desktop actually see each other over mDNS. Both
  are pinned to one service type and TXT record by a shared vectors file, but
  agreeing on paper is not the same as multicast reaching the handset.
- Every gesture threshold. How long a grab should be, and how much of a hand
  has to be visible, are answers only a real hand in front of a real camera
  can give.

## Counts

- Python: 176 tests
- Kotlin: 146 tests
