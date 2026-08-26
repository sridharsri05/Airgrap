"""The application: node, discovery, camera and tray wired together.

The asyncio work runs on a dedicated background thread because pystray's event
loop must own the main thread on Windows, and the camera runs on a third
thread of its own because reading a frame blocks. Everything crossing those
boundaries goes through run_coroutine_threadsafe.

Nothing here decides anything. Discovery decides who exists, the gesture
engine decides what the hand did, the coordinator decides whether that means a
transfer. This module only connects them and tells the user what happened.
"""

from __future__ import annotations

import asyncio
import logging
import os
import threading
import time
from pathlib import Path

from airgrab.camera import GestureCameraLoop, open_default_camera
from airgrab.capture import ScreenCapture
from airgrab.config import default_data_dir, load_settings
from airgrab.discovery import Advertiser, Browser, DiscoveredPeer
from airgrab.gesture import EventType, GestureEvent
from airgrab.node import Node, NodeConfig
from airgrab.session import GestureSession
from airgrab.ui.overlay import Overlay
from airgrab.ui.shell import Shell
from airgrab.ui.tray import Tray
from airgrab.ui.window import Actions, Arrival, Device, MainWindow, Snapshot
from airgrab.windows import ensure_firewall_rule

# How many arrivals the window remembers. A record of what just happened, not
# a file manager -- the folder itself is one click away.
KEPT_ARRIVALS = 20


def _configure_logging(data_dir: Path) -> logging.Logger:
    """Write a log file, because this application has no console.

    It is packaged with --noconsole, so a print goes nowhere and a traceback
    dies with the thread that raised it. Diagnosing anything — why the camera
    sees no hand, why a transfer failed — otherwise means guessing. The phone
    has logcat; this is the desktop's equivalent.
    """
    data_dir.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger("airgrab")
    if logger.handlers:
        return logger

    logger.setLevel(logging.INFO)
    handler = logging.FileHandler(data_dir / "airgrab.log", encoding="utf-8")
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)s %(message)s", "%H:%M:%S")
    )
    logger.addHandler(handler)
    return logger


