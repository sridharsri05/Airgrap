# AirGrab

Move a file between your phone and your PC with a gesture. Close your fist at
one device to pick something up; open your palm at the other to drop it.

Files travel directly over your own Wi-Fi. Nothing is uploaded anywhere, no
account is needed, and the two devices refuse to talk to anything they have not
been paired with.

---

## What you need

- The PC and the phone on **the same Wi-Fi network**
- A webcam on the PC
- Android 12 or newer on the phone

---

## Setting it up

### 1. The PC

Run `AirGrab.exe` **once as administrator** — right-click it and choose *Run as
administrator*. Windows will ask about network access; choose **Allow**.

That one-time step creates the firewall rule that lets your phone reach the PC.
Every launch after this is an ordinary double-click.

AirGrab lives in the system tray, next to the clock. A blue icon means it is
running.

### 2. The phone

Install `app-release.apk`. Android will warn about installing outside the Play
Store — that is expected for an app you built yourself.

Open it once. It asks for two permissions:

- **Notifications** — Android requires one to let the app keep running
- **Camera** — to see your hand. Declining still lets you *receive* files
- **Photos** — so a gesture can send the real file rather than a screenshot

One more is optional, and AirGrab offers it on its own screen: **display over
other apps**, which is what shows the indicator on top of whatever you are
using. Without it you still get the vibration.

### 3. Let it run in the background

**Not optional on most phones.** vivo, Xiaomi, Oppo, Huawei and Samsung all stop
apps they consider idle, and a stopped AirGrab means your PC simply cannot see
the phone. Motorola and Pixel are more relaxed about it.

The app detects your phone's make, shows the exact path, and gives you a button
that opens it. On vivo and iQOO there are two separate settings and **both**
matter:

1. Settings → Battery → Background power consumption management → AirGrab →
   *Allow high background power consumption*
2. Settings → Apps → Special app access → Autostart → turn on AirGrab

### 4. Pair them

Once both are running on the same Wi-Fi, each device shows the other under
**Devices**.

Tap **Pair**. Both screens show a **six-digit code**.

**Compare them.** Only continue if they match. Those six digits are the whole
security check: if someone else on the network were impersonating your PC, the
two screens would show different numbers.

Pairing is remembered, so this happens once.

---

## Using it

### Phone → PC

1. **Make a fist** at the phone's camera — you will feel a tap, and a blue
   indicator names what it picked up
2. **Turn the phone face down**, or pocket it
3. **Open your palm** at the PC's webcam

The file lands in your AirGrab downloads folder, and both screens show it
arriving.

Step 2 matters. If the phone can still see your hand when you open it, the
phone reads that as you changing your mind and nothing is sent. In everyday
use the phone is already in your pocket, so this happens by itself.

**What gets sent** is your most recent photo, at full quality — the actual
file, not a screenshot. Take a photo, make a fist, and it is on your PC.

To send something else, either tap a different photo in AirGrab's strip of
recent ones, or **share** any file to AirGrab from another app. Sharing works
for anything: documents, videos, whatever.

Android will not tell an app which photo another app is showing — that is a
deliberate privacy boundary, and the reason Huawei can do it on their own
phones is that their gesture service is part of the operating system. Hence
the picker.

### PC → phone

1. **Make a fist** at the PC's webcam — it grabs what is on screen
2. **Open your palm** at the phone's camera

The phone keeps watching for gestures while you are in other apps, or with the
screen off. It does not need to be open.

### If you change your mind

Open your hand at the *same* device you grabbed from. Nothing is sent.

A grab that nobody catches expires after a minute.

---

## When it does not work

**The devices cannot see each other.** Both must be on the same Wi-Fi. Guest
networks and "client isolation" on some routers block devices from reaching one
another — that setting is the usual cause.

**The phone disappears after a while.** Almost always the background settings in
step 3.

**The gesture does nothing.** Open the app and tap **Show details**. It shows
what the camera currently thinks your hand is:

```
hand      CLOSED_FIST
gesture   HOLDING
frames    412 of 418
```

If `hand` stays `NONE`, the camera is not seeing your hand — usually too dark,
too close, or out of frame. Roughly 30–50cm away, whole hand in view, held
still for about a second.

If `hand` shows `OTHER`, a hand is visible but the shape is not clear enough to
call. More light usually fixes it.

The PC writes the same information to `%LOCALAPPDATA%\AirGrab\airgrab.log`.

**You grabbed but nothing arrived.** A grab lasts sixty seconds and then
expires — you will feel a long buzz and see an orange indicator. Most often the
other device never saw your open palm, or your own device saw it first.

**A transfer failed.** Files are only visible under their real name once fully
received and verified, so a failed transfer leaves nothing behind. Try again.

---

## What it does not do

- Work over the internet — same Wi-Fi only, by design
- Work with no network at all. Two devices with no router between them would
  need Wi-Fi Direct, which is not built. A hotspot on one of them works today.
- Work from the lock screen
- See a gesture made at a phone lying face down (the camera is covered), which
  is exactly what makes the carry between devices work

---

## For developers

See [`PROGRESS.md`](PROGRESS.md) for build state, [`android/README.md`](android/README.md)
for the Android toolchain, [`desktop/BUILD.md`](desktop/BUILD.md) for packaging
the Windows executable, and [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md) for
the wire format.

Regenerate the application icons for both platforms with:

```
python tools/make_icons.py
```

### A note on security

TLS here provides confidentiality only — certificates are self-signed, so no
authority vouches for them. Identity is established separately: each device
proves it holds the private key behind the fingerprint it claims, by signing a
number chosen by the other and bound to *both* fingerprints. That fingerprint is
then pinned against the trust store.

A device's fingerprint is always derived from the certificate it presented,
never read from a field it filled in itself. A message field is a claim; a
signature over a certificate is proof.
