"""The ring: what the user sees when they are not looking at AirGrab.

The phone draws a glowing hollow ring over whatever is on screen — Huawei's
own receive light, the one element that makes the transfer read as the device
responding rather than an app posting a notification. The user saw it on the
phone and asked for the same on the laptop, which is exactly the right
instinct: the two devices should speak one visual language, and the pill this
module used to draw looked like a status bar next to the phone's halo.

## How a hole is cut in a Tk window

Tk has no per-pixel alpha, but on Windows a window can declare one colour to
be see-through (``-transparentcolor``). The window here is a centred square
filled with that colour; only the drawn ring and the caption are visible, and
everything else — including clicks, mostly — falls through to whatever is
underneath. The glow cannot truly fade over the desktop without real alpha,
so it is stepped: three strokes of the same circle in shades stepping down
toward the accent's darker half, which reads as light at arm's length.

## Threading

Every Tk call happens on the one thread the Shell owns. That is not a style
choice — Tkinter is not thread-safe, and calling into it from another thread
does not raise, it simply never paints. ``show`` is safe from anywhere.
"""

from __future__ import annotations

from .shell import Shell
from . import style

# Matching android/app/src/main/kotlin/com/airgrab/GestureOverlay.kt, so the
# two devices speak the same visual language. Dark-ground variants, because
# the ring floats over whatever the user happens to be looking at.
COLOURS = {
    "holding": "#6E9BFF",
    "sent": "#5FD08A",
    "received": "#5FD08A",
    "cancelled": "#F0724F",
}

TEXT = "#F2F3F5"
BACKGROUND = "#1B1C1E"

# A colour nobody's desktop legitimately contains, sacrificed to be the hole.
HOLE = "#010203"

EDGE = 460           # the square window's side, px
FULL_RADIUS = 96
VISIBLE_MS = 2100
DURATION_MS = 1900
FRAME_MS = 16


class Overlay:
    """Shows the ring. Safe to call from any thread."""

    def __init__(self, shell: Shell) -> None:
        self._shell = shell
        self._window = None
        self._canvas = None
        self._hide_job = None
        self._start = 0.0
        self._kind = "holding"
        self._text = ""
        self._rings: list = []
        self._caption = None

    # (stroke width, brightness) for the stepped glow, widest first.
    GLOW = ((14, 0.22), (7, 0.5), (3, 1.0))

    def start(self) -> None:
        self._shell.submit(self._build)

    def show(self, kind: str, text: str) -> None:
        self._shell.submit(lambda: self._present(kind, text))

    def stop(self) -> None:
        self._shell.submit(self._destroy)

    # ------------------------------------------------------------ Tk thread

    def _build(self) -> None:
        import tkinter

        root = self._shell.root
        if root is None:
            return

        window = tkinter.Toplevel(root)
        window.overrideredirect(True)          # no title bar, no border
        window.attributes("-topmost", True)
        window.configure(bg=HOLE)
        try:
            # Windows only; elsewhere the ring rides on a dark square, which
            # is survivable. Everything painted HOLE becomes see-through.
            window.attributes("-transparentcolor", HOLE)
        except Exception:
            pass

        self._canvas = tkinter.Canvas(
            window, width=EDGE, height=EDGE, bg=HOLE, highlightthickness=0
        )
        self._canvas.pack()
        window.withdraw()
        self._window = window

    def _destroy(self) -> None:
        if self._window is not None:
            try:
                self._window.destroy()
            except Exception:
                pass
            self._window = None

    def _present(self, kind: str, text: str) -> None:
        import time

        window, root = self._window, self._shell.root
        if window is None or root is None:
            return

        self._kind = kind if kind in COLOURS else "holding"
        self._text = text
        self._start = time.monotonic()

        canvas = self._canvas
        canvas.delete("all")
        centre = EDGE / 2
        self._rings = [
            canvas.create_oval(0, 0, 0, 0, outline=HOLE, width=width, state="hidden")
            for width, _dim in self.GLOW
        ]
        self._caption = None
        if text:
            text_y = centre + FULL_RADIUS + 44
            half = max(60, 8 + 4 * len(text))
            canvas.create_rectangle(
                centre - half, text_y - 14, centre + half, text_y + 14,
                fill=BACKGROUND, outline="",
            )
            self._caption = canvas.create_text(
                centre, text_y, text=text, fill=BACKGROUND,
                font=("Segoe UI", 10, "bold"),
            )

        screen_w = window.winfo_screenwidth()
        screen_h = window.winfo_screenheight()
        window.geometry(
            f"{EDGE}x{EDGE}+{(screen_w - EDGE) // 2}+{(screen_h - EDGE) // 2}"
        )
        window.deiconify()
        window.lift()

        if self._hide_job is not None:
            try:
                root.after_cancel(self._hide_job)
            except Exception:
                pass
        self._hide_job = root.after(VISIBLE_MS, self._conceal)
        self._frame()

    def _conceal(self) -> None:
        if self._window is not None:
            try:
                self._window.withdraw()
            except Exception:
                pass

    # -------------------------------------------------------------- drawing

    def _frame(self) -> None:
        """One frame of the same three-act sweep the phone plays: bloom,
        breathe, then resolve by kind — collapse for a finished transfer,
        fade-out-in-place for a hold, an apologetic shrink for a cancel."""
        import math
        import time

        window, canvas, root = self._window, self._canvas, self._shell.root
        if window is None or canvas is None or root is None:
            return
        try:
            if not window.winfo_viewable():
                return

            progress = min(1.0, (time.monotonic() - self._start) / (DURATION_MS / 1000))
            centre = EDGE / 2

            bloom = _ease(min(1.0, progress / 0.22))
            resolving = _ease((progress - 0.78) / 0.22) if progress > 0.78 else 0.0
            breathe = math.sin(progress * 5 * math.pi) * 4 if resolving == 0 else 0.0

            if self._kind in ("sent", "received"):
                radius = (18 + (FULL_RADIUS - 18) * bloom) * (1 - resolving) \
                    + 8 * resolving + breathe
                strength = bloom * (1 - resolving * resolving)
            elif self._kind == "cancelled":
                radius = (18 + (FULL_RADIUS - 18) * bloom) * (1 - resolving * 0.4)
                strength = bloom * (1 - resolving)
            else:
                radius = 18 + (FULL_RADIUS - 18) * bloom + breathe
                strength = bloom * (1 - resolving)

            # The items are created once per showing and MOVED, never
            # recreated. delete-and-redraw every frame made the animation
            # visibly step on a layered window -- the user's words were
            # "like a 30-picture animation" -- where coords() glides.
            accent = COLOURS[self._kind]
            for ring, (_width, dim) in zip(self._rings, self.GLOW):
                canvas.coords(
                    ring,
                    centre - radius, centre - radius,
                    centre + radius, centre + radius,
                )
                canvas.itemconfigure(
                    ring, outline=style.wash(accent, HOLE, dim * strength),
                    state="normal" if strength > 0.02 else "hidden",
                )
            if self._caption is not None:
                canvas.itemconfigure(
                    self._caption,
                    fill=style.wash(TEXT, BACKGROUND, min(1.0, strength * 1.5)),
                )

            if progress < 1.0:
                root.after(FRAME_MS, self._frame)
        except Exception:
            # The window being torn down mid-frame must not take the drain
            # loop with it. The next show() rebuilds cleanly.
            return


def _ease(value: float) -> float:
    """Ease-out, so motion arrives instead of stopping."""
    clamped = max(0.0, min(1.0, value))
    return 1 - (1 - clamped) * (1 - clamped)
