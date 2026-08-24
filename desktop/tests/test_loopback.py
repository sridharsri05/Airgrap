"""The milestone: two independent devices, paired, transferring both ways.

Everything before this task is scaffolding. If this passes, the desktop half
of AirGrab works, and the Android app has a proven peer to be written against.
"""

import asyncio
import hashlib
from pathlib import Path

from airgrab.node import Node, NodeConfig


def _config(root: Path, name: str) -> NodeConfig:
    return NodeConfig(
        data_dir=root / name,
        download_dir=root / name / "downloads",
        display_name=name,
        port=0,
        auto_accept=True,
    )


async def test_full_lifecycle_pair_then_transfer_both_directions(tmp_path: Path):
    pc = Node(_config(tmp_path, "DESKTOP"))
    phone = Node(_config(tmp_path, "PHONE-SIM"))
    await pc.start()
    await phone.start()

    to_phone: list[Path] = []
    to_pc: list[Path] = []
    phone.on_incoming_file = to_phone.append
    pc.on_incoming_file = to_pc.append

    codes: list[str] = []

    try:
        # 1. Pairing: one exchange grants mutual trust.
        paired = await pc.pair_with(
            "127.0.0.1", phone.port, on_sas=lambda s: (codes.append(s), True)[1]
        )
        assert paired is True
        assert len(codes[0]) == 6
        assert pc.trust.is_trusted(phone.identity.fingerprint)
        assert phone.trust.is_trusted(pc.identity.fingerprint)

        # 2. PC to phone.
        outgoing = tmp_path / "report.pdf"
        outgoing.write_bytes(b"%PDF-1.4 pretend" * 5000)
        assert await pc.send_file("127.0.0.1", phone.port, outgoing) is True
        assert len(to_phone) == 1
        assert to_phone[0].read_bytes() == outgoing.read_bytes()

        # 3. Phone to PC, on the same pairing, no re-pairing needed.
        incoming = tmp_path / "photo.jpg"
        incoming.write_bytes(bytes(range(256)) * 4000)
        assert await phone.send_file("127.0.0.1", pc.port, incoming) is True
        assert len(to_pc) == 1
        assert (
            hashlib.sha256(to_pc[0].read_bytes()).hexdigest()
            == hashlib.sha256(incoming.read_bytes()).hexdigest()
        )

    finally:
        await pc.stop()
        await phone.stop()


async def test_stranger_cannot_transfer_without_pairing(tmp_path: Path):
    pc = Node(_config(tmp_path, "DESKTOP"))
    stranger = Node(_config(tmp_path, "STRANGER"))
    await pc.start()
    await stranger.start()

    landed: list[Path] = []
    pc.on_incoming_file = landed.append

    payload = tmp_path / "malware.exe"
    payload.write_bytes(b"definitely not malware")

    try:
        assert await stranger.send_file("127.0.0.1", pc.port, payload) is False
        assert landed == []
    finally:
        await pc.stop()
        await stranger.stop()


async def test_concurrent_transfers_do_not_corrupt_each_other(tmp_path: Path):
    pc = Node(_config(tmp_path, "DESKTOP"))
    phone = Node(_config(tmp_path, "PHONE-SIM"))
    await pc.start()
    await phone.start()

    landed: list[Path] = []
    phone.on_incoming_file = landed.append

    try:
        await pc.pair_with("127.0.0.1", phone.port, on_sas=lambda s: True)

        files = []
        for index in range(4):
            path = tmp_path / f"file{index}.bin"
            path.write_bytes(bytes([index]) * (200_000 + index))
            files.append(path)

        results = await asyncio.gather(
            *(pc.send_file("127.0.0.1", phone.port, f) for f in files)
        )
        assert all(results)
        assert len(landed) == 4

        by_name = {p.name: p for p in landed}
        for original in files:
            assert by_name[original.name].read_bytes() == original.read_bytes()

    finally:
        await pc.stop()
        await phone.stop()


async def test_large_file_survives_the_round_trip(tmp_path: Path):
    """32 MB exercises chunked streaming rather than a single buffer."""
    pc = Node(_config(tmp_path, "DESKTOP"))
    phone = Node(_config(tmp_path, "PHONE-SIM"))
    await pc.start()
    await phone.start()

    landed: list[Path] = []
    phone.on_incoming_file = landed.append

    payload = tmp_path / "video.mp4"
    payload.write_bytes(bytes(range(256)) * 131_072)  # 32 MiB

    try:
        await pc.pair_with("127.0.0.1", phone.port, on_sas=lambda s: True)
        assert await pc.send_file("127.0.0.1", phone.port, payload) is True
        assert (
            hashlib.sha256(landed[0].read_bytes()).hexdigest()
            == hashlib.sha256(payload.read_bytes()).hexdigest()
        )
    finally:
        await pc.stop()
        await phone.stop()
