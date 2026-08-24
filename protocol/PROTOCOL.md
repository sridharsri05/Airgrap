# AirGrab Protocol v1

Authoritative definition. Both the Python and Kotlin implementations obey
this document. Where code and this document disagree, this document wins.

## Envelope

Every control-channel message is a UTF-8 JSON object:

    { "type": "<string>", "seq": <int>, "payload": { ... } }

`seq` is a monotonic counter, per connection, per direction, starting at 1.

Unknown `type` values MUST be ignored, not treated as errors. This is what
allows v1 and a future v2 to coexist during a rollout.

## Message types

hello, hello_ack, auth_challenge, auth_response, pair_request,
pair_challenge, pair_confirm, pair_result, offer, offer_accept,
offer_reject, progress, complete, cancel, error, ping, pong

## Error codes

version_mismatch, not_trusted, auth_failed, ticket_invalid, ticket_expired,
hash_mismatch, storage_full, storage_denied, transfer_aborted, internal

## Device ID

Lowercase hex SHA-256 of the DER-encoded device certificate.

## SAS (pairing code)

    joined  = concat(sort([fingerprint_a, fingerprint_b]))   # ASCII sort
    digest  = SHA-256(joined UTF-8 bytes)
    value   = int.from_bytes(digest[0:4], "big") mod 1000000
    sas     = value rendered as 6 decimal digits, zero-padded

Sorting makes the result independent of who initiated. Deriving it from both
fingerprints is what makes it detect a machine-in-the-middle: an attacker
holding a different certificate to each side produces a different code on
each screen.

## Authentication

TLS provides confidentiality only; certificates are not validated by a CA.
Identity is proven at the application layer, **in both directions**.

    signing_payload(nonce, server_fp, client_fp) =
        SHA-256( "airgrab-auth-v1" || nonce_hex || server_fp || client_fp )

all parts UTF-8 encoded and concatenated in that order.

Exchange:

1. Client sends `hello` with `{ v, id, name, plat, nonce }` where `nonce` is
   32 random bytes hex-encoded. `id` is the fingerprint the client claims.
2. Server replies `hello_ack` with `{ v, id, name, plat, trusted, cert, sig }`
   where `cert` is the server's DER certificate hex-encoded and `sig` is the
   server's signature over `signing_payload(client_nonce, server_fp,
   client_claimed_fp)`, with `server_fp` derived from the server's own
   certificate.
3. **The client derives the server's fingerprint from the presented
   certificate — never from any field in the message** — and verifies `sig`
   against that certificate. It then either:
   - **pins**: if the peer is already in the trust store, the derived
     fingerprint MUST equal the stored one, or the connection is aborted; or
   - **pairs**: the derived fingerprint is what feeds the SAS computation.
4. Server sends `auth_challenge` with `{ nonce }`.
5. Client replies `auth_response` with `{ cert, signature }`, signing
   `signing_payload(server_nonce, derived_server_fp, own_fp)`.
6. Server verifies the signature against the presented certificate and
   checks that certificate's fingerprint equals the `id` claimed in `hello`.

**Why every part of this is load-bearing.** An earlier draft had the server
simply *state* its fingerprint in a message and had only the client
authenticate. That is not secure: a machine-in-the-middle could claim the real
server's fingerprint, forward the client's signature to the real server, and
sit undetected on both connections. Deriving the peer fingerprint from the
presented certificate — combined with pinning for known peers and SAS
comparison for new ones — is what closes that hole. A machine-in-the-middle
must present its own certificate, which produces either a pin mismatch or two
different six-digit codes on the two screens.

## Transfer

`offer` carries `{ name, size, mime, sha256 }`. The receiver replies
`offer_accept` with `{ ticket }` or `offer_reject` with `{ reason }`.

The sender then streams the raw file bytes to
`POST https://<host>:<port>/transfer/<ticket>`.

Tickets are 256 bits of cryptographic randomness, single-use, bound to one
peer fingerprint, and valid for 60 seconds measured on a monotonic clock.

`progress` is emitted by the **receiver**, at most every 250 ms, counting
bytes written to disk rather than bytes handed to the network stack.

The receiver writes to a temporary file and renames it into place only after
the SHA-256 matches the offer. A partial or corrupt transfer is therefore
never observable under the real filename.
