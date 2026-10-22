# Development status

Checkpoint 6: 22 October 2026 — first-unlock repair built and emulator-verified.
**Repaired development APKs are ready. Physical fingerprint/UI confirmation is pending.**

## Install the repair

[Download both repaired APKs and checksums](https://github.com/nikslogy/private-doc-ai/actions/runs/39715674766/artifacts/12399575330).
Extract private-document-assistant-offline-debug.apk and open **Private Vault Test**.
Android 12/API31+ is required. The connected APK launches as **Private Vault Connected Test**.

Version code 2 / 0.1.1-dev uses com.privateassistant.app.dev; connected uses
com.privateassistant.app.connected.dev and version 0.1.1-dev-connected.
These IDs install alongside the original apps and have separate vaults.
The original ephemeral debug signing key was not retained. Do not uninstall,
clear storage or reset keys to work around signing; retain original apps/data.
The repair does not reopen or migrate another application's private files.

## Verified build identity

- Built source: 850a87567dba8cc686539419110ca0eae7d5baad.
- [Successful CI run](https://github.com/nikslogy/private-doc-ai/actions/runs/39715674766),
  attempt 1; job 119617073042. Completed 22 October 2026, 08:52 IST (03:22 UTC).
- APK artifact 12399575330: private-document-assistant-debug-apks-39715674766-1.
- ZIP: 242,861,005 bytes; expires 29 October 2026 at 08:52 IST.
- ZIP SHA-256: 30124fc6be461d0cea9be89be39679849e6a21aa5dfb60241f24fbc8b22a067c.
- [Android regression reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39715674766/artifacts/12399007128):
  XML, full credential-test transcripts and resolved emulator/image/target metadata.
- Both APK v2 signatures verified; certificate SHA-256:
  5ddc39e8f3e28c0db7ccd2b095f7bb62021c67ee95c0984e414727503836cc09.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| private-document-assistant-offline-debug.apk | 121343048 | 3b0fd4902c159f1503d66d24c0347c801e95f33adb14727b6e5a20a6b3634bf7 |
| private-document-assistant-connected-debug.apk | 121343104 | 92d24263fdd68338e8abbcf01e4a7f942419ace17f662ad158a19b1c0ec9afbe |

Both have minimum SDK 31, target SDK 35 and compile SDK 36.
SHA256SUMS, APK_MANIFEST.json and exact-byte package/signature/native/alignment
reports accompany the APKs. Evidence was checked through CI logs and GitHub
artifact metadata; no independent local ZIP-byte verification is claimed.

## Failure and corrections

The user installed both original 0.1.0 APKs on iQOO Neo 10 / Android 16.
First fingerprint authentication succeeded, then unlock failed before import.

SQLCipher 4.9.0 returns a result row for PRAGMA secure_delete = ON, while its
execSQL API rejects returned rows. VaultDatabaseSecurity now queries/consumes
that row and verifies the enabled value. The exact production callback is shared
with SQLCipher and migration tests. Encryption, authentication, keys, envelopes,
immutable originals and other security settings are unchanged. No reset/fallback.

Real Android testing also exposed a redundant explicit default SIMPLE tokenizer
option in migration 1→2. Room omits it and compares FTS option strings literally.
The migration now uses the identical SQLite default without the extra option.
It keeps version 2, document identities, ciphertext and search semantics.
Old Room test helpers now close databases explicitly in finally.

The host gate additionally compiles instrumentation sources for both modules and
variants. CI rejects skipped/missing/ambiguous regression results and publishes
APKs only after all emulator checks pass. Pinned Gradle cache actions cover only
dependency/task and wrapper caches; SDKs and Android user/signing-key homes are excluded.

## Checks actually executed

The successful Ubuntu 24.04/JDK21 runner used pinned bootstrap, then
python3 scripts/stage-apks.py → scripts/check-host.sh, followed by a provisioned
API36 x86_64 emulator with explicit ANDROID_SERIAL=emulator-5554.

| Check | Actual result |
| --- | --- |
| Python fixture/native/validation/Java-environment/APK-delivery suites | PASS: 57 tests |
| Source privacy/pinning policy | PASS |
| :core:test | PASS; 55 authored cases |
| Platform/app offline/connected JVM unit-test tasks | NO-SOURCE; no JVM coverage claimed for those modules |
| Both modules/variants' instrumentation Kotlin compilation | PASS |
| Production Kotlin/Room/Compose compilation, lint and dependency inventory | PASS; ML Kit excluded from production |
| Both debug assemblies and staged manifest/native/package checks | PASS; offline has no INTERNET permission |
| Static native 64-bit PT_LOAD and stored ZIP alignment | PASS, 16 KiB; 32-bit 16 KiB behavior unverified |
| Official apksigner and zipalign, both staged APKs | PASS |
| SqlCipherInstrumentedTest | 3 cases × 2 variants passed, none skipped |
| VaultEvidenceMigrationInstrumentedTest | 3 cases × 2 variants passed, none skipped |
| StreamingCryptoInstrumentedTest | 4 cases × 2 variants passed, none skipped |
| DurableFilesInstrumentedTest | 2 cases × 2 variants passed, none skipped |
| Real-credential VaultLifecycleInstrumentedTest | 1 case × 2 variants passed, none skipped |
| APK and regression-report uploads | PASS |

There are 26 Android test executions: 12 selected cases plus one authenticated
lifecycle case for each platform variant. The latter uses Android's actual
credential/Gatekeeper path with a synthetic PIN on the disposable emulator,
then the unchanged 30-second Keystore policy. It verifies first vault creation,
import/dedup, exact originals, evidence/search, lock/reopen, interrupted-publication
cleanup, missing-object rejection, limited private-file canary scans and deletion.
No authentication substitutes or weakened key policy were used.

This is an emulator regression subset, not the complete check-device.sh suite.
Physical fingerprint prompts, activity UI, actual process death/power loss,
OEM/16 KiB runtime behavior, comprehensive leakage/traffic/performance and
multiple physical phone tiers remain pending. Model/production OCR/recovery
checks remain unavailable. No release-readiness claim.

## Earlier attempts

- 39645060676: host passed; instrumentation compilation found old Room .use helpers;
  no APK upload.
- 39646099728: superseded/cancelled; no completed validation claimed.
- 39646510842: expanded host passed; 11/12 offline Android cases passed, FTS migration
  failed; no APK upload.
- The original host-only APK evidence and failure caveat are archived in
  [2026-10-21-first-apk.md](checkpoints/2026-10-21-first-apk.md).
  [2026-10-18.md](checkpoints/2026-10-18.md) preserves earlier milestone detail.

Local SDK/Gradle downloads were blocked in the original workspace; successful
Android builds/tests used the authorized GitHub runner. Do not repeat old long
permission waits or infer a local device pass.

## Product and next concrete task

Vault import/export/delete, immutable originals, local native PDF/text evidence,
literal encrypted search and automatic locking are implemented.
Production OCR, AI answers/summaries, derivative tools and portable backup/recovery
remain unavailable; model catalogue is empty and ML Kit is instrumentation-only.
M0/M1 stay open for the outstanding hardware/privacy/licence gates.

Next: the user should install the new offline APK, open Private Vault Test and
retry fingerprint unlock on iQOO Neo 10 / Android 16. Record the actual result.
Then run ANDROID_SERIAL=<explicit-target> scripts/check-device.sh on authorized
physical targets and document non-skipped outcomes plus full native provenance/
licence notices, real 16 KiB loading and connected traffic inspection.
Use synthetic documents and keep their sources; uninstalling/losing the Keystore
can lose access because recovery is not implemented.

No app-store publication, live bot or cloud document processing was enabled.
