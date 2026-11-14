# Implemented Bedrock protocol codecs

Target: Bedrock 1.26.60, protocol 2225.

The Java runtime now contains payload codecs for three packet types, bounded packet-batch framing, a compression envelope, and a frame-level coordinator for the initial server-side login handshake. This still does not implement NetherNet transport, identity verification, Microsoft/Xbox authentication, or a complete client session.

| Packet | ID | Payload implemented | Status |
|---|---:|---|---|
| RequestNetworkSettingsPacket | 193 | Big-endian int32 client protocol version | Payload codec + unit tests |
| NetworkSettingsPacket | 143 | uint16 threshold, uint16 algorithm, boolean, uint8 threshold, float | Payload codec + unit tests |
| LoginPacket | 1 | Big-endian int32 protocol version, varuint32-length-prefixed connection request string | Payload codec + unit tests |

## RequestNetworkSettingsPacket

The payload is exactly four bytes: the client's protocol version as a big-endian signed int32. The codec rejects both truncated and overlong payloads. The decoded packet can be inspected for exact support of protocol 2225 without conflating a well-formed packet with a supported protocol.

## NetworkSettingsPacket

The payload codec (`BedrockNetworkSettingsPacketCodec`, packet ID 143) reads and writes exactly ten bytes using little-endian fixed-width fields:

1. Compression threshold: uint16.
2. Compression algorithm: uint16 (ZLIB=0, SNAPPY=1, NONE=65535).
3. Client throttle enabled: one-byte boolean (any nonzero value reads as true).
4. Client throttle threshold: uint8.
5. Client throttle scalar: 32-bit float.

The protocol 2225 NONE enum value is 65535, not the older value 2.

## LoginPacket

The implemented payload is the big-endian protocol version followed by a varuint32 byte length and UTF-8 connection request bytes. The decoder applies a 1 MiB request limit and rejects malformed UTF-8, truncated input, and trailing bytes. The connection request is opaque data here; this codec does not validate JWTs, signatures, identity claims, or player authentication.

## Uncompressed packet batches

The batch codec implements the raw, uncompressed Bedrock batch body. It does not add a RakNet 0xFE marker. The separate compression envelope handles the post-NetworkSettings algorithm byte, raw DEFLATE, and uncompressed frames; Snappy remains unsupported.

- A batch is a sequence of packets until the batch buffer is exhausted.
- Each packet is prefixed by its byte length as varuint32. The length includes the packet header and payload, but not the length prefix.
- Each packet begins with a varuint32 header. Bits 0-9 are the packet ID, bits 10-11 are sender sub-client ID, and bits 12-13 are target sub-client ID.
- Remaining packet bytes are exposed as an immutable payload view or defensive byte-array copy.
- Decoder limits default to 8 MiB per batch, 2 MiB per packet, and 1024 packets. Limits can be tightened by callers.

The packet header and batch format are cross-checked against the Bedrock protocol documentation ecosystem and the PocketMine BedrockProtocol batch encoder/decoder implementation. These are framing-level implementation references, not proof of interoperability with protocol 2225 clients.

## Initial server-side handshake processor

`BedrockInitialHandshakeProcessor` now joins the packet-batch decoder, compression envelope, packet payload codecs, and `BedrockSessionHandshake`. Given a complete incoming frame, it:

1. `AWAITING_NETWORK_SETTINGS_REQUEST`
2. `NETWORK_SETTINGS_PENDING` after an accepted protocol-2225 request
3. `AWAITING_LOGIN` only after the host confirms that NetworkSettings was handed to the transport
4. `AUTHENTICATION_PENDING` after a matching Login packet
5. `PLAY` only after the host explicitly reports successful authentication
6. `CLOSED` for unsupported protocol, Login protocol mismatch, or explicit close

On a valid initial request it creates a NetworkSettings response frame (still uncompressed, as required before negotiation). The host must actually send that frame and only then call `markNetworkSettingsSent()`. The processor subsequently decodes Login frames using the negotiated compression setting and moves the session to `AUTHENTICATION_PENDING`. It rejects unexpected packet IDs, multi-packet handshake batches, nonzero sub-client routing, malformed frames, unsupported compression, and protocol mismatches. Snappy negotiation is blocked because its codec is not implemented. The processor does not open a transport, deliver the response, verify JWTs or signatures, contact Microsoft/Xbox services, or authenticate a player. The host must not call `markAuthenticated()` until those checks have genuinely succeeded.

## Verification boundary

JUnit tests have been authored for the codecs. They must be run by Maven or CI before being described as passing. These tests validate serialization and bounds only. They do not establish interoperability with an unmodified Bedrock client.

## Compression envelope
