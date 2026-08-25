"""An on-screen indicator, matching the one the phone shows.

The tray icon changes colour and pops a balloon, and neither is enough. A
balloon appears in the corner, is easy to miss entirely, and on Windows can be
suppressed by focus-assist without telling anyone. The user reported exactly
that: the phone told them what happened and the laptop appeared to do nothing.

This is a small borderless window at the top of the screen showing the same
thing the Android overlay shows, in the same colours, with the same wording.

## Threading

Every Tk call happens on the one thread the Shell owns. That is not a style
choice -- Tkinter is not thread-safe, and calling into it from another thread
does not raise, it simply never paints, which is the exact bug that made the
pairing dialog invisible earlier in this project. ``show`` is safe from
anywhere; it hands the work to that thread.
"""

from __future__ import annotations

from .shell import Shell

# Matching android/app/src/main/kotlin/com/airgrab/GestureOverlay.kt, so the
# two devices speak the same visual language. These are the dark-ground
# variants because the pill is always dark, on both platforms and in both
# system themes: it sits over whatever the user happens to be looking at, so
# it cannot borrow a ground it does not control.
COLOURS = {
    "holding": "#6E9BFF",
    "sent": "#5FD08A",
    "received": "#5FD08A",
    "cancelled": "#F0724F",
}

BACKGROUND = "#1B1C1E"
TEXT = "#F2F3F5"

WIDTH = 420
HEIGHT = 56
VISIBLE_MS = 1800
FADE_MS = 16
PEAK_ALPHA = 0.96


class Overlay:
    """Shows a brief status pill. Safe to call from any thread."""

    def __init__(self, shell: Shell) -> None:
        self._shell = shell
        self._window = None
        self._canvas = None
        self._hide_job = None

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
        window.attributes("-alpha", 0.0)       # invisible until asked for
        window.configure(bg=BACKGROUND)

        self._canvas = tkinter.Canvas(
            window, width=WIDTH, height=HEIGHT, bg=BACKGROUND, highlightthickness=0
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
        window, canvas, root = self._window, self._canvas, self._shell.root
        if window is None or canvas is None or root is None:
            return

        colour = COLOURS.get(kind, COLOURS["holding"])
        canvas.delete("all")

        # The dot carries the meaning; the text carries the detail. Same shape
        # as the phone, so a user who has seen one recognises the other.
        canvas.create_oval(20, 20, 36, 36, fill=colour, outline="")
        canvas.create_text(
            50, 28, text=text, anchor="w",
            fill=TEXT, font=("Segoe UI", 11, "bold"),
        )

        screen_width = window.winfo_screenwidth()
        window.geometry(f"{WIDTH}x{HEIGHT}+{(screen_width - WIDTH) // 2}+40")
        window.deiconify()
        window.attributes("-alpha", 0.0)
        self._fade(0.0, +0.12)

        if self._hide_job is not None:
            try:
                root.after_cancel(self._hide_job)
            except Exception:
                pass
        self._hide_job = root.after(VISIBLE_MS, lambda: self._fade(PEAK_ALPHA, -0.08))

    def _fade(self, value: float, step: float) -> None:
        """Fade rather than blink: a window that snaps in and out reads as a
        glitch, which is the opposite of reassuring."""
        window, root = self._window, self._shell.root
        if window is None or root is None:
            return

        value = max(0.0, min(PEAK_ALPHA, value + step))
        try:
            window.attributes("-alpha", value)
        except Exception:
            return

        if step > 0 and value < PEAK_ALPHA:
            root.after(FADE_MS, lambda: self._fade(value, step))
        elif step < 0 and value > 0.0:
            root.after(FADE_MS, lambda: self._fade(value, step))
        elif step < 0:
            window.withdraw()
