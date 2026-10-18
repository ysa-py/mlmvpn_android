# Release signing

Release APKs built by `.github/workflows/release.yml` are signed with a dedicated release key.
Android (and Play Protect) only trust an update when it is signed by the same key as the installed app,
so every release must use the same key.

## Key details

| Item | Value |
|---|---|
| Keystore type | PKCS#12 (`.p12`) |
| Key alias | `mlmvpn-release` |
| Certificate SHA-256 | `b502ed78c0b36edcc7951ca17b16eceea493ef76ecd873bd412f4cf8f73afa19` |
| Certificate validity | 30 years from creation (2026-10-18) |

The keystore file and its password are **not** in this repository. They live in two places only:

1. GitHub Actions secrets (used by the release workflow).
2. The maintainer's offline backup.

## GitHub Actions secrets

Set both under **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Contents |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The `.p12` file, base64-encoded on a single line |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password. It is also used as the key password. |

If a secret is missing, the **Prepare release signing key** step fails with a message naming the missing secret.

## What the workflow does

1. Decodes `ANDROID_KEYSTORE_BASE64` into the runner's temp directory (outside the checkout).
2. Builds with `MLM_RELEASE_KEYSTORE`, `MLM_RELEASE_KEY_ALIAS`, and `MLM_RELEASE_STORE_PASSWORD` set. `app/build.gradle` reads them and signs the `release` build type.
3. **Verify APK signatures** runs `apksigner verify` on every APK and fails the job unless each one is signed by the certificate above.
4. Deletes the decoded keystore, even if an earlier step failed.

Local builds (for example, on the Windows PC) do not set `MLM_RELEASE_KEYSTORE`, so they produce unsigned release APKs, as before.

## Rotating or recovering the key

- **Losing the key is permanent.** Installed copies can then only be updated by uninstalling first, which deletes app data. Keep an offline backup of the `.p12` file and its password.
- Changing the key means existing users can no longer update in place. Do this only if the key is compromised.
- If the key changes, update the `EXPECTED_CERT_SHA256` value in the workflow to the new certificate's SHA-256.

## Note on Play Protect

A signed APK does not, by itself, stop Play Protect warnings for a new developer key. Play Protect's
reputation check still applies. Installing the APK from a file manager can still show "Install anyway".
