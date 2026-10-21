# Development status

Checkpoint 6: first-unlock repair in progress, 21 October 2026.
User reports both original APKs install on iQOO Neo 10 / Android 16, but first
fingerprint unlock fails before document import. Do not treat the earlier host
build as evidence of working unlock. Existing vault files must be preserved.

Confirmed blocker: SQLCipher 4.9.0 returns a result row for PRAGMA secure_delete
= ON; its execSQL API rejects that result. The production open policy now uses
a cursor, consumes/verifies the enabled value and shares the exact callback with
the encrypted database/migration tests. Other security settings, authentication,
keys and envelope formats are retained. No automatic reset or key replacement.

Android 16 emulator regressions for SQLCipher opening/reopening/wrong-key
rejection, old-call failure reproduction, settings verification, encrypted
migration/search, Tink and file durability are being added before APK upload.
Actual execution results remain pending; no repaired APK is ready yet.

A further disposable-emulator probe sets a synthetic screen-lock PIN, invokes
Android's real credential/Gatekeeper verification and immediately runs the
existing full controller lifecycle test for each variant within the unchanged
30-second Keystore window. It requires one non-skipped passing result per
variant. This does not establish physical fingerprint/UI behavior. It is being
validated on a separate fix branch while the initial Android regression run
continues, then will be fast-forwarded only after its real results are inspected.

Version 0.1.1-dev / code 2 uses separate debug IDs com.privateassistant.app.dev
and com.privateassistant.app.connected.dev, with labels Private Vault Test and
Private Vault Connected Test. These install alongside the original APKs because
the original ephemeral debug signing key is unavailable. They have separate
vaults and do not reopen/migrate the original app's private files. Do not ask
the user to uninstall or clear data to work around signing.

Current checks: source policy and nine APK delivery tests passed; shell/Python
syntax passed. CI host and Android regression results pending. Next concrete
task: run full host gate plus all 12 selected Android regression tests per
platform variant, publish both repaired APKs only after success, then obtain
physical first-unlock confirmation on the user's Android 16 phone.

The first repair run (39645060676) passed the full host gate and booted the API36
emulator, then exposed an old migration-test compilation error: RoomDatabase
does not implement Closeable, so its test helper must explicitly close it in
finally. Corrected all five uses and added both modules/variants' instrumentation
Kotlin compilation to the host gate. No test was removed and no APK was uploaded.

## Earlier build evidence — original APKs have reported unlock failure

Checkpoint 5: 21 October 2026 — first successful Android build and APK delivery.
**Both host-verified debug APKs are available. Device behavior remains unverified.**
The earlier implementation checkpoint is preserved in
[checkpoints/2026-10-18.md](checkpoints/2026-10-18.md).

## Download and build identity

[Download both APKs, checksums and reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39641658548/artifacts/12364584323).
Extract the ZIP and install private-document-assistant-offline-debug.apk for
the offline development app. Android 12/API31 or newer is required.

