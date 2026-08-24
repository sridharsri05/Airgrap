"""Device identity: a self-signed P-256 certificate that never changes.

The certificate's SHA-256 fingerprint IS the device's identity. The common
name is cosmetic and is never trusted for identification.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

CERT_FILENAME = "device-cert.pem"
KEY_FILENAME = "device-key.pem"
CERT_VALIDITY_DAYS = 3650


def fingerprint_of(cert_der: bytes) -> str:
    """Lowercase hex SHA-256 of a DER-encoded certificate."""
    return hashlib.sha256(cert_der).hexdigest()


def verify_signature(cert_der: bytes, data: bytes, signature: bytes) -> bool:
    """Verify an ECDSA-SHA256 signature against a certificate's public key."""
    try:
        cert = x509.load_der_x509_certificate(cert_der)
        cert.public_key().verify(signature, data, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError, TypeError):
        return False
    return True


@dataclass(frozen=True)
class DeviceIdentity:
    display_name: str
    cert_pem: bytes
    key_pem: bytes
    cert_der: bytes
    fingerprint: str
    cert_path: Path
    key_path: Path

    @classmethod
    def load_or_create(cls, directory: Path, display_name: str) -> "DeviceIdentity":
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        _restrict_to_owner(directory)
        cert_path = directory / CERT_FILENAME
        key_path = directory / KEY_FILENAME

        if not (cert_path.exists() and key_path.exists()):
            cert_pem, key_pem = _generate(display_name)
            _write_private(key_path, key_pem)
            cert_path.write_bytes(cert_pem)  # public; no restriction needed

        cert_pem = cert_path.read_bytes()
        key_pem = key_path.read_bytes()
        cert = x509.load_pem_x509_certificate(cert_pem)
        cert_der = cert.public_bytes(serialization.Encoding.DER)

        return cls(
            display_name=display_name,
            cert_pem=cert_pem,
            key_pem=key_pem,
            cert_der=cert_der,
            fingerprint=fingerprint_of(cert_der),
            cert_path=cert_path,
            key_path=key_path,
        )

    def sign(self, data: bytes) -> bytes:
        key = serialization.load_pem_private_key(self.key_pem, password=None)
        return key.sign(data, ec.ECDSA(hashes.SHA256()))


def _write_private(path: Path, data: bytes) -> None:
    """Write secret material readable only by its owner.

    The file is created with restrictive permissions rather than created and
    then tightened, so there is no window in which the private key is
    world-readable.
    """
    fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(data)
    _restrict_to_owner(path)


def _restrict_to_owner(path: Path) -> None:
    """Best-effort owner-only access control.

    POSIX mode bits are advisory on Windows, where access is governed by ACLs
    instead. `icacls` is therefore used to strip inherited permissions and
    grant only the current user. Failure is non-fatal: the data directory
    already sits under the per-user LOCALAPPDATA tree, so this hardens a
    location that is not world-readable to begin with.
    """
    try:
        os.chmod(path, 0o700 if path.is_dir() else 0o600)
    except OSError:
        pass

    if sys.platform != "win32":
        return

    user = os.environ.get("USERNAME")
    if not user:
        return
    try:
        subprocess.run(
            ["icacls", str(path), "/inheritance:r", "/grant:r", f"{user}:(F)"],
            capture_output=True,
            check=False,
            creationflags=0x08000000,  # CREATE_NO_WINDOW
            timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        pass


def _generate(display_name: str) -> tuple[bytes, bytes]:
    key = ec.generate_private_key(ec.SECP256R1())
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, display_name)])
    now = dt.datetime.now(dt.timezone.utc)

    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=CERT_VALIDITY_DAYS))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    key_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    return cert.public_bytes(serialization.Encoding.PEM), key_pem
