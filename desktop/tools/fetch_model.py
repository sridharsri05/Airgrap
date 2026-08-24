"""Download the MediaPipe gesture recognition model.

The model is ~8 MB of binary and is deliberately not committed to git. Run
this once after cloning:

    python tools/fetch_model.py
"""

from __future__ import annotations

import hashlib
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from airgrab.handpose import DEFAULT_MODEL_PATH, MODEL_URL  # noqa: E402

EXPECTED_SHA256 = "97952348cf6a6a4915c2ea1496b4b37ebabc50cbbf80571435643c455f2b0482"


def main() -> int:
    dest = Path(DEFAULT_MODEL_PATH)
    if dest.exists():
        digest = hashlib.sha256(dest.read_bytes()).hexdigest()
        if digest == EXPECTED_SHA256:
            print(f"Model already present and verified: {dest}")
            return 0
        print(f"Model at {dest} has an unexpected hash; re-downloading.")

    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {MODEL_URL}")
    urllib.request.urlretrieve(MODEL_URL, dest)

    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    if digest != EXPECTED_SHA256:
        print(f"FAILED: hash mismatch\n  expected {EXPECTED_SHA256}\n  got      {digest}")
        dest.unlink(missing_ok=True)
        return 1

    print(f"Saved and verified: {dest} ({dest.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
