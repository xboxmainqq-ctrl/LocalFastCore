# Implemented Bedrock protocol codecs

Target: Bedrock 1.26.60, protocol 2225.

The Java runtime contains payload codecs for three packet types, bounded packet-batch framing, a compression envelope, a frame-level coordinator for the initial server-side login handshake, and transport-independent NetherNet message framing and a bounded client identity verification chain. Client and operator NetherNet identity assertions now have bounded parsing and signature-verification components. The runtime now has bounded HTTP/HTTPS signaling and a desktop Java WebRTC peer-host adapter. The Android client now creates both data channels, gathers full ICE, performs HTTP(S) signaling, verifies the operator assertion, waits for both channels to open, and sends the initial Bedrock RequestNetworkSettings packet. It still does not acquire Microsoft/Xbox credentials or complete the Bedrock Login flow, and no live device interoperability has been confirmed.

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

The implemented payload is the big-endian protocol version followed by a varuint32 byte length and UTF-8 connection request bytes. The decoder applies a 1 MiB request limit and rejects malformed UTF-8, truncated input, and trailing bytes. `BedrockConnectionRequest` structurally extracts either the legacy `chain`/`clientData` fields or the newer `AuthenticationType`/`Token`/`Certificate` envelope. It does not validate JWT signatures, issuers, Xbox claims, certificate chains, or player authentication.

## Uncompressed packet batches

The batch codec implements the raw, uncompressed Bedrock batch body. It does not add a RakNet 0xFE marker. The separate compression envelope handles the post-NetworkSettings algorithm byte, raw DEFLATE, and uncompressed frames; Snappy remains unsupported.

- A batch is a sequence of packets until the batch buffer is exhausted.
- Each packet is prefixed by its byte length as varuint32. The length includes the packet header and payload, but not the length prefix.
- Each packet begins with a varuint32 header. Bits 0-9 are the packet ID, bits 10-11 are sender sub-client ID, and bits 12-13 are target sub-client ID.
- Remaining packet bytes are exposed as an immutable payload view or defensive byte-array copy.
- Decoder limits default to 8 MiB per batch, 2 MiB per packet, and 1024 packets. Limits can be tightened by callers.

The packet header and batch format are cross-checked against the Bedrock protocol documentation ecosystem and the PocketMine BedrockProtocol batch encoder/decoder implementation. These are framing-level implementation references, not proof of interoperability with protocol 2225 clients.

## Initial server-side handshake processor

`BedrockInitialHandshakeProcessor` joins the packet-batch decoder, compression envelope, packet payload codecs, and `BedrockSessionHandshake`. Given a complete incoming frame, it accepts the initial request only in `AWAITING_NETWORK_SETTINGS_REQUEST`, transitions to `NETWORK_SETTINGS_PENDING`, and waits for the host to confirm delivery before entering `AWAITING_LOGIN`. A matching Login packet moves the session to `AUTHENTICATION_PENDING`; the host may move it to `PLAY` only after successful authentication. Unsupported protocol, malformed or out-of-order packets, and explicit close lead to `CLOSED`.

On a valid initial request it creates a NetworkSettings response frame (still uncompressed, as required before negotiation). The host must actually send that frame and only then call `markNetworkSettingsSent()`. The processor subsequently decodes Login frames using the negotiated compression setting and moves the session to `AUTHENTICATION_PENDING`. It rejects unexpected packet IDs, multi-packet handshake batches, nonzero sub-client routing, malformed frames, unsupported compression, and protocol mismatches. Snappy negotiation is blocked because its codec is not implemented. The processor does not open a transport, deliver the response, verify JWTs or signatures, contact Microsoft/Xbox services, or authenticate a player. The host must not call `markAuthenticated()` until those checks have genuinely succeeded. `BedrockInitialTransportRouter` supplies the transport integration: it installs per-connection listeners, accepts handshake frames only from the reliable ordered channel, sends the generated NetworkSettings frame, and advances the processor only after the transport accepts that send. It passes the decoded `BedrockLoginPacket` to an asynchronous authenticator. Only a successful authentication result moves the session to PLAY and enables the play-message handler; the router itself does not authenticate. `NetherNetBedrockModule` composes the peer host, HTTP signaling endpoint, and router into a single lifecycle-managed module that can be registered with `NexoraServer`.