class AirGrabApp:
    def __init__(self) -> None:
        self._data_dir = default_data_dir()
        self._log = _configure_logging(self._data_dir)
        self._last_pose = None
        self._settings = load_settings(self._data_dir / "settings.json")
        self._peers: dict[str, DiscoveredPeer] = {}

        # One thread owns every Tk call, and everything that wants to show
        # something hands it work. Tkinter never raises when this rule is
        # broken -- it just never paints, which is what made the pairing
        # dialog invisible earlier in this project.
        self._shell = Shell()

        # The tray balloon is easy to miss and can be suppressed by focus
        # assist without saying so. This is the same indicator the phone
        # shows, in the same colours.
        self._overlay = Overlay(self._shell)

        # What the window is currently saying, kept here because the tray and
        # the window have to agree: they describe the same moment.
        self._state: tuple[str, str | None, str | None] = ("starting", None, None)
        self._arrivals: list[Arrival] = []

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
        self._node.on_pair_request = self._confirm_incoming_pairing

        self._screen = ScreenCapture(self._data_dir / "captures")
        self._session = GestureSession(self._node, capture=self._screen.capture)
        self._session.on_event = self._on_gesture_event
        self._session.on_transfer = self._on_transfer_finished

        self._window = MainWindow(
            self._shell,
            Actions(
                pair=self._pair_with,
                open_folder=self._open_downloads,
                settings=self._open_settings,
                quit=self.stop,
            ),
        )

        self._tray = Tray(
            on_open_downloads=self._open_downloads,
            on_pair=self._pair_with_first_peer,
            on_quit=self.stop,
            on_settings=self._open_settings,
            on_open_window=self._window.show,
        )
        self._advertiser: Advertiser | None = None
        self._browser: Browser | None = None
        self._camera: GestureCameraLoop | None = None
        self._camera_ready = False

    # ------------------------------------------------------------- lifecycle

    def run(self) -> None:
        # Started before the tray takes the main thread. Tk gets a thread of
        # its own because every Tk call has to happen on one thread, and
        # pystray already owns this one for the life of the process.
        self._shell.start()
        self._overlay.start()
        threading.Thread(target=self._run_loop, daemon=True).start()
        self._tray.run()

    def _run_loop(self) -> None:
        asyncio.set_event_loop(self._loop)
        self._loop.run_until_complete(self._start_services())
        self._loop.run_forever()

    async def _start_services(self) -> None:
        await self._node.start()

        if not ensure_firewall_rule(self._node.port):
            self._set_state(
                "firewall",
                tray="Firewall is blocking AirGrab — run once as administrator",
            )
        else:
            self._set_state("looking", tray="Waiting for devices")

        self._advertiser = Advertiser(
            self._node.identity.fingerprint,
            self._settings.display_name,
            "windows",
            self._node.port,
        )
        await self._advertiser.start()

        self._browser = Browser(
            on_found=self._on_peer_found,
            on_lost=self._on_peer_lost,
            ignore_fingerprint=self._node.identity.fingerprint,
        )
        await self._browser.start()

        self._start_camera()

    def _start_camera(self) -> None:
        parts = open_default_camera()
        if parts is None:
            self._camera_ready = False
            self._tray.notify(
                "Gestures are off: no camera, or the gesture model is missing. "
                "Run tools/fetch_model.py to download it."
            )
            return

        read, classify, close = parts
        self._log.info("camera opened")
        self._camera = GestureCameraLoop(
            observe=self._observe_pose,
            loop=self._loop,
            read=read,
            classify=classify,
            close=close,
            on_stopped=self._on_camera_stopped,
        )
        self._camera.start()
        self._camera_ready = True

    async def _stop_services(self) -> None:
        if self._browser is not None:
            await self._browser.stop()
            self._browser = None
        if self._advertiser is not None:
            await self._advertiser.stop()
            self._advertiser = None
        await self._session.close()
        await self._node.stop()

        # zeroconf schedules its goodbye broadcasts as background tasks that
        # async_close() does not await, so stopping the loop immediately
        # destroys them mid-flight. Give them a moment, then cancel whatever
        # is still outstanding rather than leaving it to be torn down.
        await asyncio.sleep(0.5)
        pending = [
            task
            for task in asyncio.all_tasks()
            if task is not asyncio.current_task() and not task.done()
        ]
        for task in pending:
            task.cancel()
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

    def stop(self) -> None:
        # Called from the tray thread, so shutdown is marshalled onto the
        # asyncio thread rather than awaited here.
        if self._camera is not None:
            self._camera.stop()
            self._camera = None
        self._overlay.stop()
        self._window.hide()
        self._shell.stop()
        future = asyncio.run_coroutine_threadsafe(self._stop_services(), self._loop)
        try:
            future.result(timeout=10)
        except Exception:
            pass
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._tray.stop()

    # ---------------------------------------------------------------- peers

    def _on_peer_found(self, peer: DiscoveredPeer) -> None:
        self._log.info("found %s at %s:%s", peer.name, peer.host, peer.port)

        # One node per address. A reinstalled app comes back with a fresh
        # identity, but its OLD advertisement lingers in mDNS caches until
        # the TTL runs out -- there is no goodbye packet from an uninstall.
        # Keeping both meant the PC tried to link to the ghost, the pinning
        # check correctly refused the certificate it met, and the user was
        # told their phone was unreachable while it sat there working.
        stale = [
            fp for fp, known in self._peers.items()
            if fp != peer.fingerprint
            and (known.host, known.port) == (peer.host, peer.port)
        ]
        for fp in stale:
            self._log.info("dropping stale identity %s for %s", fp[:16], peer.host)
            self._peers.pop(fp, None)
            self._session.forget_peer(fp)

        self._peers[peer.fingerprint] = peer
        if not self._node.trust.is_trusted(peer.fingerprint):
            self._set_state(
                "unpaired", peer=peer.name,
                tray=f"{peer.name} found — not paired yet", tone="idle",
            )
            return

        # A paired device gets a control channel immediately. Gesture events
        # are useless if the link is only opened when a transfer starts.
        self._session.register_peer(peer.fingerprint, peer.host, peer.port)
        asyncio.run_coroutine_threadsafe(self._link_to(peer), self._loop)

    async def _link_to(self, peer: DiscoveredPeer) -> None:
        try:
            await self._node.open_link(peer.host, peer.port, expect_fp=peer.fingerprint)
        except Exception as exc:
            self._log.warning("link to %s (%s) failed: %s", peer.name, peer.host, exc)
            self._set_state(
                "unreachable", peer=peer.name,
                tray=f"Could not reach {peer.name}: {exc}",
            )
            return
        if self._camera_ready:
            self._set_state("ready", peer=peer.name)
        else:
            self._set_state(
                "gestures_off", peer=peer.name,
                tray=f"Connected to {peer.name} — gestures off",
            )

    def _on_peer_lost(self, fingerprint: str) -> None:
        peer = self._peers.pop(fingerprint, None)
        self._session.forget_peer(fingerprint)
        if not self._peers:
            self._set_state(
                "looking",
                tray="No devices found — check both are on the same Wi-Fi",
                tone="idle",
            )
        elif peer is not None:
            self._set_state(
                "looking", tray=f"{peer.name} went offline", tone="idle"
            )

    # ----------------------------------------------------------------- state

    def _set_state(
        self,
        kind: str,
        peer: str | None = None,
        file: str | None = None,
        tray: str | None = None,
        tone: str | None = None,
    ) -> None:
        """Say the same thing in the tray and in the window.

        They describe one moment, so they are set together. Letting each be
        updated at its own call site is how they drift, and a tray that says
        "Ready" beside a window that says "Looking for your phone" is worse
        than either alone.
        """
        self._state = (kind, peer, file)

        from airgrab.ui.window import describe

        _, state_tone, headline, detail = describe(kind, peer, file)
        self._tray.set_status(
            tray if tray is not None else (f"{headline} — {detail}" if detail else headline),
            tone or {"good": "connected", "warn": "problem"}.get(state_tone, "idle"),
        )
        self._refresh_window()

    def _refresh_window(self) -> None:
        kind, peer, file = self._state
        devices = tuple(
            Device(
                fingerprint=found.fingerprint,
                name=found.name,
                host=found.host,
                paired=self._node.trust.is_trusted(found.fingerprint),
            )
            for found in self._peers.values()
        )
        self._window.update(
            Snapshot(
                kind=kind,
                peer=peer,
                file=file,
                outgoing_note="taken when you make a fist",
                devices=devices,
                arrivals=tuple(self._arrivals),
            )
        )

    # -------------------------------------------------------------- feedback

    async def _observe_pose(self, pose) -> None:
        """Log pose CHANGES, then hand the frame on.

        Per-frame logging at twenty frames a second buries everything else and
        slows the loop being measured. What anyone debugging needs is whether
        the camera saw a fist at all, and when.
        """
        if pose is not self._last_pose:
            self._last_pose = pose
            self._log.info("hand: %s", getattr(pose, "name", pose))
        await self._session.observe(pose)

    def _on_gesture_event(self, event: GestureEvent) -> None:
        self._log.info("gesture: %s", event.type.name)
        """The user cannot see the state machine, so the tray has to show it.

        Without this the gesture feels broken while it is working perfectly:
        there is no other signal that a grab was registered.
        """
        peer = self._first_peer_name()
        if event.type is EventType.ARMED:
            self._set_state("ready", peer=peer)
        elif event.type is EventType.GRABBED:
            self._set_state("holding", peer=peer)
            self._overlay.show("holding", "Holding — open your hand at the other device")
        elif event.type is EventType.CATCH_READY:
            self._set_state("incoming", peer=peer)
            self._overlay.show("holding", "Open your hand to receive")
        elif event.type is EventType.CANCELLED:
            self._set_state("expired", peer=peer, tone="idle")
            self._tray.notify("Grab expired — nothing was sent.")
            self._overlay.show("cancelled", "Grab expired — nothing was sent")
        elif event.type is EventType.DISARMED:
            self._set_state("ready" if self._peers else "looking", peer=peer)

    def _first_peer_name(self) -> str | None:
        """The device a sentence should name.

        Prefers a paired one: an unpaired device is visible but cannot receive
        anything, so telling the user to open their palm at it would send them
        to the wrong machine.
        """
        for peer in self._peers.values():
            if self._node.trust.is_trusted(peer.fingerprint):
                return peer.name
        return next((peer.name for peer in self._peers.values()), None)

    def _on_transfer_finished(self, peer_fp: str, ok: bool) -> None:
        peer = self._peers.get(peer_fp)
        name = peer.name if peer else "the other device"
        if ok:
            self._tray.notify(f"Sent to {name}")
            self._set_state("sent", peer=name)
            self._overlay.show("sent", f"Sent to {name}")
        else:
            self._tray.notify(f"Could not send to {name}")
            self._set_state("send_failed", peer=name)
            self._overlay.show("cancelled", f"Could not send to {name}")

    def _on_camera_stopped(self, reason: str) -> None:
        self._camera_ready = False
        if reason != "stopped":
            self._tray.notify(f"Gestures stopped: {reason}")
            self._set_state("gestures_off", tray=f"Gestures stopped: {reason}")

    def _on_file_received(self, path: Path) -> None:
        self._log.info("received %s", path.name)
        size = path.stat().st_size if path.exists() else 0
        self._arrivals.insert(
            0, Arrival(name=path.name, size=size, when=time.strftime("%H:%M"))
        )
        del self._arrivals[KEPT_ARRIVALS:]

        self._tray.notify(f"Received {path.name}")
        self._set_state("received", peer=self._first_peer_name(), file=path.name)
        self._overlay.show("received", f"Received {path.name}")

    # ---------------------------------------------------------- user actions

    def _open_downloads(self) -> None:
        self._settings.download_dir.mkdir(parents=True, exist_ok=True)
        os.startfile(str(self._settings.download_dir))  # noqa: S606 - Windows only

    def _open_settings(self) -> None:
        """Opened on the Tk thread, parented to the hidden root.

        It used to build a Tk root of its own from whichever thread the tray
        menu ran on. A second Tk in a second thread is the same mistake that
        made the pairing dialog invisible: it does not raise, it just may
        never paint.

        Most settings only take effect on restart, which the window says
        itself; nothing here tries to apply them live.
        """
        from airgrab.ui.settings import SettingsWindow

        path = self._data_dir / "settings.json"
        saved = self._shell.call(
            lambda: SettingsWindow(path).show(parent=self._shell.root)
        )
        if saved is not None:
            self._tray.notify("Settings saved. Restart AirGrab to apply them.")

    def _pair_with_first_peer(self) -> None:
        unpaired = [
            peer
            for peer in self._peers.values()
            if not self._node.trust.is_trusted(peer.fingerprint)
        ]
        if not unpaired:
            self._tray.notify(
                "No unpaired devices found. Make sure both are on the same Wi-Fi."
            )
            return
        self._pair_with(unpaired[0].fingerprint)

    def _pair_with(self, fingerprint: str) -> None:
        """Pair with one named device, chosen in the window."""
        peer = self._peers.get(fingerprint)
        if peer is None:
            self._tray.notify("That device is no longer visible.")
            return

        future = asyncio.run_coroutine_threadsafe(
            self._node.pair_with(peer.host, peer.port, on_sas=self._confirm_sas),
            self._loop,
        )

        def report(done) -> None:
            try:
                granted = done.result()
            except Exception as exc:
                self._tray.notify(f"Pairing failed: {exc}")
                return
            if granted:
                self._tray.notify(f"Paired with {peer.name}")
                self._on_peer_found(peer)  # link immediately now that it is trusted
                self._refresh_window()     # the chip says Paired straight away
            else:
                self._tray.notify("Pairing cancelled")

        future.add_done_callback(report)

    def _confirm_sas(self, sas: str) -> bool:
        return self._ask(
            "AirGrab pairing",
            f"Does the other device show this code?\n\n        {sas}\n\n"
            "Only accept if the codes match exactly.",
        )

    def _confirm_incoming_pairing(self, sas: str, peer_name: str) -> bool:
        return self._ask(
            "AirGrab pairing request",
            f"{peer_name} wants to pair.\n\n        {sas}\n\n"
            "Only accept if the codes match exactly.",
        )

    @staticmethod
    def _ask(title: str, message: str) -> bool:
        """A yes/no dialog that works from ANY thread.

        This uses the Win32 message box rather than tkinter, and the reason is
        not stylistic. Pairing confirmations arrive on the asyncio thread,
        because that is where the control channel reads them, while pystray
        owns the main thread for the whole life of the process. Tkinter
        requires every call to happen on the thread that created its root;
        creating one from another thread does not raise, it simply never
        paints. The symptom was a pairing that failed with no dialog on the PC
        at all and no error anywhere: the phone waited for an answer nobody
        could give.

        MessageBoxW has no such constraint — it runs its own modal message
        loop on whichever thread calls it.
        """
        import ctypes

        MB_YESNO = 0x00000004
        MB_ICONQUESTION = 0x00000020
        MB_SYSTEMMODAL = 0x00001000  # keeps it above other windows
        MB_SETFOREGROUND = 0x00010000
        IDYES = 6

        try:
            answer = ctypes.windll.user32.MessageBoxW(
                None,
                message,
                title,
                MB_YESNO | MB_ICONQUESTION | MB_SYSTEMMODAL | MB_SETFOREGROUND,
            )
            return answer == IDYES
        except Exception:
            # Never assume yes. A dialog that cannot be shown must not become
            # silent consent to pair with an unknown device.
            return False


def main() -> None:
    AirGrabApp().run()


if __name__ == "__main__":
    main()
