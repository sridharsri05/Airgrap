"""Settings window.

The window itself is a thin tkinter shell. Everything that decides anything --
validation, normalisation, persistence, autostart -- lives in widget-free
functions below so it can be tested without a display, which CI does not have.
Nothing in this module imports tkinter at import time; the shell imports it
lazily, exactly as the pairing dialogs in app.py do.
"""

from __future__ import annotations

from pathlib import Path
from typing import Callable

from ..config import Settings, load_settings, save_settings

MIN_PORT = 1024
MAX_PORT = 65535

RESTART_NOTICE = (
    "Changes to the display name or port only take effect "
    "after you restart AirGrab."
)


def settings_to_values(settings: Settings) -> dict:
    """Turn a Settings into the string/bool form the widgets hold."""
    return {
        "display_name": settings.display_name,
        "download_dir": str(settings.download_dir),
        "auto_accept": bool(settings.auto_accept),
        "autostart": bool(settings.autostart),
        "port": str(settings.port),
    }


def validate_settings(values: dict) -> tuple[Settings | None, list[str]]:
    """Validate raw form values.

    Returns (settings, []) when everything is acceptable, or (None, errors)
    with one human-readable message per problem. Every field is checked even
    after the first failure, so the user can fix everything in one pass instead
    of playing whack-a-mole with the dialog.
    """
    errors: list[str] = []

    name = str(values.get("display_name") or "").strip()
    if not name:
        errors.append("Display name cannot be empty.")

    folder = str(values.get("download_dir") or "").strip()
    if not folder:
        errors.append("Download folder cannot be empty.")

    raw_port = str(values.get("port") or "").strip()
    port: int | None = None
    if not raw_port:
        errors.append(f"Port must be a whole number between {MIN_PORT} and {MAX_PORT}.")
    else:
        try:
            port = int(raw_port, 10)
        except ValueError:
            errors.append(
                f"Port must be a whole number between {MIN_PORT} and {MAX_PORT}."
            )
        else:
            if not MIN_PORT <= port <= MAX_PORT:
                errors.append(f"Port must be between {MIN_PORT} and {MAX_PORT}.")

    if errors:
        return None, errors

    assert port is not None
    return (
        Settings(
            display_name=name,
            download_dir=Path(folder),
            auto_accept=bool(values.get("auto_accept", True)),
            autostart=bool(values.get("autostart", False)),
            port=port,
        ),
        [],
    )


def _default_set_autostart(enabled: bool) -> None:
    from .. import windows

    windows.set_autostart(enabled)


def apply_settings(
    path: Path,
    settings: Settings,
    previous: Settings | None = None,
    set_autostart: Callable[[bool], None] | None = None,
) -> list[str]:
    """Persist settings and sync the Windows Run key.

    Returns warnings -- an empty list means everything applied cleanly. The file
    is written first because it is the durable record; a registry write that
    fails (locked-down machine, no permission) must not cost the user the rest
    of their edits, so it is reported rather than raised.
    """
    warnings: list[str] = []
    save_settings(path, settings)

    if previous is None or previous.autostart != settings.autostart:
        setter = set_autostart or _default_set_autostart
        try:
            setter(settings.autostart)
        except Exception as exc:  # registry access is best-effort
            warnings.append(f"Could not change 'start with Windows': {exc}")
    return warnings


