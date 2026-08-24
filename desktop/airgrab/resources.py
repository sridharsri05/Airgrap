"""Where the runtime assets live, in source checkouts and frozen builds alike.

The gesture model is an 8 MB file that is not importable Python, so it has to
travel beside the code rather than inside it. Where "beside" is depends on how
the application was started, and getting that wrong is the single most common
way a PyInstaller build passes every test and then dies on the user's machine.

Running from source, the layout is::

    desktop/
        airgrab/resources.py
        models/gesture_recognizer.task

so the root is ``Path(__file__).parents[1]``.

Frozen by PyInstaller, there is no ``desktop/`` any more. The bootloader
unpacks the bundled data into a temporary directory and records it as
``sys._MEIPASS``; ``__file__`` for a module inside the frozen archive points
into that same tree but is *not* guaranteed to be a real, walkable path, and
``sys.executable`` points at the .exe in the dist folder, which is the parent
of ``_internal`` rather than of ``models``. So the only reliable root is
``sys._MEIPASS`` itself, and assets must be looked up relative to it.

Two details worth keeping:

* ``sys._MEIPASS`` is set by the bootloader and simply does not exist in a
  normal interpreter, so it must be read with ``getattr``, never ``sys._MEIPASS``
  directly. ``sys.frozen`` is likewise absent when running from source.
* ``is_frozen()`` keys off ``sys.frozen`` but the root falls back to the
  source layout if ``_MEIPASS`` is somehow missing. A build that sets one flag
  and not the other should degrade to a wrong-but-defined path rather than
  raising ``AttributeError`` at import time, where no user-facing error
  handling exists yet.

``airgrab.handpose.DEFAULT_MODEL_PATH`` computes the same path from
``__file__``, which happens to work under PyInstaller too *because* the
bundled ``models/`` directory is placed at the archive root, one level above
the bundled ``airgrab`` package — the build script deliberately preserves that
relative layout. This module is the explicit, tested statement of that
contract: keep the model at ``<root>/models/gesture_recognizer.task`` and both
resolutions agree.
"""

from __future__ import annotations

import sys
from pathlib import Path

MODEL_FILENAME = "gesture_recognizer.task"
MODEL_DIRNAME = "models"


def is_frozen() -> bool:
    """True when running from a PyInstaller (or similar) frozen build."""
    return bool(getattr(sys, "frozen", False))


def resource_root() -> Path:
    """The directory that bundled data files are rooted at.

    Frozen: the bootloader's unpack directory (``sys._MEIPASS``).
    Source: the ``desktop/`` directory containing the ``airgrab`` package.
    """
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        return Path(meipass)
    return Path(__file__).resolve().parents[1]


def resource_path(*parts: str) -> Path:
    """Resolve a bundled asset path, e.g. ``resource_path("models", "x.task")``.

    The path is returned whether or not the file exists; callers that need a
    present file should check, so they can produce their own error message.
    """
    return resource_root().joinpath(*parts)


def model_path() -> Path:
    """Full path to the MediaPipe gesture recognizer model."""
    return resource_path(MODEL_DIRNAME, MODEL_FILENAME)


def model_is_available() -> bool:
    """Whether the gesture model is actually present at the resolved path."""
    try:
        return model_path().is_file()
    except OSError:
        return False
