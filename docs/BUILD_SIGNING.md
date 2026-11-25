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


## License activation: unofficial default and official owner key

NEXORA has two license states:

- **Development / unofficial** is the built-in default. If no official license
  public key is pinned in the JAR, the server creates or reuses `data/instance.id`
  and starts without asking for a key. This is deliberately marked
  `DEV-UNOFFICIAL`; it is not an official license and must not be presented as
  one.
- **Official license** is a separate Ed25519-signed token issued for the exact
  Server ID. When the official public key is configured in
  `src/main/resources/META-INF/nexora-license-public-key.b64`, the console
  requires a valid token on first launch. Invalid, expired, or mismatched keys
  stop startup. A valid token is saved as `data/license.key`.

The Server ID is generated on the first launch and printed in the console. Run
`/nexora` in-game to view whether the current server is using the unofficial
development mode or an active official license. The command also reports the
build-signature status separately.

### Owner key setup

The owner’s official license is not embedded in the public repository. Generate
a dedicated Ed25519 key pair on a trusted computer:

```sh
openssl genpkey -algorithm ED25519 -out nexora-license-private.pem
openssl pkey -in nexora-license-private.pem -pubout -outform DER \
  | base64 -w0 > nexora-license-public-key.b64
```

1. Keep `nexora-license-private.pem` offline and backed up securely. Never
   commit it, put it in the JAR, or send it in chat.
2. Pin only the Base64 public key in
   `server/nexora-core/src/main/resources/META-INF/nexora-license-public-key.b64`
   and rebuild the core. The public key is safe to publish; the private key is not.
3. Issue the perpetual owner token on the trusted computer (requires OpenSSL):

   ```sh
   bash tools/issue-nexora-license.sh nexora-license-private.pem <SERVER-ID> NEXORA-OWNER
   ```

   The script prints one token. Save it privately as your separate owner key;
   do not commit it or post it publicly. It is bound to the exact Server ID.
4. Put the token on one line in `data/license.key` beside `instance.id`.
   NEXORA validates it at startup and reports the expiry as **бессрочно**.
   Without a valid token, the core continues to start in unofficial mode.

The issuer uses the signed expiry value `9223372036854775807` (Java
`Long.MAX_VALUE`) for a perpetual license. The core validates it like any
other signed token; no date conversion overflow occurs when displaying it.

The license token format remains
`base64url(payload).base64url(ed25519-signature)`, with the signed payload
`NEXORA-LICENSE|1|<licenseId>|<serverId>|<expiresAtEpochSeconds>`.
The private signing key is the issuer authority; the token is the actual
server license. Do not confuse the two.

The current Server ID is a generated UUID stored in `data/instance.id`, not a
hardware fingerprint. Copying both `instance.id` and `license.key` can copy
the identity to another installation. Offline expiry also depends on the
server's system clock; online revocation and VK issuance are future work.
