"""The window's wording and its snapshot, tested without a display.

Everything here runs on a machine with no window system, which is the point:
the parts that decide what the user reads are separated from the parts that
draw it, so they can be checked in CI and on any machine.

The sentences matter more than they look. Each one replaces a moment that
happened on real hardware where the PC said nothing at all and the user was
left guessing whether the app was working.
"""

from __future__ import annotations

import pytest

from airgrab.ui import style
from airgrab.ui.window import (
    STATES,
    Arrival,
    Device,
    Snapshot,
    describe,
)


# ------------------------------------------------------------------- wording


def test_every_state_names_the_users_next_move():
    for kind, (glyph, tone, headline, detail) in STATES.items():
        assert glyph, f"{kind} has no glyph"
        assert tone in {"accent", "good", "warn", "muted"}, f"{kind}: {tone}"
        assert headline, f"{kind} has no headline"
        # Headlines are sentence case, never SHOUTING or an enum name.
        assert headline[0].isupper(), kind
        assert "_" not in headline, kind


def test_the_peer_name_is_substituted():
    _, _, _, detail = describe("holding", peer="Moto G85")
    assert "Moto G85" in detail
    assert "{peer}" not in detail


def test_a_missing_peer_name_still_reads_as_a_sentence():
    # Discovery may not have filled in a name yet. A cosmetic gap is fine; a
    # render that raises in front of the user is not.
    _, _, _, detail = describe("holding")
    assert "the other device" in detail
    assert "{" not in detail


def test_a_received_file_is_named():
    _, _, headline, detail = describe("received", file="holiday.jpg")
    assert headline == "Received"
    assert "holiday.jpg" in detail


def test_an_unknown_state_does_not_raise():
    glyph, tone, headline, detail = describe("something-new")
    assert headline
    assert "{" not in detail


def test_ready_and_holding_use_different_hands():
    # The hand is the whole indicator. A fist and an open palm that looked
    # the same would make the card useless at a glance, which is the only
    # way it is ever read.
    assert describe("ready")[0] != describe("holding")[0]


# ------------------------------------------------------------------ snapshot


def test_snapshots_compare_by_value():
    # The window redraws only when this changes. If equality were identity,
    # it would rebuild every widget four times a second and flicker.
    left = Snapshot(kind="ready", peer="Moto", devices=(
        Device("ab", "Moto", "192.168.1.4", True),
    ))
    right = Snapshot(kind="ready", peer="Moto", devices=(
        Device("ab", "Moto", "192.168.1.4", True),
    ))
    assert left == right


def test_a_changed_device_makes_a_different_snapshot():
    paired = Snapshot(devices=(Device("ab", "Moto", "192.168.1.4", True),))
    not_paired = Snapshot(devices=(Device("ab", "Moto", "192.168.1.4", False),))
    assert paired != not_paired


def test_a_new_arrival_makes_a_different_snapshot():
    before = Snapshot()
    after = Snapshot(arrivals=(Arrival("a.jpg", 10, "15:04"),))
    assert before != after


def test_a_snapshot_is_immutable():
    # Held by the window as the last thing it drew. If a caller could mutate
    # it in place, the comparison would say nothing had changed.
    snapshot = Snapshot()
    with pytest.raises(Exception):
        snapshot.kind = "ready"


# --------------------------------------------------------------------- style


def test_the_two_themes_define_the_same_tokens():
    # A token defined in one theme and not the other is how a widget ends up
    # with no colour at all, which Tk renders as black on black.
    assert set(style.LIGHT) == set(style.DARK)


def test_every_colour_is_a_full_hex_value():
    for theme in (style.LIGHT, style.DARK):
        for name, value in theme.items():
            assert value.startswith("#") and len(value) == 7, f"{name}={value}"


def test_cosmic_blue_is_huaweis_own():
    assert style.LIGHT["accent"] == "#0A59F7"


def test_a_wash_sits_between_the_colour_and_its_ground():
    # Tk has no alpha, so a chip's ground is mixed by hand. A wash that came
    # out darker than both would make the label unreadable.
    mixed = style.wash("#0A59F7", "#FFFFFF", 0.14)
    assert mixed != "#0a59f7"
    assert mixed != "#ffffff"
    red = int(mixed[1:3], 16)
    assert 0x0A < red < 0xFF


def test_a_full_strength_wash_is_the_colour_itself():
    assert style.wash("#0A59F7", "#FFFFFF", 1.0) == "#0a59f7"


@pytest.mark.parametrize(
    "size,expected",
    [
        (0, "0 bytes"),
        (999, "999 bytes"),
        (1_000, "1 KB"),
        (278_687, "279 KB"),
        (184_000_000, "184.0 MB"),
        (2_400_000_000, "2.4 GB"),
    ],
)
def test_sizes_read_the_way_a_person_says_them(size, expected):
    assert style.human_size(size) == expected


def test_an_unknown_theme_falls_back_to_light():
    # windows_theme returns "light" when the registry will not answer, and
    # anything unexpected must land somewhere legible rather than nowhere.
    assert style.palette("nonsense") is style.LIGHT
