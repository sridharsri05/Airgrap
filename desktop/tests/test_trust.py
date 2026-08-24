from pathlib import Path

from airgrab.trust import TrustStore

FP_A = "a" * 64
FP_B = "b" * 64


def test_unknown_peer_is_not_trusted(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    assert store.is_trusted(FP_A) is False


def test_added_peer_is_trusted(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    store.add(FP_A, "My Phone", "android")
    assert store.is_trusted(FP_A) is True


def test_trust_persists_across_instances(tmp_path: Path):
    path = tmp_path / "trust.json"
    TrustStore(path).add(FP_A, "My Phone", "android")
    assert TrustStore(path).is_trusted(FP_A) is True


def test_get_returns_peer_details(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    store.add(FP_A, "My Phone", "android")
    peer = store.get(FP_A)
    assert peer is not None
    assert peer.name == "My Phone"
    assert peer.platform == "android"


def test_adding_twice_updates_rather_than_duplicates(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    store.add(FP_A, "Old Name", "android")
    store.add(FP_A, "New Name", "android")
    assert len(store.all()) == 1
    assert store.get(FP_A).name == "New Name"


def test_remove_revokes_trust(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    store.add(FP_A, "My Phone", "android")
    store.remove(FP_A)
    assert store.is_trusted(FP_A) is False


def test_removing_unknown_peer_is_harmless(tmp_path: Path):
    store = TrustStore(tmp_path / "trust.json")
    store.remove(FP_B)
    assert store.all() == []


def test_corrupt_store_is_treated_as_empty(tmp_path: Path):
    path = tmp_path / "trust.json"
    path.write_text("this is not json")
    assert TrustStore(path).all() == []
