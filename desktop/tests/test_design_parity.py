"""The two devices must not drift apart visually.

The whole design argument is that a user who has seen one device recognises
the other, and that only holds while the two palettes are the same. They are
written in two languages, in two files, and nothing connects them: today the
desktop indicator was restyled and the phone's was left on the old blue, while
a comment in the desktop file went on claiming they matched.

That is the same failure as the pairing bug, in a smaller key -- two
implementations of one agreement, each internally consistent, disagreeing with
each other, and every test passing. So the agreement is asserted here.

These tests read the Kotlin source as text. They are checking a shared
constant, not behaviour, and a parser would be more machinery than the thing
it verifies.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from airgrab.ui import overlay, style

REPO = Path(__file__).resolve().parents[2]
ANDROID = REPO / "android" / "app" / "src" / "main" / "kotlin" / "com" / "airgrab"
STYLE_KT = ANDROID / "ui" / "Style.kt"
OVERLAY_KT = ANDROID / "GestureOverlay.kt"

# Checked out without the Android half, or run from a packaged copy.
needs_android = pytest.mark.skipif(
    not STYLE_KT.is_file(), reason="the Android sources are not present"
)


def colours_in(path: Path) -> set[str]:
    """Every hex colour in a file, upper-cased, alpha prefix removed.

    Android writes #AARRGGBB where the desktop writes #RRGGBB, so the eight
    digit form is trimmed to its last six before comparing.
    """
    found = set()
    for value in re.findall(r"#[0-9A-Fa-f]{6,8}\b", path.read_text(encoding="utf-8")):
        digits = value[1:].upper()
        found.add("#" + (digits[-6:] if len(digits) == 8 else digits))
    return found


@needs_android
def test_the_phone_carries_every_desktop_palette_colour():
    kotlin = colours_in(STYLE_KT)
    missing = {
        f"{name}={value}"
        for theme in (style.LIGHT, style.DARK)
        for name, value in theme.items()
        if value.upper() not in kotlin
    }
    assert not missing, (
        f"{STYLE_KT.name} does not define: {sorted(missing)}. "
        "The two design systems have drifted."
    )


@needs_android
def test_the_two_indicators_use_the_same_colours():
    # The indicator is the one thing the user sees on BOTH devices during a
    # single transfer, seconds apart. A mismatch here is the most visible
    # drift possible.
    kotlin = colours_in(OVERLAY_KT)
    for kind, value in overlay.COLOURS.items():
        assert value.upper() in kotlin, (
            f"the phone's indicator has no {kind} colour {value}"
        )


@needs_android
def test_the_indicator_ground_and_ink_match():
    kotlin = colours_in(OVERLAY_KT)
    assert overlay.BACKGROUND.upper() in kotlin
    assert overlay.TEXT.upper() in kotlin


@needs_android
def test_cosmic_blue_is_the_accent_on_both():
    # Named because it is Huawei's, not ours. If this ever fails it should be
    # because someone chose to leave HarmonyOS's palette, not by accident.
    assert style.LIGHT["accent"] == "#0A59F7"
    assert "#0A59F7" in colours_in(STYLE_KT)
