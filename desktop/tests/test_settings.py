"""Tests for the widget-free half of the settings window.

Nothing here touches tkinter: the dialog is a thin shell over these functions
precisely so that CI, which has no display, can still cover the decisions.
"""

from pathlib import Path

from airgrab.config import Settings, load_settings
from airgrab.ui.settings import (
    apply_settings,
    settings_to_values,
    validate_settings,
)


def _form(**overrides) -> dict:
    values = {
        "display_name": "MY-PC",
        "download_dir": r"C:\Users\me\Downloads\AirGrab",
        "auto_accept": True,
        "autostart": False,
        "port": "53421",
    }
    values.update(overrides)
    return values


def test_valid_input_produces_settings():
    settings, errors = validate_settings(_form())
    assert errors == []
    assert settings == Settings(
        display_name="MY-PC",
        download_dir=Path(r"C:\Users\me\Downloads\AirGrab"),
        auto_accept=True,
        autostart=False,
        port=53421,
    )


def test_empty_display_name_is_rejected():
    settings, errors = validate_settings(_form(display_name=""))
    assert settings is None
    assert any("Display name" in e for e in errors)


def test_whitespace_only_display_name_is_rejected():
    settings, errors = validate_settings(_form(display_name="   "))
    assert settings is None
    assert any("Display name" in e for e in errors)


def test_empty_download_folder_is_rejected():
    settings, errors = validate_settings(_form(download_dir="   "))
    assert settings is None
    assert any("Download folder" in e for e in errors)


def test_port_below_range_is_rejected():
    settings, errors = validate_settings(_form(port="1023"))
    assert settings is None
    assert any("Port" in e for e in errors)


def test_port_above_range_is_rejected():
    settings, errors = validate_settings(_form(port="65536"))
    assert settings is None
    assert any("Port" in e for e in errors)


def test_port_boundaries_are_accepted():
    for value in ("1024", "65535"):
        settings, errors = validate_settings(_form(port=value))
        assert errors == []
        assert settings is not None and settings.port == int(value)


def test_non_numeric_port_is_rejected():
    for value in ("", "  ", "abc", "53421.5", "53 421"):
        settings, errors = validate_settings(_form(port=value))
        assert settings is None, value
        assert any("Port" in e for e in errors), value


def test_multiple_errors_are_reported_together():
    settings, errors = validate_settings(
        _form(display_name="", download_dir="", port="nope")
    )
    assert settings is None
    assert len(errors) == 3


def test_whitespace_is_trimmed():
    settings, errors = validate_settings(
        _form(display_name="  MY-PC  ", download_dir="  C:\\drop  ", port="  53421 ")
    )
    assert errors == []
    assert settings.display_name == "MY-PC"
    assert settings.download_dir == Path("C:\\drop")
    assert settings.port == 53421


def test_checkbox_values_are_coerced_to_bool():
    settings, errors = validate_settings(_form(auto_accept=0, autostart=1))
    assert errors == []
    assert settings.auto_accept is False
    assert settings.autostart is True


def test_missing_checkbox_keys_use_documented_defaults():
    values = _form()
    del values["auto_accept"]
    del values["autostart"]
    settings, errors = validate_settings(values)
    assert errors == []
    assert settings.auto_accept is True
    assert settings.autostart is False


def test_settings_to_values_round_trips_through_validation():
    original = Settings(
        display_name="DESK-1",
        download_dir=Path("C:\\drop"),
        auto_accept=False,
        autostart=True,
        port=51000,
    )
    settings, errors = validate_settings(settings_to_values(original))
    assert errors == []
    assert settings == original


def test_apply_settings_round_trips_to_disk(tmp_path: Path):
    path = tmp_path / "settings.json"
    settings, errors = validate_settings(
        _form(download_dir=str(tmp_path / "drop"), port="51000", autostart=True)
    )
    assert errors == []

    calls: list[bool] = []
    warnings = apply_settings(path, settings, previous=None, set_autostart=calls.append)

    assert warnings == []
    assert calls == [True]
    assert load_settings(path) == settings


def test_apply_settings_only_touches_autostart_when_it_changed(tmp_path: Path):
    path = tmp_path / "settings.json"
    previous = Settings(
        display_name="MY-PC",
        download_dir=tmp_path / "drop",
        auto_accept=True,
        autostart=False,
        port=53421,
    )
    settings, _ = validate_settings(
        _form(download_dir=str(tmp_path / "drop"), display_name="RENAMED")
    )

    calls: list[bool] = []
    assert apply_settings(path, settings, previous, calls.append) == []
    assert calls == []
    assert load_settings(path).display_name == "RENAMED"


def test_apply_settings_reports_autostart_failure_but_still_saves(tmp_path: Path):
    path = tmp_path / "settings.json"

    def boom(_enabled: bool) -> None:
        raise OSError("access denied")

    settings, _ = validate_settings(
        _form(download_dir=str(tmp_path / "drop"), autostart=True)
    )
    warnings = apply_settings(path, settings, previous=None, set_autostart=boom)

    assert len(warnings) == 1
    assert "access denied" in warnings[0]
    assert load_settings(path) == settings
