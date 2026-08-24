"""Synthetic gesture sequences — no camera, no MediaPipe, fully deterministic.

Nearly every bug in a gesture interaction lives in the timing and debouncing
rather than in the vision model, so this is where the coverage belongs.

Frames are fed with an explicit frame interval so the suite can prove the
engine behaves identically on a slow camera and a fast one. That is not a
theoretical concern: the development webcam measured 30fps in good light and
10fps once auto-exposure lengthened indoors.
"""

from airgrab.gesture import (
    EventType,
    GestureConfig,
    GrabStateMachine,
    Pose,
    State,
)

FPS_30 = 1 / 30
FPS_10 = 1 / 10
FPS_60 = 1 / 60


class FakeClock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class Rig:
    """A state machine plus the clock driving it."""

    def __init__(self, **overrides):
        self.clock = FakeClock()
        defaults = dict(
            arm_seconds=0.10,
            grab_seconds=0.06,
            release_seconds=0.06,
            catch_seconds=0.06,
            disarm_seconds=0.10,
            min_frames=2,
            hold_timeout_seconds=10.0,
        )
        defaults.update(overrides)
        self.machine = GrabStateMachine(GestureConfig(**defaults), clock=self.clock)

    def feed(self, pose: Pose, count: int, interval: float = FPS_30):
        events = []
        for _ in range(count):
            self.clock.advance(interval)
            events.extend(self.machine.observe(pose))
        return events

    def hold(self, pose: Pose, seconds: float, interval: float = FPS_30):
        return self.feed(pose, max(2, int(seconds / interval)), interval)

    @property
    def state(self) -> State:
        return self.machine.state


def _types(events):
    return [e.type for e in events]


def test_starts_idle():
    assert Rig().state is State.IDLE


def test_open_palm_arms_once_held_long_enough():
    rig = Rig()
    events = rig.hold(Pose.OPEN_PALM, 0.5)
    assert _types(events) == [EventType.ARMED]
    assert rig.state is State.ARMED


def test_brief_palm_does_not_arm():
    rig = Rig()
    events = rig.feed(Pose.OPEN_PALM, 2)  # ~0.07s, under the 0.10s threshold
    assert events == []
    assert rig.state is State.IDLE


def test_single_stray_frame_never_arms():
    rig = Rig()
    for _ in range(20):
        rig.feed(Pose.OPEN_PALM, 1)
        rig.feed(Pose.NONE, 1)
    assert rig.state is State.IDLE


def test_min_frames_blocks_a_single_frame_on_a_very_slow_camera():
    """One frame can span longer than the threshold at low fps; the frame
    floor is what stops a lone misclassification firing a transfer."""
    rig = Rig()
    events = rig.feed(Pose.OPEN_PALM, 1, interval=2.0)
    assert events == []
    assert rig.state is State.IDLE


def test_full_send_gesture_arms_then_grabs():
    rig = Rig()
    rig.hold(Pose.OPEN_PALM, 0.5)
    events = rig.hold(Pose.CLOSED_FIST, 0.3)
    assert _types(events) == [EventType.GRABBED]
    assert rig.state is State.HOLDING


def test_hold_survives_the_hand_leaving_the_frame():
    """The user is carrying the file to the other device. This is the success
    path, and cancelling here would break the entire interaction."""
    rig = Rig()
    rig.hold(Pose.OPEN_PALM, 0.5)
    rig.hold(Pose.CLOSED_FIST, 0.3)
    assert rig.state is State.HOLDING

    rig.hold(Pose.NONE, 3.0)
    assert rig.state is State.HOLDING


def test_open_palm_after_hold_releases():
    rig = Rig()
    rig.hold(Pose.OPEN_PALM, 0.5)
    rig.hold(Pose.CLOSED_FIST, 0.3)
    rig.hold(Pose.NONE, 1.0)
    events = rig.hold(Pose.OPEN_PALM, 0.3)
    assert _types(events) == [EventType.RELEASED]
    assert rig.state is State.IDLE


def test_withdrawing_before_grabbing_disarms():
    rig = Rig()
    rig.hold(Pose.OPEN_PALM, 0.5)
    assert rig.state is State.ARMED
    events = rig.hold(Pose.NONE, 0.4)
    assert _types(events) == [EventType.DISARMED]
    assert rig.state is State.IDLE


def test_fist_without_a_preceding_palm_means_this_device_receives():
    rig = Rig()
    events = rig.hold(Pose.CLOSED_FIST, 0.3)
    assert _types(events) == [EventType.CATCH_READY]
    assert rig.state is State.CATCHING


def test_catching_completes_on_open_palm():
    rig = Rig()
    rig.hold(Pose.CLOSED_FIST, 0.3)
    events = rig.hold(Pose.OPEN_PALM, 0.3)
    assert _types(events) == [EventType.RELEASED]
    assert rig.state is State.IDLE


