"""Generate the application icons for both platforms from one definition.

Drawn in code rather than checked in as art so the two platforms cannot drift
apart, and so a colour change is one edit instead of a re-export of eleven
files. The glyph matches the tray icon in `desktop/airgrab/ui/tray.py`: the
same mark in the system tray, the taskbar and the phone's home screen.

Run:  python tools/make_icons.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]

# The accent blue used by the Android UI, so the icon belongs to the app.
BRAND = (27, 98, 214, 255)
BRAND_DARK = (18, 70, 158, 255)
WHITE = (255, 255, 255, 255)


def _arrow(draw: ImageDraw.ImageDraw, size: int, colour=WHITE) -> None:
    """An upward arrow: the app's whole job is sending things somewhere else."""
    u = size / 64.0
    draw.polygon(
        [
            (32 * u, 14 * u),
            (48 * u, 33 * u),
            (38.5 * u, 33 * u),
            (38.5 * u, 50 * u),
            (25.5 * u, 50 * u),
            (25.5 * u, 33 * u),
            (16 * u, 33 * u),
        ],
        fill=colour,
    )


def _open_hand(draw: ImageDraw.ImageDraw, size: int, colour=WHITE) -> None:
    """A shallow arc above the arrow, reading as a hand letting go.

    Kept deliberately simple. Anything more literal turns to mush at 48px,
    which is the size that actually matters on a home screen.
    """
    u = size / 64.0
    draw.arc(
        [(14 * u, 2 * u), (50 * u, 26 * u)],
        start=200,
        end=340,
        fill=colour,
        width=max(2, int(3.5 * u)),
    )


def _vertical_gradient(size: int, top, bottom) -> Image.Image:
    image = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        blend = y / max(1, size - 1)
        draw.line(
            [(0, y), (size, y)],
            fill=tuple(
                int(top[channel] + (bottom[channel] - top[channel]) * blend)
                for channel in range(4)
            ),
        )
    return image


def rounded_icon(size: int) -> Image.Image:
    """The full icon: rounded square, gradient, glyph. Used by Windows."""
    image = _vertical_gradient(size, BRAND, BRAND_DARK)

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [(0, 0), (size - 1, size - 1)], radius=int(size * 0.22), fill=255
    )
    image.putalpha(mask)

    draw = ImageDraw.Draw(image)
    _open_hand(draw, size)
    _arrow(draw, size)
    return image


def adaptive_foreground(size: int) -> Image.Image:
    """Android's foreground layer: glyph only, transparent background.

    Android crops an adaptive icon to whatever shape the launcher prefers, and
    masks away everything outside the middle 66%. The glyph is drawn into that
    safe zone, so a circular launcher and a squircle one both keep the whole
    arrow.
    """
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    safe = int(size * 0.60)
    glyph = Image.new("RGBA", (safe, safe), (0, 0, 0, 0))
    draw = ImageDraw.Draw(glyph)
    _open_hand(draw, safe)
    _arrow(draw, safe)
    offset = (size - safe) // 2
    image.paste(glyph, (offset, offset), glyph)
    return image


def adaptive_background(size: int) -> Image.Image:
    return _vertical_gradient(size, BRAND, BRAND_DARK)


def legacy_icon(size: int) -> Image.Image:
    """For launchers older than adaptive icons, and for the notification list."""
    return rounded_icon(size)


def write_android() -> list[Path]:
    written: list[Path] = []
    res = ROOT / "android" / "app" / "src" / "main" / "res"

    # Android's density buckets. 48dp at each scale factor.
    densities = {
        "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
    }

    for bucket, px in densities.items():
        directory = res / f"mipmap-{bucket}"
        directory.mkdir(parents=True, exist_ok=True)

        for name, image in (
            ("ic_launcher.png", legacy_icon(px)),
            ("ic_launcher_round.png", _circular(px)),
            # Adaptive layers are drawn at 108dp, of which only the middle
            # 72dp is guaranteed visible.
            ("ic_launcher_foreground.png", adaptive_foreground(int(px * 108 / 48))),
            ("ic_launcher_background.png", adaptive_background(int(px * 108 / 48))),
        ):
            path = directory / name
            image.save(path)
            written.append(path)

    anydpi = res / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = anydpi / name
        path.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
            "</adaptive-icon>\n"
        )
        written.append(path)

    # A monochrome notification icon. Android draws these as a silhouette, so
    # anything with colour or a filled background becomes a white blob.
    for bucket, px in densities.items():
        directory = res / f"drawable-{bucket}"
        directory.mkdir(parents=True, exist_ok=True)
        image = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image)
        _open_hand(draw, px)
        _arrow(draw, px)
        path = directory / "ic_notification.png"
        image.save(path)
        written.append(path)

    return written


def _circular(size: int) -> Image.Image:
    image = _vertical_gradient(size, BRAND, BRAND_DARK)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([(0, 0), (size - 1, size - 1)], fill=255)
    image.putalpha(mask)
    draw = ImageDraw.Draw(image)
    _open_hand(draw, size)
    _arrow(draw, size)
    return image


def write_windows() -> Path:
    directory = ROOT / "desktop" / "assets"
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "airgrab.ico"

    # Every size Windows asks for. Missing one makes Explorer scale a large
    # bitmap down, which looks noticeably worse than a purpose-drawn small one.
    #
    # Saved from the LARGEST image, not the smallest: Pillow silently drops any
    # requested size bigger than the source, so passing the 16px one produces a
    # single-entry file that looks fine in Explorer's list view and blurry
    # everywhere else.
    sizes = [16, 24, 32, 48, 64, 128, 256]
    rounded_icon(256).save(path, format="ICO", sizes=[(s, s) for s in sizes])

    rounded_icon(512).save(directory / "airgrab.png")
    return path


if __name__ == "__main__":
    android = write_android()
    windows = write_windows()
    print(f"android: {len(android)} files under android/app/src/main/res")
    print(f"windows: {windows.relative_to(ROOT)}")
