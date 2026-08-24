"""System tray presence.

The app has no main window in normal operation: it is a background service
with a menu. Everything the user needs day to day — which device is connected,
where files land, whether it is actually working — has to be legible from the
tray alone, because that is the only surface they will look at.
"""

from __future__ import annotations

from typing import Callable

import pystray
from PIL import Image, ImageDraw

_CONNECTED = (46, 160, 67, 255)
_IDLE = (110, 118, 129, 255)
_PROBLEM = (209, 36, 47, 255)


def _icon_image(colour: tuple[int, int, int, int]) -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((6, 6, 58, 58), fill=colour)
    # An upward arrow: the app's whole job is sending things somewhere else.
    draw.polygon(
        [(32, 16), (46, 33), (37, 33), (37, 47), (27, 47), (27, 33), (18, 33)],
        fill=(255, 255, 255, 255),
    )
    return image


class Tray:
    def __init__(
        self,
        on_open_downloads: Callable[[], None],
        on_pair: Callable[[], None],
        on_quit: Callable[[], None],
    ) -> None:
        self._status = "Starting..."
        self._icon = pystray.Icon(
            "airgrab",
            _icon_image(_IDLE),
            "AirGrab",
            menu=pystray.Menu(
                pystray.MenuItem(lambda item: self._status, None, enabled=False),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Pair a device...", lambda: on_pair()),
                pystray.MenuItem("Open received files", lambda: on_open_downloads()),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Quit", lambda: on_quit()),
            ),
        )

    def set_status(self, text: str, state: str = "idle") -> None:
        """state is one of: connected, idle, problem."""
        colour = {"connected": _CONNECTED, "problem": _PROBLEM}.get(state, _IDLE)
        self._status = text
        self._icon.icon = _icon_image(colour)
        self._icon.title = f"AirGrab — {text}"
        try:
            self._icon.update_menu()
        except Exception:
            pass  # the icon may not be visible yet during startup

    def notify(self, message: str) -> None:
        try:
            self._icon.notify(message, "AirGrab")
        except Exception:
            pass  # notifications are a nicety, never a failure path

    def run(self) -> None:
        self._icon.run()

    def stop(self) -> None:
        self._icon.stop()
