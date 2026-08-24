"""The list of devices this device has paired with.

A corrupt or unreadable store is treated as empty rather than fatal: the
worst outcome is re-pairing, and refusing to start is a far worse experience
than asking for six digits again.
"""

from __future__ import annotations

import json
import os
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class TrustedPeer:
    fingerprint: str
    name: str
    platform: str


class TrustStore:
    def __init__(self, path: Path) -> None:
        self._path = Path(path)
        self._peers: dict[str, TrustedPeer] = {}
        self._load()

    def is_trusted(self, fingerprint: str) -> bool:
        return fingerprint in self._peers

    def get(self, fingerprint: str) -> TrustedPeer | None:
        return self._peers.get(fingerprint)

    def all(self) -> list[TrustedPeer]:
        return list(self._peers.values())

    def add(self, fingerprint: str, name: str, platform: str) -> None:
        self._peers[fingerprint] = TrustedPeer(fingerprint, name, platform)
        self._save()

    def remove(self, fingerprint: str) -> None:
        if self._peers.pop(fingerprint, None) is not None:
            self._save()

    def _load(self) -> None:
        if not self._path.exists():
            return
        try:
            raw = json.loads(self._path.read_text(encoding="utf-8"))
            for entry in raw.get("peers", []):
                peer = TrustedPeer(
                    fingerprint=entry["fingerprint"],
                    name=entry.get("name", "Unknown"),
                    platform=entry.get("platform", "unknown"),
                )
                self._peers[peer.fingerprint] = peer
        except (json.JSONDecodeError, KeyError, TypeError, AttributeError, OSError):
            self._peers = {}

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"peers": [asdict(p) for p in self._peers.values()]}
        fd, tmp = tempfile.mkstemp(dir=str(self._path.parent), suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(payload, handle, indent=2)
            os.replace(tmp, self._path)
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise
