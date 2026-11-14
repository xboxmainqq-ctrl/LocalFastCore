# Implemented Bedrock payload codecs

Target: Bedrock 1.26.60, protocol 2225.

This document records payload codecs present in the Java runtime. A payload codec does not implement the outer packet batch, compression envelope, NetherNet connection, authentication, or a complete client session.

| Packet | ID | Payload implemented | Status |
|---|---:|---|---|
| RequestNetworkSettingsPacket | 193 | Big-endian int32 client protocol version | Payload codec + unit tests |
| NetworkSettingsPacket | 143 | uint16 threshold, uint16 algorithm, boolean, uint8 threshold, float | Payload codec + unit tests |
| LoginPacket | 1 | Big-endian int32 protocol version, varuint32-length-prefixed connection request string | Payload codec + unit tests |

## RequestNetworkSettingsPacket

The payload is exactly four bytes: the client's protocol version as a big-endian signed int32. The codec rejects both truncated and overlong payloads. The decoded packet can be inspected for exact support of protocol 2225 without conflating a well-formed packet with a supported protocol.

## NetworkSettingsPacket

The payload is exactly ten bytes and uses little-endian fixed-width fields:

1. Compression threshold: uint16.
2. Compression algorithm: uint16 (ZLIB=0, SNAPPY=1, NONE=65535).
3. Client throttle enabled: one-byte boolean (any nonzero value reads as true).
4. Client throttle threshold: uint8.
5. Client throttle scalar: 32-bit float.

The protocol 2225 NONE enum value is 65535, not the older value 2.

## LoginPacket

The implemented payload is the big-endian protocol version followed by a varuint32 byte length and UTF-8 connection request bytes. The decoder applies a 1 MiB request limit and rejects malformed UTF-8, truncated input, and trailing bytes. The connection request is opaque data here; this codec does not validate JWTs, signatures, identity claims, or player authentication.

## Verification boundary

JUnit tests have been authored for the codecs. They must be run by Maven or CI before being described as passing. These tests validate payload serialization only. They do not establish interoperability with an unmodified Bedrock client.

Before the next networking layer is implemented, packet-batch framing and compression framing must be verified independently against the protocol reference and a trusted implementation. Do not infer these envelopes from the payload schemas.
