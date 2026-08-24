"""Ticket issuance and verified file reception.

Two invariants this module exists to guarantee:

1. A file visible under its real name is complete and hash-verified. Bytes
   land in a temporary file and are renamed only after verification, so a
   partial or corrupt transfer is never observable as a real file.
2. A ticket authorises exactly one upload, from exactly one peer, for at most
   sixty seconds.
"""

from __future__ import annotations

import hashlib
import os
import secrets
import time
from dataclasses import dataclass
from pathlib import Path
from typing import AsyncIterator, Callable

from airgrab.protocol import ErrorCode

TICKET_TTL_SECONDS = 60
TICKET_BYTES = 32
READ_CHUNK = 256 * 1024


class TicketError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class TransferError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class Ticket:
    value: str
    peer_fp: str
    expected_sha256: str
    size: int
    issued_at: float


class TicketStore:
    """Single-use, peer-bound, time-limited upload authorisations.

    Expiry uses a monotonic clock so that a system clock change mid-transfer
    cannot expire a live ticket or resurrect a dead one.
    """

    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self._tickets: dict[str, Ticket] = {}

    def issue(self, peer_fp: str, expected_sha256: str, size: int) -> str:
        value = secrets.token_hex(TICKET_BYTES)
        self._tickets[value] = Ticket(
            value=value,
            peer_fp=peer_fp,
            expected_sha256=expected_sha256,
            size=size,
            issued_at=self._clock(),
        )
        return value

    def redeem(self, ticket: str, peer_fp: str) -> Ticket:
        found = self._tickets.get(ticket)
        if found is None or not secrets.compare_digest(found.peer_fp, peer_fp):
            raise TicketError(ErrorCode.TICKET_INVALID)

        del self._tickets[ticket]

        if self._clock() - found.issued_at > TICKET_TTL_SECONDS:
            raise TicketError(ErrorCode.TICKET_EXPIRED)

        return found

    def discard(self, ticket: str) -> None:
        self._tickets.pop(ticket, None)


def unique_destination(directory: Path, filename: str) -> Path:
    """Resolve a safe, non-colliding path inside `directory`.

    The basename is taken deliberately: a peer-supplied name containing path
    separators must never escape the destination folder. Backslashes are
    normalised first so a Windows-style traversal cannot slip past on POSIX.
    """
    directory = Path(directory)
    safe = os.path.basename(filename.replace("\\", "/")) or "received"
    candidate = directory / safe
    if not candidate.exists():
        return candidate

    stem = candidate.stem
    suffix = candidate.suffix
    counter = 2
    while True:
        candidate = directory / f"{stem} ({counter}){suffix}"
        if not candidate.exists():
            return candidate
        counter += 1


async def receive_to_file(
    chunks: AsyncIterator[bytes],
    destination: Path,
    expected_sha256: str,
    progress: Callable[[int], None],
) -> Path:
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp_path = destination.with_name(f".{destination.name}.airgrab-part")

    digest = hashlib.sha256()
    written = 0

    try:
        with open(temp_path, "wb") as handle:
            async for chunk in chunks:
                handle.write(chunk)
                digest.update(chunk)
                written += len(chunk)
                progress(written)
            handle.flush()
            os.fsync(handle.fileno())
    except ConnectionError as exc:
        # ConnectionResetError and friends subclass OSError, so this branch MUST
        # precede the storage branch below. Without it a peer vanishing mid-
        # transfer is reported to the user as a disk permission problem.
        temp_path.unlink(missing_ok=True)
        raise TransferError(ErrorCode.TRANSFER_ABORTED) from exc
    except OSError as exc:
        temp_path.unlink(missing_ok=True)
        code = (
            ErrorCode.STORAGE_FULL
            if getattr(exc, "errno", None) == 28
            else ErrorCode.STORAGE_DENIED
        )
        raise TransferError(code) from exc
    except BaseException as exc:
        temp_path.unlink(missing_ok=True)
        raise TransferError(ErrorCode.TRANSFER_ABORTED) from exc

    if digest.hexdigest() != expected_sha256:
        temp_path.unlink(missing_ok=True)
        raise TransferError(ErrorCode.HASH_MISMATCH)

    os.replace(temp_path, destination)
    return destination


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            block = handle.read(READ_CHUNK)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()
