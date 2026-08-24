import stat
import sys
from pathlib import Path

import pytest

from airgrab.identity import DeviceIdentity, fingerprint_of, verify_signature


@pytest.mark.skipif(sys.platform == "win32", reason="POSIX mode bits are advisory on Windows")
def test_private_key_is_not_readable_by_others(tmp_path: Path):
    ident = DeviceIdentity.load_or_create(tmp_path / "d", "TEST-PC")
    mode = ident.key_path.stat().st_mode
    assert mode & (stat.S_IRWXG | stat.S_IRWXO) == 0


def test_private_key_file_exists_and_is_a_regular_file(tmp_path: Path):
    ident = DeviceIdentity.load_or_create(tmp_path / "d", "TEST-PC")
    assert ident.key_path.is_file()
    assert b"PRIVATE KEY" in ident.key_path.read_bytes()


def test_fingerprint_is_64_hex_chars(tmp_path: Path):
    ident = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    assert len(ident.fingerprint) == 64
    assert ident.fingerprint == ident.fingerprint.lower()
    int(ident.fingerprint, 16)  # raises if not hex


def test_fingerprint_matches_certificate(tmp_path: Path):
    ident = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    assert ident.fingerprint == fingerprint_of(ident.cert_der)


def test_identity_is_stable_across_reloads(tmp_path: Path):
    first = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    second = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    assert first.fingerprint == second.fingerprint
    assert first.cert_der == second.cert_der


def test_separate_directories_get_separate_identities(tmp_path: Path):
    a = DeviceIdentity.load_or_create(tmp_path / "a", "A")
    b = DeviceIdentity.load_or_create(tmp_path / "b", "B")
    assert a.fingerprint != b.fingerprint


def test_signature_round_trip(tmp_path: Path):
    ident = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    sig = ident.sign(b"hello world")
    assert verify_signature(ident.cert_der, b"hello world", sig) is True


def test_signature_rejects_tampered_data(tmp_path: Path):
    ident = DeviceIdentity.load_or_create(tmp_path, "TEST-PC")
    sig = ident.sign(b"hello world")
    assert verify_signature(ident.cert_der, b"goodbye world", sig) is False


def test_signature_rejects_wrong_signer(tmp_path: Path):
    a = DeviceIdentity.load_or_create(tmp_path / "a", "A")
    b = DeviceIdentity.load_or_create(tmp_path / "b", "B")
    sig = a.sign(b"payload")
    assert verify_signature(b.cert_der, b"payload", sig) is False
