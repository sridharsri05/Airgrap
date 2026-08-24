"""Watch the gesture engine work against your real webcam.

    python tools/gesture_live.py                 # live window, press q to quit
    python tools/gesture_live.py --headless -n 90  # no window, just report

The window shows the current pose, the state machine's state, and any events
as they fire. This is the only way to judge detection accuracy — the automated
tests cover the timing logic, which is where the bugs live, but they cannot
tell you whether the model recognises YOUR hand in YOUR lighting.
"""

from __future__ import annotations

import argparse
import sys
import time
from collections import Counter
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from airgrab.gesture import GrabStateMachine, Pose  # noqa: E402
from airgrab.handpose import HandPoseDetector, ModelMissing  # noqa: E402

_COLOUR = {
    Pose.OPEN_PALM: (80, 220, 100),
    Pose.CLOSED_FIST: (80, 160, 250),
    Pose.OTHER: (180, 180, 180),
    Pose.NONE: (120, 120, 120),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--camera", type=int, default=0)
    parser.add_argument("--headless", action="store_true")
    parser.add_argument("-n", "--frames", type=int, default=0,
                        help="stop after N frames (0 = until quit)")
    args = parser.parse_args()

    try:
        detector = HandPoseDetector()
    except ModelMissing as exc:
        print(exc)
        return 1

    capture = cv2.VideoCapture(args.camera)  # default backend: MSMF on Windows, 3x faster than DSHOW
    if not capture.isOpened():
        print(f"Could not open camera {args.camera}.")
        detector.close()
        return 1

    machine = GrabStateMachine()
    seen: Counter = Counter()
    events_seen: list[str] = []
    frames = 0
    started = time.monotonic()

    print("Show an open palm to arm, close a fist to grab, open again to release.")
    if not args.headless:
        print("Press q in the window to quit.")

    try:
        while True:
            ok, frame_bgr = capture.read()
            if not ok:
                print("Camera stopped delivering frames.")
                break

            frame_bgr = cv2.flip(frame_bgr, 1)  # mirror, so it feels natural
            frame_rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)

            pose = detector.classify(frame_rgb)
            events = machine.observe(pose)
            events.extend(machine.tick())

            seen[pose.name] += 1
            frames += 1

            for event in events:
                label = f"{event.type.name} ({event.from_state.name} -> {event.to_state.name})"
                events_seen.append(event.type.name)
                print(f"  [{frames:5}] {label}", flush=True)

            if not args.headless:
                colour = _COLOUR.get(pose, (200, 200, 200))
                cv2.putText(frame_bgr, f"pose  {pose.name}", (14, 34),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.8, colour, 2)
                cv2.putText(frame_bgr, f"state {machine.state.name}", (14, 68),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.8, (240, 240, 240), 2)
                if events_seen:
                    cv2.putText(frame_bgr, f"last  {events_seen[-1]}", (14, 102),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.7, (120, 200, 255), 2)
                cv2.imshow("AirGrab gesture engine", frame_bgr)
                if cv2.waitKey(1) & 0xFF == ord("q"):
                    break

            if args.frames and frames >= args.frames:
                break
    finally:
        capture.release()
        if not args.headless:
            cv2.destroyAllWindows()
        detector.close()

    elapsed = time.monotonic() - started
    print(f"\nframes     : {frames} in {elapsed:.1f}s "
          f"({frames / elapsed:.1f} fps)" if elapsed else "")
    print(f"poses seen : {dict(seen)}")
    print(f"events     : {events_seen or 'none'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
