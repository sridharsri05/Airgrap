"""Frozen-vs-source asset resolution, without needing a real frozen build.

PyInstaller's bootloader sets ``sys.frozen`` and ``sys._MEIPASS`` before the
interpreter runs any application code, so both can be simulated exactly by
setting them on ``sys``. Neither attribute exists in a normal interpreter,
which is the case the code must not crash on either.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

from airgrab import resources


@pytest.fixture(autouse=True)
def clean_freeze_flags(monkeypatch):
    """Guarantee a known starting state whatever the host interpreter is."""
    monkeypatch.delattr(sys, "frozen", raising=False)
    monkeypatch.delattr(sys, "_MEIPASS", raising=False)


# ------------------------------------------------------------------- source

def test_not_frozen_when_attributes_absent():
    assert resources.is_frozen() is False


def test_source_root_contains_the_package():
    root = resources.resource_root()
    assert (root / "airgrab" / "resources.py").is_file()


def test_source_model_path_is_under_models():
    path = resources.model_path()
    assert path.parent.name == "models"
    assert path.name == "gesture_recognizer.task"
    assert path.is_absolute()


def test_matches_handpose_default_when_running_from_source():
    """The frozen layout only works if both resolutions agree at the source."""
    from airgrab.handpose import DEFAULT_MODEL_PATH

    assert resources.model_path() == Path(DEFAULT_MODEL_PATH)


def test_nothing_raises_with_attributes_absent():
    # The whole public surface, exercised with neither attribute defined.
    assert resources.resource_root().is_absolute()
    assert resources.resource_path("anything").name == "anything"
    assert isinstance(resources.model_is_available(), bool)


# ------------------------------------------------------------------- frozen

def test_frozen_root_is_meipass(tmp_path, monkeypatch):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)

    assert resources.is_frozen() is True
    assert resources.resource_root() == tmp_path


def test_frozen_model_path_is_under_meipass(tmp_path, monkeypatch):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)

    assert resources.model_path() == tmp_path / "models" / "gesture_recognizer.task"


def test_frozen_model_availability_follows_the_file(tmp_path, monkeypatch):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)

    assert resources.model_is_available() is False

    model = tmp_path / "models" / "gesture_recognizer.task"
    model.parent.mkdir(parents=True)
    model.write_bytes(b"not really a model")

    assert resources.model_is_available() is True


def test_resource_path_joins_multiple_parts(tmp_path, monkeypatch):
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)

    assert resources.resource_path("a", "b", "c.bin") == tmp_path / "a" / "b" / "c.bin"


# --------------------------------------------------------------- half-frozen

def test_frozen_without_meipass_falls_back_instead_of_raising(monkeypatch):
    """sys.frozen set but no _MEIPASS must not blow up at resolve time."""
    monkeypatch.setattr(sys, "frozen", True, raising=False)

    assert resources.is_frozen() is True
    root = resources.resource_root()
    assert root.is_absolute()
    assert (root / "airgrab" / "resources.py").is_file()


def test_meipass_without_frozen_still_uses_meipass(tmp_path, monkeypatch):
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)

    assert resources.is_frozen() is False
    assert resources.resource_root() == tmp_path


def test_empty_meipass_is_treated_as_absent(monkeypatch):
    monkeypatch.setattr(sys, "_MEIPASS", "", raising=False)

    assert (resources.resource_root() / "airgrab" / "resources.py").is_file()
