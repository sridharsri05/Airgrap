"""An on-screen indicator, matching the one the phone shows.

The tray icon changes colour and pops a balloon, and neither is enough. A
balloon appears in the corner, is easy to miss entirely, and on Windows can be
suppressed by focus-assist without telling anyone. The user reported exactly
that: the phone told them what happened and the laptop appeared to do nothing.

This is a small borderless window at the top of the screen showing the same
thing the Android overlay shows, in the same colours, with the same wording.

## Threading

Every Tk call happens on ONE thread, which this module owns.

That is not a style choice. Tkinter is not thread-safe, and calling into it
from another thread does not raise — it simply never paints, which is the
exact bug that made the pairing dialog invisible earlier in this project.
Requests arrive on a queue and are drained by the Tk thread itself.
"""

from __future__ import annotations

import queue
import threading
import tkinter
from dataclasses import dataclass

# Matching android/app/src/main/kotlin/com/airgrab/GestureOverlay.kt, so the
# two devices speak the same visual language.
COLOURS = {
    "holding": "#4C8DFF",
    "sent": "#3FD07E",
    "received": "#3FD07E",
    "cancelled": "#F0724F",
}

BACKGROUND = "#1A1D21"
VISIBLE_MS = 1800
FADE_MS = 16


@dataclass(frozen=True)
class _Request:
    kind: str
    text: str


class Overlay:
    """Shows a brief status pill. Safe to call from any thread."""

    def __init__(self) -> None:
        self._requests: queue.Queue[_Request | None] = queue.Queue()
        self._thread: threading.Thread | None = None
        self._root: tkinter.Tk | None = None
        self._window: tkinter.Toplevel | None = None
        self._hide_job = None
        self._started = threading.Event()

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._run, name="airgrab-overlay", daemon=True)
        self._thread.start()
        # Waited on so the first show() is not dropped while Tk is still
        # coming up, which would silently lose the very first grab.
        self._started.wait(timeout=5)

    def show(self, kind: str, text: str) -> None:
        if self._thread is None:
            return
        self._requests.put(_Request(kind=kind, text=text))

    def stop(self) -> None:
        if self._thread is None:
            return
        self._requests.put(None)
        self._thread = None

    # ------------------------------------------------------------ Tk thread

    def _run(self) -> None:
        try:
            root = tkinter.Tk()
            root.withdraw()
            self._root = root
            self._build()
            self._started.set()
            root.after(50, self._drain)
            root.mainloop()
        except Exception:
            # An overlay that cannot start must not take the application with
            # it. Transfers work perfectly well with no indicator.
            self._started.set()

    def _build(self) -> None:
        assert self._root is not None
        window = tkinter.Toplevel(self._root)
        window.overrideredirect(True)          # no title bar, no border
        window.attributes("-topmost", True)
        window.attributes("-alpha", 0.0)       # invisible until asked for
        window.configure(bg=BACKGROUND)

        self._canvas = tkinter.Canvas(
            window, width=420, height=56, bg=BACKGROUND, highlightthickness=0
        )
        self._canvas.pack()
        window.withdraw()
        self._window = window

    def _drain(self) -> None:
        assert self._root is not None
        try:
            while True:
                request = self._requests.get_nowait()
                if request is None:
                    self._root.quit()
                    return
                self._present(request)
        except queue.Empty:
            pass
        self._root.after(50, self._drain)

    def _present(self, request: _Request) -> None:
        window, canvas = self._window, self._canvas
        if window is None:
            return

        colour = COLOURS.get(request.kind, COLOURS["holding"])
        canvas.delete("all")

        # The dot carries the meaning; the text carries the detail. Same shape
        # as the phone, so a user who has seen one recognises the other.
        canvas.create_oval(20, 20, 36, 36, fill=colour, outline="")
        canvas.create_text(
            50, 28, text=request.text, anchor="w",
            fill="#FFFFFF", font=("Segoe UI", 11, "bold"),
        )

        width = 420
        screen_width = window.winfo_screenwidth()
        window.geometry(f"{width}x56+{(screen_width - width) // 2}+40")
        window.deiconify()
        window.attributes("-alpha", 0.0)
        self._fade(0.0, +0.12)

        if self._hide_job is not None:
            self._root.after_cancel(self._hide_job)
        self._hide_job = self._root.after(VISIBLE_MS, lambda: self._fade(0.96, -0.08))

    def _fade(self, value: float, step: float) -> None:
        """Fade rather than blink: a window that snaps in and out reads as a
        glitch, which is the opposite of reassuring."""
        window, root = self._window, self._root
        if window is None or root is None:
            return

        value = max(0.0, min(0.96, value + step))
        try:
            window.attributes("-alpha", value)
        except Exception:
            return

        if step > 0 and value < 0.96:
            root.after(FADE_MS, lambda: self._fade(value, step))
        elif step < 0 and value > 0.0:
            root.after(FADE_MS, lambda: self._fade(value, step))
        elif step < 0:
            window.withdraw()
