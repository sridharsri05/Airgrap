"""Synthetic gesture sequences — no camera, no MediaPipe, fully deterministic.

Nearly every bug in a gesture interaction lives in the timing and debouncing
rather than in the vision model, so this is where the coverage belongs.
"""

from airgrab.gesture import (
    EventType,
    GestureConfig,
    GrabStateMachine,
    Pose,
    State,
)


class FakeClock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def _machine(**overrides):
    clock = FakeClock()
    config = GestureConfig(
        arm_frames=3, grab_frames=2, release_frames=2, catch_frames=2,
        disarm_frames=3, hold_timeout_seconds=10.0, **overrides
    )
    return GrabStateMachine(config, clock=clock), clock


def _feed(machine, pose: Pose, count: int):
    events = []
    for _ in range(count):
        events.extend(machine.observe(pose))
    return events


def test_starts_idle():
    machine, _ = _machine()
    assert machine.state is State.IDLE


def test_open_palm_arms_after_threshold():
    machine, _ = _machine()
    assert _feed(machine, Pose.OPEN_PALM, 2) == []
    events = _feed(machine, Pose.OPEN_PALM, 1)
    assert [e.type for e in events] == [EventType.ARMED]
    assert machine.state is State.ARMED


def test_single_stray_frame_does_not_arm():
    machine, _ = _machine()
    for _ in range(10):
        _feed(machine, Pose.OPEN_PALM, 1)
        _feed(machine, Pose.NONE, 1)
    assert machine.state is State.IDLE


def test_full_send_gesture_arms_then_grabs():
    machine, _ = _machine()
    _feed(machine, Pose.OPEN_PALM, 3)
    events = _feed(machine, Pose.CLOSED_FIST, 2)
    assert [e.type for e in events] == [EventType.GRABBED]
    assert machine.state is State.HOLDING


def test_hold_survives_the_hand_leaving_the_frame():
    """The user is carrying the file to the other device. This is the success
    path, and cancelling here would break the entire interaction."""
    machine, _ = _machine()
    _feed(machine, Pose.OPEN_PALM, 3)
    _feed(machine, Pose.CLOSED_FIST, 2)
    assert machine.state is State.HOLDING

    _feed(machine, Pose.NONE, 100)
    assert machine.state is State.HOLDING


def test_open_palm_after_hold_releases():
    machine, _ = _machine()
    _feed(machine, Pose.OPEN_PALM, 3)
    _feed(machine, Pose.CLOSED_FIST, 2)
    _feed(machine, Pose.NONE, 20)
    events = _feed(machine, Pose.OPEN_PALM, 2)
    assert [e.type for e in events] == [EventType.RELEASED]
    assert machine.state is State.IDLE


def test_withdrawing_before_grabbing_disarms():
    machine, _ = _machine()
    _feed(machine, Pose.OPEN_PALM, 3)
    assert machine.state is State.ARMED
    events = _feed(machine, Pose.NONE, 3)
    assert [e.type for e in events] == [EventType.DISARMED]
    assert machine.state is State.IDLE


def test_fist_without_a_preceding_palm_means_this_device_receives():
    machine, _ = _machine()
    events = _feed(machine, Pose.CLOSED_FIST, 2)
    assert [e.type for e in events] == [EventType.CATCH_READY]
    assert machine.state is State.CATCHING


def test_catching_completes_on_open_palm():
    machine, _ = _machine()
    _feed(machine, Pose.CLOSED_FIST, 2)
    events = _feed(machine, Pose.OPEN_PALM, 2)
    assert [e.type for e in events] == [EventType.RELEASED]
    assert machine.state is State.IDLE


def test_hold_expires_after_the_timeout():
    machine, clock = _machine()
    _feed(machine, Pose.OPEN_PALM, 3)
    _feed(machine, Pose.CLOSED_FIST, 2)
    assert machine.state is State.HOLDING

    clock.advance(11.0)
    events = machine.tick()
    assert [e.type for e in events] == [EventType.CANCELLED]
    assert machine.state is State.IDLE


def test_tick_does_nothing_while_idle():
    machine, clock = _machine()
    clock.advance(1000.0)
    assert machine.tick() == []


def test_release_does_not_immediately_refire():
    """After a transition the streak restarts, so holding the same pose does
    not emit the same event on every subsequent frame."""
    machine, _ = _machine()
    _feed(machine, Pose.OPEN_PALM, 3)
    _feed(machine, Pose.CLOSED_FIST, 2)
    _feed(machine, Pose.OPEN_PALM, 2)  # RELEASED
    events = _feed(machine, Pose.OPEN_PALM, 10)
    # Palm held long enough re-arms exactly once; it must not release again.
    assert [e.type for e in events] == [EventType.ARMED]


def test_arming_does_not_refire_while_the_palm_stays_up():
    machine, _ = _machine()
    _feed(machine, Pose.OPEN_PALM, 3)
    events = _feed(machine, Pose.OPEN_PALM, 30)
    assert events == []
    assert machine.state is State.ARMED


def test_other_poses_are_ignored():
    machine, _ = _machine()
    _feed(machine, Pose.OTHER, 50)
    assert machine.state is State.IDLE


def test_interrupted_palm_streak_restarts_the_count():
    machine, _ = _machine()
    _feed(machine, Pose.OPEN_PALM, 2)
    _feed(machine, Pose.OTHER, 1)
    _feed(machine, Pose.OPEN_PALM, 2)
    assert machine.state is State.IDLE
    _feed(machine, Pose.OPEN_PALM, 1)
    assert machine.state is State.ARMED


def test_reset_returns_to_idle():
    machine, _ = _machine()
    _feed(machine, Pose.OPEN_PALM, 3)
    _feed(machine, Pose.CLOSED_FIST, 2)
    machine.reset()
    assert machine.state is State.IDLE


def test_a_realistic_full_round_trip():
    """Sender: present, grab, carry away. Receiver: fist arrives, palm opens."""
    sender, _ = _machine()
    receiver, _ = _machine()

    sender_events = []
    sender_events += _feed(sender, Pose.NONE, 10)
    sender_events += _feed(sender, Pose.OPEN_PALM, 5)
    sender_events += _feed(sender, Pose.CLOSED_FIST, 3)
    sender_events += _feed(sender, Pose.NONE, 30)

    receiver_events = []
    receiver_events += _feed(receiver, Pose.NONE, 25)
    receiver_events += _feed(receiver, Pose.CLOSED_FIST, 4)
    receiver_events += _feed(receiver, Pose.OPEN_PALM, 3)

    assert [e.type for e in sender_events] == [EventType.ARMED, EventType.GRABBED]
    assert [e.type for e in receiver_events] == [
        EventType.CATCH_READY,
        EventType.RELEASED,
    ]
    assert sender.state is State.HOLDING
    assert receiver.state is State.IDLE
