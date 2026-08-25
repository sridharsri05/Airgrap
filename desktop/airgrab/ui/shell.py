"""The one thread that owns Tkinter.

## Why this exists

Tkinter requires every call to happen on the thread that created its root.
Breaking that rule does not raise -- it simply never paints. That exact bug
has already cost this project a day: the pairing dialog was built from the
asyncio thread, so the PC showed nothing at all while the phone sat waiting
for an answer nobody could give.

The application has three threads that all want to show something:

* the main thread, owned by pystray for the whole life of the process
* the asyncio thread, where the control channel reads a pairing request
* the camera thread, where a gesture is recognised

None of them may touch Tk. So Tk gets a thread of its own, and everything
else hands it work through a queue.

## Using it

``submit`` is safe from any thread and returns immediately. ``call`` waits for
a result, which is what a dialog needs -- but never call it FROM the Tk thread
itself, which would deadlock waiting for a reply it is meant to produce.
"""

from __future__ import annotations

import logging
import queue
import threading
from typing import Any, Callable

_log = logging.getLogger("airgrab")

# How often the Tk thread looks for new work. Fast enough that a dialog feels
# immediate, slow enough to cost nothing while idle.
DRAIN_MS = 40


class Shell:
    """Owns a hidden Tk root and the thread it lives on."""

    def __init__(self) -> None:
        self._work: queue.Queue[Callable[[], Any] | None] = queue.Queue()
        self._thread: threading.Thread | None = None
        self._root = None
        self._ready = threading.Event()
        self._failed = False

    # ------------------------------------------------------------- lifecycle

    def start(self) -> bool:
        """Start the thread and wait for Tk to come up.

        Returns False if Tk is unavailable -- a machine with no display, or a
        broken install. The caller carries on without a window: transfers work
        perfectly well with nothing to look at.
        """
        if self._thread is not None:
            return not self._failed

        self._thread = threading.Thread(target=self._run, name="airgrab-ui", daemon=True)
        self._thread.start()
        # Waited on so the first submit is not dropped while Tk is still
        # starting, which would silently lose the very first indicator.
        self._ready.wait(timeout=5)
        return not self._failed

    @property
    def root(self):
        """The hidden root. Only touch this from the Tk thread."""
        return self._root

    @property
    def alive(self) -> bool:
        return self._thread is not None and not self._failed

    def stop(self) -> None:
        if self._thread is None:
            return
        self._work.put(None)
        self._thread = None

    # ----------------------------------------------------------------- work

    def submit(self, function: Callable[[], Any]) -> None:
        """Run ``function`` on the Tk thread. Safe from any thread."""
        if self._thread is None or self._failed:
            return
        self._work.put(function)

    def call(self, function: Callable[[], Any], timeout: float = 300.0) -> Any:
        """Run ``function`` on the Tk thread and wait for its result.

        Used by dialogs, which have to block their caller until the user
        answers. Never call this from the Tk thread: it would wait for a reply
        that only the waiting thread could produce.
        """
        if self._thread is None or self._failed:
            return None
        if threading.current_thread() is self._thread:
            raise RuntimeError("Shell.call from the Tk thread would deadlock")

        done: queue.Queue = queue.Queue(maxsize=1)

        def wrapper() -> None:
            try:
                done.put((True, function()))
            except Exception as exc:  # pragma: no cover - defensive
                done.put((False, exc))

        self._work.put(wrapper)
        try:
            ok, value = done.get(timeout=timeout)
        except queue.Empty:
            return None
        if not ok:
            raise value
        return value

    # ------------------------------------------------------------ Tk thread

    def _run(self) -> None:
        try:
            import tkinter

            root = tkinter.Tk()
            root.withdraw()
            self._root = root
        except Exception as exc:
            # A missing display must not take the application with it.
            self._failed = True
            _log.warning("no window system available: %s", exc)
            self._ready.set()
            return

        self._ready.set()
        root.after(DRAIN_MS, self._drain)
        try:
            root.mainloop()
        except Exception as exc:  # pragma: no cover - defensive
            _log.exception("the UI thread stopped: %s", exc)

    def _drain(self) -> None:
        root = self._root
        if root is None:
            return
        try:
            while True:
                job = self._work.get_nowait()
                if job is None:
                    root.quit()
                    return
                try:
                    job()
                except Exception:
                    # One broken window must not stop the queue. Losing the
                    # indicator is survivable; losing every future dialog,
                    # including pairing, is not.
                    _log.exception("a UI job failed")
        except queue.Empty:
            pass
        root.after(DRAIN_MS, self._drain)