## NetherNet data-message framing

`NetherNetMessageFraming` implements the one-byte data-channel message envelope independently of any WebRTC library:

- `fragmentReliable` splits an application payload into bounded frames. The configured maximum frame size includes the one-byte header.
- Fragment headers count down: the first frame carries the number of fragments remaining, and the final frame carries zero.
- The encoder enforces the configured fragment cap and the protocol's one-byte header limit of 255 fragments.
- `decodeUnreliable` strips the header only when it is zero, rejects fragmented unreliable messages, and enforces a caller-provided frame-size limit.
- The existing `NetherNetFragmentAssembler` reassembles reliable fragments and applies message-size, fragment-count, and timeout limits.

These helpers do not create data channels, perform SDP negotiation, gather ICE candidates, or authenticate peers. The transport adapter must use one assembler per reliable channel direction, deliver unreliable messages without reassembly, and apply bounded outbound queues. See the [Mojang NetherNet onboarding guide](https://github.com/Mojang/bedrock-protocol-docs/blob/main/additional_docs/NetherNetOnboardingGuide.md) for the signaling and WebRTC requirements.


## NetherNet SDP identity extraction

`NetherNetSdpDocument` is a bounded SDP pre-parser for incoming NetherNet offers:

- Enforces configurable UTF-8 SDP and decoded identity-envelope byte limits.
- Requires exactly one session-level `a=identity` line, placed after session fingerprint attributes and before the first media section.
- Base64-decodes the identity envelope using strict UTF-8 decoding and preserves the original SDP, encoded identity, decoded envelope text, and fingerprint lines.
- Produces a separate WebRTC SDP string with only the `a=identity` line removed. The original SDP must be retained for signature verification; the cleaned SDP is only for the WebRTC library.
- Rejects missing, duplicate, misplaced, malformed, or oversized identity attributes.

This parser is the first step only. The following identity components now build on it:

- `NetherNetIdentityAssertion` parses the nested JSON, requires the current GameServerToken RS256 header with `kid`, the franchise authorization issuer and multiplayer audience, a Base64 DER SubjectPublicKeyInfo `cpk`, and the detached ES384 JWS envelope. It applies strict duplicate-key detection and bounded JSON/token sizes.
- `NetherNetJwksKeyCache` fetches RSA signing keys over HTTPS from the franchise authorization service's JWKS endpoint, caps response size, validates RSA key sizes, caches keys, and rate-limits refreshes for unknown key IDs.
- `NetherNetGameServerTokenVerifier` verifies RS256, re-checks that the parsed claims exactly match the signed JWT, enforces `exp`/`nbf` with bounded clock skew, and decodes the P-384 `cpk` public key.
- `NetherNetFingerprintPayload` reconstructs the canonical JSON payload from SDP fingerprint attributes.
- `NetherNetFingerprintJwsVerifier` verifies the detached ES384 signature with the already-authenticated `cpk`.
- `NetherNetIdentityValidator` composes both signature checks. `NetherNetAuthenticatedOfferValidator` invokes the server's admission policy only after identity verification succeeds.

The authentication service's signing-key URL, RS256 algorithm, issuer, audience, and the fact that `cpk` is a Base64 DER public-key blob are aligned with the current MiNET validation implementation. That implementation is a cross-check, not a substitute for Mojang's protocol guide. See the [Mojang NetherNet onboarding guide](https://mojang.github.io/bedrock-protocol-docs/guides/nether-net-onboarding-guide/) and [MiNET's current GameServerToken validator](https://github.com/NiclasOlofsson/MiNET/blob/master/src/MiNET/MiNET/Utils/Cryptography/FranchiseTokenValidator.cs).

These are source implementations and authored tests; they are not yet a claim of successful CI, real Xbox authentication, or interoperability with an unmodified Bedrock client.


## NetherNet identity assertion structure

`NetherNetIdentityAssertion` parses the decoded `a=identity` envelope and nested assertion JSON with Jackson Core strict duplicate-key detection and bounded nesting/string/number sizes. It requires:

- `idp.domain` to be non-empty and bounded, and `idp.protocol` to equal `default`.
- The client `GameServerToken` to use compact JWT form with RS256 and a `kid`, the franchise authorization issuer, multiplayer audience, expiry, and a Base64 DER SubjectPublicKeyInfo `cpk`.
- The fingerprint proof to use detached compact JWS form `base64url(header)..base64url(signature)` with ES384.

The client identity verification chain is implemented in these components:

- `NetherNetJwksKeyCache` fetches RSA signing keys over HTTPS from the franchise authorization service's JWKS endpoint, caps response size, validates RSA key sizes, caches keys, and rate-limits refreshes for unknown key IDs.
- `NetherNetGameServerTokenVerifier` verifies RS256, re-checks that parsed identity fields exactly match the signed JWT, enforces `exp`/`nbf` with bounded clock skew, and decodes the P-384 `cpk` public key.
- `NetherNetFingerprintPayload` reconstructs canonical JSON from SDP fingerprint attributes.
- `NetherNetFingerprintJwsVerifier` verifies the detached ES384 signature with the authenticated `cpk`.
- `NetherNetIdentityValidator` composes both signature checks. `NetherNetAuthenticatedOfferValidator` invokes the server admission policy only after identity verification succeeds.

The server side is also represented:

- `NetherNetServerIdentitySigner` creates the operator JWT and detached ES384 fingerprint JWS for SDP answers, embeds the P-384 public key as a public JWK, and inserts `a=identity` at session level. The private key is supplied by the host and is never written to the repository.
- `NetherNetServerIdentityVerifier` validates the operator JWT/JWS and calculates the RFC 7638 SHA-256 thumbprint of the public JWK. It returns one of three trust states: TLS-authenticated, already pinned, or first-use confirmation required. It never silently stores a new pin; explicit user approval belongs in the Android UI.

The authorization-service endpoint, RS256 algorithm, issuer, audience, and client `cpk` SPKI representation are cross-checked against [MiNET's current GameServerToken validator](https://github.com/NiclasOlofsson/MiNET/blob/master/src/MiNET/MiNET/Utils/Cryptography/FranchiseTokenValidator.cs). This is an implementation reference, not a substitute for the [Mojang NetherNet onboarding guide](https://mojang.github.io/bedrock-protocol-docs/guides/nether-net-onboarding-guide/).

## NetherNet HTTP signaling

`NetherNetSignalingServer` exposes the documented HTTP routes:

- `GET /v1/join` returns bounded server metadata.
- `POST /v1/join/{networkId}` accepts an `application/sdp` offer with a strict UTF-8 and byte limit.
- The configured `NetherNetOfferValidator` runs before the peer-host adapter allocates a WebRTC peer. The authenticated adapter can be built with `NetherNetAuthenticatedOfferValidator`.
- The peer host receives the SDP with the client-only `a=identity` attribute removed.
- The returned SDP answer is signed with the configured operator P-384 key using `NetherNetServerIdentitySigner`; the response is checked for a valid session-level identity attribute before being returned.
- HTTP worker count, queue depth, request size, and answer timeout are bounded.

The listener can bind plain HTTP or HTTPS when the host supplies an `SSLContext`; certificate loading and lifecycle remain the host application's responsibility. Plain HTTP clients must complete the TOFU confirmation returned by `NetherNetServerIdentityVerifier`.

Authenticated deployments must use `NetherNetAuthenticatedOfferValidator`. For local transport development only, `NetherNetDevelopmentOfferValidator(true)` explicitly permits identity-free offers so the Android client can exercise WebRTC before Microsoft/Xbox GameServerToken acquisition exists. This mode provides no player authentication or authorization and must not be exposed on a public or production server.

## WebRTC peer host and data channels

`WebRtcJavaNetherNetPeerHost` is the first native WebRTC adapter. It uses `dev.onvoid.webrtc:webrtc-java:1.0.0` and implements both `NetherNetPeerHost` and `NetworkTransport`.

- ICE configuration matches the NetherNet profile: no STUN/TURN servers, TCP candidates disabled, `MAX_BUNDLE`, and `GATHER_ONCE` (full ICE).
- The host receives the client's in-band `ReliableDataChannel` and `UnreliableDataChannel` through `onDataChannel` and validates their ordered/retransmission settings.
- Reliable frames are reassembled with the bounded countdown assembler; unreliable frames must carry a zero header and are never fragmented.
- The transport copies inbound bytes before native callbacks return, applies bounded outbound buffering, and closes peers on malformed framing or asynchronous send failure.
- The adapter uses a conservative 64 KiB SCTP message-frame limit, an 8 MiB application-message cap, and a 10-second fragment-assembly timeout.
`BedrockInitialTransportRouter` installs per-connection handshake processors. It accepts the reliable RequestNetworkSettings frame, enqueues the NetworkSettings response, advances the handshake only after the response is accepted by the transport, and delegates Login authentication to a required host callback. After authentication succeeds it routes play messages to the configured game-session handler. `NetherNetBedrockModule` starts the WebRTC peer host before HTTP signaling and wires the connection listener to this router.

`NetherNetBedrockModule` performs that order and wires the peer host to the handshake router. Construct the same `WebRtcJavaNetherNetPeerHost` instance into the signaling server and module; the module checks that they match.

A minimal host setup is:

```java
var peerHost = new WebRtcJavaNetherNetPeerHost();
var handshakeRouter = new BedrockInitialTransportRouter(
    networkSettings, loginAuthenticator, playListener, playMessageHandler);
var signaling = new NetherNetSignalingServer(
    bindAddress, serverInfo, offerValidator, peerHost,
    partnerDomain, operatorKeyPair);
var module = new NetherNetBedrockModule(peerHost, peerHost, signaling, handshakeRouter);
var server = new NexoraServer();
server.register(module);
server.start();
```

Configure a directly reachable UDP host candidate. This configuration intentionally does not provide STUN/TURN relay traversal; a host behind NAT may therefore be unreachable without a public address or a future relay-enabled profile.

The desktop native library is not the Android SDK. The Android app depends on `io.github.webrtc-sdk:android:150.7871.04` and now implements its own PeerConnection, full-ICE offer gathering, HTTP(S) signaling, operator JWT/JWS verification, first-use key-pinning UI, and the initial RequestNetworkSettings exchange. It still does not obtain Microsoft/Xbox player credentials or send a valid Bedrock Login packet, so this is a transport/NetworkSettings test client rather than a playable Minecraft client. The desktop adapter and Android SDK have not yet been proven to interoperate with each other or with an unmodified Minecraft client.

## Verification boundary

JUnit tests have been authored for the codecs, initial transport handshake routing, NetherNet framing, legacy and modern connection-request parsing, identity parsing, canonical fingerprint serialization, RS256 GameServerToken verification, detached ES384 proof verification, operator identity signing/TOFU verification, bounded HTTP/HTTPS signaling, and the desktop WebRTC configuration. They must be run by Maven or CI before being described as passing. These tests are authored but have not yet been confirmed by a successful Maven/CI run. Even when they pass, they validate isolated implementation behavior only and do not establish interoperability with an unmodified Bedrock client or a live Microsoft/Xbox account.

## Compression envelope
