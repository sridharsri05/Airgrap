"""Turning camera frames into hand poses. Nothing else.

This module is deliberately dumb. It answers one question per frame — open
palm, closed fist, something else, or no hand — and holds no memory. All
debouncing, timing and interpretation belongs to gesture.py, which can be
tested exhaustively without a camera.

MediaPipe's IMAGE mode is used rather than VIDEO mode: it is stateless, which
keeps this module's behaviour identical frame to frame and reproducible in
tests. Temporal smoothing is the state machine's responsibility, so the
tracking that VIDEO mode adds would only duplicate it.
"""

from __future__ import annotations

from pathlib import Path

import mediapipe as mp
import numpy as np
from mediapipe.tasks.python import BaseOptions
from mediapipe.tasks.python import vision

from airgrab.gesture import Pose

DEFAULT_MODEL_PATH = Path(__file__).resolve().parents[1] / "models" / "gesture_recognizer.task"
MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/"
    "gesture_recognizer/float16/1/gesture_recognizer.task"
)

# MediaPipe's canned gesture names. Anything not listed is a hand we do not
# act on, which is distinct from no hand at all.
_POSE_BY_GESTURE = {
    "Open_Palm": Pose.OPEN_PALM,
    "Closed_Fist": Pose.CLOSED_FIST,
}


class ModelMissing(RuntimeError):
    """The gesture model has not been downloaded."""


class HandPoseDetector:
    def __init__(
        self,
        model_path: Path | None = None,
        min_confidence: float = 0.5,
    ) -> None:
        path = Path(model_path or DEFAULT_MODEL_PATH)
        if not path.exists():
            raise ModelMissing(
                f"Gesture model not found at {path}. "
                f"Run: python tools/fetch_model.py"
            )

        self._min_confidence = min_confidence
        options = vision.GestureRecognizerOptions(
            base_options=BaseOptions(model_asset_path=str(path)),
            running_mode=vision.RunningMode.IMAGE,
            num_hands=1,
        )
        self._recognizer = vision.GestureRecognizer.create_from_options(options)

    @staticmethod
    def _is_usable(frame: object) -> bool:
        """Reject a frame BEFORE MediaPipe sees it.

        This is prevention rather than error handling, and the distinction is
        load-bearing. A zero-dimension or wrong-shaped frame makes MediaPipe's
        graph fail internally, and that failure is permanent: every later
        frame silently returns nothing, and the process then hangs on exit
        because the graph's threads never terminate. Catching the exception
        afterwards does not undo any of that. The only working strategy is to
        never hand it a frame it cannot process.
        """
        if not isinstance(frame, np.ndarray):
            return False
        if frame.ndim != 3 or frame.shape[2] != 3:
            return False
        if frame.shape[0] <= 0 or frame.shape[1] <= 0:
            return False
        if frame.dtype != np.uint8:
            return False
        return True

    def classify(self, frame_rgb: np.ndarray) -> Pose:
        """Classify one RGB frame. A camera glitch reads as 'no hand'."""
        if not self._is_usable(frame_rgb):
            return Pose.NONE

        frame = np.ascontiguousarray(frame_rgb)
        try:
            image = mp.Image(image_format=mp.ImageFormat.SRGB, data=frame)
            result = self._recognizer.recognize(image)
        except Exception:
            return Pose.NONE

        if not result.gestures or not result.gestures[0]:
            return Pose.NONE

        top = result.gestures[0][0]
        if top.score < self._min_confidence:
            return Pose.OTHER
        if top.category_name in ("None", "none", ""):
            return Pose.OTHER

        return _POSE_BY_GESTURE.get(top.category_name, Pose.OTHER)

    def close(self) -> None:
        """Shut the recognizer down.

        MediaPipe defers graph errors: a malformed frame that `classify`
        already handled and recovered from re-surfaces here, seconds later, as
        a RuntimeError from the C++ layer. Letting that escape would break
        application shutdown over a frame that caused no actual harm, so it is
        swallowed. Nothing downstream of close() can act on it anyway.
        """
        try:
            self._recognizer.close()
        except Exception:
            pass

    def __enter__(self) -> "HandPoseDetector":
        return self

    def __exit__(self, *exc) -> None:
        self.close()
