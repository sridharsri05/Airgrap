"""Screen capture: what actually gets sent when you close your fist."""

from pathlib import Path

from airgrab.capture import ScreenCapture


class FakeImage:
    def __init__(self, fail: bool = False) -> None:
        self.fail = fail
        self.saved_to: Path | None = None

    def save(self, path):
        if self.fail:
            raise OSError("disk full")
        self.saved_to = Path(path)
        Path(path).write_bytes(b"\x89PNG fake")


def test_capture_writes_a_file(tmp_path: Path):
    capture = ScreenCapture(tmp_path, grabber=lambda: FakeImage())
    path = capture.capture()
    assert path is not None
    assert path.exists()
    assert path.suffix == ".png"


def test_two_captures_in_the_same_second_do_not_overwrite(tmp_path: Path):
    frozen = lambda: 1_700_000_000.0  # noqa: E731 - same timestamp both times
    capture = ScreenCapture(tmp_path, grabber=lambda: FakeImage(), clock=frozen)
    first = capture.capture()
    second = capture.capture()
    assert first != second
    assert first.exists() and second.exists()


def test_a_failing_grab_returns_none_rather_than_raising(tmp_path: Path):
    def explode():
        raise RuntimeError("no display")

    assert ScreenCapture(tmp_path, grabber=explode).capture() is None


def test_a_failing_save_returns_none(tmp_path: Path):
    capture = ScreenCapture(tmp_path, grabber=lambda: FakeImage(fail=True))
    assert capture.capture() is None


def test_a_grabber_returning_nothing_is_handled(tmp_path: Path):
    assert ScreenCapture(tmp_path, grabber=lambda: None).capture() is None


def test_old_captures_are_pruned(tmp_path: Path):
    capture = ScreenCapture(tmp_path, grabber=lambda: FakeImage(), keep=3)
    for _ in range(8):
        capture.capture()
    remaining = list(tmp_path.glob("airgrab-capture-*.png"))
    assert len(remaining) <= 3


def test_capture_directory_is_created_on_demand(tmp_path: Path):
    nested = tmp_path / "does" / "not" / "exist"
    capture = ScreenCapture(nested, grabber=lambda: FakeImage())
    assert capture.capture() is not None
    assert nested.is_dir()
