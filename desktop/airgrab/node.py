"""One AirGrab device: server and client in a single object.

Both sides of every exchange live here because the protocol is symmetric —
a device that can only receive is half an implementation, and keeping the two
halves adjacent is what stops them drifting apart.

The control channel is a WebSocket at /control; file bytes travel separately
to /transfer/{ticket} on the same TLS port. Small coordination messages
therefore cannot be stalled behind a multi-gigabyte upload.
"""

from __future__ import annotations

import asyncio
import contextlib
import ssl
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import aiohttp
from aiohttp import web

from airgrab import auth
from airgrab.identity import DeviceIdentity
from airgrab.protocol import (
    PROTOCOL_VERSION,
    Envelope,
    ErrorCode,
    MessageType,
    ProtocolError,
    compute_sas,
    decode,
    encode,
)
from airgrab.transfer import (
    TicketError,
    TicketStore,
    TransferError,
    hash_file,
    receive_to_file,
    unique_destination,
)
from airgrab.trust import TrustStore

PLATFORM = "windows"
PROGRESS_INTERVAL_SECONDS = 0.25
UPLOAD_CHUNK = 256 * 1024


@dataclass
class NodeConfig:
    data_dir: Path
    download_dir: Path
    display_name: str
    port: int = 53421
    auto_accept: bool = True
    trust_filename: str = "trust.json"


@dataclass
class _Session:
    """Per-connection state on the server side."""

    peer_fp: str = ""
    peer_name: str = "Unknown"
    peer_platform: str = "unknown"
    nonce: str = ""
    authenticated: bool = False
    pair_accepted: bool = False
    seq: int = field(default=0)

    def next_seq(self) -> int:
        self.seq += 1
        return self.seq


