"""Zero-configuration peer discovery over mDNS.

Devices advertise their fingerprint, name, platform and port; the fingerprint
in the TXT record is a hint for the UI only. It is never trusted — identity is
established by the signed-nonce exchange in auth.py once a connection opens,
and pinned against the trust store before any file moves.
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
            try:
                self._zeroconf.unregister_service(self._info)
            finally:
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
        self._peers[name] = peer
        self._on_found(peer)

    def update_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        self.add_service(zc, type_, name)

    def remove_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        peer = self._peers.pop(name, None)
        if peer is not None:
            self._on_lost(peer.fingerprint)


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
