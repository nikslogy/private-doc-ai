# Development status

## Checkpoint 11 — compact model passes a 4 GiB Android experiment

[Download the stable-signed 0.4.2-dev APKs](https://github.com/nikslogy/private-doc-ai/actions/runs/40168429140/artifacts/12589662196).
Unzip and install the offline APK over **Private Vault Preview**, without
uninstalling or clearing data. Code7 keeps the existing Preview IDs and pinned
signing identity. Android 12/API31+ is required. Earlier Test/original apps still
have separate vaults; no automatic migration or portable recovery exists.
Actual same-key physical-phone update preservation remains a separate check.

Open **Assistant → Set up on-device AI** and choose the recommended **Qwen 3
0.6B** entry. Download/import its **347,251,840-byte** file, then run **Test local
model**. The previous 1.6 GB Qwen download is a different retired model; do not
reuse it. Its existing slot/receipt remains recognized and preserved until
explicit removal. Normal document questions/summaries remain unavailable while
M5's citation/intent/evaluation layer is pending. This update fixes the model
memory path; it does not complete the whole assistant.

The user reports that code6's memory check blocked their iQOO Neo 10 with 12 GB
physical RAM/256 GB storage and Android 16. That was admission failure before
loading, not a measured native failure on the phone. Smaller Gemma models
working in Google's app are consistent with the artifact/runtime choice being
the limitation, not the phone being inherently unable to run local AI.

### Concrete changes and actual results

The new exact INT4/no-thinking Qwen3 0.6B artifact exports 1280 context rather
than the old INT8 model's 4096. LiteRT-LM is pinned to 0.16.0; the official AAR
matches Google's published SHA-256. The adapter keeps two CPU threads and a
512-token combined budget, adds a native 128-output-token bound, disables
thinking/tools/cache and allows a five-minute operation for slower phones.
Initialization/hangs still lack a hard native interruption guarantee.

The compact profile has a 3 GiB Android-reported-total/1536 MiB currently
available admission setting, 64-bit and no low-memory state. A fresh check runs
inside the shared heavy-work gate immediately before native allocation. These
are artifact-specific experimental settings, not a universal 4 GB minimum. The
old 10/8 GiB profile is retired, not applied to every model or silently weakened.
A historical-runtime receipt regression verifies recognition/preservation and
explicit removal while leaving unknown files intact.

| Gate | Actual result |
| --- | --- |
| Local scripts/check-host.sh | PASS: 109 Python, 138 passing JVM XML cases (unchanged core 82 UP-TO-DATE, 28+28 platform fresh), no failures/skips; instrumentation compilation, lint, both assemblies and exact manifest/native checks |
| Stable CI 40168429140, attempt 1, job 121094145801 | PASS complete host and 68 Android executions (33 regression+1 authenticated lifecycle per variant), none skipped |
| Signatures/packages | PASS both Preview IDs, fixed public certificate, v2 verification and official 16 KiB zipalign |
| Offline/production policy | PASS actual offline APK has no INTERNET; ML Kit excluded from both production dependency graphs |
| Static native checks | PASS: 18 64-bit libraries/APK; 16 32-bit libraries remain unverified for 16 KiB behavior |
| Real compact-model probe 40168449811 | PASS one fresh test, 0 failures/skips; actual application admission and forced denial-before-initialization both exercised |
| Model integrity/narrow file canaries | PASS host/device size/hash, unchanged post-run model and baseline/final bounded canary scans |

The real model experiment used a disposable API 36 x86_64 emulator with 4096 MiB
nominal RAM. Android reported 4,014,272 KiB total and 2,457,980 KiB available before
the test, with 4096-byte pages. It generated 50 UTF-16 units in a 54,688 ms complete
operation, including initialization. A separate cancellation operation took
21,783 ms including its own initialization; 71 native cancel calls returned and
synchronous send failed once as expected. This is not cancellation-request
latency or phone speed evidence.

Numeric sampling collected 84 samples of PID 2701 with zero read/timeout/metadata
errors. Maximum sampled RSS was 2,630,602,752 bytes; observed kernel VmHWM was
2,653,343,744 bytes (~2.47 GiB). The old model's separate 10 GiB experiment observed
~8.14 GiB. These are exact experiment observations, not a controlled performance
benchmark, a hard memory bound or proof for all 4 GB ARM phones. Both real native
operations passed the model-specific admission check on the constrained target.

### Exact delivered identity

- Source: 0d213a5537e4dc7550ac3aadc312284c4efdec32.
- Built stable commit: f5c70f231d29c4d0be1993d2630516a66d195b68.
- Native marker: dbb46b0b56e7994d934b27a9fde8097dce2a8efc; same production source.
- [Stable CI](https://github.com/nikslogy/private-doc-ai/actions/runs/40168429140)
  completed/success; final job logs 25 October 2026 06:24:34 UTC.
- APK artifact 12589662196: 314,526,647 bytes; SHA-256
  4533edaecf41d4f020c4a6e2feeedd886dcf4c96db5de5141d8ea7d094e438a0;
  expires 1 November 2026 at 06:24:32 UTC.
- Existing signer certificate SHA-256:
  0a4f0f2f8fa9f25b20d43c3d2c407f05fd757e4b6cd080548146e5e1ca182467.
- Regression report 12589632064: 142,547 bytes, SHA-256
  ad7bba06a06dbcb3a5f3d33c7e2965728396e2a2048e877db43548d87c61a8b6.
  Independently downloaded/hash checked and parsed: 96 entries, 2 XML suites,
  66 cases, 0 failures/errors/skips; 2 additional lifecycle passes are log evidence.
- [Native report 12589781782](https://github.com/nikslogy/private-doc-ai/actions/runs/40168449811/artifacts/12589781782):
  8,264 bytes, SHA-256 265e7e328c7215085d807e092356c40e3dcc99de0b7ca8e59b11538a6579dbde,
  independently downloaded/hash checked/parsed; expires 1 November at 06:22:46 UTC.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| private-document-assistant-offline-debug.apk | 157165902 | 25cd358284c6ee7c919d47bedf21ffd40fd2bff7841cf68883b04da8db4da0ef |
| private-document-assistant-connected-debug.apk | 157165954 | 8d291239f343a9450202a15376357bf676d002eb883c01923dc25215bce25b17 |

APK identities/signatures/alignment are from actual CI logs and GitHub metadata;
the large APK ZIP was not independently downloaded locally. Small regression and
native reports were independently checked. No signing key was read locally, regenerated or committed, and no temporary-signing fallback exists. JVM XML was not
uploaded by stable CI. Local XML contains 138 passing cases: 82 unchanged core
cases reused UP-TO-DATE, plus 56 freshly executed platform cases. CI task
execution and authored source counts are recorded separately.

Evidence: [build checkpoint](checkpoints/2026-10-25-compact-model-build.json),
[native checkpoint](checkpoints/2026-10-25-compact-native-probe.json),
[local host](checkpoints/2026-10-25-local-host-build.json),
[public provenance/desktop smoke](checkpoints/2026-10-25-compact-model-provenance.json),
[decision 0007](decisions/0007-low-memory-model.md), [model instructions](MODEL_SETUP.md).
The desktop native smoke observed allocator-retained memory after engine close;
the Android experiment exercised a second native operation, but sustained repeated
use and process isolation remain open. This is a real constrained-emulator result,
not verified iQOO behavior, representative document accuracy or release readiness.

Next: verify the code7 same-key phone update, compact-model import/generation,
repeat after OCR, cancel/lock/restart and record exact APK/model/device identities,
available memory and latency under DEVICE_TESTS.md. Then implement M5's bounded
revision-pinned evidence/typed intent/verified cited QA and held-out injection/
language evaluation. Four GB remains a physical-device target until measured on
representative phones. Hard native isolation, full privacy/traffic/thermal checks,
real 16 KiB loading, representative accuracy and encrypted backup/recovery remain
pending. OCR tuning remains deferred by user request.

## Historical checkpoint 10 — verified installation and real native generation probe

[Download the stable-signed 0.4.1-dev APKs](https://github.com/nikslogy/private-doc-ai/actions/runs/39819743976/artifacts/12443389300).
Both APKs passed the full host gate and **66 Android test executions, none
skipped**, in stable run 39819743976, attempt 1. This validates the new model
installer and its synthetic storage/privacy cases. A separate real-model probe
also passed generation/cancellation and narrow hash/canary checks. Phone
compatibility and document QA remain unproven. General questions/summaries
remain disabled; the experimental model test uses only a fixed synthetic prompt.

Install the offline APK as an update to **Private Vault Preview** without
uninstalling or clearing storage. Version code 6 / 0.4.1-dev requires Android
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
[MODEL_SETUP.md](MODEL_SETUP.md) before testing. Native execution passed in two
fresh explicit emulator runs; installation
alone does not make document questions available. Code6 / 0.4.1-dev tightens
the CPU memory guard and has passed local and signed CI validation. Use this
update for phone model testing; code5's older guard admitted an insufficient
memory state. OCR production behavior is unchanged, and tuning remains deferred
by user request.

## Exact delivered build and validation

- Source: f2f5c9274617111b96001d48a70c4f9d8d8a9947.
- Built commit: 44fd595dab8bbed9bd996e88e2c99746b990e079.
- [Stable run 39819743976](https://github.com/nikslogy/private-doc-ai/actions/runs/39819743976),
  attempt 1, job 119967552372; completed/success at 22 October 2026 17:15:37 UTC.
- APK artifact 12443389300: 309,771,448 bytes; expires 29 October at 17:15:28 UTC.
- ZIP SHA-256: 0d7d03715d24711f80d50328e75aba8012d6e8188db3787f40a229c20c39de63.
- Both v2 signatures match the existing public certificate SHA-256:
  0a4f0f2f8fa9f25b20d43c3d2c407f05fd757e4b6cd080548146e5e1ca182467.
- [Regression reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39819743976/artifacts/12443189699):
  116,654 bytes, ZIP SHA-256 6945abf139474c41a567c4a6796bd9f43895a384872c6a12f72f980de72beb01.
- [Machine-readable checkpoint](checkpoints/2026-10-22-model-setup-build.json).

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| private-document-assistant-offline-debug.apk | 154788418 | 99b72b755409ffbb9c92fe2907bf88ea4a6e9a00feb037b6624470c5663e1430 |
| private-document-assistant-connected-debug.apk | 154788470 | 228ffd76e3ccf558cee90dc4f5d04eb864968481131f4fbf7a6746338a8ce0fa |

APK identities/signatures are evidence from actual CI logs and GitHub metadata;
the APK ZIP was not independently downloaded locally. The regression ZIP was
independently downloaded, hash checked and parsed: 32 XML cases per variant,
zero failures/errors/skips. Two additional credential-bound lifecycle passes
are evidenced by actual instrumentation logs, giving 66 executions total.
User-owned signing secrets remain excluded local backups/repository Actions
secrets; no key was regenerated or published. No temporary signing fallback exists.

| Check | Actual delivered-source result |
| --- | --- |
| Python fixture/native/validation/Java/APK/OCR/signing checks | PASS: 97 cases |
| Source policy | PASS |
| :core:test | PASS task; 82 authored cases |
| Both platform JVM tasks | PASS tasks; 24 authored cases per variant in code6 |
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
All 66 Android executions used an explicit API 36 x86_64 emulator, with no skips.
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
package exit-info/crash diagnostics were used in subsequent synthetic CI runs.
Physical-target scripts do not collect these crash logs. The earlier emulator
had 4 GiB RAM; its crash remains unattributed. The final target used 10 GiB nominal
RAM and required 12 GiB measured host availability. Nominal emulator RAM differs
from Android-reported total memory. This direct runtime probe does not exercise
the phone UI's independent capability guard. Neither setting is a measured model
minimum or phone proof.

## Code6 source checks and historical next task

A bounded explicit GET bootstrap now verifies the same pinned public OCR AAR
before atomic publication in an ignored exact-coordinate local Maven cache.
It preserves the configured proxy/CA and rejects redirects, wrong size/hash and
symlinks. Only transient transport/404/429/5xx failures receive three bounded
attempts. The mandatory Gradle pre-build hash check remains unchanged. No
verified official alternate mirror was established and no dependency pin changed.
The actual local GET verified all 12,848,454 bytes and the existing SHA-256.
Twenty new synthetic tests passed, raising routine Python checks from 77 to 97.
The code5 stable run independently verified this bootstrap and passed all 97
Python checks plus its full host/66-case Android gate. Code6 repeated these
gates with the stricter memory policy and a new version; its APK bytes differ.

Pinned SDK/Gradle bootstrap now works locally. The first full local attempt
failed at the missing javac in the preinstalled runtime; the next exposed the
new JDK's missing environment CA. A published-checksum-verified Temurin JDK21
using the existing system Java trust store resolved both without disabling TLS.
The full code6 scripts/check-host.sh run then passed: 97 Python checks,
130 actual JVM cases (82 core + 24 per platform variant, no failures/skips), all
four instrumentation Kotlin compilations, lint, assemblies, production dependency
checks, manifests and native checks. The independently parsed local JVM XML and
exact environment scope are recorded in the
[local host checkpoint](checkpoints/2026-10-22-local-host-build.json).
Local APKs are diagnostic .dev builds, not the stable signed user delivery.
KVM is absent locally; no local device suite or native inference pass is claimed.

The 6 GiB nominal native probe 39814089348 compiled with the verified GET cache,
verified all model bytes on host and device, and passed its baseline canary scan.
Android then killed PID2985 for reason3 LOW_MEMORY before generation started.
Actual pre-test MemTotal was 6,072,516 KiB and MemAvailable 4,457,868 KiB. This is
an observed memory failure for this experiment, not evidence about the earlier
4 GiB crash or a phone minimum. The scoped 6,890-byte diagnostic ZIP was downloaded
and independently SHA-256 checked. A larger bounded target subsequently passed. A smaller public candidate
assessment is retained as [research only](decisions/0006-smaller-cpu-candidate.md);
no additional model was registered or tested.

The subsequent [10 GiB nominal probe](checkpoints/2026-10-22-native-model-probe.json)
39816778021 passed exactly one fresh non-skipped real generation/cancellation
test. It generated 111 UTF-16 units in a 58,576 ms full operation, including
initialization. The separate cancellation operation took 17,295 ms including
its own initialization; 204 native cancellation calls returned and synchronous
send failed once before cancellation returned. These are emulator operation
observations, not phone latency or semantic-quality claims. Model bytes/hash
were unchanged and both narrow canary scans completed. Actual pre-test guest
MemTotal was 10,188,980 KiB and MemAvailable 8,492,668 KiB. The 7,208-byte report
ZIP was independently downloaded/hash checked. Its memory sampler captured
zero samples because tr buffered the phase/PID stream; zero is absent evidence,
not a measured RSS peak. The line-buffering fix was validated and repeated in
run 39819779900, which passed one fresh real test with no skips. It generated
111 UTF-16 units in a 79,135 ms complete operation; the separate cancellation
operation took 16,700 ms, including its own initialization. Native cancellation
returned successfully 242 times and synchronous send failed once as expected.
Model hash and both narrow canary scans passed. The sampler captured 110
numeric process samples with zero read/timeout/metadata errors: observed max
RSS 8,704,966,656 bytes and kernel-reported VmHWM 8,742,727,680 bytes (~8.14 GiB).
Pre-test guest MemTotal was 10,188,992 KiB and MemAvailable 8,547,288 KiB. The
8,356-byte report was independently downloaded and SHA-256 checked. These are
x86_64 emulator process measurements, not a physical-phone RAM minimum. The
direct runtime probe bypasses the separate UI memory guard.

Code6 source adds a reviewed-artifact/runtime-only CPU policy: at least 10 GiB
Android-reported total memory and 8 GiB currently available, 64-bit and no low
memory state. Unknown profiles fail closed; stricter catalogue requirements
remain binding. The UI checks before model verification and again immediately
before native load, showing actual/required memory when denied. These are
conservative application settings for a 12 GB class phone, not measured model
minimums or a compatibility promise. Eight new policy cases passed per variant
(24 platform JVM cases each). The full code6 host run then passed all 97
Python checks, 130 actual JVM cases (82 + 24 + 24, zero failures/skips), all
instrumentation Kotlin compilation, lint, assembly and exact manifest/native
checks. The stable signed CI run passed its complete host/66-case Android gate
and published the code6 APKs. The measured-memory repeat also passed as above.

Next: run the phone import/tamper/cancel/lock/restart and same-key update
procedure in DEVICE_TESTS.md, recording exact APK/model identity and actual
memory/latency. The reported iQOO Neo 10 / Android 16 has 12 GB physical RAM
and 256 GB storage, according to the user; it has no independently measured
inference result. The high emulator memory observation warrants a cautious
synthetic phone test, not a compatibility promise. M5's next implementation is
revision-pinned cited questions/summaries with typed intent and injection
controls; user-visible QA remains gated on device and held-out evaluation.
Installation does not complete M4. M6 tools and M7 portable encrypted
backup/recovery follow.

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
