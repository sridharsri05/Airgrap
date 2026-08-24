"""Windows-specific integration: firewall and autostart.

The spec requires that a blocked firewall be detected and fixable in one click
rather than presenting as "no devices found" — a silent empty list is the
single most confusing failure this app can have. Rule creation needs
elevation, so it is attempted once and its failure is reported, never hidden.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

RULE_NAME = "AirGrab"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
_NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0


def _netsh(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["netsh", "advfirewall", "firewall", *args],
        capture_output=True,
        text=True,
        check=False,
        creationflags=_NO_WINDOW,
        timeout=20,
    )


def firewall_rule_exists() -> bool:
    if sys.platform != "win32":
        return True
    try:
        return _netsh("show", "rule", f"name={RULE_NAME}").returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def ensure_firewall_rule(port: int) -> bool:
    """Create the inbound rule. Returns False if it could not be created.

    False almost always means "not running as administrator". The caller is
    expected to surface that to the user rather than swallow it, because the
    symptom otherwise looks like a broken network.
    """
    if sys.platform != "win32":
        return True
    if firewall_rule_exists():
        return True
    try:
        result = _netsh(
            "add", "rule", f"name={RULE_NAME}", "dir=in", "action=allow",
            "protocol=TCP", f"localport={port}",
        )
        return result.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def set_autostart(enabled: bool) -> None:
    if sys.platform != "win32":
        return
    import winreg

    with winreg.OpenKey(
        winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE
    ) as key:
        if enabled:
            target = f'"{Path(sys.executable).resolve()}"'
            winreg.SetValueEx(key, RULE_NAME, 0, winreg.REG_SZ, target)
        else:
            try:
                winreg.DeleteValue(key, RULE_NAME)
            except FileNotFoundError:
                pass


def autostart_enabled() -> bool:
    if sys.platform != "win32":
        return False
    import winreg

    try:
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_READ
        ) as key:
            winreg.QueryValueEx(key, RULE_NAME)
            return True
    except FileNotFoundError:
        return False
    except OSError:
        return False
