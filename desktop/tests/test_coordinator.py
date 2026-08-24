"""Correlation logic — no network, no camera, no real clock.

Every race this layer can lose is cheap to reproduce here and expensive to
reproduce with two machines and a pair of hands, so this is where the
coverage belongs.
"""

import pytest

from airgrab.coordinator import (
    Broadcast,
    CaptureContent,
    ClearContent,
    GestureCoordinator,
    GestureMessage,
    SendCapturedFile,
    SendMessage,
)
from airgrab.gesture import EventType, GestureEvent, State

PEER_A = "a" * 64
PEER_B = "b" * 64


class FakeClock:
    def __init__(self) -> None:
        self.now = 500.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def _event(kind: EventType) -> GestureEvent:
    return GestureEvent(type=kind, at=0.0, from_state=State.IDLE, to_state=State.IDLE)


def _coordinator(window: float = 20.0):
    clock = FakeClock()
    return GestureCoordinator(clock=clock, hold_window_seconds=window), clock


def _kinds(actions):
    return [type(a).__name__ for a in actions]


# ----------------------------------------------------------------- the sender


def test_grabbing_captures_content_and_announces_the_hold():
    coord, _ = _coordinator()
    actions = coord.on_local_event(_event(EventType.GRABBED))
    assert _kinds(actions) == ["CaptureContent", "Broadcast"]
    assert actions[1].type == GestureMessage.HOLD
    assert coord.holding is True


def test_a_peer_release_while_holding_sends_the_file():
    coord, _ = _coordinator()
    coord.on_local_event(_event(EventType.GRABBED))
    actions = coord.on_peer_message(PEER_A, GestureMessage.RELEASE, {})
    assert _kinds(actions) == ["SendCapturedFile", "Broadcast", "ClearContent"]
    assert actions[0].peer_fp == PEER_A
    assert coord.holding is False


def test_releasing_on_the_same_device_cancels_instead_of_sending():
    """Opening your hand where you grabbed is putting it back down."""
    coord, _ = _coordinator()
    coord.on_local_event(_event(EventType.GRABBED))
    actions = coord.on_local_event(_event(EventType.RELEASED))
    assert _kinds(actions) == ["Broadcast", "ClearContent"]
    assert actions[0].type == GestureMessage.HOLD_END
    assert not any(isinstance(a, SendCapturedFile) for a in actions)
    assert coord.holding is False


def test_a_peer_cannot_pull_a_file_when_nothing_was_grabbed():
    """A transfer always requires a deliberate grab on this device first."""
    coord, _ = _coordinator()
    actions = coord.on_peer_message(PEER_A, GestureMessage.RELEASE, {})
    assert actions == []


def test_a_second_release_sends_nothing_further():
    coord, _ = _coordinator()
    coord.on_local_event(_event(EventType.GRABBED))
    coord.on_peer_message(PEER_A, GestureMessage.RELEASE, {})
    again = coord.on_peer_message(PEER_A, GestureMessage.RELEASE, {})
    assert again == []


def test_cancelled_hold_announces_the_end():
    coord, _ = _coordinator()
    coord.on_local_event(_event(EventType.GRABBED))
    actions = coord.on_local_event(_event(EventType.CANCELLED))
    assert _kinds(actions) == ["Broadcast", "ClearContent"]
    assert coord.holding is False


def test_hold_expires_on_its_own():
    coord, clock = _coordinator(window=5.0)
    coord.on_local_event(_event(EventType.GRABBED))
    assert coord.tick() == []
    clock.advance(6.0)
    actions = coord.tick()
    assert _kinds(actions) == ["Broadcast", "ClearContent"]
    assert coord.holding is False


def test_expired_hold_does_not_send_on_a_late_release():
    coord, clock = _coordinator(window=5.0)
    coord.on_local_event(_event(EventType.GRABBED))
    clock.advance(6.0)
    coord.tick()
    assert coord.on_peer_message(PEER_A, GestureMessage.RELEASE, {}) == []


