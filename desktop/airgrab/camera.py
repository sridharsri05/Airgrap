"""Driving the gesture session from a camera, off the event loop.

Reading a frame blocks for roughly a frame interval, and classifying it costs
another 15ms. Doing either on the asyncio thread would stall the control
channel and the file transfers sharing it, so the capture loop lives on its
own thread and hands poses back with run_coroutine_threadsafe.

`read` and `classify` are injected rather than constructed here, which is what
makes the loop testable with no camera and no model.
"""

from __future__ import annotations

import asyncio
import threading
import time
from dataclasses import dataclass, field
from typing import Callable

from airgrab.gesture import Pose


@dataclass
class LoopStats:
    frames: int = 0
    errors: int = 0
    started_at: float = field(default_factory=time.monotonic)

    @property
    def fps(self) -> float:
        elapsed = time.monotonic() - self.started_at
        return self.frames / elapsed if elapsed > 0 else 0.0


class GestureCameraLoop:
    def __init__(
        self,
        observe: Callable[[Pose], object],
        loop: asyncio.AbstractEventLoop,
        read: Callable[[], object | None],
        classify: Callable[[object], Pose],
        close: Callable[[], None] | None = None,
        max_fps: float = 30.0,
        on_stopped: Callable[[str], None] | None = None,
    ) -> None:
        self._observe = observe
        self._loop = loop
        self._read = read
        self._classify = classify
        self._close = close
        self._min_interval = 1.0 / max_fps if max_fps > 0 else 0.0
        self._on_stopped = on_stopped

        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self.stats = LoopStats()

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> None:
        if self.running:
            return
        self._stop.clear()
        self.stats = LoopStats()
        self._thread = threading.Thread(
            target=self._run, name="airgrab-camera", daemon=True
        )
        self._thread.start()

    def stop(self, timeout: float = 3.0) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=timeout)
            self._thread = None

    def _run(self) -> None:
        reason = "stopped"
        try:
            while not self._stop.is_set():
                cycle_started = time.monotonic()

                try:
                    frame = self._read()
                except Exception:
                    self.stats.errors += 1
                    frame = None

                if frame is None:
                    # A camera that has stopped delivering will not usually
                    # recover on its own, but a couple of dropped frames are
                    # normal, so this tolerates a burst before giving up.
                    self.stats.errors += 1
                    if self.stats.errors > 30:
                        reason = "camera stopped delivering frames"
                        break
                    self._stop.wait(0.05)
                    continue

                try:
                    pose = self._classify(frame)
                except Exception:
                    self.stats.errors += 1
                    pose = Pose.NONE

                self.stats.frames += 1
                self._dispatch(pose)

                spare = self._min_interval - (time.monotonic() - cycle_started)
                if spare > 0:
                    self._stop.wait(spare)
        finally:
            if self._close is not None:
                try:
                    self._close()
                except Exception:
                    pass
            if self._on_stopped is not None:
                self._on_stopped(reason)

    def _dispatch(self, pose: Pose) -> None:
        """Hand the pose to the asyncio thread without waiting for it.

        Blocking on the result would couple frame rate to network latency: a
        slow transfer would stall gesture recognition, which is precisely
        backwards.
        """
        if self._stop.is_set() or not self._loop.is_running():
            self._stop.set()
            return

        coro = self._observe(pose)
        try:
            asyncio.run_coroutine_threadsafe(coro, self._loop)
        except RuntimeError:
            # The loop shut down between the check above and here. Closing the
            # coroutine explicitly avoids leaking it with a "never awaited"
            # warning on every frame still in flight during shutdown.
            close = getattr(coro, "close", None)
            if close is not None:
                close()
            self._stop.set()


def open_default_camera(camera_index: int = 0):
    """Build (read, classify, close) from the real webcam and model.

    Returns None if either is unavailable, so the application can report a
    specific cause instead of failing to start.
    """
    import cv2

    from airgrab.handpose import HandPoseDetector, ModelMissing

    try:
        detector = HandPoseDetector()
    except ModelMissing:
        return None

    # The default backend is MSMF on Windows, measured at 30fps against
    # DSHOW's 10fps on the development machine.
    capture = cv2.VideoCapture(camera_index)
    if not capture.isOpened():
        detector.close()
        return None

    def read():
        ok, frame = capture.read()
        if not ok:
            return None
        return cv2.cvtColor(cv2.flip(frame, 1), cv2.COLOR_BGR2RGB)

    def close() -> None:
        capture.release()
        detector.close()

    return read, detector.classify, close