class Node:
    def __init__(self, config: NodeConfig) -> None:
        self.config = config
        self.identity = DeviceIdentity.load_or_create(
            config.data_dir, config.display_name
        )
        self.trust = TrustStore(config.data_dir / config.trust_filename)
        self.tickets = TicketStore()
        self.on_incoming_file: Callable[[Path], None] | None = None
        self.on_pair_request: Callable[[str, str], bool] | None = None

        # Per-instance, never class-level: two Nodes in one process (the
        # loopback tests, and any future multi-peer support) must not share
        # in-flight transfer state.
        self._pending_names: dict[str, str] = {}
        self._pending_sockets: dict[str, tuple] = {}

        self._runner: web.AppRunner | None = None
        self._site: web.TCPSite | None = None
        self._port = config.port

    @property
    def port(self) -> int:
        return self._port

    # ---------------------------------------------------------------- server

    async def start(self) -> None:
        app = web.Application(client_max_size=0)  # 0 = no upload size limit
        app.router.add_get("/control", self._handle_control)
        app.router.add_post("/transfer/{ticket}", self._handle_upload)

        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_context.load_cert_chain(
            certfile=str(self.identity.cert_path), keyfile=str(self.identity.key_path)
        )

        self._runner = web.AppRunner(app)
        await self._runner.setup()
        self._site = web.TCPSite(
            self._runner, "0.0.0.0", self.config.port, ssl_context=ssl_context
        )
        await self._site.start()
        self._port = self._resolve_port()

    def _resolve_port(self) -> int:
        for address in self._runner.addresses if self._runner else []:
            if isinstance(address, tuple) and len(address) >= 2:
                return int(address[1])
        return self.config.port

    async def stop(self) -> None:
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
            self._site = None

    async def _handle_control(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=15)
        await ws.prepare(request)
        session = _Session()

        async for message in ws:
            if message.type is not aiohttp.WSMsgType.TEXT:
                continue
            try:
                envelope = decode(message.data)
            except ProtocolError:
                continue  # unparseable messages are ignored, per PROTOCOL.md
            try:
                await self._dispatch(ws, session, envelope)
            except Exception:
                with contextlib.suppress(Exception):
                    await self._send(
                        ws, session, MessageType.ERROR,
                        {"code": ErrorCode.INTERNAL, "message": "server error"},
                    )
                break

        return ws

    async def _send(self, ws, session: _Session, type: str, payload: dict) -> None:
        await ws.send_str(encode(type, session.next_seq(), payload))

    async def _dispatch(self, ws, session: _Session, env: Envelope) -> None:
        payload = env.payload

        if env.type == MessageType.HELLO:
            if payload.get("v") != PROTOCOL_VERSION:
                await self._send(
                    ws, session, MessageType.ERROR,
                    {"code": ErrorCode.VERSION_MISMATCH, "message": "protocol version"},
                )
                await ws.close()
                return

            session.peer_fp = str(payload.get("id", ""))
            session.peer_name = str(payload.get("name", "Unknown"))
            session.peer_platform = str(payload.get("plat", "unknown"))
            session.nonce = auth.new_nonce()
            client_nonce = str(payload.get("nonce", ""))

            # Prove possession of our own key over the client's nonce, so the
            # client can pin us. Without this the client has no way to tell us
            # apart from a machine-in-the-middle.
            server_sig = self.identity.sign(
                auth.signing_payload(
                    client_nonce, self.identity.fingerprint, session.peer_fp
                )
            )
            await self._send(ws, session, MessageType.HELLO_ACK, {
                "v": PROTOCOL_VERSION,
                "id": self.identity.fingerprint,
                "name": self.identity.display_name,
                "plat": PLATFORM,
                "trusted": self.trust.is_trusted(session.peer_fp),
                "cert": self.identity.cert_der.hex(),
                "sig": server_sig.hex(),
            })
            await self._send(
                ws, session, MessageType.AUTH_CHALLENGE, {"nonce": session.nonce}
            )
            return

        if env.type == MessageType.AUTH_RESPONSE:
            try:
                cert_der = bytes.fromhex(str(payload.get("cert", "")))
                signature = bytes.fromhex(str(payload.get("signature", "")))
            except ValueError:
                cert_der, signature = b"", b""

            result = auth.verify_auth_response(
                cert_der=cert_der,
                signature=signature,
                nonce=session.nonce,
                server_fp=self.identity.fingerprint,
                claimed_client_fp=session.peer_fp,
            )
            session.authenticated = result.ok
            if not result.ok:
                await self._send(
                    ws, session, MessageType.ERROR,
                    {"code": ErrorCode.AUTH_FAILED, "message": result.reason},
                )
                await ws.close()
            return

        if not session.authenticated:
            await self._send(
                ws, session, MessageType.ERROR,
                {"code": ErrorCode.AUTH_FAILED, "message": "not authenticated"},
            )
            await ws.close()
            return

        if env.type == MessageType.PAIR_REQUEST:
            sas = compute_sas(self.identity.fingerprint, session.peer_fp)
            accepted = True
            if self.on_pair_request is not None:
                accepted = bool(self.on_pair_request(sas, session.peer_name))
            session.pair_accepted = accepted
            await self._send(ws, session, MessageType.PAIR_CHALLENGE, {"sas": sas})
            return

        if env.type == MessageType.PAIR_CONFIRM:
            accepted = bool(payload.get("accepted")) and session.pair_accepted
            if accepted:
                self.trust.add(session.peer_fp, session.peer_name, session.peer_platform)
            await self._send(ws, session, MessageType.PAIR_RESULT, {"accepted": accepted})
            return

        if env.type == MessageType.OFFER:
            if not self.trust.is_trusted(session.peer_fp):
                await self._send(
                    ws, session, MessageType.OFFER_REJECT,
                    {"reason": ErrorCode.NOT_TRUSTED},
                )
                return
            if not self.config.auto_accept:
                await self._send(
                    ws, session, MessageType.OFFER_REJECT,
                    {"reason": ErrorCode.TRANSFER_ABORTED},
                )
                return

            ticket = self.tickets.issue(
                session.peer_fp,
                str(payload.get("sha256", "")),
                int(payload.get("size", 0)),
            )
            self._pending_names[ticket] = str(payload.get("name", "received"))
            self._pending_sockets[ticket] = (ws, session)
            await self._send(ws, session, MessageType.OFFER_ACCEPT, {"ticket": ticket})
            return

        if env.type == MessageType.PING:
            await self._send(ws, session, MessageType.PONG, {})
            return

        # Unknown types are ignored so v1 and a future v2 can coexist.

    async def _handle_upload(self, request: web.Request) -> web.Response:
        ticket_value = request.match_info["ticket"]
        entry = self._pending_sockets.get(ticket_value)
        if entry is None:
            return web.json_response({"code": ErrorCode.TICKET_INVALID}, status=403)
        ws, session = entry

        try:
            ticket = self.tickets.redeem(ticket_value, session.peer_fp)
        except TicketError as exc:
            self._cleanup_ticket(ticket_value)
            return web.json_response({"code": exc.code}, status=403)

        filename = self._pending_names.get(ticket_value, "received")
        destination = unique_destination(self.config.download_dir, filename)

        last_report = 0.0
        scheduled: list[asyncio.Task] = []

        def report(received: int) -> None:
            nonlocal last_report
            now = time.monotonic()
            if now - last_report < PROGRESS_INTERVAL_SECONDS:
                return
            last_report = now
            scheduled.append(asyncio.create_task(
                self._send(ws, session, MessageType.PROGRESS,
                           {"received": received, "total": ticket.size})
            ))

        async def stream():
            while True:
                chunk = await request.content.readany()
                if not chunk:
                    break
                yield chunk

        try:
            path = await receive_to_file(
                stream(), destination, ticket.expected_sha256, report
            )
        except TransferError as exc:
            self._cleanup_ticket(ticket_value)
            with contextlib.suppress(Exception):
                await self._send(ws, session, MessageType.COMPLETE,
                                 {"ok": False, "code": exc.code})
            return web.json_response({"code": exc.code}, status=400)

        self._cleanup_ticket(ticket_value)

        # Drain throttled progress sends, then emit a final 100% report before
        # COMPLETE. Without this the last update can race the completion
        # message and the progress bar visibly stops short of the end.
        for task in scheduled:
            with contextlib.suppress(Exception):
                await task
        with contextlib.suppress(Exception):
            await self._send(ws, session, MessageType.PROGRESS,
                             {"received": ticket.size, "total": ticket.size})
            await self._send(ws, session, MessageType.COMPLETE,
                             {"ok": True, "path": str(path)})

        if self.on_incoming_file is not None:
            self.on_incoming_file(path)
        return web.json_response({"ok": True})

    def _cleanup_ticket(self, ticket_value: str) -> None:
        self.tickets.discard(ticket_value)
        self._pending_names.pop(ticket_value, None)
        self._pending_sockets.pop(ticket_value, None)

    # ---------------------------------------------------------------- client

    def _client_ssl(self) -> ssl.SSLContext:
        """TLS for confidentiality only.

        Certificate validation is deliberately disabled here because peers use
        self-signed certificates that no CA can vouch for. Identity is NOT
        skipped — it is established by the signed-nonce exchange below, which
        pins the peer's certificate fingerprint against the trust store.
        """
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        return context

    @contextlib.asynccontextmanager
    async def _connect(self, host: str, port: int, expect_fp: str | None = None):
        connector = aiohttp.TCPConnector(ssl=self._client_ssl())
        async with aiohttp.ClientSession(connector=connector) as http:
            async with http.ws_connect(f"https://{host}:{port}/control") as ws:
                seq = {"n": 0}

                async def send(type: str, payload: dict) -> None:
                    seq["n"] += 1
                    await ws.send_str(encode(type, seq["n"], payload))

                async def recv(*expected: str) -> Envelope:
                    async for message in ws:
                        if message.type is not aiohttp.WSMsgType.TEXT:
                            continue
                        env = decode(message.data)
                        if not expected or env.type in expected or env.type == MessageType.ERROR:
                            return env
                    raise ConnectionError("control channel closed")

                client_nonce = auth.new_nonce()
                await send(MessageType.HELLO, {
                    "v": PROTOCOL_VERSION,
                    "id": self.identity.fingerprint,
                    "name": self.identity.display_name,
                    "plat": PLATFORM,
                    "nonce": client_nonce,
                })
                ack = await recv(MessageType.HELLO_ACK)
                if ack.type == MessageType.ERROR:
                    raise ConnectionError(str(ack.payload.get("code", "error")))

                # Authenticate the SERVER before revealing anything further.
                # peer_fp comes from the presented certificate, never from
                # ack.payload["id"] — that field is attacker-controlled.
                #
                # The pin comes from the CALLER's intent, never from what the
                # peer claims about itself. Deriving it from ack.payload["id"]
                # would let an attacker skip the check simply by claiming a
                # fingerprint that is not in the trust store.
                pinned_fp = expect_fp
                try:
                    cert_der = bytes.fromhex(str(ack.payload.get("cert", "")))
                    server_sig = bytes.fromhex(str(ack.payload.get("sig", "")))
                except ValueError:
                    cert_der, server_sig = b"", b""

                server_result, peer_fp = auth.verify_server_identity(
                    cert_der=cert_der,
                    signature=server_sig,
                    nonce=client_nonce,
                    client_fp=self.identity.fingerprint,
                    pinned_fp=pinned_fp,
                )
                if not server_result.ok:
                    raise ConnectionError(
                        f"server authentication failed: {server_result.reason}"
                    )

                challenge = await recv(MessageType.AUTH_CHALLENGE)
                if challenge.type == MessageType.ERROR:
                    raise ConnectionError(str(challenge.payload.get("code", "error")))

                nonce = str(challenge.payload.get("nonce", ""))
                signature = self.identity.sign(
                    auth.signing_payload(nonce, peer_fp, self.identity.fingerprint)
                )
                await send(MessageType.AUTH_RESPONSE, {
                    "cert": self.identity.cert_der.hex(),
                    "signature": signature.hex(),
                })

                yield http, ws, send, recv, peer_fp, ack.payload

    async def pair_with(
        self, host: str, port: int, on_sas: Callable[[str], bool]
    ) -> bool:
        async with self._connect(host, port) as (_http, _ws, send, recv, peer_fp, ack):
            await send(MessageType.PAIR_REQUEST, {})
            challenge = await recv(MessageType.PAIR_CHALLENGE)
            if challenge.type != MessageType.PAIR_CHALLENGE:
                return False

            sas = str(challenge.payload.get("sas", ""))
            # Recompute locally from the fingerprint we derived from the
            # certificate. A mismatch means the peer computed it against a
            # different identity than the one it proved it holds.
            if sas != compute_sas(self.identity.fingerprint, peer_fp):
                return False

            accepted = bool(on_sas(sas))
            await send(MessageType.PAIR_CONFIRM, {"accepted": accepted})
            result = await recv(MessageType.PAIR_RESULT)
            granted = accepted and bool(result.payload.get("accepted"))

            if granted:
                self.trust.add(
                    peer_fp,
                    str(ack.get("name", "Unknown")),
                    str(ack.get("plat", "unknown")),
                )
            return granted

    async def send_file(
        self,
        host: str,
        port: int,
        path: Path,
        on_progress: Callable[[int, int], None] | None = None,
        expect_fp: str | None = None,
    ) -> bool:
        """Send one file. Returns True only if the peer confirmed receipt.

        `expect_fp` names the device the caller intends to reach. When given, a
        peer presenting any other certificate raises ConnectionError rather
        than returning False: something is impersonating a device you named,
        which is an alarm, not a routine refusal.
        """
        path = Path(path)
        size = path.stat().st_size
        digest = hash_file(path)

        async with self._connect(host, port, expect_fp=expect_fp) as (
            http, _ws, send, recv, peer_fp, _ack,
        ):
            # Never hand a file to a device we have not paired with. The
            # fingerprint checked here is the one derived from the peer's
            # certificate, so an impostor cannot satisfy it.
            if not self.trust.is_trusted(peer_fp):
                return False

            await send(MessageType.OFFER, {
                "name": path.name,
                "size": size,
                "mime": "application/octet-stream",
                "sha256": digest,
            })
            reply = await recv(MessageType.OFFER_ACCEPT, MessageType.OFFER_REJECT)
            if reply.type != MessageType.OFFER_ACCEPT:
                return False
            ticket = str(reply.payload.get("ticket", ""))

            async def body():
                with open(path, "rb") as handle:
                    while True:
                        chunk = handle.read(UPLOAD_CHUNK)
                        if not chunk:
                            break
                        yield chunk

            upload = await http.post(
                f"https://{host}:{port}/transfer/{ticket}", data=body()
            )
            if upload.status != 200:
                return False

            while True:
                env = await recv()
                if env.type == MessageType.PROGRESS and on_progress is not None:
                    on_progress(
                        int(env.payload.get("received", 0)),
                        int(env.payload.get("total", size)),
                    )
                elif env.type == MessageType.COMPLETE:
                    return bool(env.payload.get("ok"))
                elif env.type == MessageType.ERROR:
                    return False
