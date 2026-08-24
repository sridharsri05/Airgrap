import hashlib
from pathlib import Path

import pytest

from airgrab.node import Node, NodeConfig


def _config(root: Path, name: str, auto_accept: bool = True) -> NodeConfig:
    return NodeConfig(
        data_dir=root / name,
        download_dir=root / name / "downloads",
        display_name=name,
        port=0,
        auto_accept=auto_accept,
    )


async def _running(root: Path, name: str, **kw):
    node = Node(_config(root, name, **kw))
    await node.start()
    return node


async def test_two_nodes_have_distinct_identities(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    try:
        assert a.identity.fingerprint != b.identity.fingerprint
    finally:
        await a.stop()
        await b.stop()


async def test_pairing_shows_matching_code_and_grants_mutual_trust(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    codes: list[str] = []
    try:
        ok = await a.pair_with(
            "127.0.0.1", b.port, on_sas=lambda s: (codes.append(s), True)[1]
        )
        assert ok is True
        assert len(codes) == 1 and len(codes[0]) == 6
        assert a.trust.is_trusted(b.identity.fingerprint)
        assert b.trust.is_trusted(a.identity.fingerprint)
    finally:
        await a.stop()
        await b.stop()


async def test_declining_the_code_leaves_both_untrusted(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    try:
        ok = await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: False)
        assert ok is False
        assert not a.trust.is_trusted(b.identity.fingerprint)
        assert not b.trust.is_trusted(a.identity.fingerprint)
    finally:
        await a.stop()
        await b.stop()


async def test_transfer_to_untrusted_peer_is_refused(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "f.txt"
    payload.write_bytes(b"data")
    try:
        assert await a.send_file("127.0.0.1", b.port, payload) is False
    finally:
        await a.stop()
        await b.stop()


async def test_paired_nodes_transfer_a_file(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "photo.jpg"
    payload.write_bytes(b"pretend jpeg bytes" * 1000)
    landed: list[Path] = []
    b.on_incoming_file = landed.append
    try:
        await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: True)
        assert await a.send_file("127.0.0.1", b.port, payload) is True
        assert len(landed) == 1
        assert landed[0].read_bytes() == payload.read_bytes()
        assert landed[0].name == "photo.jpg"
    finally:
        await a.stop()
        await b.stop()


async def test_progress_is_reported_and_ends_at_file_size(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "big.bin"
    payload.write_bytes(b"x" * (2 * 1024 * 1024))
    seen: list[tuple[int, int]] = []
    try:
        await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: True)
        await a.send_file(
            "127.0.0.1", b.port, payload, on_progress=lambda r, t: seen.append((r, t))
        )
        size = payload.stat().st_size
        assert seen
        assert seen[-1] == (size, size)
    finally:
        await a.stop()
        await b.stop()


async def test_collision_is_renamed_not_overwritten(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "note.txt"
    payload.write_bytes(b"second version")
    b.config.download_dir.mkdir(parents=True, exist_ok=True)
    (b.config.download_dir / "note.txt").write_bytes(b"first version")
    landed: list[Path] = []
    b.on_incoming_file = landed.append
    try:
        await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: True)
        await a.send_file("127.0.0.1", b.port, payload)
        assert landed[0].name == "note (2).txt"
        assert (b.config.download_dir / "note.txt").read_bytes() == b"first version"
    finally:
        await a.stop()
        await b.stop()


async def test_received_file_hash_matches_source(tmp_path: Path):
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "data.bin"
    payload.write_bytes(bytes(range(256)) * 500)
    landed: list[Path] = []
    b.on_incoming_file = landed.append
    try:
        await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: True)
        await a.send_file("127.0.0.1", b.port, payload)
        expected = hashlib.sha256(payload.read_bytes()).hexdigest()
        assert hashlib.sha256(landed[0].read_bytes()).hexdigest() == expected
    finally:
        await a.stop()
        await b.stop()


async def test_paired_peer_that_changes_certificate_is_refused(tmp_path: Path):
    """After pairing, a device presenting a different key must be rejected.

    This is the machine-in-the-middle case at the connection level: the
    attacker controls the address but not the private key. Deleting the
    identity files and restarting simulates exactly that.
    """
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    payload = tmp_path / "f.txt"
    payload.write_bytes(b"data")
    try:
        await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: True)
        assert await a.send_file("127.0.0.1", b.port, payload) is True

        # B is replaced by an impostor at the same address.
        await b.stop()
        for stale in (tmp_path / "B").glob("device-*.pem"):
            stale.unlink()
        real_fingerprint = b.identity.fingerprint
        impostor = await _running(tmp_path, "B")
        assert impostor.identity.fingerprint != real_fingerprint
        try:
            # Without naming an expected device: the impostor's derived
            # fingerprint is not trusted, so the file is simply not sent.
            assert await a.send_file("127.0.0.1", impostor.port, payload) is False

            # Naming the device we meant to reach turns it into an alarm.
            with pytest.raises(ConnectionError, match="pin_mismatch"):
                await a.send_file(
                    "127.0.0.1", impostor.port, payload, expect_fp=real_fingerprint
                )
        finally:
            await impostor.stop()
    finally:
        await a.stop()


async def test_pin_is_not_bypassed_by_claiming_an_unknown_identity(tmp_path: Path):
    """Regression: the pin must come from caller intent, not the peer's claim.

    An earlier implementation looked up the pin using the fingerprint the peer
    declared in hello_ack. An attacker could therefore skip the check entirely
    by declaring a fingerprint absent from the trust store.
    """
    a = await _running(tmp_path, "A")
    b = await _running(tmp_path, "B")
    stranger = await _running(tmp_path, "STRANGER")
    payload = tmp_path / "f.txt"
    payload.write_bytes(b"data")
    try:
        await a.pair_with("127.0.0.1", b.port, on_sas=lambda s: True)
        # A intends to reach B but is answered by STRANGER.
        with pytest.raises(ConnectionError, match="pin_mismatch"):
            await a.send_file(
                "127.0.0.1", stranger.port, payload,
                expect_fp=b.identity.fingerprint,
            )
    finally:
        await a.stop()
        await b.stop()
        await stranger.stop()
