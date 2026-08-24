"""Zero-configuration peer discovery over mDNS.

Devices advertise their fingerprint, name, platform and port; the fingerprint
in the TXT record is a hint for the UI only. It is never trusted — identity is
established by the signed-nonce exchange in auth.py once a connection opens,
and pinned against the trust store before any file moves.

This module is asyncio-native on purpose. python-zeroconf's synchronous API
reuses the caller's running event loop, so calling it from inside a coroutine
deadlocks with EventLoopBlocked. Since AirGrab's node is asyncio-based and
advertising starts alongside it, the async API is the only correct choice
here — the sync one fails exclusively at runtime, never in a unit test that
happens to run outside a loop.
"""

from __future__ import annotations

import asyncio
import contextlib
import socket
from dataclasses import dataclass
from typing import Callable

from zeroconf import ServiceStateChange
from zeroconf.asyncio import AsyncServiceBrowser, AsyncServiceInfo, AsyncZeroconf

from airgrab.protocol import PROTOCOL_VERSION

SERVICE_TYPE = "_airgrab._tcp.local."
RESOLVE_TIMEOUT_MS = 3000


@dataclass(frozen=True)
class DiscoveredPeer:
    fingerprint: str
    name: str
    platform: str
    host: str
    port: int


class Advertiser:
    """Announces this device on the local network."""

    def __init__(self, fingerprint: str, name: str, platform: str, port: int) -> None:
        self._aiozc: AsyncZeroconf | None = None
        self._info = AsyncServiceInfo(
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

    async def start(self) -> None:
        self._aiozc = AsyncZeroconf()
        await self._aiozc.async_register_service(self._info)

    async def stop(self) -> None:
        if self._aiozc is None:
            return
        with contextlib.suppress(Exception):
            await self._aiozc.async_unregister_service(self._info)
        await self._aiozc.async_close()
        self._aiozc = None


class Browser:
    """Watches the local network for other AirGrab devices."""

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
        self._aiozc: AsyncZeroconf | None = None
        self._browser: AsyncServiceBrowser | None = None
        self._tasks: set[asyncio.Task] = set()

    async def start(self) -> None:
        self._aiozc = AsyncZeroconf()
        self._browser = AsyncServiceBrowser(
            self._aiozc.zeroconf, SERVICE_TYPE, handlers=[self._on_state_change]
        )

    async def stop(self) -> None:
        for task in list(self._tasks):
            task.cancel()
        self._tasks.clear()
        if self._browser is not None:
            with contextlib.suppress(Exception):
                await self._browser.async_cancel()
            self._browser = None
        if self._aiozc is not None:
            await self._aiozc.async_close()
            self._aiozc = None

    def peers(self) -> list[DiscoveredPeer]:
        return list(self._peers.values())

    def _on_state_change(
        self, zeroconf, service_type: str, name: str, state_change: ServiceStateChange
    ) -> None:
        if state_change is ServiceStateChange.Removed:
            peer = self._peers.pop(name, None)
            if peer is not None:
                self._on_lost(peer.fingerprint)
            return

        # Resolution needs a round trip, so it cannot happen in this callback.
        task = asyncio.ensure_future(self._resolve(zeroconf, service_type, name))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def _resolve(self, zeroconf, service_type: str, name: str) -> None:
        info = AsyncServiceInfo(service_type, name)
        if not await info.async_request(zeroconf, RESOLVE_TIMEOUT_MS):
            return

        props: dict[str, str] = {}
        for key, value in (info.properties or {}).items():
            if key is None or value is None:
                continue
            key_text = key.decode() if isinstance(key, bytes) else str(key)
            value_text = value.decode() if isinstance(value, bytes) else str(value)
            props[key_text] = value_text

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
        if self._peers.get(name) == peer:
            return
        self._peers[name] = peer
        self._on_found(peer)


def _local_address() -> str:
    """Which local interface would be used to reach the outside world.

    UDP connect only sets a default route; no packet is sent, so this works
    with no internet connection present.
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()
