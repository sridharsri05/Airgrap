"""User settings, with defaults matching the spec's settled defaults.

A corrupt settings file falls back to defaults rather than refusing to start:
the cost of a wrong setting is an inconvenience, the cost of not launching is
a broken product.
"""

from __future__ import annotations

import json
import os
import platform
from dataclasses import dataclass, replace
from pathlib import Path

DEFAULT_PORT = 53421


@dataclass(frozen=True)
class Settings:
    display_name: str
    download_dir: Path
    auto_accept: bool = True
    autostart: bool = False
    port: int = DEFAULT_PORT


def default_data_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA") or str(Path.home() / ".local" / "share")
    return Path(base) / "AirGrab"


def _defaults() -> Settings:
    return Settings(
        display_name=platform.node() or "Windows PC",
        download_dir=Path.home() / "Downloads" / "AirGrab",
        auto_accept=True,
        autostart=False,
        port=DEFAULT_PORT,
    )


def load_settings(path: Path) -> Settings:
    defaults = _defaults()
    path = Path(path)
    if not path.exists():
        return defaults
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        return replace(
            defaults,
            display_name=str(raw.get("display_name", defaults.display_name)),
            download_dir=Path(raw.get("download_dir", str(defaults.download_dir))),
            auto_accept=bool(raw.get("auto_accept", defaults.auto_accept)),
            autostart=bool(raw.get("autostart", defaults.autostart)),
            port=int(raw.get("port", defaults.port)),
        )
    except (json.JSONDecodeError, TypeError, ValueError, OSError, AttributeError):
        return defaults


def save_settings(path: Path, settings: Settings) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "display_name": settings.display_name,
                "download_dir": str(settings.download_dir),
                "auto_accept": settings.auto_accept,
                "autostart": settings.autostart,
                "port": settings.port,
            },
            indent=2,
        ),
        encoding="utf-8",
    )
