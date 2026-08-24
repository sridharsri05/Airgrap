"""Matching a grab on one device to a release on another.

This is the actual invention: nothing here moves bytes or touches a camera. It
decides, from local gesture events and messages from peers, whether a transfer
should happen and to whom.

The module is pure and synchronous. Every method returns a list of Actions for
the caller to carry out rather than performing them, which makes every race
and edge case testable with no network, no camera and no clock.

**Clocks are deliberately never compared across devices.** Two machines never
agree on the time and synchronising them is a rabbit hole. The holding device
instead treats the *arrival* of a peer's release message as the timing signal:
it knows it is holding right now, so a release arriving right now correlates.
Only a single device's own monotonic clock is ever read.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable

from airgrab.gesture import EventType, GestureEvent


class GestureMessage:
    """Control-channel message types used only by gesture correlation."""

    HOLD = "gesture_hold"           # "I have picked something up"
    HOLD_END = "gesture_hold_end"   # "my hold is over; do not expect a file"
    RELEASE = "gesture_release"     # "a release gesture happened on me"


# --------------------------------------------------------------------- actions


@dataclass(frozen=True)
class Action:
    pass


@dataclass(frozen=True)
class CaptureContent(Action):
    """Grab whatever the user is pointing at, ready to send."""


@dataclass(frozen=True)
class ClearContent(Action):
    """Drop any captured content; the gesture did not complete."""


@dataclass(frozen=True)
class Broadcast(Action):
    """Send a message to every connected trusted peer."""

    type: str
    payload: dict


@dataclass(frozen=True)
class SendMessage(Action):
    peer_fp: str
    type: str
    payload: dict


@dataclass(frozen=True)
class SendCapturedFile(Action):
    """Deliver the captured content to this peer."""

    peer_fp: str


# ----------------------------------------------------------------- coordinator


class GestureCoordinator:
    def __init__(
        self,
        clock: Callable[[], float] = time.monotonic,
        hold_window_seconds: float = 60.0,
    ) -> None:
        self._clock = clock
        self._hold_window = hold_window_seconds
        self._holding = False
        self._hold_started = 0.0
        self._peers_holding: dict[str, float] = {}

    @property
    def holding(self) -> bool:
        return self._holding

    def peers_holding(self) -> list[str]:
        self._expire()
        return list(self._peers_holding)

    # ------------------------------------------------------------ local input

    def on_local_event(self, event: GestureEvent) -> list[Action]:
        self._expire()

        if event.type is EventType.GRABBED:
            self._holding = True
            self._hold_started = self._clock()
            return [CaptureContent(), Broadcast(GestureMessage.HOLD, {})]

        if event.type is EventType.RELEASED:
            # A release on the SAME device that grabbed is the user putting it
            # back down, not a transfer. Treating it as a send would fire a
            # transfer every time someone changed their mind.
            if self._holding:
                return self._end_hold()

            peer = self._most_recent_holder()
            if peer is None:
                return []
            return [SendMessage(peer, GestureMessage.RELEASE, {})]

        if event.type is EventType.CANCELLED:
            if self._holding:
                return self._end_hold()
            return []

        # ARMED, DISARMED and CATCH_READY are indicator-only.
        return []

    # ------------------------------------------------------------- peer input

    def on_peer_message(
        self, peer_fp: str, message_type: str, payload: dict
    ) -> list[Action]:
        self._expire()

        if message_type == GestureMessage.HOLD:
            self._peers_holding[peer_fp] = self._clock()
            return []

        if message_type == GestureMessage.HOLD_END:
            self._peers_holding.pop(peer_fp, None)
            return []

        if message_type == GestureMessage.RELEASE:
            # Only act while genuinely holding. Without this a peer could ask
            # for a file at any moment; with it, a transfer always requires a
            # deliberate grab on this device first.
            if not self._holding:
                return []
            self._holding = False
            return [
                SendCapturedFile(peer_fp),
                Broadcast(GestureMessage.HOLD_END, {}),
                ClearContent(),
            ]

        return []

    # ----------------------------------------------------------------- timing

    def tick(self) -> list[Action]:
        """Expire a hold nobody ever caught."""
        self._expire()
        if self._holding and self._clock() - self._hold_started > self._hold_window:
            return self._end_hold()
        return []

    def peer_disconnected(self, peer_fp: str) -> None:
        self._peers_holding.pop(peer_fp, None)

    def reset(self) -> None:
        self._holding = False
        self._hold_started = 0.0
        self._peers_holding.clear()

    # -------------------------------------------------------------- internals

    def _end_hold(self) -> list[Action]:
        self._holding = False
        self._hold_started = 0.0
        return [Broadcast(GestureMessage.HOLD_END, {}), ClearContent()]

    def _most_recent_holder(self) -> str | None:
        """With more than one peer holding, the most recent announcement wins.

        Two people grabbing simultaneously is not a scenario worth solving
        properly for a personal two-device setup; picking deterministically
        beats picking arbitrarily.
        """
        if not self._peers_holding:
            return None
        return max(self._peers_holding.items(), key=lambda item: item[1])[0]

    def _expire(self) -> None:
        now = self._clock()
        stale = [
            fp
            for fp, at in self._peers_holding.items()
            if now - at > self._hold_window
        ]
        for fp in stale:
            del self._peers_holding[fp]
