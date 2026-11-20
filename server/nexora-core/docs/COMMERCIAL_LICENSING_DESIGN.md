# NEXORA Commercial Licensing Architecture

Status: design proposal; not yet implemented.

## Product model

NEXORA Core is distributed under a commercial, proprietary license. A customer purchases a license for the number and type of server instances stated in the order. Third-party plugins are allowed subject to the Plugin Security Policy.

The license service grants permission to use NEXORA Core; it does not make a copied JAR impossible. Copyright notices, access controls, signed releases, license enforcement, and evidence of provenance work together.

## License lifecycle

1. Customer purchases a plan and accepts the applicable license terms.
2. The license portal issues a random activation code. Store only a hash of activation codes server-side.
3. On first activation, the server creates a local key pair. The private key remains on that server and is never uploaded.
4. The server sends the activation code, product/build identifier, public key, and a customer-approved instance label to the license API over HTTPS.
5. The license service validates the purchase and returns a signed license lease.
6. NEXORA Core verifies the lease signature locally using a pinned public verification key. No shared signing secret is embedded in the JAR.
7. The server periodically renews its lease. A proposed initial offline grace period is 7 days; this value must be configurable by the service operator and disclosed to customers.
8. Customer support can deactivate or transfer an instance. Revocation takes effect on the next successful renewal; do not pretend an offline server can receive an immediate revocation.

## Signed license claims

A lease should contain only the claims needed for enforcement:
- schema version;
- license ID (opaque, non-secret identifier);
- product and edition;
- allowed instance count;
- permitted feature flags;
- issued-at, not-before, and expiry timestamps;
- renewal/grace policy;
- bound server public-key fingerprint;
- license-service key ID;
- unique lease ID.

Sign canonical serialized claims with Ed25519. Verify algorithm and key ID against an allow-listed set; reject unknown algorithms, malformed claims, invalid signatures, expired leases outside the disclosed grace policy, and mismatched server keys. Include key rotation support.

## Availability and failure behavior

- Use HTTPS with normal certificate validation.
- Cache the last valid signed lease atomically on disk.
- Network outage: continue only within the published grace period and log a clear warning.
- License API outage must not corrupt worlds or player data.
- Invalid, expired, or revoked lease: enter a documented restricted state; do not delete data or silently wipe the world.
- Clock rollback: detect suspicious rollback, but provide a support/recovery path for legitimate host clock problems.
- Never transmit world contents, player chat, passwords, or unrelated personal data to the license service.

## Customer privacy and portability

Avoid invasive hardware fingerprinting. Bind activation to a generated server key pair, with a documented reset/transfer workflow for host migration. Publish what activation metadata is collected, retention periods, and how customers request deletion.

## Release integrity

- Sign release artifacts and publish SHA-256 checksums.
- Keep signing private keys outside GitHub source and build logs, preferably in a managed signing service or protected CI secret.
- Verify signatures before release publication.
- Keep reproducible build inputs and an auditable release history where practical.
- Obfuscation may raise the cost of casual inspection, but is not a security boundary and must not replace signatures or access control.

## Plugin compatibility

Plugins are separate works with their own authors and licenses. NEXORA's core license must not claim ownership of plugin code. See `PLUGIN_SECURITY_POLICY.md`.

A Java plugin loaded in-process has the same effective privileges as the server process. A classloader, manifest permission list, or bytecode scan is not a secure sandbox. For genuinely untrusted plugins, use process/container isolation with restricted OS permissions and a narrow IPC API. Otherwise, clearly require administrator trust and explicit approval before loading.

## Implementation phases

1. Publish reviewed commercial terms and third-party notices.
2. Build the license API and portal (purchase, activation, renewal, transfer, revoke, support).
3. Add local key generation, signed lease verification, caching, grace handling, and safe restricted mode to the core.
4. Add artifact signing and CI verification.
5. Add plugin manifest validation, scanning hooks, quarantine, admin approval, and audit logs.
6. Add integration tests for invalid signatures, expiry, offline mode, clock changes, key rotation, transfers, and API outages.

## Not yet implemented

This document is a design only. It does not mean the current NEXORA JAR checks a commercial license, signs artifacts, or scans plugins. Do not advertise those features until code and tests exist.
