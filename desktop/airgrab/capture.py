"""What actually gets sent when you close your fist.

A screenshot of the current screen is the honest default: it needs no
permission on Windows, it works from any application, and it matches what the
user was looking at when they grabbed. Selecting a specific file or the
clipboard image can layer on later — this is the floor, not the ceiling.

Captures accumulate in a scratch directory, so old ones are pruned. A folder
that grows without bound is a bug that only shows up months later, on someone
else's disk.
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Callable

MAX_KEPT_CAPTURES = 20
CAPTURE_PREFIX = "airgrab-capture-"


def _default_grabber():
    from PIL import ImageGrab

    return ImageGrab.grab()


class ScreenCapture:
    def __init__(
        self,
        directory: Path,
        grabber: Callable[[], object] = _default_grabber,
        clock: Callable[[], float] = time.time,
        keep: int = MAX_KEPT_CAPTURES,
    ) -> None:
        self._directory = Path(directory)
        self._grabber = grabber
        self._clock = clock
        self._keep = keep

    def capture(self) -> Path | None:
        """Grab the screen. Returns None rather than raising: a failed capture
        should cancel one gesture, never take the application down."""
        try:
            image = self._grabber()
        except Exception:
            return None
        if image is None:
            return None

        self._directory.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime(self._clock()))
        path = self._directory / f"{CAPTURE_PREFIX}{stamp}.png"

        # Two grabs inside the same second would otherwise overwrite silently.
        counter = 2
        while path.exists():
            path = self._directory / f"{CAPTURE_PREFIX}{stamp}-{counter}.png"
            counter += 1

        try:
            image.save(path)
        except Exception:
            return None

        self._prune()
        return path

    def _prune(self) -> None:
        try:
            existing = sorted(
                self._directory.glob(f"{CAPTURE_PREFIX}*.png"),
                key=lambda p: p.stat().st_mtime,
            )
        except OSError:
            return
        for stale in existing[: max(0, len(existing) - self._keep)]:
            try:
                stale.unlink()
            except OSError:
                pass
