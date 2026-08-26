"""Where the gesture engine, the correlation logic and the network meet.

Each of the three parts below is independently testable and deliberately
ignorant of the others:

    handpose.py    pixels  -> Pose
    gesture.py     Pose    -> GestureEvent      (pure)
    coordinator.py events  -> Action            (pure)
    session.py     Action  -> actually doing it

This module is the only one that knows about all of them, and it is
deliberately thin: it executes actions and owns no decisions of its own.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from pathlib import Path
from typing import Callable

from airgrab.coordinator import (
    Broadcast,
    CaptureContent,
    ClearContent,
    GestureCoordinator,
    SendCapturedFile,
    SendMessage,
)
from airgrab.gesture import GestureConfig, GestureEvent, GrabStateMachine, Pose
from airgrab.node import Node


class GestureSession:
    def __init__(
        self,
        node: Node,
        capture: Callable[[], Path | None],
        config: GestureConfig | None = None,
        clock: Callable[[], float] = time.monotonic,
        hold_window_seconds: float = 20.0,
    ) -> None:
        self.node = node
        self.machine = GrabStateMachine(config, clock=clock)
        self.coordinator = GestureCoordinator(
            clock=clock, hold_window_seconds=hold_window_seconds
        )
        self._capture = capture
        self._captured: Path | None = None

        # Peer addresses come from mDNS discovery. The control channel does
        # not carry them: an inbound connection's source port is not the port
        # the peer listens on, so it cannot be inferred from the socket.
        self._addresses: dict[str, tuple[str, int]] = {}

        self._pending: set[asyncio.Task] = set()
        self.on_event: Callable[[GestureEvent], None] | None = None
        self.on_transfer: Callable[[str, bool], None] | None = None

        node.on_peer_gesture = self._on_peer_gesture

    # ------------------------------------------------------------------ setup

    def register_peer(self, peer_fp: str, host: str, port: int) -> None:
        self._addresses[peer_fp] = (host, port)

    def forget_peer(self, peer_fp: str) -> None:
        self._addresses.pop(peer_fp, None)
        self.coordinator.peer_disconnected(peer_fp)
        self.machine.set_peer_holding(bool(self.coordinator.peers_holding()))

    @property
    def captured(self) -> Path | None:
        return self._captured

    # ------------------------------------------------------------------ input

    async def observe(self, pose: Pose) -> None:
        """Feed one classified frame and carry out whatever it implies."""
        events = self.machine.observe(pose)
        events.extend(self.machine.tick())
        # Expired peer holds are pruned inside the coordinator's tick, so the
        # flag is refreshed here too, not only when a message arrives.
        self.machine.set_peer_holding(bool(self.coordinator.peers_holding()))
        for event in events:
            if self.on_event is not None:
                self.on_event(event)
            await self._run(self.coordinator.on_local_event(event))
        await self._run(self.coordinator.tick())

    def _on_peer_gesture(self, peer_fp: str, message_type: str, payload: dict) -> None:
        """Called from the control channel's reader, which is not a place we
        can await, so the resulting work is scheduled."""
        actions = self.coordinator.on_peer_message(peer_fp, message_type, payload)
        self.machine.set_peer_holding(bool(self.coordinator.peers_holding()))
        if not actions:
            return
        task = asyncio.create_task(self._run(actions))
        self._pending.add(task)
        task.add_done_callback(self._pending.discard)

    # -------------------------------------------------------------- execution

    async def _run(self, actions: list) -> None:
        for action in actions:
            if isinstance(action, CaptureContent):
                self._captured = self._capture()

            elif isinstance(action, ClearContent):
                self._captured = None

            elif isinstance(action, Broadcast):
                await self.node.broadcast(action.type, action.payload)

            elif isinstance(action, SendMessage):
                await self.node.send_to_peer(
                    action.peer_fp, action.type, action.payload
                )

            elif isinstance(action, SendCapturedFile):
                await self._deliver(action.peer_fp)

    async def _deliver(self, peer_fp: str) -> None:
        path = self._captured
        address = self._addresses.get(peer_fp)
        if path is None or address is None:
            if self.on_transfer is not None:
                self.on_transfer(peer_fp, False)
            return

        host, port = address
        try:
            ok = await self.node.send_file(host, port, path, expect_fp=peer_fp)
        except Exception:
            ok = False
        if self.on_transfer is not None:
            self.on_transfer(peer_fp, ok)

    async def drain(self) -> None:
        """Wait for work scheduled from the control-channel reader."""
        while self._pending:
            await asyncio.gather(*list(self._pending), return_exceptions=True)

    async def close(self) -> None:
        for task in list(self._pending):
            task.cancel()
        for task in list(self._pending):
            with contextlib.suppress(Exception, asyncio.CancelledError):
                await task
        self._pending.clear()
        self.node.on_peer_gesture = None
