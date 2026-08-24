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

### 3. Let it run in the background

**This step is not optional on your iQOO.** vivo's software stops apps it thinks
are idle, and a stopped AirGrab means your PC simply cannot see the phone.

The app shows a warning with the exact path for your phone, and a button that
takes you there. On vivo and iQOO there are two separate settings and **both**
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

1. Find the photo or file on your phone
2. **Share** it → choose **AirGrab**
3. **Make a fist** at the phone's camera
4. **Open your palm** at the PC's webcam

The file lands in your AirGrab downloads folder.

A phone has no single "thing on screen" the way a PC does, so sharing is how you
point at what you mean.

### PC → phone

1. **Make a fist** at the PC's webcam — it grabs what is on screen
2. **Open your palm** at the phone's camera

### If you change your mind

Open your hand at the *same* device you grabbed from. Nothing is sent.

A grab that nobody catches expires after twenty seconds.

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
too close, or out of frame.

**A transfer failed.** Files are only visible under their real name once fully
received and verified, so a failed transfer leaves nothing behind. Try again.

---

## What it does not do

- Work over the internet — same Wi-Fi only, by design
- Work from the lock screen
- Transfer from a phone whose screen is off (the camera cannot see)

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
