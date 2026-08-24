import json
from pathlib import Path

import pytest

from airgrab.protocol import (
    PROTOCOL_VERSION,
    MessageType,
    ProtocolError,
    compute_sas,
    decode,
    encode,
)

FP_A = "a" * 64
FP_B = "b" * 64


def test_protocol_version_is_one():
    assert PROTOCOL_VERSION == 1


def test_encode_decode_round_trip():
    raw = encode(MessageType.HELLO, 1, {"id": FP_A})
    env = decode(raw)
    assert env.type == MessageType.HELLO
    assert env.seq == 1
    assert env.payload == {"id": FP_A}


def test_encode_produces_json_with_expected_keys():
    raw = json.loads(encode(MessageType.PING, 7, {}))
    assert set(raw.keys()) == {"type", "seq", "payload"}


def test_decode_rejects_non_json():
    with pytest.raises(ProtocolError):
        decode("not json at all")


def test_decode_rejects_missing_type():
    with pytest.raises(ProtocolError):
        decode(json.dumps({"seq": 1, "payload": {}}))


def test_decode_rejects_non_integer_seq():
    with pytest.raises(ProtocolError):
        decode(json.dumps({"type": "ping", "seq": "one", "payload": {}}))


def test_decode_rejects_boolean_seq():
    """bool is a subclass of int in Python; the Kotlin side has no such trap."""
    with pytest.raises(ProtocolError):
        decode(json.dumps({"type": "ping", "seq": True, "payload": {}}))


def test_decode_defaults_missing_payload_to_empty():
    env = decode(json.dumps({"type": "ping", "seq": 1}))
    assert env.payload == {}


def test_sas_is_six_digits():
    sas = compute_sas(FP_A, FP_B)
    assert len(sas) == 6
    assert sas.isdigit()


def test_sas_is_order_independent():
    assert compute_sas(FP_A, FP_B) == compute_sas(FP_B, FP_A)


def test_sas_differs_for_different_peers():
    assert compute_sas(FP_A, FP_B) != compute_sas(FP_A, "c" * 64)


def test_sas_matches_shared_vectors():
    vectors = json.loads(
        (Path(__file__).parents[2] / "protocol" / "vectors" / "sas.json").read_text()
    )
    assert vectors["cases"], "vectors file must not be empty"
    for case in vectors["cases"]:
        assert compute_sas(case["a"], case["b"]) == case["sas"]
