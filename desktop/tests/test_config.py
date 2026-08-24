from pathlib import Path

from airgrab.config import Settings, load_settings, save_settings


def test_missing_file_yields_documented_defaults(tmp_path: Path):
    settings = load_settings(tmp_path / "settings.json")
    assert settings.auto_accept is True
    assert settings.port == 53421
    assert settings.display_name


def test_settings_round_trip(tmp_path: Path):
    path = tmp_path / "settings.json"
    original = Settings(
        display_name="MY-PC",
        download_dir=tmp_path / "drop",
        auto_accept=False,
        autostart=True,
        port=51000,
    )
    save_settings(path, original)
    assert load_settings(path) == original


def test_corrupt_settings_fall_back_to_defaults(tmp_path: Path):
    path = tmp_path / "settings.json"
    path.write_text("{ not valid json")
    assert load_settings(path).port == 53421


def test_partial_settings_keep_defaults_for_missing_keys(tmp_path: Path):
    path = tmp_path / "settings.json"
    path.write_text('{"port": 51234}')
    settings = load_settings(path)
    assert settings.port == 51234
    assert settings.auto_accept is True
