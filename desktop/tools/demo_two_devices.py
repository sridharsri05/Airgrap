"""Manual verification harness: two real AirGrab devices, two OS processes.

The automated suite runs both peers inside one process on 127.0.0.1. That
proves the protocol but not the product: it never touches mDNS on a real
interface, never crosses a process boundary, and never uses the machine's
actual network stack. This script does all three.

    python tools/demo_two_devices.py receiver --dir <workdir>
    python tools/demo_two_devices.py sender   --dir <workdir> --file <path>

The sender finds the receiver by mDNS alone — it is given no address.
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
from airgrab.node import Node, NodeConfig  # noqa: E402


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


async def run_receiver(work_dir: Path) -> int:
    node = _node(work_dir, "RECEIVER")
    await node.start()

    received: list[Path] = []
    node.on_incoming_file = received.append

    advertiser = Advertiser(
        node.identity.fingerprint, "RECEIVER", "windows", node.port
    )
    await advertiser.start()

    print(f"[receiver] listening on port {node.port}", flush=True)
    print(f"[receiver] fingerprint {node.identity.fingerprint[:16]}...", flush=True)
    print("[receiver] advertising over mDNS, waiting for a file", flush=True)

    deadline = time.time() + 90
    while time.time() < deadline and not received:
        await asyncio.sleep(0.2)

    await advertiser.stop()
    await node.stop()

    if not received:
        print("[receiver] TIMEOUT - nothing arrived", flush=True)
        return 1

    landed = received[0]
    digest = hashlib.sha256(landed.read_bytes()).hexdigest()
    print(f"[receiver] RECEIVED {landed.name} ({landed.stat().st_size} bytes)", flush=True)
    print(f"[receiver] saved to  {landed}", flush=True)
    print(f"[receiver] sha256    {digest}", flush=True)
    return 0


async def run_sender(work_dir: Path, file_path: Path) -> int:
    node = _node(work_dir, "SENDER")
    await node.start()

    found: list[DiscoveredPeer] = []
    browser = Browser(
        on_found=found.append,
        on_lost=lambda fp: None,
        ignore_fingerprint=node.identity.fingerprint,
    )
    await browser.start()
    print("[sender] browsing mDNS for AirGrab devices", flush=True)

    deadline = time.time() + 30
    while time.time() < deadline and not found:
        await asyncio.sleep(0.2)

    if not found:
        print("[sender] no devices discovered", flush=True)
        await browser.stop()
        await node.stop()
        return 1

    peer = found[0]
    print(f"[sender] discovered {peer.name} at {peer.host}:{peer.port}", flush=True)

    codes: list[str] = []

    def show_code(sas: str) -> bool:
        codes.append(sas)
        print(f"[sender] pairing code {sas} - accepting", flush=True)
        return True

    paired = await node.pair_with(peer.host, peer.port, on_sas=show_code)
    if not paired:
        print("[sender] pairing FAILED", flush=True)
        await browser.stop()
        await node.stop()
        return 1
    print("[sender] paired", flush=True)

    source_digest = hashlib.sha256(file_path.read_bytes()).hexdigest()
    print(f"[sender] sending {file_path.name} sha256 {source_digest}", flush=True)

    last = {"pct": -1}

    def progress(received: int, total: int) -> None:
        pct = int(received * 100 / total) if total else 100
        if pct >= last["pct"] + 25:
            last["pct"] = pct
            print(f"[sender] progress {pct}%", flush=True)

    ok = await node.send_file(
        peer.host, peer.port, file_path,
        on_progress=progress,
        expect_fp=peer.fingerprint,
    )

    await browser.stop()
    await node.stop()

    print(f"[sender] {'DELIVERED' if ok else 'FAILED'}", flush=True)
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=["receiver", "sender"])
    parser.add_argument("--dir", required=True, type=Path)
    parser.add_argument("--file", type=Path)
    args = parser.parse_args()

    args.dir.mkdir(parents=True, exist_ok=True)

    if args.role == "receiver":
        return asyncio.run(run_receiver(args.dir))

    if args.file is None:
        parser.error("--file is required for the sender role")
    return asyncio.run(run_sender(args.dir, args.file))


if __name__ == "__main__":
    raise SystemExit(main())
