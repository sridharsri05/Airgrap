"""The desktop's half of the design system, matching the phone's Style.kt.

The colours are Huawei's rather than invented: Cosmic Blue as the single
accent, Snow Gray as the light ground, Night Black as the dark one. Every
value here has a twin in ``android/app/src/main/kotlin/com/airgrab/ui/Style.kt``
and the two must be changed together, because the whole point is that a user
who has seen one device recognises the other.

Typography deliberately does NOT match. The phone uses the Android system
face; this uses Segoe UI, which is what Windows actually has. Forcing one
typeface across two operating systems buys nothing and costs a font
substitution that would look wrong on whichever platform lost.

Nothing here imports tkinter at module level, so the palette and the theme
lookup can be tested on a machine with no display -- which is what CI is.
"""

from __future__ import annotations

LIGHT = {
    "ground": "#F1F3F5",   # Snow Gray
    "surface": "#FFFFFF",
    "raised": "#F1F3F5",
    "ink": "#0D0D0D",
    "muted": "#5E6469",
    "accent": "#0A59F7",   # Cosmic Blue
    "on_accent": "#FFFFFF",
    "good": "#12724A",
    "warn": "#8A4B00",
    "rule": "#E4E7EB",
}

DARK = {
    "ground": "#0D0D0D",   # Night Black
    "surface": "#1B1C1E",
    "raised": "#26282B",
    "ink": "#F2F3F5",
    "muted": "#9AA0A6",
    "accent": "#6E9BFF",   # Cosmic Blue, lifted so it survives a black ground
    "on_accent": "#0D0D0D",
    "good": "#5FD08A",
    "warn": "#F0B36B",
    "rule": "#2C2E31",
}

# Segoe UI is on every Windows 10 and 11 install, so there is no substitution
# to worry about. Sizes are points, which is what Tk wants.
FACE = "Segoe UI"
MONO = "Consolas"
# Segoe UI *Symbol*, not Segoe UI Emoji. The emoji face renders these as
# colour bitmaps, which ignore the fill colour entirely -- so the hand could
# not carry the state, which is the only reason it is there.
GLYPH = "Segoe UI Symbol"

FONT_TITLE = (FACE, 8, "bold")
FONT_HEADLINE = (FACE, 16, "bold")
FONT_BODY = (FACE, 10)
FONT_STRONG = (FACE, 10, "bold")
FONT_META = (MONO, 8)
FONT_CHIP = (FACE, 8, "bold")
FONT_BUTTON = (FACE, 10, "bold")
FONT_GLYPH = (GLYPH, 15)

CARD_RADIUS = 16
CARD_PAD = 14
GAP = 10

# The window is a fixed width on purpose: a resizable window means every card
# has to recompute its rounded outline on every drag, and there is nothing
# here that benefits from being wider.
WINDOW_WIDTH = 400
WINDOW_HEIGHT = 720


def palette(theme: str) -> dict:
    """The colour set for ``light`` or ``dark``. Unknown names fall back to light."""
    return DARK if theme == "dark" else LIGHT


def windows_theme() -> str:
    """Whichever theme Windows is set to, or ``light`` if it will not say.

    Read once when the window opens rather than watched. Following a live
    theme change would mean rebuilding every widget, and nobody toggles their
    system theme while watching a file transfer.
    """
    try:
        import winreg

        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
        ) as key:
            apps_use_light, _ = winreg.QueryValueEx(key, "AppsUseLightTheme")
        return "light" if apps_use_light else "dark"
    except Exception:
        # Not Windows, key missing, or access denied. Light is the safer
        # guess: dark text on a light ground is legible either way round,
        # whereas guessing dark on a light system is not.
        return "light"


def wash(colour: str, ground: str, strength: float = 0.14) -> str:
    """A tint of ``colour`` over ``ground``, for the ground behind a chip.

    Tk has no alpha on canvas items or widget backgrounds, so a translucent
    wash has to be mixed by hand against the surface it will sit on. Derived
    from the colour it accompanies rather than listed as its own token, so a
    colour and its wash cannot drift apart.
    """
    def parts(value: str) -> tuple[int, int, int]:
        value = value.lstrip("#")
        return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)

    fr, fg, fb = parts(colour)
    br, bg, bb = parts(ground)
    mix = lambda f, b: round(b + (f - b) * strength)  # noqa: E731
    return f"#{mix(fr, br):02x}{mix(fg, bg):02x}{mix(fb, bb):02x}"


def human_size(size: int | float) -> str:
    """Bytes as the user would say them. Matches humanSize in MainActivity."""
    size = float(size)
    if size >= 1_000_000_000:
        return f"{size / 1e9:.1f} GB"
    if size >= 1_000_000:
        return f"{size / 1e6:.1f} MB"
    if size >= 1_000:
        return f"{size / 1e3:.0f} KB"
    return f"{int(size)} bytes"


def rounded_rect(canvas, x1: float, y1: float, x2: float, y2: float, radius: float, **kwargs):
    """Draw a rounded rectangle, which Tk's canvas does not offer.

    A smoothed polygon rather than four arcs and three rectangles: one item to
    create, one to delete on redraw, and no seams where the pieces meet.
    """
    radius = min(radius, abs(x2 - x1) / 2, abs(y2 - y1) / 2)
    points = [
        x1 + radius, y1,
        x2 - radius, y1, x2, y1,
        x2, y1 + radius,
        x2, y2 - radius, x2, y2,
        x2 - radius, y2,
        x1 + radius, y2, x1, y2,
        x1, y2 - radius,
        x1, y1 + radius, x1, y1,
    ]
    return canvas.create_polygon(points, smooth=True, **kwargs)
