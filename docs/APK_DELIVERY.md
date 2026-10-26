# Development APK delivery

## Code9 chat repair

Version0.5.1-dev/code9 is being validated after user-reported repeated greetings
and rejected document questions in code8. The update keeps the existing Preview
IDs/signing pin and exact compact model receipt. Consult [STATUS](STATUS.md) for
new delivery results. Install a verified stable-signed update over Preview without
uninstalling or clearing data; local diagnostic .dev APKs are a separate app.

## Historical code8 Preview chat build

[Download stable-signed v0.5.0-dev chat APKs](https://github.com/nikslogy/private-doc-ai/actions/runs/40190793629/artifacts/12598142402).
Unzip/install the offline APK over **Private Vault Preview**, without uninstalling
or clearing data. Code8 keeps com.privateassistant.app.preview and
com.privateassistant.app.connected.preview with the same public signing pin.
Android 12/API31+ is required. Earlier Test/original apps keep separate vaults;
no automatic migration/recovery exists. Physical update-preservation remains a
separate phone check; keep original source files outside the app.

Open **Assistant**, type a message and press **Send**. The already installed
compact Qwen3 model is reused after receipt/hash verification. Chat uses bounded
recent history and clears when the vault locks; it is not a saved transcript.
General model replies are labelled unverified and cannot operate on files.
For supported policy/invoice questions choose **Ask a document**; printed values
come with source-page links. Summary means a bounded extractive summary of those
facts, not unrestricted generated document analysis. See [CHAT.md](CHAT.md).

[Stable run 40190793629](https://github.com/nikslogy/private-doc-ai/actions/runs/40190793629)
passed full host checks, signer/package/native checks and 96 actual Android test
executions, none skipped. Those include nine controller lifecycle cases and five
gateway cases per variant, existing vault/OCR regressions and real credential
lifecycle probes. The separate actual model-chat/history/cancel verdict is in
STATUS and its native checkpoint. These do not establish representative accuracy,
full Compose tapping/keyboard behavior, physical 4 GB support or release readiness.

The persistent user-owned signing key stays in repository Actions secrets;
only its public certificate is committed. Stable delivery has no temporary-key
fallback and uploads only after all build/regression gates pass. Ordinary Host
checks .dev APKs remain diagnostic builds. The compact model catalogue/receipt
identity is unchanged; no new model file is bundled or needed after a verified
code7 import. Exact APK/ZIP hashes, report identities and seven-day expiry are
recorded in [STATUS.md](STATUS.md). No private key was read locally or regenerated.

## Historical repair build

The original version 0.1.0 APKs passed host checks on 21 October 2026, but the
user reports first-fingerprint-unlock failure on iQOO Neo 10 / Android 16.
Keep any installed original apps and their vault data; the original bundle is
historical build evidence, not the recommended build for another unlock attempt.

Version 0.1.1-dev / code 2 repairs a SQLCipher security-setting call during
database opening. The [repair CI run](https://github.com/nikslogy/private-doc-ai/actions/runs/39715674766)
passed the full host gate, 12 Android 16 regression tests and one real
credential-bound lifecycle probe per variant: 26 emulator test executions,
none skipped. Both APKs also passed package, signature and native/alignment checks.
[Download the repaired APKs, checksums and reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39715674766/artifacts/12399575330).
The bundle is 242,861,005 bytes; SHA-256 is
`30124fc6be461d0cea9be89be39679849e6a21aa5dfb60241f24fbc8b22a067c`.
Built source: `850a87567dba8cc686539419110ca0eae7d5baad`.
The user confirms unlock/login and import on iQOO Neo 10 / Android 16. Full
physical fingerprint/UI automation remains unverified. See
[STATUS.md](STATUS.md) for actual results and [DEVICE_TESTS.md](DEVICE_TESTS.md)
for the separate physical-phone checks.
This repair build predates production OCR. New source adds local Tesseract OCR;
its build/device results and signing/install status are tracked in STATUS.
AI answers and portable vault recovery remain unavailable.

## Repair build identity and existing vaults

| APK | Launcher label | Application ID | Version |
| --- | --- | --- | --- |
| private-document-assistant-offline-debug.apk | Private Vault Test | com.privateassistant.app.dev | 0.1.1-dev / code 2 |
| private-document-assistant-connected-debug.apk | Private Vault Connected Test | com.privateassistant.app.connected.dev | 0.1.1-dev-connected / code 2 |

These debug IDs differ from the original `com.privateassistant.app` and
`com.privateassistant.app.connected`. The original ephemeral debug signing key
was not retained, so the repair builds intentionally install alongside those
apps. They create separate vaults and cannot open, migrate or replace the
original apps' private files. Do not uninstall, clear data or reset keys to work
around a signing mismatch. Preserve the old installations and original source
documents. Test the new launcher labels with synthetic documents.

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
The delivery contains no private vault, signing keys or SDK installation.
OCR builds bundle verified public tessdata_fast weights and their licences
inside the APKs. AI assistant model weights remain absent.

## CI downloads

The checked-in Host checks workflow runs the same staging command, then requires
the selected non-skipped Android 16 SQLCipher/Tink/migration/durability and OCR tests
and the real credential-bound lifecycle probe for both platform variants.
Only after all those steps succeed does it upload `artifacts/apk/`. Host staging
alone does not satisfy the workflow's Android regression gate. The artifact name starts with
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

For current stable downloads choose **Private Vault Preview** for offline testing:
application ID `com.privateassistant.app.preview`, with no INTERNET permission.
**Private Vault Connected Preview** uses `com.privateassistant.app.connected.preview`.
The earlier Test/original apps have separate IDs/vaults. Connected still has no
remote document processing. Use synthetic documents while the device/security
gates remain open.

Stable Preview APKs use the pinned development signing identity. Ordinary Host
checks builds use separate diagnostic .dev IDs and temporary keys; inspect
`APK_MANIFEST.json` before attempting an update. Uninstalling or losing
the Keystore can permanently lose vault access because portable recovery is
not implemented. Keep original source files outside the app.

Host/signature/alignment verification does not establish working first unlock,
phone behavior, full licence review or release readiness. The new emulator
tests passed real Android credential/Keystore authorization, but they do not
verify physical fingerprint prompts or the phone vendor's authentication path.
The full device suite remains pending. Run the separate
explicit-target device suite from [DEVICE_TESTS.md](DEVICE_TESTS.md); absence of
a provisioned device is not a pass. Pipeline tests in `test-apk-delivery.py`
use synthetic ZIPs and mocked SDK output, and provide no real APK evidence.