- Repository: [nikslogy/private-doc-ai](https://github.com/nikslogy/private-doc-ai).
- Built source commit: 82758e97a250d8dfca884a429146e4cfd416cdc2.
- [Successful Host checks run](https://github.com/nikslogy/private-doc-ai/actions/runs/39641658548),
  attempt 1, job 119366471210; completed 21 October 2026 at 17:17 UTC.
- Artifact 12364584323: private-document-assistant-debug-apks-39641658548-1.
- ZIP: 242,842,436 bytes; expires 28 October 2026 at 17:17 UTC.
- ZIP SHA-256: e95b71fc4686fff95c1629cc661bd9a22dcbdcb0a9523cbc8a01c5a323be58a6.
- APK v2 signatures verified; debug certificate SHA-256:
  4d211d949b97ce0ca966a00810966800610c6e7abfd88ff22e3fa4bb943c3e58.

| APK | Application ID | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| private-document-assistant-offline-debug.apk | com.privateassistant.app | 121343048 | b9237d5a35fca31b8e1f2113a81c4c9dcc25620c9909fa43b5a67fa1d5716e28 |
| private-document-assistant-connected-debug.apk | com.privateassistant.app.connected | 121343092 | 926566ed2ebed8501f30c3a431e4203eb43f9bd6f4be6e8832483c81379a8c7f |

Both have version code 1, minimum SDK 31 and target SDK 35. Version names are
0.1.0-dev and 0.1.0-dev-connected. SHA256SUMS and APK_MANIFEST.json inside the
download identify the exact APK bytes. The GitHub artifact is the delivery;
SDKs, signing keys, models and private documents were not committed.
See [APK_DELIVERY.md](APK_DELIVERY.md) for installation and signing limitations.

## Checks actually executed

GitHub's Ubuntu 24.04 runner successfully executed pinned bootstrap with JDK 21,
then python3 scripts/stage-apks.py, which invokes the full scripts/check-host.sh.
Gradle reported BUILD SUCCESSFUL in 4m 55s; 197 tasks, 171 executed and 26 cached.
Staging verified the copied APK bytes again before atomic publication and upload.

| Check | Actual result |
| --- | --- |
| Python fixture/native/validation/Java-environment/APK-delivery suites | PASS: 6 + 22 + 10 + 10 + 9 = 57 tests |
| Source privacy/pinning policy | PASS |
| :core:test | PASS; 55 authored test cases |
| Platform/app offline and connected JVM unit-test tasks | NO-SOURCE; no Android-module JVM coverage claimed |
| Platform Kotlin compilation and Room schema generation, both variants | PASS |
| App Kotlin/Compose compilation, both variants | PASS |
| Platform/app lint, both variants | PASS; warnings/deprecations are not a zero-warning claim |
| :app:verifyProductionDependencies | PASS; production inventory generated and ML Kit excluded |
| :app:assembleOfflineDebug / :app:assembleConnectedDebug | PASS |
| Exact original and staged merged-manifest/native checks | PASS; offline has no INTERNET permission |
| Actual native inventory | 18 libraries per APK: 5 arm64-v8a, 5 x86_64, 4 armeabi-v7a, 4 x86 |
| All packaged 64-bit PT_LOAD and stored ZIP alignment | PASS: at least 16 KiB; 32-bit 16 KiB behavior remains unverified |
| Official apksigner verify and zipalign -c -P 16 -v 4, both staged APKs | PASS |
| Artifact upload | PASS; both APKs, SHA256SUMS, manifest and reports available |
| Instrumentation, installation, physical-device/privacy/model checks | UNRUN; no provisioned explicit target |

Full host/package/signature/alignment/dependency reports accompany the APKs.
Static native alignment does not establish successful loading on a 16 KiB phone.
No model, OCR quality, traffic, runtime leakage or recovery result is inferred
from compilation, source review or mocked pipeline tests.

## Build corrections and earlier attempts

The user authorized this repository for the APK build. The initial empty repo
was populated with 127 source files, and every remote blob hash/file mode was
compared with its local source before updating main. Scripts retained executable
modes. GitHub access is working; do not repeat the earlier installation blocker.

1. [First CI run](https://github.com/nikslogy/private-doc-ai/actions/runs/39640417464),
   source 83667e96fcfac2c89aeb111e934ccee1461036a1: setup, Python/source checks
   and core tests passed; Room KSP failed while both variants shared a schema
   output. No APK delivery.
2. [Second CI run](https://github.com/nikslogy/private-doc-ai/actions/runs/39641140014),
   source 8173ded425dbb481f8bdc97d7391f8dab2229a52: Room generation passed after
   pinning the official Room plugin/runtime/compiler to 2.7.2 and using its
   separate per-task output directories with checked schema consolidation.
   Compilation then found O_DIRECTORY is absent from Android's public SDK.
   No APK delivery.
3. Successful run above: directory sync uses public O_RDONLY/O_CLOEXEC,
   validates the opened inode with fstat/S_ISDIR, and retains fsync and guaranteed
   descriptor close. Two synthetic Android durability probes are authored;
   their device execution remains pending.

Earlier fixes corrected nine Tink calls to the pinned associated-data APIs.
Bootstrap/wrapper use a shared credential-free Java proxy setup and ignored
writable tool homes. Staging always runs all host checks and publishes both APKs
together only after exact-byte manifest/native/package/signature/alignment checks.
No privacy, lint or native gate was disabled to obtain this build.

Locally, SDK/Gradle downloads remain unavailable through the workspace proxy;
the successful build used the authorized GitHub runner. Do not repeat earlier
long network permission waits. No local Android build or device pass is claimed.

## Product and milestone state

Implemented and now host-compiled: encrypted immutable-original vault,
system unlock, native PDF/text evidence, encrypted literal search, page-text
viewer, explicit export/delete, lock/cancellation boundaries and schema 1→2
migration. Runtime correctness still needs device evidence.

Production OCR, AI questions/summaries, derivative tools, portable backup and
recovery remain unavailable. ML Kit remains synthetic instrumentation-only;
the model catalogue is empty. No production fake or remote processing was added.
M0/M1 remain open until their real native/crypto/device gates close.

These are development test APKs. Use synthetic documents and retain originals.
Uninstalling or losing the Keystore can permanently lose vault access because
portable recovery is not implemented. Debug signing certificates may differ
between builders. No app-store/release, model-quality or audited-security claim.

## Next concrete action

Provision an explicitly authorized Android target and run
ANDROID_SERIAL=<explicit-target> scripts/check-device.sh. Record non-skipped
installation, authentication, durability, original-byte, migration, search/delete,
lock/cancellation, parser and canary-leakage results. Include a real 16 KiB target
and physical phone tiers before release; device absence is not a passing suite.
Complete resolved native provenance/licence notices and traffic inspection.
Select/verify an actual licensed model only when the earlier milestone gates
permit it; never fabricate catalogue checksums or language-quality results.

No app-store publication, Drive enablement or WhatsApp bot was authorized.