def test_hold_expires_after_the_timeout():
    rig = Rig()
    rig.hold(Pose.OPEN_PALM, 0.5)
    rig.hold(Pose.CLOSED_FIST, 0.3)
    assert rig.state is State.HOLDING

    rig.clock.advance(11.0)
    events = rig.machine.tick()
    assert _types(events) == [EventType.CANCELLED]
    assert rig.state is State.IDLE


def test_tick_does_nothing_while_idle():
    rig = Rig()
    rig.clock.advance(1000.0)
    assert rig.machine.tick() == []


def test_palm_left_up_after_a_release_starts_nothing_new():
    """Your hand is still open the instant after you release. Re-arming there
    would begin a gesture you never made."""
    rig = Rig()
    rig.hold(Pose.OPEN_PALM, 0.5)
    rig.hold(Pose.CLOSED_FIST, 0.3)
    released = rig.hold(Pose.OPEN_PALM, 0.3)
    assert _types(released) == [EventType.RELEASED]

    events = rig.hold(Pose.OPEN_PALM, 2.0)
    assert events == []
    assert rig.state is State.IDLE


def test_changing_the_hand_clears_the_refractory_period():
    """The lock-out ends as soon as the pose changes, so a deliberate second
    gesture still works immediately."""
    rig = Rig()
    rig.hold(Pose.OPEN_PALM, 0.5)
    rig.hold(Pose.CLOSED_FIST, 0.3)
    rig.hold(Pose.OPEN_PALM, 0.3)  # RELEASED

    rig.hold(Pose.NONE, 0.3)       # hand lowered
    events = rig.hold(Pose.OPEN_PALM, 0.5)
    assert _types(events) == [EventType.ARMED]
    assert rig.state is State.ARMED


def test_arming_does_not_refire_while_the_palm_stays_up():
    rig = Rig()
    rig.hold(Pose.OPEN_PALM, 0.5)
    events = rig.hold(Pose.OPEN_PALM, 2.0)
    assert events == []
    assert rig.state is State.ARMED


def test_other_poses_are_ignored():
    rig = Rig()
    rig.hold(Pose.OTHER, 3.0)
    assert rig.state is State.IDLE


def test_interrupted_palm_streak_restarts_the_clock():
    rig = Rig()
    rig.feed(Pose.OPEN_PALM, 2)
    rig.feed(Pose.OTHER, 1)
    rig.feed(Pose.OPEN_PALM, 2)
    assert rig.state is State.IDLE
    rig.hold(Pose.OPEN_PALM, 0.5)
    assert rig.state is State.ARMED


def test_reset_returns_to_idle():
    rig = Rig()
    rig.hold(Pose.OPEN_PALM, 0.5)
    rig.hold(Pose.CLOSED_FIST, 0.3)
    rig.machine.reset()
    assert rig.state is State.IDLE


def test_behaviour_is_identical_at_10fps_and_60fps():
    """The reason thresholds are durations rather than frame counts.

    The same wall-clock gesture must produce the same events whether the
    camera manages 10fps in a dim room or 60fps in daylight.
    """
    results = {}
    for label, interval in (("10fps", FPS_10), ("60fps", FPS_60)):
        rig = Rig()
        events = []
        events += rig.hold(Pose.OPEN_PALM, 0.5, interval)
        events += rig.hold(Pose.CLOSED_FIST, 0.4, interval)
        events += rig.hold(Pose.NONE, 1.5, interval)
        events += rig.hold(Pose.OPEN_PALM, 0.4, interval)
        results[label] = (_types(events), rig.state)

    assert results["10fps"] == results["60fps"]
    assert results["10fps"][0] == [
        EventType.ARMED,
        EventType.GRABBED,
        EventType.RELEASED,
    ]


def test_a_realistic_full_round_trip():
    """Sender: present, grab, carry away. Receiver: fist arrives, palm opens."""
    sender = Rig()
    receiver = Rig()

    sender_events = []
    sender_events += sender.hold(Pose.NONE, 0.5)
    sender_events += sender.hold(Pose.OPEN_PALM, 0.4)
    sender_events += sender.hold(Pose.CLOSED_FIST, 0.3)
    sender_events += sender.hold(Pose.NONE, 1.5)

    receiver_events = []
    receiver_events += receiver.hold(Pose.NONE, 1.0)
    receiver_events += receiver.hold(Pose.CLOSED_FIST, 0.3)
    receiver_events += receiver.hold(Pose.OPEN_PALM, 0.3)

    assert _types(sender_events) == [EventType.ARMED, EventType.GRABBED]
    assert _types(receiver_events) == [EventType.CATCH_READY, EventType.RELEASED]
    assert sender.state is State.HOLDING
    assert receiver.state is State.IDLE
