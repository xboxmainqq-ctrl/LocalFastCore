# NEXORA official build signatures

NEXORA uses a detached Ed25519 signature and a SHA-256 checksum for its standalone
`server.jar`. The signature authenticates the exact JAR bytes; the checksum makes
accidental corruption easy to diagnose.

## One-time signing-key setup

Generate the key pair on a trusted computer with OpenSSL 3:

```sh
openssl genpkey -algorithm ED25519 -out nexora-ed25519-private.pem
openssl pkey -in nexora-ed25519-private.pem -pubout -outform DER \
  | base64 -w0 > nexora-signing-public-key.b64
```

1. Keep `nexora-ed25519-private.pem` offline and make an encrypted backup. Never
   commit it, upload it as a repository file, or put it inside the JAR.
2. Copy the one-line contents of `nexora-signing-public-key.b64` into
   `src/main/resources/META-INF/nexora-signing-public-key.b64` (replace
   `UNCONFIGURED`) and commit that public key.
3. In GitHub, open **Settings → Secrets and variables → Actions → New repository
   secret**. Name it `NEXORA_SIGNING_PRIVATE_KEY` and paste the complete PEM
   contents of `nexora-ed25519-private.pem`. The secret is only exposed to the
   signing step in GitHub Actions.

Do not send the private key in chat or add it to any commit.

## Building and verifying

- Pushes to `main` always publish `server.jar` and `server.jar.sha256`.
- Once both keys are configured, CI also creates `server.jar.sig`.
- Version tags matching `v*` require the signing secret and pinned public key;
  a tag build fails rather than silently publishing an unsigned release.
- Keep all three files in the same directory on the server:
  `server.jar`, `server.jar.sha256`, and `server.jar.sig`.
- At startup, NEXORA checks the SHA-256 digest and Ed25519 signature against the
  public key embedded in the JAR. It prints **Signature: VALID**, **Integrity:
  VERIFIED**, and **Build: OFFICIAL** only when both checks pass.

Set `NEXORA_REQUIRE_OFFICIAL_SIGNATURE=true` in the server environment to refuse
startup unless the signature is valid. Leave it unset (the default) while setting
up the signing keys; an unsigned or unconfigured build then starts with an explicit
warning instead of being mistaken for an official build.

A signature proves that a JAR is an unchanged build signed by the holder of the
private key. It does not stop someone from redistributing an unchanged official
JAR, and it cannot prevent a modified fork from removing its own verifier.


## Mandatory license activation

NEXORA requires a separate Ed25519-signed license before starting network or
gameplay services. This is independent from the build signature above.

- On first launch, the console displays a stable **Server ID** and asks for a
  license key. Empty input, a malformed key, an invalid signature, an expired
  license, or a key issued for another Server ID stops startup.
- A successful key is saved in `NEXORA_DATA_DIR/license.key`; the generated
  `instance.id` is stored beside it. Keep both files together when moving the
  same licensed server installation.
- The license public key is pinned in
  `src/main/resources/META-INF/nexora-license-public-key.b64`. Replace
  `UNCONFIGURED` with the Base64 DER Ed25519 public key before building.
- Keep the matching private key offline. It is the only authority that can
  issue license tokens. Never put it in this repository, the server JAR, or chat.
- The signed token contains the format version, license ID, Server ID, and
  expiration time. VK bot issuance/management will use this exact token format;
  the bot must never receive or store the private signing key in public code.

Generate a dedicated license key pair (do not reuse the build-signing key):

```sh
openssl genpkey -algorithm ED25519 -out nexora-license-private.pem
openssl pkey -in nexora-license-private.pem -pubout -outform DER \
  | base64 -w0 > nexora-license-public-key.b64
```

The console activation path is fail-closed. Until the license public key is
pinned and a trusted issuer (the planned VK bot/license service) has issued a
matching token, startup is intentionally blocked. Do not distribute this
intermediate build as a ready-to-run server.
