from pathlib import Path

from airgrab.auth import (
    new_nonce,
    signing_payload,
    verify_auth_response,
    verify_server_identity,
)
from airgrab.identity import DeviceIdentity


def _pair(tmp_path: Path):
    server = DeviceIdentity.load_or_create(tmp_path / "server", "SERVER")
    client = DeviceIdentity.load_or_create(tmp_path / "client", "CLIENT")
    return server, client


def test_nonce_is_64_hex_chars_and_unique():
    a, b = new_nonce(), new_nonce()
    assert len(a) == 64 and len(b) == 64
    int(a, 16)
    assert a != b


def test_valid_response_is_accepted(tmp_path: Path):
    server, client = _pair(tmp_path)
    nonce = new_nonce()
    sig = client.sign(signing_payload(nonce, server.fingerprint, client.fingerprint))
    result = verify_auth_response(
        client.cert_der, sig, nonce, server.fingerprint, client.fingerprint
    )
    assert result.ok is True


def test_wrong_nonce_is_rejected(tmp_path: Path):
    server, client = _pair(tmp_path)
    sig = client.sign(
        signing_payload(new_nonce(), server.fingerprint, client.fingerprint)
    )
    result = verify_auth_response(
        client.cert_der, sig, new_nonce(), server.fingerprint, client.fingerprint
    )
    assert result.ok is False
    assert result.reason == "bad_signature"


def test_signature_from_another_connection_is_rejected(tmp_path: Path):
    """A signature bound to a different server fingerprint must not replay."""
    server, client = _pair(tmp_path)
    other_server_fp = "9" * 64
    nonce = new_nonce()
    sig = client.sign(signing_payload(nonce, other_server_fp, client.fingerprint))
    result = verify_auth_response(
        client.cert_der, sig, nonce, server.fingerprint, client.fingerprint
    )
    assert result.ok is False
    assert result.reason == "bad_signature"


def test_certificate_not_matching_claimed_id_is_rejected(tmp_path: Path):
    """Presenting a valid signature while claiming someone else's ID fails."""
    server, client = _pair(tmp_path)
    nonce = new_nonce()
    lie = "7" * 64
    sig = client.sign(signing_payload(nonce, server.fingerprint, lie))
    result = verify_auth_response(client.cert_der, sig, nonce, server.fingerprint, lie)
    assert result.ok is False
    assert result.reason == "identity_mismatch"


def test_garbage_certificate_is_rejected(tmp_path: Path):
    server, client = _pair(tmp_path)
    nonce = new_nonce()
    sig = client.sign(signing_payload(nonce, server.fingerprint, client.fingerprint))
    result = verify_auth_response(
        b"not a certificate", sig, nonce, server.fingerprint, client.fingerprint
    )
    assert result.ok is False


# --- server-side identity, the half that stops machine-in-the-middle ---


def test_server_identity_is_derived_from_certificate_not_claimed(tmp_path: Path):
    server, client = _pair(tmp_path)
    nonce = new_nonce()
    sig = server.sign(signing_payload(nonce, server.fingerprint, client.fingerprint))
    result, derived = verify_server_identity(
        server.cert_der, sig, nonce, client.fingerprint, pinned_fp=None
    )
    assert result.ok is True
    assert derived == server.fingerprint


def test_pinned_server_fingerprint_must_match(tmp_path: Path):
    server, client = _pair(tmp_path)
    nonce = new_nonce()
    sig = server.sign(signing_payload(nonce, server.fingerprint, client.fingerprint))
    result, _ = verify_server_identity(
        server.cert_der, sig, nonce, client.fingerprint, pinned_fp="9" * 64
    )
    assert result.ok is False
    assert result.reason == "pin_mismatch"


def test_impostor_cannot_impersonate_a_pinned_server(tmp_path: Path):
    """The core MITM case: attacker has its own key, claims the real server."""
    real, client = _pair(tmp_path)
    attacker = DeviceIdentity.load_or_create(tmp_path / "attacker", "MITM")
    nonce = new_nonce()
    # Attacker signs correctly with ITS key, and cannot do otherwise.
    sig = attacker.sign(signing_payload(nonce, attacker.fingerprint, client.fingerprint))
    result, derived = verify_server_identity(
        attacker.cert_der, sig, nonce, client.fingerprint, pinned_fp=real.fingerprint
    )
    assert result.ok is False
    assert result.reason == "pin_mismatch"
    assert derived == attacker.fingerprint


def test_impostor_cannot_forge_a_signature_for_a_stolen_certificate(tmp_path: Path):
    """Attacker presents the real server's certificate but lacks its key."""
    real, client = _pair(tmp_path)
    attacker = DeviceIdentity.load_or_create(tmp_path / "attacker", "MITM")
    nonce = new_nonce()
    sig = attacker.sign(signing_payload(nonce, real.fingerprint, client.fingerprint))
    result, _ = verify_server_identity(
        real.cert_der, sig, nonce, client.fingerprint, pinned_fp=real.fingerprint
    )
    assert result.ok is False
    assert result.reason == "bad_signature"


def test_mitm_produces_a_different_pairing_code_on_each_side(tmp_path: Path):
    """During pairing there is no pin, so SAS mismatch is the defence."""
    from airgrab.protocol import compute_sas

    real, client = _pair(tmp_path)
    attacker = DeviceIdentity.load_or_create(tmp_path / "attacker", "MITM")

    client_side_sas = compute_sas(client.fingerprint, attacker.fingerprint)
    server_side_sas = compute_sas(real.fingerprint, attacker.fingerprint)
    honest_sas = compute_sas(client.fingerprint, real.fingerprint)

    assert client_side_sas != server_side_sas
    assert client_side_sas != honest_sas
