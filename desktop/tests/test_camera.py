"""Camera loop behaviour, with no camera and no model.

`read` and `classify` are injected precisely so this can be exercised
deterministically: a real webcam would make these tests slow and flaky, and
would not let us simulate a camera that dies halfway through.
"""

import asyncio

from airgrab.camera import GestureCameraLoop
from airgrab.gesture import Pose


async def _collect(frames, max_wait=3.0):
    """Run the loop over a scripted frame list and return the poses observed."""
    seen: list[Pose] = []
    finished = asyncio.Event()
    loop = asyncio.get_running_loop()
    remaining = list(frames)

    async def observe(pose: Pose) -> None:
        seen.append(pose)
        if not remaining and len(seen) >= len([f for f in frames if f is not None]):
            finished.set()

    def read():
        if not remaining:
            return None
        return remaining.pop(0)

    camera = GestureCameraLoop(
        observe=observe,
        loop=loop,
        read=read,
        classify=lambda frame: frame,
        max_fps=0,
    )
    camera.start()
    try:
        await asyncio.wait_for(finished.wait(), timeout=max_wait)
    except asyncio.TimeoutError:
        pass
    camera.stop()
    return seen, camera


async def test_poses_reach_the_session_in_order():
    script = [Pose.NONE, Pose.OPEN_PALM, Pose.CLOSED_FIST, Pose.OPEN_PALM]
    seen, _ = await _collect(script)
    assert seen[: len(script)] == script


async def test_loop_reports_frame_count():
    seen, camera = await _collect([Pose.OPEN_PALM] * 5)
    assert camera.stats.frames >= 5


async def test_classification_failure_is_treated_as_no_hand():
    loop = asyncio.get_running_loop()
    seen: list[Pose] = []
    frames = [1, 2, 3]

    async def observe(pose: Pose) -> None:
        seen.append(pose)

    def classify(frame):
        raise ValueError("model exploded")

    camera = GestureCameraLoop(
        observe=observe, loop=loop,
        read=lambda: frames.pop(0) if frames else None,
        classify=classify, max_fps=0,
    )
    camera.start()
    for _ in range(60):
        await asyncio.sleep(0.02)
        if len(seen) >= 3:
            break
    camera.stop()

    assert seen[:3] == [Pose.NONE, Pose.NONE, Pose.NONE]
    assert camera.stats.errors >= 3


async def test_a_dead_camera_stops_the_loop_and_reports_why():
    loop = asyncio.get_running_loop()
    stopped: list[str] = []

    camera = GestureCameraLoop(
        observe=lambda pose: asyncio.sleep(0),
        loop=loop,
        read=lambda: None,          # never delivers a frame
        classify=lambda frame: Pose.NONE,
        max_fps=0,
        on_stopped=stopped.append,
    )
    camera.start()
    for _ in range(100):
        await asyncio.sleep(0.05)
        if stopped:
            break
    camera.stop()

    assert stopped and "camera" in stopped[0]
    assert camera.running is False


async def test_a_brief_dropout_does_not_kill_the_loop():
    """A couple of dropped frames are normal and must be tolerated."""
    loop = asyncio.get_running_loop()
    seen: list[Pose] = []
    script = [None, None, Pose.OPEN_PALM, None, Pose.CLOSED_FIST]

    async def observe(pose: Pose) -> None:
        seen.append(pose)

    def read():
        return script.pop(0) if script else Pose.NONE

    camera = GestureCameraLoop(
        observe=observe, loop=loop, read=read,
        classify=lambda frame: frame, max_fps=0,
    )
    camera.start()
    for _ in range(60):
        await asyncio.sleep(0.02)
        if Pose.CLOSED_FIST in seen:
            break
    camera.stop()

    assert Pose.OPEN_PALM in seen
    assert Pose.CLOSED_FIST in seen


async def test_close_is_called_when_the_loop_stops():
    loop = asyncio.get_running_loop()
    closed: list[bool] = []

    camera = GestureCameraLoop(
        observe=lambda pose: asyncio.sleep(0),
        loop=loop,
        read=lambda: Pose.NONE,
        classify=lambda frame: frame,
        close=lambda: closed.append(True),
        max_fps=0,
    )
    camera.start()
    await asyncio.sleep(0.1)
    camera.stop()
    assert closed == [True]


async def test_starting_twice_is_harmless():
    loop = asyncio.get_running_loop()
    camera = GestureCameraLoop(
        observe=lambda pose: asyncio.sleep(0),
        loop=loop,
        read=lambda: Pose.NONE,
        classify=lambda frame: frame,
        max_fps=0,
    )
    camera.start()
    camera.start()
    await asyncio.sleep(0.05)
    camera.stop()
    assert camera.running is False


async def test_max_fps_limits_the_rate():
    loop = asyncio.get_running_loop()
    seen: list[Pose] = []

    async def observe(pose: Pose) -> None:
        seen.append(pose)

    camera = GestureCameraLoop(
        observe=observe, loop=loop,
        read=lambda: Pose.NONE, classify=lambda f: f,
        max_fps=10.0,
    )
    camera.start()
    await asyncio.sleep(0.5)
    camera.stop()
    # 0.5s at 10fps is ~5 frames; allow generous slack for scheduling.
    assert 1 <= len(seen) <= 12
