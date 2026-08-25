# AirGrab — Build Progress

Updated as each layer lands. A layer counts as done when it has been seen
working on real hardware — not when its tests pass. Last night proved the
difference: 151 Kotlin tests were green while the phone could not pair with
the desktop at all.

## Working, verified on hardware

A Moto G85 5G (Android 16) and a Windows 11 desktop, 24 August:

- A gesture moved a photo. Fist at the phone, palm at the webcam, 278,687
  bytes arriving hash-verified.
- TLS server running on Android; the phone can receive, not only send.
- Each found the other over mDNS on the same Wi-Fi.
- Paired, six digits matching on both screens.
- Camera runs in the foreground service, so gestures work with the app
  closed and the screen off.
- Feedback on both devices: haptics and an on-screen indicator.

## Built, not yet exercised on hardware

| Piece | Note |
|-------|------|
| Photo picker and latest-photo grab | Sends the original file, no share step |
| The redesigned screen | HarmonyOS colours, hand indicator, arrived-files list |
| PC → phone | The reverse direction has never been run |
| Phone → phone | Should work on one Wi-Fi; needs two handsets to know |
| Signed release APK | Built before last night's fixes; needs rebuilding |

## Not built

- **Wi-Fi Direct.** Two devices with no router between them cannot currently
  reach each other. The transport is network-agnostic, so this changes only
  discovery and connection setup — but Wi-Fi Direct on Android differs by
  manufacturer and would be the fiddliest thing in the project. A hotspot on
  one device works today.
- **R8 minification.** Left off deliberately: MediaPipe, Netty and Ktor all
  resolve classes by name, and a slightly wrong keep rule builds cleanly then
  fails at runtime inside a library.

## Still unknown

- Whether the app survives a night in the background.
- Whether the gesture timings feel right. A pose must hold for about a
  second, and a grab lasts a minute. Both are guesses until someone uses it.

## Counts

- Kotlin: 160 tests
- Python: 181 tests

Both languages are pinned to shared vectors for the wire format, the pairing
code, the discovery record, and — since it was the one gap that let a live
pairing fail — a real signature made by one and verified by the other.
