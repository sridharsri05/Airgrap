"""Vision-layer tests.

These need the downloaded model, so they skip cleanly without it rather than
failing — a fresh clone should have a green suite before running
tools/fetch_model.py.

A real hand cannot be synthesised, so these verify the contract around
detection (loads, never crashes, no hand reads as NONE) rather than detection
accuracy itself. Accuracy is checked by hand with tools/gesture_live.py.
"""

import numpy as np
import pytest

from airgrab.gesture import Pose
from airgrab.handpose import DEFAULT_MODEL_PATH, HandPoseDetector, ModelMissing

pytestmark = pytest.mark.skipif(
    not DEFAULT_MODEL_PATH.exists(),
    reason="gesture model not downloaded; run tools/fetch_model.py",
)


def test_detector_loads_the_model():
    with HandPoseDetector() as detector:
        assert detector is not None


def test_blank_frame_reports_no_hand():
    with HandPoseDetector() as detector:
        blank = np.zeros((480, 640, 3), dtype=np.uint8)
        assert detector.classify(blank) is Pose.NONE


def test_random_noise_reports_no_hand():
    rng = np.random.default_rng(1234)
    noise = rng.integers(0, 256, size=(480, 640, 3), dtype=np.uint8)
    with HandPoseDetector() as detector:
        assert detector.classify(noise) is Pose.NONE


def test_malformed_frame_does_not_crash_the_capture_loop():
    """A camera glitch must read as 'no hand', never take the process down."""
    with HandPoseDetector() as detector:
        assert detector.classify(np.zeros((0, 0, 3), dtype=np.uint8)) is Pose.NONE
        assert detector.classify(np.zeros((10, 10), dtype=np.uint8)) is Pose.NONE


def test_every_malformed_shape_is_rejected_before_mediapipe_sees_it():
    """A wedged MediaPipe graph is unrecoverable and hangs the process at
    exit, so bad frames must be filtered rather than caught."""
    bad_frames = [
        None,
        "not an array",
        np.zeros((0, 0, 3), dtype=np.uint8),      # zero dimensions
        np.zeros((480, 0, 3), dtype=np.uint8),    # zero width
        np.zeros((10, 10), dtype=np.uint8),       # greyscale, not RGB
        np.zeros((10, 10, 4), dtype=np.uint8),    # RGBA
        np.zeros((10, 10, 3), dtype=np.float32),  # wrong dtype
    ]
    with HandPoseDetector() as detector:
        for frame in bad_frames:
            assert detector.classify(frame) is Pose.NONE


def test_detector_still_works_after_malformed_frames():
    """The real requirement: a camera glitch must not end the session."""
    with HandPoseDetector() as detector:
        for _ in range(5):
            detector.classify(np.zeros((0, 0, 3), dtype=np.uint8))
        # Still alive and classifying normally.
        assert detector.classify(np.zeros((480, 640, 3), dtype=np.uint8)) is Pose.NONE


def test_non_contiguous_frame_is_handled():
    """Slicing a frame (a crop, or a BGR->RGB flip) produces a non-contiguous
    array, which MediaPipe cannot read directly."""
    full = np.zeros((480, 640, 3), dtype=np.uint8)
    flipped = full[:, :, ::-1]  # non-contiguous view
    assert not flipped.flags["C_CONTIGUOUS"]
    with HandPoseDetector() as detector:
        assert detector.classify(flipped) is Pose.NONE


def test_repeated_classification_is_stable():
    """IMAGE mode is stateless, so the same frame must classify identically."""
    blank = np.zeros((480, 640, 3), dtype=np.uint8)
    with HandPoseDetector() as detector:
        results = {detector.classify(blank) for _ in range(5)}
    assert results == {Pose.NONE}


def test_missing_model_raises_a_clear_error(tmp_path):
    with pytest.raises(ModelMissing, match="fetch_model"):
        HandPoseDetector(model_path=tmp_path / "nope.task")
