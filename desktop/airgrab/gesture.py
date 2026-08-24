"""The grab/release state machine, independent of any camera.

Everything here operates on a stream of already-classified hand poses, so the
whole of the interaction logic can be tested with synthetic sequences and no
webcam. Turning pixels into poses is handpose.py's job and nothing else's.

Two things about this interaction are easy to get wrong and are worth stating:

1. **Losing sight of the hand while holding is the success path, not a
   failure.** The user grabbed something and is moving toward the other
   device; leaving the camera's view is exactly what they are supposed to do.
   A hold therefore survives the hand vanishing, and ends only on a release or
   a timeout.

2. **A fist with no preceding open palm means this device is the
   destination.** On the sending device the gesture is palm-then-fist. On the
   receiving device the hand arrives already closed. That asymmetry is how a
   device knows which end of the transfer it is on, without being told.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable


class Pose(Enum):
    """What the hand is doing in a single frame."""

    NONE = "none"           # no hand visible
    OPEN_PALM = "open_palm"
    CLOSED_FIST = "closed_fist"
    OTHER = "other"         # a hand, but not a pose we act on


class State(Enum):
    IDLE = "idle"
    ARMED = "armed"         # open palm held; ready to grab
    HOLDING = "holding"     # fist closed after arming; this device is the source
    CATCHING = "catching"   # fist seen without arming; this device is the destination


class EventType(Enum):
    ARMED = "armed"                 # show the open-hand indicator
    DISARMED = "disarmed"           # hand withdrawn before grabbing
    GRABBED = "grabbed"             # capture content; this device is sending
    CATCH_READY = "catch_ready"     # an incoming hand is present; show the target
    RELEASED = "released"           # palm opened; complete the gesture
    CANCELLED = "cancelled"         # hold expired without a release


@dataclass(frozen=True)
class GestureEvent:
    type: EventType
    at: float
    from_state: State
    to_state: State


@dataclass(frozen=True)
class GestureConfig:
    """Thresholds are durations, not frame counts.

    Frame counts look natural here and are the wrong abstraction. Measured on
    real hardware, the same webcam delivers 30fps in good light and 10fps once
    auto-exposure lengthens indoors — so a 6-frame threshold silently changes
    from 0.2s to 0.6s as the room gets darker, and changes again on a
    different machine. Durations behave identically everywhere.

    `min_frames` remains as a floor: a duration alone would let a single
    misclassified frame satisfy the threshold on a slow camera, so a pose must
    be seen at least twice however long it lasted.
    """

    arm_seconds: float = 0.20
    grab_seconds: float = 0.13
    release_seconds: float = 0.13
    catch_seconds: float = 0.13
    disarm_seconds: float = 0.27
    min_frames: int = 2
    # How long a grab survives with no hand in view: the walk from one
    # device to the other. Twenty seconds sounded ample and was not — on a
    # real attempt it expired mid-stride and the grab silently became a
    # cancel.
    hold_timeout_seconds: float = 60.0


class GrabStateMachine:
    def __init__(
        self,
        config: GestureConfig | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.config = config or GestureConfig()
        self._clock = clock
        self._state = State.IDLE
        self._streak_pose: Pose | None = None
        self._streak_count = 0
        self._streak_started = 0.0
        self._held_since = 0.0
        self._refractory_pose: Pose | None = None

    @property
    def state(self) -> State:
        return self._state

    def reset(self) -> None:
        self._state = State.IDLE
        self._streak_pose = None
        self._streak_count = 0
        self._streak_started = 0.0
        self._held_since = 0.0
        self._refractory_pose = None

    def observe(self, pose: Pose) -> list[GestureEvent]:
        """Feed one frame's classification. Returns any events it triggered."""
        now = self._clock()
        if pose == self._streak_pose:
            self._streak_count += 1
        else:
            self._streak_pose = pose
            self._streak_count = 1
            self._streak_started = now

        # After a gesture completes, the hand is usually still in the pose
        # that ended it — a palm stays open right after a release. Acting on
        # that immediately would start a fresh gesture the user never made, so
        # the hand must change before anything new can begin.
        if self._refractory_pose is not None:
            if pose is self._refractory_pose:
                return []
            self._refractory_pose = None

        events: list[GestureEvent] = []

        # A hold that never ends would leave the UI stuck showing a grabbed
        # item forever, so it expires on its own.
        if self._state in (State.HOLDING, State.CATCHING):
            if now - self._held_since > self.config.hold_timeout_seconds:
                events.append(self._transition(EventType.CANCELLED, State.IDLE, now))
                return events

        if self._state is State.IDLE:
            if self._streak_is(Pose.OPEN_PALM, self.config.arm_seconds, now):
                events.append(self._transition(EventType.ARMED, State.ARMED, now))
            elif self._streak_is(Pose.CLOSED_FIST, self.config.catch_seconds, now):
                # A closed hand we never saw open: someone is arriving with
                # something, so this device is the destination.
                self._held_since = now
                events.append(
                    self._transition(EventType.CATCH_READY, State.CATCHING, now)
                )
            return events

        if self._state is State.ARMED:
            if self._streak_is(Pose.CLOSED_FIST, self.config.grab_seconds, now):
                self._held_since = now
                events.append(self._transition(EventType.GRABBED, State.HOLDING, now))
            elif self._streak_is(Pose.NONE, self.config.disarm_seconds, now):
                events.append(self._transition(EventType.DISARMED, State.IDLE, now))
            return events

        if self._state is State.HOLDING:
            # Pose.NONE is deliberately not handled here: the hand leaving the
            # frame is the user carrying the file to the other device.
            if self._streak_is(Pose.OPEN_PALM, self.config.release_seconds, now):
                events.append(self._transition(EventType.RELEASED, State.IDLE, now))
            return events

        if self._state is State.CATCHING:
            if self._streak_is(Pose.OPEN_PALM, self.config.release_seconds, now):
                events.append(self._transition(EventType.RELEASED, State.IDLE, now))
            return events

        return events

    def tick(self) -> list[GestureEvent]:
        """Advance time without a new frame, so timeouts fire when the camera
        stalls or the hand simply never comes back."""
        now = self._clock()
        if self._state in (State.HOLDING, State.CATCHING):
            if now - self._held_since > self.config.hold_timeout_seconds:
                return [self._transition(EventType.CANCELLED, State.IDLE, now)]
        return []

    # ------------------------------------------------------------- internals

    def _streak_is(self, pose: Pose, seconds: float, now: float) -> bool:
        if self._streak_pose is not pose:
            return False
        if self._streak_count < self.config.min_frames:
            return False
        return (now - self._streak_started) >= seconds

    def _transition(
        self, event: EventType, to_state: State, now: float
    ) -> GestureEvent:
        from_state = self._state
        self._state = to_state
        if to_state is State.IDLE and from_state is not State.IDLE:
            self._refractory_pose = self._streak_pose
        # A completed transition must not immediately re-fire on the next
        # frame of the same pose, so the streak restarts here.
        self._streak_count = 0
        self._streak_pose = None
        self._streak_started = now
        if to_state is State.IDLE:
            self._held_since = 0.0
        return GestureEvent(
            type=event, at=now, from_state=from_state, to_state=to_state
        )
