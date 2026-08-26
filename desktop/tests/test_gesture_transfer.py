"""The whole point of the project, end to end, with no camera.

Poses are injected directly, so these tests exercise the real network, the
real control channel and the real transfer path while remaining deterministic.
The only thing simulated is the hand.
"""

import asyncio
import hashlib
from pathlib import Path

from airgrab.gesture import GestureConfig, Pose
from airgrab.node import Node, NodeConfig
from airgrab.session import GestureSession

# Thresholds are durations; zero plus the two-frame floor means a gesture
# completes as soon as a pose has been seen twice.
FAST = GestureConfig(
    arm_seconds=0.0,
    grab_seconds=0.0,
    release_seconds=0.0,
    catch_seconds=0.0,
    disarm_seconds=0.0,
    min_frames=2,
    hold_timeout_seconds=30.0,
    # Zero, or the change-of-mind test would have to really wait five
    # seconds. The grace behaviour itself is covered in test_gesture.py.
    cancel_grace_seconds=0.0,
    cancel_seconds=0.0,
)


def _node(root: Path, name: str) -> Node:
    return Node(
        NodeConfig(
            data_dir=root / name,
            download_dir=root / name / "downloads",
            display_name=name,
            port=0,
            auto_accept=True,
        )
    )


async def _feed(session: GestureSession, pose: Pose, frames: int = 3) -> None:
    for _ in range(frames):
        await session.observe(pose)
        await asyncio.sleep(0)


class Rig:
    """Two paired devices with an open control channel between them."""

    def __init__(self, tmp_path: Path, payload: Path) -> None:
        self.tmp_path = tmp_path
        self.payload = payload
        self.landed: list[Path] = []
        self.transfers: list[tuple[str, bool]] = []

    async def __aenter__(self):
        self.pc = _node(self.tmp_path, "DESKTOP")
        self.phone = _node(self.tmp_path, "PHONE-SIM")
        await self.pc.start()
        await self.phone.start()
        self.phone.on_incoming_file = self.landed.append
        self.pc.on_incoming_file = self.landed.append

        await self.pc.pair_with("127.0.0.1", self.phone.port, on_sas=lambda s: True)

        self.pc_session = GestureSession(
            self.pc, capture=lambda: self.payload, config=FAST
        )
        self.phone_session = GestureSession(
            self.phone, capture=lambda: self.payload, config=FAST
        )
        self.pc_session.on_transfer = lambda fp, ok: self.transfers.append((fp, ok))
        self.phone_session.on_transfer = lambda fp, ok: self.transfers.append((fp, ok))

        # The persistent control channel gesture events travel over.
        await self.pc.open_link("127.0.0.1", self.phone.port)

        self.pc_session.register_peer(
            self.phone.identity.fingerprint, "127.0.0.1", self.phone.port
        )
        self.phone_session.register_peer(
            self.pc.identity.fingerprint, "127.0.0.1", self.pc.port
        )
        return self

    async def __aexit__(self, *exc):
        await self.pc_session.close()
        await self.phone_session.close()
        await self.pc.stop()
        await self.phone.stop()

    async def settle(self):
        for _ in range(50):
            await asyncio.sleep(0.02)
            if self.landed:
                break
        await self.pc_session.drain()
        await self.phone_session.drain()


async def test_grab_on_one_device_release_on_the_other_moves_the_file(tmp_path: Path):
    payload = tmp_path / "holiday.jpg"
    payload.write_bytes(bytes(range(256)) * 2000)

    async with Rig(tmp_path, payload) as rig:
        # Present a palm at the PC, then close a fist: content is captured and
        # the hold is announced.
        await _feed(rig.pc_session, Pose.OPEN_PALM)
        await _feed(rig.pc_session, Pose.CLOSED_FIST)
        assert rig.pc_session.coordinator.holding is True
        assert rig.pc_session.captured == payload

        await asyncio.sleep(0.1)
        assert rig.phone_session.coordinator.peers_holding() == [
            rig.pc.identity.fingerprint
        ]

        # Carry the hand away from the PC and open it at the phone.
        await _feed(rig.pc_session, Pose.NONE, frames=4)
        await _feed(rig.phone_session, Pose.CLOSED_FIST)
        await _feed(rig.phone_session, Pose.OPEN_PALM)

        await rig.settle()

        assert len(rig.landed) == 1
        assert rig.landed[0].name == "holiday.jpg"
        assert (
            hashlib.sha256(rig.landed[0].read_bytes()).hexdigest()
            == hashlib.sha256(payload.read_bytes()).hexdigest()
        )
        assert rig.pc_session.coordinator.holding is False
        assert rig.pc_session.captured is None


async def test_releasing_on_the_grabbing_device_transfers_nothing(tmp_path: Path):
    """Changing your mind must not send the file anywhere."""
    payload = tmp_path / "private.txt"
    payload.write_bytes(b"should stay put")

    async with Rig(tmp_path, payload) as rig:
        await _feed(rig.pc_session, Pose.OPEN_PALM)
        await _feed(rig.pc_session, Pose.CLOSED_FIST)
        assert rig.pc_session.coordinator.holding is True

        # Open the hand at the same device that grabbed.
        await _feed(rig.pc_session, Pose.NONE, frames=4)
        await _feed(rig.pc_session, Pose.OPEN_PALM)

        await rig.settle()

        assert rig.landed == []
        assert rig.pc_session.coordinator.holding is False
        assert rig.pc_session.captured is None


async def test_a_release_with_nothing_held_transfers_nothing(tmp_path: Path):
    payload = tmp_path / "nothing.txt"
    payload.write_bytes(b"x")

    async with Rig(tmp_path, payload) as rig:
        await _feed(rig.phone_session, Pose.CLOSED_FIST)
        await _feed(rig.phone_session, Pose.OPEN_PALM)
        await rig.settle()
        assert rig.landed == []


async def test_the_gesture_works_in_the_other_direction_too(tmp_path: Path):
    """Grab on the phone, release at the PC. Same pairing, same link."""
    payload = tmp_path / "from-phone.jpg"
    payload.write_bytes(b"phone bytes" * 500)

    async with Rig(tmp_path, payload) as rig:
        await _feed(rig.phone_session, Pose.OPEN_PALM)
        await _feed(rig.phone_session, Pose.CLOSED_FIST)
        assert rig.phone_session.coordinator.holding is True

        await asyncio.sleep(0.1)
        assert rig.pc_session.coordinator.peers_holding() == [
            rig.phone.identity.fingerprint
        ]

        await _feed(rig.phone_session, Pose.NONE, frames=4)
        await _feed(rig.pc_session, Pose.CLOSED_FIST)
        await _feed(rig.pc_session, Pose.OPEN_PALM)

        await rig.settle()

        assert len(rig.landed) == 1
        assert rig.landed[0].name == "from-phone.jpg"


async def test_an_expired_hold_does_not_transfer_later(tmp_path: Path):
    payload = tmp_path / "stale.txt"
    payload.write_bytes(b"stale")

    async with Rig(tmp_path, payload) as rig:
        await _feed(rig.pc_session, Pose.OPEN_PALM)
        await _feed(rig.pc_session, Pose.CLOSED_FIST)

        # Force the hold to expire rather than waiting out the real timeout.
        rig.pc_session.coordinator.reset()

        await _feed(rig.phone_session, Pose.CLOSED_FIST)
        await _feed(rig.phone_session, Pose.OPEN_PALM)
        await rig.settle()

        assert rig.landed == []
