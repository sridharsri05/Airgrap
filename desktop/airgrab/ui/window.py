"""The main window.

The PC has had no screen at all: a tray icon, a balloon, and a menu. The user
clicked it expecting something and reported that nothing came up. Everything
the phone shows -- what is happening, what a grab would send, which devices
are around, what has arrived -- had no equivalent here.

This is that screen, laid out exactly like the phone's so that the two halves
of the product read as one thing.

## What is testable here

``describe`` and the snapshot types are free of tkinter and carry all the
wording, so the sentences the user reads can be tested on a machine with no
display. The widget code below them decides nothing.

## Threading

Every method that touches a widget runs on the Shell's thread. The public
methods -- ``show``, ``hide``, ``update`` -- are safe from anywhere and hand
their work over.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable

from . import style
from .shell import Shell

# ---------------------------------------------------------------- the wording

# The hand is the signature element, borrowed from Huawei's own gesture UI:
# it is the one thing that says the device is watching you. The sentence names
# the user's next move rather than an internal state, because "Connected"
# tells them nothing they can act on.
#
# Every entry here is a moment that happened on real hardware with nothing on
# the PC to explain it.
STATES: dict[str, tuple[str, str, str, str]] = {
    "starting": ("•", "muted", "Starting", "Setting up the link."),
    "looking": (
        "◌", "warn", "Looking for your phone",
        "Both devices need the same Wi-Fi, with AirGrab running on the phone.",
    ),
    "unpaired": (
        "◌", "warn", "Pair to continue",
        "A device is nearby but not paired yet.",
    ),
    "ready": (
        "✋", "good", "Ready",
        "Make a fist to pick something up, then open your palm at {peer}.",
    ),
    "holding": (
        "✊", "accent", "Holding",
        "Open your palm at {peer} to drop it.",
    ),
    "incoming": (
        "✋", "accent", "Incoming",
        "Open your palm at the webcam to receive it.",
    ),
    "expired": (
        "⏱", "warn", "Grab expired",
        "Nothing was sent. Grab again when you are in front of the other device.",
    ),
    "sent": ("✓", "good", "Sent", "{file} is on {peer}."),
    "received": ("✓", "good", "Received", "{file} is in your AirGrab folder."),
    "send_failed": (
        "⚠", "warn", "Could not send",
        "{peer} did not take it. Check it is still on the same Wi-Fi.",
    ),
    "unreachable": (
        "⚠", "warn", "Could not reach {peer}",
        "It is advertising itself but not answering. Check it is still awake "
        "and on the same Wi-Fi.",
    ),
    "firewall": (
        "⚠", "warn", "Firewall is blocking AirGrab",
        "Close AirGrab, right-click it, and choose Run as administrator — once.",
    ),
    "gestures_off": (
        "⚠", "warn", "Gestures are off",
        "No camera was found, or the gesture model is missing. "
        "You can still receive files.",
    ),
}

# The one the code falls back to, rather than raising in front of the user.
UNKNOWN = ("•", "muted", "Working", "")


def describe(kind: str, peer: str | None = None, file: str | None = None):
    """Turn a state name into (glyph, tone, headline, detail).

    Substitution is forgiving on purpose. A missing peer name is a cosmetic
    problem; a window that raises mid-render because discovery had not filled
    in a name yet is not.
    """
    glyph, tone, headline, detail = STATES.get(kind, UNKNOWN)

    # Both halves, not just the detail. Substituting only the sentence put
    # the words "Could not reach {peer}" on screen as a headline, in front of
    # the user, which is exactly the kind of thing that reads as unfinished
    # software even when everything underneath is working.
    def fill(text: str) -> str:
        return (
            text.replace("{peer}", peer or "the other device")
                .replace("{file}", file or "The file")
        )

    return glyph, tone, fill(headline), fill(detail)


# --------------------------------------------------------------- the snapshot


@dataclass(frozen=True)
class Device:
    fingerprint: str
    name: str
    host: str
    paired: bool


@dataclass(frozen=True)
class Arrival:
    name: str
    size: int
    when: str


@dataclass(frozen=True)
class Snapshot:
    """Everything the window draws, in a form that can be compared.

    Frozen, with tuples rather than lists, so the window can tell whether
    anything actually changed. Rebuilding widgets four times a second when
    nothing moved would flicker and waste the machine.
    """

    kind: str = "starting"
    peer: str | None = None
    file: str | None = None
    outgoing_name: str | None = None
    outgoing_size: int | None = None
    outgoing_note: str = ""
    devices: tuple[Device, ...] = field(default_factory=tuple)
    arrivals: tuple[Arrival, ...] = field(default_factory=tuple)


@dataclass
class Actions:
    """What the window can ask the application to do."""

    pair: Callable[[str], None]
    open_folder: Callable[[], None]
    settings: Callable[[], None]
    quit: Callable[[], None]


MAX_ARRIVALS = 3


# ----------------------------------------------------------------- the widgets


class _Card:
    """A rounded panel. Tk draws rectangles, so the corners are painted."""

    def __init__(self, parent, pal: dict, width: int) -> None:
        import tkinter

        self.pal = pal
        self.width = width
        self.canvas = tkinter.Canvas(
            parent, bg=pal["ground"], highlightthickness=0, bd=0,
            width=width, height=10,
        )
        self.body = tkinter.Frame(self.canvas, bg=pal["surface"])
        self._fill = pal["surface"]
        self._rect = None
        self.canvas.create_window(
            style.CARD_PAD, style.CARD_PAD, anchor="nw", window=self.body,
            width=width - 2 * style.CARD_PAD,
        )

    def pack(self, **kwargs) -> None:
        self.canvas.pack(**kwargs)

    def clear(self) -> None:
        for child in self.body.winfo_children():
            child.destroy()

    def tint(self, colour: str) -> None:
        """Colour the whole card, not just something inside it.

        Set BEFORE the body is filled: every label takes its background from
        its parent, so a tint applied afterwards would leave each line of text
        sitting on a rectangle of the old colour.
        """
        self._fill = colour
        self.body.configure(bg=colour)

    def refresh(self) -> None:
        """Size the canvas to its contents and repaint the outline.

        Called after the body is filled, because a Frame does not know how
        tall it is until Tk has laid its children out.
        """
        self.body.update_idletasks()
        height = self.body.winfo_reqheight() + 2 * style.CARD_PAD
        self.canvas.configure(height=height)
        if self._rect is not None:
            self.canvas.delete(self._rect)
        self._rect = style.rounded_rect(
            self.canvas, 1, 1, self.width - 1, height - 1, style.CARD_RADIUS,
            fill=self._fill,
            outline=self.pal["rule"] if self._fill == self.pal["surface"] else self._fill,
        )
        self.canvas.tag_lower(self._rect)


class _Pill:
    """A rounded button, for the same reason the cards are rounded."""

    def __init__(
        self,
        parent,
        pal: dict,
        text: str,
        command: Callable[[], None],
        width: int,
        filled: bool = True,
    ) -> None:
        import tkinter

        self.pal = pal
        self.command = command
        self.filled = filled
        self.height = 32

        ground = parent["bg"]
        self.fill = pal["accent"] if filled else style.wash(pal["accent"], ground, 0.14)
        self.ink = pal["on_accent"] if filled else pal["accent"]
        self.hover = style.wash(pal["ink"], self.fill, 0.10)

        self.canvas = tkinter.Canvas(
            parent, bg=ground, highlightthickness=0, bd=0,
            width=width, height=self.height, cursor="hand2",
        )
        self._shape = style.rounded_rect(
            self.canvas, 0, 0, width, self.height, self.height / 2,
            fill=self.fill, outline="",
        )
        self.canvas.create_text(
            width / 2, self.height / 2, text=text,
            fill=self.ink, font=style.FONT_BUTTON,
        )
        self.canvas.bind("<Button-1>", self._clicked)
        # Something that can be pressed should look like it notices being
        # pointed at. Without this the buttons read as coloured labels.
        self.canvas.bind("<Enter>", lambda _e: self.canvas.itemconfigure(
            self._shape, fill=self.hover))
        self.canvas.bind("<Leave>", lambda _e: self.canvas.itemconfigure(
            self._shape, fill=self.fill))

    def _clicked(self, _event) -> None:
        try:
            self.command()
        except Exception:
            # A failing action must not take the window with it -- but a
            # swallowed one must leave a trace. This exact handler ate the
            # Settings deadlock guard, and the symptom was a button that
            # "did nothing" with no evidence anywhere.
            import logging

            logging.getLogger("airgrab").exception("a button action failed")

    def pack(self, **kwargs) -> None:
        self.canvas.pack(**kwargs)


class MainWindow:
    """The window. Safe to drive from any thread."""

    def __init__(self, shell: Shell, actions: Actions, theme: str | None = None) -> None:
        self._shell = shell
        self._actions = actions
        self._theme = theme or style.windows_theme()
        self._pal = style.palette(self._theme)
        self._window = None
        self._cards: dict[str, _Card] = {}
        self._last: Snapshot | None = None
        self._pending = Snapshot()

        # The hero badge's animation. One after-loop, owned by the Tk thread;
        # _render_state retargets or stops it as the state changes.
        self._anim_job = None
        self._anim_canvas = None
        self._anim_rings: list = []
        self._anim_phase = 0.0
        self._anim_burst = 0  # ripples left in a one-shot burst, 0 = continuous
        self._last_kind: str | None = None

    # ------------------------------------------------------------ public API

    def show(self) -> None:
        self._shell.submit(self._show)

    def hide(self) -> None:
        self._shell.submit(self._hide)

    def update(self, snapshot: Snapshot) -> None:
        """Redraw, but only if something actually changed."""
        if snapshot == self._last:
            return
        self._last = snapshot
        self._pending = snapshot
        self._shell.submit(lambda: self._render(snapshot))

    # ------------------------------------------------------------- Tk thread

    def _show(self) -> None:
        if self._window is None:
            self._build()
        if self._window is None:
            return
        self._window.deiconify()
        self._window.lift()
        self._window.focus_force()

    def _hide(self) -> None:
        """Closing hides. AirGrab keeps running -- that is the whole point of
        an app that answers a gesture made while you are using something else.
        Quit, in the footer, is the control that actually stops it."""
        if self._window is not None:
            self._window.withdraw()

    def _build(self) -> None:
        import tkinter

        root = self._shell.root
        if root is None:
            return

        pal = self._pal
        window = tkinter.Toplevel(root)
        window.title("AirGrab")
        window.configure(bg=pal["ground"])
        # Fitted to the screen, not to a number I picked. 720 pixels plus a
        # title bar does not fit on a 768-high laptop, and the footer -- which
        # holds Quit -- ends up behind the taskbar where nothing can reach it.
        available = window.winfo_screenheight() - 120
        height = max(360, min(style.WINDOW_HEIGHT, available))
        left = (window.winfo_screenwidth() - style.WINDOW_WIDTH) // 2
        window.geometry(f"{style.WINDOW_WIDTH}x{height}+{left}+40")
        window.minsize(style.WINDOW_WIDTH, 360)
        window.resizable(False, True)
        window.protocol("WM_DELETE_WINDOW", self._hide)

        # Best-effort: without it the title bar and taskbar show Tk's own
        # feather, which looks like a stray Python script rather than AirGrab.
        try:
            from ..resources import resource_path

            icon = resource_path("assets", "airgrab.ico")
            if icon.exists():
                window.iconbitmap(str(icon))
        except Exception:
            pass

        # A scrolling body, because the device and arrival lists grow. Without
        # it a fourth paired device would push the footer off the bottom with
        # no way to reach it.
        outer = tkinter.Canvas(window, bg=pal["ground"], highlightthickness=0, bd=0)
        scrollbar = tkinter.Scrollbar(
            window, orient="vertical", command=outer.yview,
            width=10, borderwidth=0, relief="flat", highlightthickness=0,
            troughcolor=pal["ground"], bg=pal["rule"], activebackground=pal["muted"],
        )
        content = tkinter.Frame(outer, bg=pal["ground"])

        def fit(_event=None) -> None:
            """Keep the scroll region honest, and hide the bar when it is not
            needed. Tk's scrollbar is a raw system widget and looks like one;
            leaving it on screen beside a window that does not scroll is a
            piece of chrome doing nothing."""
            outer.configure(scrollregion=outer.bbox("all"))
            needed = content.winfo_reqheight() > outer.winfo_height()
            if needed and not scrollbar.winfo_ismapped():
                scrollbar.pack(side="right", fill="y")
            elif not needed and scrollbar.winfo_ismapped():
                scrollbar.pack_forget()

        content.bind("<Configure>", fit)
        outer.bind("<Configure>", fit)
        outer.create_window((0, 0), window=content, anchor="nw")
        outer.configure(yscrollcommand=scrollbar.set)
        outer.pack(side="left", fill="both", expand=True)

        def wheel(event) -> None:
            outer.yview_scroll(-1 * (event.delta // 120), "units")

        window.bind("<MouseWheel>", wheel)

        inner = style.WINDOW_WIDTH - 2 * style.GAP - 16  # 16 leaves room for the bar
        holder = tkinter.Frame(content, bg=pal["ground"])
        holder.pack(padx=style.GAP, pady=style.GAP)

        # The wordmark lives above the cards, where a title belongs. Inside
        # the state card it competed with the one sentence that matters.
        head = tkinter.Frame(holder, bg=pal["ground"])
        head.pack(fill="x", pady=(2, 10))
        mark = tkinter.Canvas(
            head, width=22, height=22, bg=pal["ground"], highlightthickness=0, bd=0
        )
        style.rounded_rect(mark, 0, 0, 22, 22, 7, fill=pal["accent"], outline="")
        mark.create_text(
            11, 11, text="↑", fill=pal["on_accent"], font=(style.FACE, 10, "bold")
        )
        mark.pack(side="left")
        tkinter.Label(
            head, text="AirGrab", font=style.FONT_WORDMARK,
            fg=pal["ink"], bg=pal["ground"],
        ).pack(side="left", padx=(8, 0))

        for name in ("state", "outgoing", "devices", "arrivals"):
            card = _Card(holder, pal, inner)
            card.pack(pady=(0, style.GAP))
            self._cards[name] = card

        footer = tkinter.Frame(holder, bg=pal["ground"])
        footer.pack(fill="x")
        half = (inner - style.GAP) // 2
        _Pill(footer, {**pal, "surface": pal["ground"]}, "Settings",
              self._actions.settings, half, filled=False).pack(side="left")
        _Pill(footer, {**pal, "surface": pal["ground"]}, "Quit",
              self._actions.quit, half, filled=False).pack(side="right")

        self._window = window
        self._render(self._pending)
        self._match_title_bar(window)

    def _match_title_bar(self, window) -> None:
        """Ask Windows for a dark title bar on a dark window.

        The frame is drawn by the system, not by us, so a black window still
        gets a white bar unless it opts in. Best-effort in every direction:
        the attribute number changed between Windows 10 builds, and on
        anything older neither works. A light bar is a blemish; a crash on
        opening the window is not.
        """
        if self._theme != "dark":
            return
        try:
            import ctypes

            window.update_idletasks()
            handle = ctypes.windll.user32.GetParent(window.winfo_id())
            enabled = ctypes.c_int(1)
            for attribute in (20, 19):  # USE_IMMERSIVE_DARK_MODE, new and old
                if ctypes.windll.dwmapi.DwmSetWindowAttribute(
                    handle, attribute, ctypes.byref(enabled), ctypes.sizeof(enabled)
                ) == 0:
                    return
        except Exception:
            pass

    # -------------------------------------------------------------- rendering

    def _label(self, parent, text: str, font, colour: str, **pack):
        import tkinter

        widget = tkinter.Label(
            parent, text=text, font=font, fg=colour, bg=parent["bg"],
            anchor="w", justify="left",
        )
        widget.pack(fill="x", **pack)
        return widget

    def _title(self, parent, text: str) -> None:
        # Tk has no letter-spacing, so the spacing is in the string. Crude,
        # and the only way to get the phone's wide small-caps label here.
        self._label(parent, " ".join(text), style.FONT_TITLE, self._pal["muted"])

    def _render(self, snapshot: Snapshot) -> None:
        if self._window is None:
            return
        self._render_state(snapshot)
        self._render_outgoing(snapshot)
        self._render_devices(snapshot)
        self._render_arrivals(snapshot)

    def _render_state(self, snapshot: Snapshot) -> None:
        """The hero. The whole card takes the state's colour as a wash, so
        the answer to "is it working" is readable from across the room before
        a single word is: green card good, amber card needs you."""
        import tkinter

        pal = self._pal
        card = self._cards["state"]
        card.clear()

        glyph, tone, headline, detail = describe(
            snapshot.kind, snapshot.peer, snapshot.file
        )
        colour = pal.get(tone, pal["muted"])
        ground = style.wash(colour, pal["surface"], 0.10)
        card.tint(ground)

        row = tkinter.Frame(card.body, bg=ground)
        row.pack(fill="x", pady=(4, 0))

        badge = tkinter.Canvas(
            row, width=56, height=56, bg=ground, highlightthickness=0, bd=0
        )
        style.rounded_rect(
            badge, 0, 0, 56, 56, 18,
            fill=style.wash(colour, ground, 0.22), outline="",
        )
        badge.create_text(28, 28, text=glyph, fill=colour, font=style.FONT_GLYPH)
        badge.pack(side="left")

        text = tkinter.Frame(row, bg=ground)
        text.pack(side="left", padx=(14, 0), fill="x", expand=True)
        headline_label = tkinter.Label(
            text, text=headline, font=style.FONT_HEADLINE, fg=pal["ink"],
            bg=ground, anchor="w", justify="left",
            wraplength=card.width - 2 * style.CARD_PAD - 70,
        )
        headline_label.pack(fill="x")

        if detail:
            wrapped = tkinter.Label(
                card.body, text=detail, font=style.FONT_BODY, fg=pal["muted"],
                bg=ground, anchor="w", justify="left",
                wraplength=card.width - 2 * style.CARD_PAD,
            )
            wrapped.pack(fill="x", pady=(12, 4))

        card.refresh()
        self._retarget_animation(snapshot.kind, badge, colour, ground)

    # ------------------------------------------------------------- animation

    # States that mean "actively looking": the badge pulses like a radar so
    # the card reads as searching rather than stuck. A still screen and a
    # frozen screen are indistinguishable, and this app has genuinely been
    # both in front of its user.
    PULSING = frozenset({"starting", "looking", "unpaired", "holding", "incoming"})

    def _retarget_animation(self, kind: str, badge, colour: str, ground: str) -> None:
        """Called by _render_state, on the Tk thread, after every redraw."""
        root = self._shell.root
        if root is None:
            return
        if self._anim_job is not None:
            try:
                root.after_cancel(self._anim_job)
            except Exception:
                pass
            self._anim_job = None

        arrived = self._last_kind in self.PULSING and kind == "ready"
        self._last_kind = kind

        self._anim_canvas = badge
        self._anim_colour = colour
        self._anim_ground = ground
        self._anim_rings = []
        self._anim_phase = 0.0

        if kind in self.PULSING:
            self._anim_burst = 0          # pulse until told otherwise
            self._tick_animation()
        elif arrived:
            self._anim_burst = 2          # a short greeting, then stillness
            self._tick_animation()

    def _tick_animation(self) -> None:
        """One frame: a ring grows out of the badge and fades as it goes.

        Everything is wrapped against TclError because the canvas being drawn
        on is destroyed by the next render; the frame after that must simply
        stop, not take the drain loop with it.
        """
        root, canvas = self._shell.root, self._anim_canvas
        if root is None or canvas is None:
            return
        try:
            if not canvas.winfo_exists():
                return

            self._anim_phase += 0.04
            if self._anim_phase >= 1.0:
                self._anim_phase = 0.0
                if self._anim_burst > 0:
                    self._anim_burst -= 1
                    if self._anim_burst == 0:
                        for ring in self._anim_rings:
                            canvas.delete(ring)
                        self._anim_rings = []
                        self._anim_job = None
                        return

            for ring in self._anim_rings:
                canvas.delete(ring)

            # Radius grows with the phase; presence fades against the card's
            # ground, because Tk has no real alpha to fade with.
            radius = 12.0 + self._anim_phase * 15.0
            strength = max(0.0, 0.55 * (1.0 - self._anim_phase))
            shade = style.wash(self._anim_colour, self._anim_ground, strength)
            self._anim_rings = [canvas.create_oval(
                28 - radius, 28 - radius, 28 + radius, 28 + radius,
                outline=shade, width=2,
            )]

            self._anim_job = root.after(40, self._tick_animation)
        except Exception:
            self._anim_job = None

    def _render_outgoing(self, snapshot: Snapshot) -> None:
        import tkinter

        pal = self._pal
        card = self._cards["outgoing"]
        card.clear()

        self._title(card.body, "READY TO SEND")

        row = tkinter.Frame(card.body, bg=pal["surface"])
        row.pack(fill="x", pady=(12, 0))

        # A tile even when empty: a card that changes height as files come and
        # go makes everything below it jump.
        tile = tkinter.Canvas(
            row, width=52, height=52, bg=pal["surface"], highlightthickness=0, bd=0
        )
        style.rounded_rect(tile, 0, 0, 52, 52, 12, fill=pal["raised"], outline="")
        tile.pack(side="left")

        text = tkinter.Frame(row, bg=pal["surface"])
        text.pack(side="left", padx=(12, 0), fill="x", expand=True)

        if snapshot.outgoing_name:
            self._label(text, snapshot.outgoing_name, style.FONT_STRONG, pal["ink"])
            meta = style.human_size(snapshot.outgoing_size or 0)
            if snapshot.outgoing_note:
                meta = f"{meta}  ·  {snapshot.outgoing_note}"
            self._label(text, meta, style.FONT_META, pal["muted"])
        else:
            self._label(text, "A picture of your screen", style.FONT_STRONG, pal["ink"])
            self._label(
                text, "Taken at the moment you make a fist",
                style.FONT_META, pal["muted"],
            )

        card.refresh()

    def _render_devices(self, snapshot: Snapshot) -> None:
        import tkinter

        pal = self._pal
        card = self._cards["devices"]
        card.clear()

        self._title(card.body, "DEVICES")

        if not snapshot.devices:
            self._label(
                card.body, "None found yet.", style.FONT_BODY, pal["muted"],
                pady=(12, 0),
            )
            card.refresh()
            return

        for index, device in enumerate(snapshot.devices):
            row = tkinter.Frame(card.body, bg=pal["surface"])
            row.pack(fill="x", pady=(12 if index == 0 else 14, 0))

            text = tkinter.Frame(row, bg=pal["surface"])
            text.pack(side="left", fill="x", expand=True)
            self._label(text, device.name, style.FONT_STRONG, pal["ink"])
            self._label(text, device.host, style.FONT_META, pal["muted"])

            tone = pal["good"] if device.paired else pal["warn"]
            word = "Paired" if device.paired else "Not paired"
            chip = tkinter.Canvas(
                row, width=68, height=22, bg=pal["surface"],
                highlightthickness=0, bd=0,
            )
            style.rounded_rect(
                chip, 0, 0, 68, 22, 11,
                fill=style.wash(tone, pal["surface"], 0.16), outline="",
            )
            chip.create_text(34, 11, text=word, fill=tone, font=style.FONT_CHIP)
            chip.pack(side="right")

            if not device.paired:
                fingerprint = device.fingerprint
                _Pill(
                    card.body, pal, f"Pair with {device.name}",
                    lambda fp=fingerprint: self._actions.pair(fp),
                    card.width - 2 * style.CARD_PAD,
                ).pack(pady=(12, 0), fill="x")

        card.refresh()

    def _render_arrivals(self, snapshot: Snapshot) -> None:
        import tkinter

        pal = self._pal
        card = self._cards["arrivals"]
        card.clear()

        self._title(card.body, "ARRIVED")

        if not snapshot.arrivals:
            self._label(
                card.body, "Nothing yet.", style.FONT_BODY, pal["muted"], pady=(12, 0)
            )
        else:
            for index, arrival in enumerate(snapshot.arrivals[:MAX_ARRIVALS]):
                block = tkinter.Frame(card.body, bg=pal["surface"])
                block.pack(fill="x", pady=(12 if index == 0 else 10, 0))
                self._label(block, arrival.name, style.FONT_STRONG, pal["ink"])
                self._label(
                    block,
                    f"{style.human_size(arrival.size)}  ·  {arrival.when}",
                    style.FONT_META, pal["muted"],
                )

        _Pill(
            card.body, pal, "Open the folder", self._actions.open_folder,
            card.width - 2 * style.CARD_PAD, filled=False,
        ).pack(pady=(14, 0), fill="x")

        card.refresh()
