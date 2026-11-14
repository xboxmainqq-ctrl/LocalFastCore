# Verifying NEXORA release artifacts

NEXORA tagged releases are intended to include four files:

- `NexoraCore-X.Y.Z.jar` — compiled plugin.
- `SHA256SUMS.txt` — SHA-256 digest of the JAR.
- `NexoraCore-X.Y.Z.jar.sigstore.json` — Sigstore verification bundle for the JAR.
- `SHA256SUMS.txt.sigstore.json` — Sigstore verification bundle for the checksum manifest.

Replace `X.Y.Z` with the version in the release tag (for example, `nexora-v1.2.3`).

## 1. Check the checksum

On Linux:

```bash
sha256sum --check SHA256SUMS.txt
```

On Windows PowerShell:

```powershell
$expected = (Get-Content .\SHA256SUMS.txt).Split(' ')[0]
$actual = (Get-FileHash .\NexoraCore-X.Y.Z.jar -Algorithm SHA256).Hash.ToLower()
if ($actual -ne $expected) { throw "NEXORA JAR checksum mismatch" }
```

A checksum detects accidental corruption or a file that differs from the manifest. By itself, it does not prove who produced the file.

## 2. Verify the Sigstore signature

Install the official Sigstore `cosign` CLI from its trusted distribution channel, then run:

```bash
cosign verify-blob \
  --bundle NexoraCore-X.Y.Z.jar.sigstore.json \
  --certificate-identity "https://github.com/aqaqpopa-del/LocalFastCore/.github/workflows/release-nexora.yml@refs/tags/nexora-vX.Y.Z" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  NexoraCore-X.Y.Z.jar
```

Verify the checksum manifest signature too:

```bash
cosign verify-blob \
  --bundle SHA256SUMS.txt.sigstore.json \
  --certificate-identity "https://github.com/aqaqpopa-del/LocalFastCore/.github/workflows/release-nexora.yml@refs/tags/nexora-vX.Y.Z" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  SHA256SUMS.txt
```

Use the exact version in the release tag in both identity strings. Do not ignore verification errors or install a file whose signature identity differs.

## Security notes

- The workflow uses GitHub Actions OIDC and Sigstore keyless signing; there is no long-lived private signing key in the repository.
- A valid signature proves the artifact was signed by the specified workflow identity. It does not prove the source code is bug-free or that the server host is uncompromised.
- Release tags must point to commits already contained in `nexora/compatibility-edition`.
- The release workflow must first be merged into the repository's default branch before GitHub will trigger it from version tags.
- Do not install development snapshots on a live server. Test a release on a separate server copy first.
