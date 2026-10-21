# Development APK delivery

Both offline and connected APKs can be supplied as downloadable development
builds once the real Android build and all host gates pass. Source ZIPs are
separate artifacts. No APK is represented as ready until its checks succeed.
See [STATUS.md](STATUS.md) for actual build and device results.

## Build and stage

After provisioning the pinned toolchain and accepting the Android SDK terms:

```bash
source .tooling/env.sh
python3 scripts/stage-apks.py
```

The staging command always runs `scripts/check-host.sh`; there is no skip
option. It then copies the two assembled APKs into a temporary directory and
checks those exact copies with the existing manifest/native verifier. Official
SDK build-tools `35.0.0` supply `aapt` package/version/SDK inspection,
`apksigner verify --verbose --print-certs --min-sdk-version 31` and
`zipalign -c -P 16 -v 4`. Missing tools, failed checks, incorrect application IDs,
non-debug APKs, missing signer certificates or missing production dependency
inventory stop staging. Staging never signs, repairs or changes an APK.

Successful output is published together in `artifacts/apk/`:

- `private-document-assistant-offline-debug.apk`
- `private-document-assistant-connected-debug.apk`
- `SHA256SUMS` and `APK_MANIFEST.json` with flavor, application ID, version,
  minimum/target SDK, APK bytes/hash and verified signer certificate hashes.
- `reports/` with host, merged-manifest/native, package, signature, alignment
  and production dependency inventory output, plus installation notes.

A new attempt removes the previous staged delivery before checking anything.
Failure publishes neither APK. The source/build-output files are untouched.
The delivery contains no vault, model weights, signing keys or SDK binaries.

## CI downloads

The checked-in Host checks workflow runs the same staging command and uploads
`artifacts/apk/` only after success. The artifact name starts with
`private-document-assistant-debug-apks-` and includes the run ID and attempt.
Download it from that successful workflow run's Artifacts section and unpack
the archive. Retention is seven days; missing files fail the upload step.
Repository access and GitHub sign-in may be required.

The upload action is pinned to
[`actions/upload-artifact` v7.0.2](https://github.com/actions/upload-artifact/releases/tag/v7.0.2),
commit [`cf430e030ddbb5b0abf93d22962f4752f3646cd9`](https://github.com/actions/upload-artifact/commit/cf430e030ddbb5b0abf93d22962f4752f3646cd9).
Its official release-to-commit link and Node 24 action metadata were inspected
on 19 October 2026. On 21 October, the user-authorized source was published to
[`nikslogy/private-doc-ai`](https://github.com/nikslogy/private-doc-ai), triggering
the first [Host checks run](https://github.com/nikslogy/private-doc-ai/actions/runs/39640417464).
See STATUS for its actual result; a running workflow is not an APK delivery.

## Before installing

Android 12/API31 or newer is required. Check hashes from the extracted directory:

```bash
sha256sum -c SHA256SUMS
```

Offline uses application ID `com.privateassistant.app` and has no INTERNET
permission. Connected uses `com.privateassistant.app.connected`; it can be
installed alongside offline and still has no remote document processing.
Use synthetic documents while the device/security gates remain open.

These APKs use development debug signing. Builder debug certificates can differ;
inspect `APK_MANIFEST.json` before attempting an update. Uninstalling or losing
the Keystore can permanently lose vault access because portable recovery is
not implemented. Keep original source files outside the app.

Host/signature/alignment verification does not establish successful installation,
phone behavior, full licence review or release readiness. Run the separate
explicit-target device suite from [DEVICE_TESTS.md](DEVICE_TESTS.md); absence of
a provisioned device is not a pass. Pipeline tests in `test-apk-delivery.py`
use synthetic ZIPs and mocked SDK output, and provide no real APK evidence.