# --------------------------------------------------------------- the receiver


def test_releasing_while_a_peer_holds_tells_that_peer():
    coord, _ = _coordinator()
    coord.on_peer_message(PEER_A, GestureMessage.HOLD, {})
    actions = coord.on_local_event(_event(EventType.RELEASED))
    assert _kinds(actions) == ["SendMessage"]
    assert actions[0].peer_fp == PEER_A
    assert actions[0].type == GestureMessage.RELEASE


def test_releasing_with_nobody_holding_does_nothing():
    coord, _ = _coordinator()
    actions = coord.on_local_event(_event(EventType.RELEASED))
    assert actions == []


def test_a_peer_that_ended_its_hold_is_not_notified():
    coord, _ = _coordinator()
    coord.on_peer_message(PEER_A, GestureMessage.HOLD, {})
    coord.on_peer_message(PEER_A, GestureMessage.HOLD_END, {})
    assert coord.on_local_event(_event(EventType.RELEASED)) == []


def test_a_stale_peer_hold_is_ignored():
    coord, clock = _coordinator(window=5.0)
    coord.on_peer_message(PEER_A, GestureMessage.HOLD, {})
    clock.advance(6.0)
    assert coord.peers_holding() == []
    assert coord.on_local_event(_event(EventType.RELEASED)) == []


def test_the_most_recent_holder_wins_when_two_peers_hold():
    coord, clock = _coordinator()
    coord.on_peer_message(PEER_A, GestureMessage.HOLD, {})
    clock.advance(1.0)
    coord.on_peer_message(PEER_B, GestureMessage.HOLD, {})
    actions = coord.on_local_event(_event(EventType.RELEASED))
    assert actions[0].peer_fp == PEER_B


def test_a_disconnected_peer_stops_being_a_candidate():
    coord, _ = _coordinator()
    coord.on_peer_message(PEER_A, GestureMessage.HOLD, {})
    coord.peer_disconnected(PEER_A)
    assert coord.on_local_event(_event(EventType.RELEASED)) == []


# ------------------------------------------------------------------- general


@pytest.mark.parametrize(
    "kind", [EventType.ARMED, EventType.DISARMED, EventType.CATCH_READY]
)
def test_indicator_events_produce_no_actions(kind):
    coord, _ = _coordinator()
    assert coord.on_local_event(_event(kind)) == []


def test_unknown_peer_messages_are_ignored():
    coord, _ = _coordinator()
    assert coord.on_peer_message(PEER_A, "something_else_entirely", {}) == []


def test_reset_clears_everything():
    coord, _ = _coordinator()
    coord.on_local_event(_event(EventType.GRABBED))
    coord.on_peer_message(PEER_B, GestureMessage.HOLD, {})
    coord.reset()
    assert coord.holding is False
    assert coord.peers_holding() == []


def test_the_whole_two_device_choreography():
    """Both sides, driven only by gesture events and each other's messages."""
    sender, _ = _coordinator()
    receiver, _ = _coordinator()

    # 1. Sender grabs. It captures and announces.
    sender_actions = sender.on_local_event(_event(EventType.GRABBED))
    assert isinstance(sender_actions[0], CaptureContent)
    announcement = sender_actions[1]
    assert isinstance(announcement, Broadcast)

    # 2. The announcement reaches the receiver.
    receiver.on_peer_message(PEER_A, announcement.type, announcement.payload)
    assert receiver.peers_holding() == [PEER_A]

    # 3. The user opens their palm at the receiver.
    receiver_actions = receiver.on_local_event(_event(EventType.RELEASED))
    notice = receiver_actions[0]
    assert isinstance(notice, SendMessage)

    # 4. That notice reaches the sender, which delivers the file.
    final = sender.on_peer_message(PEER_B, notice.type, notice.payload)
    assert isinstance(final[0], SendCapturedFile)
    assert final[0].peer_fp == PEER_B
    assert any(isinstance(a, ClearContent) for a in final)
    assert sender.holding is False
