import hashlib
from pathlib import Path

import pytest

from airgrab.transfer import (
    TicketError,
    TicketStore,
    TransferError,
    hash_file,
    receive_to_file,
    unique_destination,
)

FP_A = "a" * 64
FP_B = "b" * 64


async def _chunks(*blobs: bytes):
    for blob in blobs:
        yield blob


def test_issued_ticket_can_be_redeemed_once():
    store = TicketStore()
    ticket = store.issue(FP_A, "0" * 64, 10)
    assert store.redeem(ticket, FP_A).peer_fp == FP_A
    with pytest.raises(TicketError) as exc:
        store.redeem(ticket, FP_A)
    assert exc.value.code == "ticket_invalid"


def test_ticket_is_bound_to_one_peer():
    store = TicketStore()
    ticket = store.issue(FP_A, "0" * 64, 10)
    with pytest.raises(TicketError) as exc:
        store.redeem(ticket, FP_B)
    assert exc.value.code == "ticket_invalid"


def test_unknown_ticket_is_rejected():
    store = TicketStore()
    with pytest.raises(TicketError):
        store.redeem("nope", FP_A)


def test_expired_ticket_is_rejected():
    # issue() reads the clock once, redeem() once: 61s elapsed, over the 60s TTL.
    clock = iter([100.0, 161.0])
    store = TicketStore(clock=lambda: next(clock))
    ticket = store.issue(FP_A, "0" * 64, 10)
    with pytest.raises(TicketError) as exc:
        store.redeem(ticket, FP_A)
    assert exc.value.code == "ticket_expired"


def test_unique_destination_avoids_collision(tmp_path: Path):
    (tmp_path / "photo.jpg").write_bytes(b"x")
    assert unique_destination(tmp_path, "photo.jpg").name == "photo (2).jpg"


def test_unique_destination_keeps_counting(tmp_path: Path):
    (tmp_path / "photo.jpg").write_bytes(b"x")
    (tmp_path / "photo (2).jpg").write_bytes(b"x")
    assert unique_destination(tmp_path, "photo.jpg").name == "photo (3).jpg"


def test_unique_destination_passes_through_when_free(tmp_path: Path):
    assert unique_destination(tmp_path, "photo.jpg").name == "photo.jpg"


def test_unique_destination_strips_path_separators(tmp_path: Path):
    result = unique_destination(tmp_path, "../../evil.txt")
    assert result.parent == tmp_path
    assert result.name == "evil.txt"


def test_unique_destination_strips_windows_separators(tmp_path: Path):
    result = unique_destination(tmp_path, r"..\..\windows\system32\evil.dll")
    assert result.parent == tmp_path
    assert result.name == "evil.dll"


def test_unique_destination_handles_empty_name(tmp_path: Path):
    assert unique_destination(tmp_path, "").name == "received"


async def test_receive_writes_verified_file(tmp_path: Path):
    data = b"hello world"
    digest = hashlib.sha256(data).hexdigest()
    dest = tmp_path / "out.bin"
    seen: list[int] = []

    result = await receive_to_file(
        _chunks(b"hello ", b"world"), dest, digest, seen.append
    )

    assert result == dest
    assert dest.read_bytes() == data
    assert seen and seen[-1] == len(data)


async def test_receive_rejects_corrupt_data_and_leaves_nothing(tmp_path: Path):
    dest = tmp_path / "out.bin"
    with pytest.raises(TransferError) as exc:
        await receive_to_file(_chunks(b"tampered"), dest, "0" * 64, lambda n: None)
    assert exc.value.code == "hash_mismatch"
    assert not dest.exists()
    assert list(tmp_path.iterdir()) == []


async def test_receive_leaves_no_partial_file_on_stream_error(tmp_path: Path):
    async def exploding():
        yield b"partial"
        raise ConnectionResetError("peer vanished")

    dest = tmp_path / "out.bin"
    with pytest.raises(TransferError) as exc:
        await receive_to_file(exploding(), dest, "0" * 64, lambda n: None)
    assert exc.value.code == "transfer_aborted"
    assert list(tmp_path.iterdir()) == []


def test_hash_file_matches_hashlib(tmp_path: Path):
    path = tmp_path / "f.bin"
    path.write_bytes(b"some content")
    assert hash_file(path) == hashlib.sha256(b"some content").hexdigest()
