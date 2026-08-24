"""Gesture-driven transfer between two real processes on the real network.

Two modes, because the machine has one webcam and two devices are needed:

    --camera    use the real webcam and your real hand
    --script    perform the gesture automatically (for verification)

Sender (holds the file, grabs it):

    python tools/demo_gesture.py sender --dir work --file photo.jpg --camera
    python tools/demo_gesture.py sender --dir work --file photo.jpg --script

Receiver (stands in for the phone, catches it):

    python tools/demo_gesture.py receiver --dir work --script

The two find each other by mDNS; neither is told the other's address.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from airgrab.discovery import Advertiser, Browser, DiscoveredPeer  # noqa: E402
from airgrab.gesture import GestureConfig, Pose  # noqa: E402
from airgrab.node import Node, NodeConfig  # noqa: E402
from airgrab.session import GestureSession  # noqa: E402

SCRIPTED = GestureConfig(
    arm_seconds=0.0, grab_seconds=0.0, release_seconds=0.0,
    catch_seconds=0.0, disarm_seconds=0.0, min_frames=2,
    hold_timeout_seconds=60.0,
)


def _node(work_dir: Path, name: str) -> Node:
    return Node(
        NodeConfig(
            data_dir=work_dir / name,
            download_dir=work_dir / name / "downloads",
            display_name=name,
            port=0,
            auto_accept=True,
        )
    )


async def _pump(session: GestureSession, pose: Pose, frames: int = 3) -> None:
    for _ in range(frames):
        await session.observe(pose)
        await asyncio.sleep(0.02)


async def _camera_loop(session: GestureSession, label: str, stop_after: float) -> None:
    """Drive the session from the real webcam until the deadline."""
    import cv2

    from airgrab.handpose import HandPoseDetector

    detector = HandPoseDetector()
    capture = cv2.VideoCapture(0)
    if not capture.isOpened():
        print(f"[{label}] could not open the camera", flush=True)
        detector.close()
        return

    session.on_event = lambda e: print(f"[{label}] gesture {e.type.name}", flush=True)
    print(f"[{label}] camera running - open palm to arm, fist to grab", flush=True)

    deadline = time.monotonic() + stop_after
    try:
        while time.monotonic() < deadline:
            ok, frame = capture.read()
            if not ok:
                break
            frame = cv2.flip(frame, 1)
            pose = detector.classify(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            await session.observe(pose)
            cv2.putText(frame, f"{pose.name} / {session.machine.state.name}",
                        (14, 34), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (240, 240, 240), 2)
            cv2.imshow(f"AirGrab {label}", frame)
            if cv2.waitKey(1) & 0xFF == ord("q"):
                break
            await asyncio.sleep(0)
    finally:
        capture.release()
        cv2.destroyAllWindows()
        detector.close()


async def run_sender(work_dir: Path, file_path: Path, use_camera: bool) -> int:
    node = _node(work_dir, "SENDER")
    await node.start()

    found: list[DiscoveredPeer] = []
    browser = Browser(
        on_found=found.append, on_lost=lambda fp: None,
        ignore_fingerprint=node.identity.fingerprint,
    )
    await browser.start()
    print("[sender] looking for a device to pair with", flush=True)

    deadline = time.time() + 30
    while time.time() < deadline and not found:
        await asyncio.sleep(0.2)
    if not found:
        print("[sender] no devices discovered", flush=True)
        await browser.stop()
        await node.stop()
        return 1

    peer = found[0]
    print(f"[sender] found {peer.name} at {peer.host}:{peer.port}", flush=True)

    if not await node.pair_with(peer.host, peer.port, on_sas=lambda s: True):
        print("[sender] pairing failed", flush=True)
        await browser.stop()
        await node.stop()
        return 1
    print("[sender] paired", flush=True)

    session = GestureSession(
        node, capture=lambda: file_path,
        config=None if use_camera else SCRIPTED,
    )
    session.register_peer(peer.fingerprint, peer.host, peer.port)
    session.on_transfer = lambda fp, ok: print(
        f"[sender] transfer {'DELIVERED' if ok else 'FAILED'}", flush=True
    )

    await node.open_link(peer.host, peer.port, expect_fp=peer.fingerprint)
    print("[sender] control channel open", flush=True)

    digest = hashlib.sha256(file_path.read_bytes()).hexdigest()
    print(f"[sender] holding {file_path.name} sha256 {digest}", flush=True)

    if use_camera:
        await _camera_loop(session, "sender", stop_after=60)
    else:
        await asyncio.sleep(1.0)
        print("[sender] performing the grab gesture", flush=True)
        await _pump(session, Pose.OPEN_PALM)
        await _pump(session, Pose.CLOSED_FIST)
        print(f"[sender] holding={session.coordinator.holding}", flush=True)
        await _pump(session, Pose.NONE, frames=4)
        # Wait for the receiver to catch it.
        for _ in range(150):
            await asyncio.sleep(0.1)
            if not session.coordinator.holding:
                break

    await session.drain()
    await session.close()
    await browser.stop()
    await node.stop()
    return 0


async def run_receiver(work_dir: Path, use_camera: bool) -> int:
    node = _node(work_dir, "RECEIVER")
    await node.start()

    landed: list[Path] = []
    node.on_incoming_file = landed.append

    advertiser = Advertiser(
        node.identity.fingerprint, "RECEIVER", "windows", node.port
    )
    await advertiser.start()
    print(f"[receiver] advertising on port {node.port}", flush=True)

    session = GestureSession(
        node, capture=lambda: None,
        config=None if use_camera else SCRIPTED,
    )

    if use_camera:
        await _camera_loop(session, "receiver", stop_after=60)
    else:
        print("[receiver] waiting for a peer to announce a hold", flush=True)
        deadline = time.time() + 60
        while time.time() < deadline and not session.coordinator.peers_holding():
            await asyncio.sleep(0.1)

        holders = session.coordinator.peers_holding()
        if not holders:
            print("[receiver] nobody ever grabbed anything", flush=True)
        else:
            print(f"[receiver] {holders[0][:16]}... is holding something", flush=True)
            for peer_fp in holders:
                session.register_peer(peer_fp, "127.0.0.1", 0)
            print("[receiver] performing the release gesture", flush=True)
            await _pump(session, Pose.CLOSED_FIST)
            await _pump(session, Pose.OPEN_PALM)

        for _ in range(150):
            await asyncio.sleep(0.1)
            if landed:
                break

    await session.drain()
    await session.close()
    await advertiser.stop()
    await node.stop()

    if not landed:
        print("[receiver] nothing arrived", flush=True)
        return 1

    got = landed[0]
    print(f"[receiver] RECEIVED {got.name} ({got.stat().st_size} bytes)", flush=True)
    print(f"[receiver] sha256   {hashlib.sha256(got.read_bytes()).hexdigest()}",
          flush=True)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=["sender", "receiver"])
    parser.add_argument("--dir", required=True, type=Path)
    parser.add_argument("--file", type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--camera", action="store_true", help="use the real webcam")
    mode.add_argument("--script", action="store_true", help="perform the gesture automatically")
    args = parser.parse_args()

    args.dir.mkdir(parents=True, exist_ok=True)

    if args.role == "sender":
        if args.file is None:
            parser.error("--file is required for the sender")
        return asyncio.run(run_sender(args.dir, args.file, args.camera))
    return asyncio.run(run_receiver(args.dir, args.camera))


if __name__ == "__main__":
    raise SystemExit(main())
