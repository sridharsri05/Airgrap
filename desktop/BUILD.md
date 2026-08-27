# Building AirGrab for Windows

Produces a single-folder Windows build: `dist/AirGrab/AirGrab.exe` plus the
`_internal/` folder it needs. Both ship together.

## Prerequisites

- Windows 10/11, 64-bit.
- Python 3.11+ (the project is developed and built against 3.12).
- The project virtual environment, with runtime dependencies installed:

  ```
  py -m venv .venv
  .venv\Scripts\python.exe -m pip install -e desktop[dev]
  .venv\Scripts\python.exe -m pip install mediapipe opencv-python
  ```

  `mediapipe` and `opencv-python` are not in `pyproject.toml`'s dependency
  list but are required at runtime for gesture recognition, and therefore
  required for the build.

- PyInstaller. The build script installs it automatically into the running
  interpreter if it is missing, so this is optional:

  ```
  .venv\Scripts\python.exe -m pip install pyinstaller
  ```

## 1. Fetch the gesture model

The MediaPipe gesture recognizer is ~8 MB of binary and is deliberately not
committed. From the `desktop` directory:

```
..\.venv\Scripts\python.exe tools\fetch_model.py
```

It downloads to `desktop/models/gesture_recognizer.task` and verifies the
SHA-256. The build refuses to run without it â€” a build with no model produces
an application whose gestures silently never fire.

## 2. Build

From the `desktop` directory:

```
..\.venv\Scripts\python.exe tools\build_exe.py
```

Takes a few minutes, mostly spent scanning and copying MediaPipe's native
libraries. On success it prints the executable path, the bundled model path
and the total folder size.

## Output

```
desktop/dist/AirGrab/
    AirGrab.exe
    _internal/
        models/gesture_recognizer.task
        mediapipe/, cv2/, PIL/, ... native libraries and data
```

Roughly **300 MB** on disk (MediaPipe and OpenCV dominate; zipped it is
considerably smaller). Intermediate build files land in `desktop/build/` and
can be deleted.

Ship or copy **the whole `AirGrab` folder**. `AirGrab.exe` on its own will not
start â€” the `_internal` folder next to it is the application.

## First run

AirGrab listens on TCP 53421 for incoming transfers, so Windows Firewall must
allow it. **Run `AirGrab.exe` as administrator once** (right-click â†’ Run as
administrator) so it can create its inbound firewall rule; after that, normal
launches work. Without it the tray shows
"Firewall is blocking AirGrab â€” run once as administrator", discovery still
works, but incoming transfers will not.

AirGrab lives in the notification area. **Double-click the tray icon** to open
its window: what is happening, which devices are around, and what has arrived.
Closing the window hides it and leaves AirGrab running -- Quit, in the window's
footer or the tray menu, is what actually stops it.

The build is unsigned, so SmartScreen will show "Windows protected your PC" on
first launch â€” More info â†’ Run anyway.

## Why single-folder, not single-file

`--onefile` unpacks the entire archive into a temp directory on *every*
launch. With MediaPipe's native payload and the 8 MB model that is several
seconds of startup each time, and the unpack step is a frequent cause of
antivirus false positives and of failures on locked-down temp directories. A
folder starts immediately.

## Known limitations

- **Windows only.** Firewall setup, `os.startfile`, and the `pystray._win32`
  tray backend are Windows-specific.
- **Unsigned.** No code-signing certificate is applied, so SmartScreen warns
  on first run and some corporate policies will block it outright. Signing
  `AirGrab.exe` after the build is the fix.
- **Large.** ~300 MB, almost entirely MediaPipe and OpenCV. Trimming it means
  excluding MediaPipe's unused task bundles by hand, which risks breaking
  gesture recognition, so it is not done by default. The build also pulls in
  matplotlib as a transitive MediaPipe dependency.
- **The model is baked in at build time.** Updating it requires a rebuild.
- **No camera, no gestures.** With no webcam (or a camera in use by another
  application) the app still starts and transfers still work manually; the
  tray reports that gestures are off.
- **Not tested under a per-machine installer.** There is no MSI/NSIS wrapper;
  distribution is "copy the folder".
- **Antivirus.** Unsigned PyInstaller bootloaders are occasionally flagged
  heuristically. Signing generally resolves it.

## 3. Installer (optional)

To get a normal Windows setup wizard instead of a bare folder, compile
`tools\AirGrab.iss` with Inno Setup 6 after the build above:

```
ISCC.exe tools\AirGrab.iss
```

Output: `dist/AirGrab-Setup.exe` (~90 MB). It installs to Program Files,
creates Start menu / desktop icons, offers run-at-startup, and adds the
inbound firewall rule for TCP 53421 itself -- so the "run once as
administrator" step in "First run" above is not needed when installing
this way.

