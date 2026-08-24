"""Application entry point: wires the node, discovery, and tray together.

The asyncio work runs on a dedicated background thread because pystray's
event loop must own the main thread on Windows. Everything crossing that
boundary goes through run_coroutine_threadsafe.
"""

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
        self._node.on_pair_request = self._confirm_incoming_pairing

        self._tray = Tray(
            on_open_downloads=self._open_downloads,
            on_pair=self._pair_with_first_peer,
            on_quit=self.stop,
        )
        self._advertiser: Advertiser | None = None
        self._browser: Browser | None = None

    # ------------------------------------------------------------- lifecycle

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
                "Firewall is blocking AirGrab — run once as administrator", "problem"
            )
        else:
            self._tray.set_status("Waiting for devices", "idle")

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

    async def _stop_services(self) -> None:
        if self._browser is not None:
            await self._browser.stop()
            self._browser = None
        if self._advertiser is not None:
            await self._advertiser.stop()
            self._advertiser = None
        await self._node.stop()

    def stop(self) -> None:
        # Called from the tray thread, so shutdown has to be marshalled onto
        # the asyncio thread rather than awaited here.
        future = asyncio.run_coroutine_threadsafe(self._stop_services(), self._loop)
        try:
            future.result(timeout=10)
        except Exception:
            pass
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._tray.stop()

    # ---------------------------------------------------------------- events

    def _on_peer_found(self, peer: DiscoveredPeer) -> None:
        self._peers[peer.fingerprint] = peer
        trusted = self._node.trust.is_trusted(peer.fingerprint)
        if trusted:
            self._tray.set_status(f"Connected to {peer.name}", "connected")
        else:
            self._tray.set_status(f"{peer.name} found — not paired yet", "idle")

    def _on_peer_lost(self, fingerprint: str) -> None:
        self._peers.pop(fingerprint, None)
        if not self._peers:
            self._tray.set_status(
                "No devices found — check both are on the same Wi-Fi", "idle"
            )

    def _on_file_received(self, path: Path) -> None:
        self._tray.notify(f"Received {path.name}")

    # ------------------------------------------------------------ user actions

    def _open_downloads(self) -> None:
        self._settings.download_dir.mkdir(parents=True, exist_ok=True)
        os.startfile(str(self._settings.download_dir))  # noqa: S606 - Windows only

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

        peer = unpaired[0]
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
            self._tray.notify(
                f"Paired with {peer.name}" if granted else "Pairing cancelled"
            )

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
        import tkinter
        from tkinter import messagebox

        root = tkinter.Tk()
        root.withdraw()
        root.attributes("-topmost", True)
        try:
            return bool(messagebox.askyesno(title, message, parent=root))
        finally:
            root.destroy()


def main() -> None:
    AirGrabApp().run()


if __name__ == "__main__":
    main()
