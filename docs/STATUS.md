# Development status

## Checkpoint 10 — verified model installation, native runtime still pending

[Download the stable-signed 0.4.0-dev APKs](https://github.com/nikslogy/private-doc-ai/actions/runs/39760351605/artifacts/12419471716).
Both APKs passed the full host gate and **66 Android test executions, none
skipped**, in stable run 39760351605, attempt 2. This validates the new model
installer and its synthetic storage/privacy cases. It does not establish native
generation, phone compatibility or document QA. General questions/summaries
remain disabled; the experimental model test uses only a fixed synthetic prompt.

Install the offline APK as an update to **Private Vault Preview** without
uninstalling or clearing storage. Version code 5 / 0.4.0-dev requires Android
12/API31+. Connected adds the -connected version suffix. Permanent Preview IDs
and the pinned signing identity are unchanged. Earlier Test/original apps have
separate vaults; no automatic migration or portable recovery exists. Actual
same-key phone update preservation still needs its synthetic-data procedure.

| Variant | Application ID | Launcher |
| --- | --- | --- |
| Offline | com.privateassistant.app.preview | Private Vault Preview |
| Connected | com.privateassistant.app.connected.preview | Private Vault Connected Preview |

Assistant → Document facts retains literal labelled values, conflicts and exact
source-page references. Assistant → Set up on-device AI adds reviewed model
metadata, licence acknowledgement and verified file import. Consult
[MODEL_SETUP.md](MODEL_SETUP.md) before testing. Native execution has not yet
passed; installation alone does not make document questions available. OCR
production behavior is unchanged, and tuning remains deferred by user request.

## Exact delivered build and validation

- Source: b35642df7e7835ac7efc6229af134c6d9b80c9e8.
- Built commit: 2e5dbc2153d8c41eab147ec4b539cb87bcc5ca37.
- [Stable run 39760351605](https://github.com/nikslogy/private-doc-ai/actions/runs/39760351605),
  attempt 2, job 119767928162; completed 22 October 2026 at 10:32:03 UTC.
- APK artifact 12419471716: 309,734,836 bytes; expires 29 October at 10:31:57 UTC.
- ZIP SHA-256: a3079c5eaf17be0d432dc2399f61990a497afeb5a9e49a065427fab5251d388f.
- Both v2 signatures match the existing public certificate SHA-256:
  0a4f0f2f8fa9f25b20d43c3d2c407f05fd757e4b6cd080548146e5e1ca182467.
- [Regression reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39760351605/artifacts/12419471703):
  128,295 bytes, ZIP SHA-256 8c159fab6541f187e657ae3e3bd0bc0412a31a31c91d0559f029e813a9234c42.
- [Machine-readable checkpoint](checkpoints/2026-10-22-model-setup-build.json).

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| private-document-assistant-offline-debug.apk | 154770214 | b78c18ffcc7a70d6fb14412a6160b732606d9f7ba3d55f5117d47f2e1708c168 |
| private-document-assistant-connected-debug.apk | 154770266 | d1c5c4c34cf8356dce7940a066af7a698e8cc2d6e8cf4b3113df4d091ef0bb76 |

APK identities/signatures are evidence from actual CI logs and GitHub metadata;
the APK ZIP was not independently downloaded locally. The regression ZIP was
independently downloaded, hash checked and parsed: 32 XML cases per variant,
zero failures/errors/skips. Two additional credential-bound lifecycle passes
are evidenced by actual instrumentation logs, giving 66 executions total.
User-owned signing secrets remain excluded local backups/repository Actions
secrets; no key was regenerated or published. No temporary signing fallback exists.

| Check | Actual delivered-source result |
| --- | --- |
| Python fixture/native/validation/Java/APK/OCR/signing checks | PASS: 77 cases |
| Source policy | PASS |
| :core:test | PASS task; 82 authored cases |
| Both platform JVM tasks | PASS tasks; 16 authored cases per variant |
| App JVM tasks | NO-SOURCE; no app JVM coverage claimed |
| All four instrumentation Kotlin compilations | PASS |
| Both app/platform lint, production dependencies, compilation and assemblies | PASS |
| Exact APK manifests/packages | PASS; offline has no INTERNET; ML Kit excluded |
| Official apksigner, zipalign and fixed certificate pin | PASS both APKs |
| Static native 16 KiB checks | PASS: 18 64-bit libraries/APK; 16 32-bit libraries unverified |
| SQLCipher3 + migration3 + Tink4 + durability2 per variant | PASS: 24 Android executions |
| OCR boundary3 + actual Tesseract6 per variant | PASS: 18 Android executions |
| Model-store10 + encrypted input-staging privacy1 per variant | PASS: 22 Android executions |
| Real-credential Keystore lifecycle1 per variant | PASS: 2 Android executions |

JVM XML was not uploaded by that workflow; exact executed JVM counts have not
been independently extracted. The authored counts above are source counts.
All 66 Android executions used an explicit API36 x86_64 emulator, with no skips.
They do not establish the full check-device.sh suite, Compose UI races,
physical fingerprint/vendor behavior or release readiness.

## M4 implementation and model evidence

The installer accepts only reviewed public model IDs and current licence
acknowledgement. Unknown SAF input is first encrypted using Tink Streaming AEAD
with a fresh memory-only key. Only an exact expected public size/hash match
allows authenticated decryption into plaintext public weights, a second hash,
fsync and atomic publication. Incorrect private documents cannot enter plaintext
staging. The Android regression scans a same-sized synthetic private canary
while copying and after rejection. Slots/receipts live in noBackup storage;
source names/URIs and staging keys are not persisted. Scoped model leases
serialize verification/native use with removal and expire on return.

The real pinned LiteRT-LM CPU adapter configures two threads, a 512-token combined
budget, no cache/tools, bounded input and post-allocation output checks. A joined
watchdog requests native cancellation; synchronous send must return before close.
Native initialization/hangs have no hard interrupt guarantee. Lock clears UI
state and revokes work. Only fixed synthetic input reaches the diagnostic; vault
keys, document evidence and user questions do not. See ARCHITECTURE and PRIVACY.

The experimental catalogue contains only legacy Qwen 2.5 1.5B at immutable
revision 19edb84c69a0212f29a6ef17ba0d6f278b6a1614, 1,597,931,520 bytes, SHA-256
faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9.
Its exact public download was independently size/hash verified in both explicit
model probes. No inference weights are bundled or downloaded by routine checks.
Evaluated task/language lists remain empty. The pinned runtime README links the
legacy artifact; a later same-named file has different bytes/runtime metadata.
[Decision 0005](decisions/0005-local-model-candidate.md) records exact provenance
and licence sources. Gemma's checksum/licence gate remains unresolved.

| Attempt | Actual outcome |
| --- | --- |
| Stable 39759673652 | Failed app Kotlin compilation at a missing provider-stream closing brace; corrected before delivered build |
| Stable 39760351605 attempt 1 | Pinned JitPack AAR unavailable; no APK; unchanged attempt 2 passed full gate |
| Model 39759683730 | Public bytes verified; failed before load because the raw path check rejected an Android app-data alias |
| Model 39760757984 | Public bytes verified; test process crashed after start; no completed test/native metrics and cause unknown |
| Diagnostic 39762884663 attempts 1 and 2 | Same pinned JitPack AAR unavailable before platform/probe Kotlin/emulator execution; core Kotlin did execute |

The alias fix preserves canonical containment, controlled filenames, symlink
rejection, exact hashes and canary scans. Test-only phase/PID events and focused
package exit-info/crash diagnostics are prepared for the next synthetic CI run.
Physical-target scripts do not collect these crash logs. The earlier emulator
had 4 GiB RAM; its crash remains unattributed. The next target uses 6 GiB RAM
and requires 8 GiB measured host availability, matching the app's conservative
configuration. Neither guard is a measured model minimum or phone proof.

## Current source checks and next concrete task

A bounded explicit GET bootstrap now verifies the same pinned public OCR AAR
before atomic publication in an ignored exact-coordinate local Maven cache.
It preserves the configured proxy/CA and rejects redirects, wrong size/hash and
symlinks. Only transient transport/404/429/5xx failures receive three bounded
attempts. The mandatory Gradle pre-build hash check remains unchanged. No
verified official alternate mirror was established and no dependency pin changed.
The actual local GET verified all 12,848,454 bytes and the existing SHA-256.
Twenty new synthetic tests passed, raising routine Python checks from 77 to 97.
This bootstrap/source change is newer than the delivered APK's build identity.

Pinned SDK/Gradle bootstrap now works locally with current network access. A
fresh scripts/check-host.sh run passed 97 Python checks, source policy, assets
and exact AAR verification, then failed because the installed Java 21 runtime
lacked javac. A complete, published-checksum-verified JDK is being provisioned
before retrying. Full current-source host success has not yet been established. Earlier local runs stopped honestly
at absent tooling. Local KVM is absent; Android tests continue on explicit CI
emulators, never counted as a local passing device suite.

Next: finish current-source host/build validation and the explicit 6 GiB native
probe; diagnose any actual exit, then establish real generation, active native
cancellation and narrow canary/hash evidence. Next run the phone import/tamper/
cancel/lock/restart and same-key update procedure in DEVICE_TESTS.md. The reported
iQOO Neo 10 / Android 16 has no independently measured inference result. Only
then proceed to M5's evaluated cited questions/summaries; installation does not
complete M4 or enable QA. M6 tools and M7 portable encrypted backup/recovery follow.

Remaining gates include the full explicit-target device suite, two physical
phone tiers, real 16 KiB loading, physical authentication/lifecycle, hard native
isolation, comprehensive filesystem/log/traffic/memory/thermal checks, native
provenance/notices, encrypted thumbnails and remaining import validation.
Representative accuracy requires the plan's labelled held-out evaluation.
Recovery remains unavailable. No release, remote private processing, telemetry
or hidden cloud fallback was enabled.

## Earlier checkpoints

The user reported unlock/import and later OCR working on iQOO Neo 10 / Android16
and deferred OCR tuning. This manual report is not an independently verified
APK hash, same-key update preservation test or full hardware suite.

- [Document-facts build](checkpoints/2026-10-22-document-facts-build.json): code4,
  full host and 44 selected Android executions. Its OCR snapshot isolation removed
  only ProfileInstallerInitializer metadata in the test APK; production startup
  and every file/canary/hash assertion remain unchanged.
- [Stable OCR build](checkpoints/2026-10-22-stable-ocr-build.json),
  [earlier OCR build](checkpoints/2026-10-22-ocr-build.json) and
  [OCR input provenance](checkpoints/2026-10-22-ocr-input-provenance.json).
- [First-unlock repair](checkpoints/2026-10-22-first-unlock-repair.md): historical
  SQLCipher-setting repair and selected real-credential emulator evidence.