class SettingsWindow:
    """Modal tkinter settings dialog. Never raises into the caller."""

    def __init__(self, path: Path, current: Settings | None = None) -> None:
        self.path = Path(path)
        self._previous = current if current is not None else load_settings(self.path)
        self.result: Settings | None = None

    def show(self, parent=None) -> Settings | None:
        """Open the dialog and block until it closes.

        Returns the saved Settings, or None if cancelled or if the UI could not
        be created at all.
        """
        try:
            return self._show(parent)
        except Exception:
            return None

    def _show(self, parent):
        import tkinter
        from tkinter import filedialog, messagebox, ttk

        owned_root = parent is None
        root = tkinter.Tk() if owned_root else tkinter.Toplevel(parent)
        if owned_root:
            root.attributes("-topmost", True)
        root.title("AirGrab Settings")
        root.resizable(False, False)

        values = settings_to_values(self._previous)
        name_var = tkinter.StringVar(root, values["display_name"])
        folder_var = tkinter.StringVar(root, values["download_dir"])
        port_var = tkinter.StringVar(root, values["port"])
        accept_var = tkinter.BooleanVar(root, values["auto_accept"])
        autostart_var = tkinter.BooleanVar(root, values["autostart"])

        frame = ttk.Frame(root, padding=14)
        frame.grid(row=0, column=0, sticky="nsew")
        frame.columnconfigure(1, weight=1)

        ttk.Label(frame, text="Display name").grid(row=0, column=0, sticky="w", pady=4)
        ttk.Entry(frame, textvariable=name_var, width=34).grid(
            row=0, column=1, columnspan=2, sticky="ew", pady=4
        )

        ttk.Label(frame, text="Download folder").grid(
            row=1, column=0, sticky="w", pady=4
        )
        ttk.Entry(frame, textvariable=folder_var, width=26).grid(
            row=1, column=1, sticky="ew", pady=4
        )

        def browse() -> None:
            chosen = filedialog.askdirectory(
                parent=root,
                title="Choose download folder",
                initialdir=folder_var.get() or str(Path.home()),
            )
            if chosen:
                folder_var.set(chosen)

        ttk.Button(frame, text="Browse...", command=browse).grid(
            row=1, column=2, sticky="e", padx=(8, 0), pady=4
        )

        ttk.Label(frame, text="Port").grid(row=2, column=0, sticky="w", pady=4)
        ttk.Entry(frame, textvariable=port_var, width=10).grid(
            row=2, column=1, sticky="w", pady=4
        )

        ttk.Checkbutton(
            frame,
            text="Automatically accept files from paired devices",
            variable=accept_var,
        ).grid(row=3, column=0, columnspan=3, sticky="w", pady=(10, 2))

        ttk.Checkbutton(
            frame, text="Start AirGrab when Windows starts", variable=autostart_var
        ).grid(row=4, column=0, columnspan=3, sticky="w", pady=2)

        ttk.Separator(frame, orient="horizontal").grid(
            row=5, column=0, columnspan=3, sticky="ew", pady=10
        )
        ttk.Label(frame, text=RESTART_NOTICE, wraplength=380, justify="left").grid(
            row=6, column=0, columnspan=3, sticky="w"
        )

        buttons = ttk.Frame(frame)
        buttons.grid(row=7, column=0, columnspan=3, sticky="e", pady=(14, 0))

        def close() -> None:
            try:
                root.grab_release()
            except Exception:
                pass
            root.destroy()

        def save() -> None:
            settings, errors = validate_settings(
                {
                    "display_name": name_var.get(),
                    "download_dir": folder_var.get(),
                    "port": port_var.get(),
                    "auto_accept": accept_var.get(),
                    "autostart": autostart_var.get(),
                }
            )
            if settings is None:
                messagebox.showerror(
                    "Check your settings", "\n".join(errors), parent=root
                )
                return
            try:
                warnings = apply_settings(self.path, settings, self._previous)
            except Exception as exc:
                messagebox.showerror("Could not save settings", str(exc), parent=root)
                return
            if warnings:
                messagebox.showwarning(
                    "Saved with warnings", "\n".join(warnings), parent=root
                )
            self.result = settings
            close()

        ttk.Button(buttons, text="Cancel", command=close).grid(row=0, column=0, padx=6)
        ttk.Button(buttons, text="Save", command=save).grid(row=0, column=1)

        root.protocol("WM_DELETE_WINDOW", close)
        root.bind("<Escape>", lambda _event: close())
        try:
            root.grab_set()  # modal
        except Exception:
            pass
        root.update_idletasks()

        if owned_root:
            root.mainloop()
        else:
            root.wait_window()
        return self.result


def open_settings_window(
    path: Path, current: Settings | None = None, parent=None
) -> Settings | None:
    """Open the modal settings window. Returns the saved Settings, or None."""
    try:
        return SettingsWindow(path, current).show(parent)
    except Exception:
        return None
