# Development status

## M3 document-facts update in progress — 22 October 2026

The user reports the Preview app is working and asks to defer OCR tuning.
This is a manual report, not a completed physical-device suite. The next source
adds an explicit document/revision picker and conservative English labelled
fields with exact quotes/page links, distinct monetary meanings, visible conflicts
and partial-text warnings. Facts clear on lock/delete/reindex and persist only in
unlocked memory. No model, AI answer, remote processing or schema migration was
added. See [DOCUMENT_FACTS.md](DOCUMENT_FACTS.md). Source version is now code 4 /
0.3.0-dev; the latest verified/downloadable APK below is still code 3 until the
new stable delivery gate passes.

Local scripts/check-host.sh passed all 77 Python cases and source policy checks,
then failed at absent pinned Gradle/Android SDK prerequisites. New Kotlin tests,
UI compilation/lint and augmented real-credential Android lifecycle checks await
the GitHub runner. No local Android result is claimed.

M3 source d45201d3c21a0367954729fe34f4273796b9c9f7 passed full host and 44 Android
executions in run 39727560530. A review fix then added safe rejection of oversized
grouped monetary tokens and a 27th facts test (82 core cases total). Its run
39727845022 passed host/unit/lint/assembly and exact package/signature checks,
but failed the offline OCR file snapshot: unrelated AndroidX startup asynchronously
created files/profileInstalled between snapshots. Model hashes were unchanged;
21 offline tests ran with one failure, no skips; later suites did not run and no
APK artifact was uploaded. The next test APK removes only the documented
ProfileInstallerInitializer startup metadata and asserts its actual installed
absence before supplying synthetic pixels. All file/canary/hash assertions and
production startup remain unchanged. A fresh full stable run is required.

Checkpoint 8: 22 October 2026 — stable-signed offline OCR development APKs ready.
**Full host checks and 44 Android test executions passed. New Preview apps install
alongside existing apps and start separate vaults. Keep the existing apps/data.**

## Install and use OCR

[Download both stable-signed APKs, checksums and reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39724441904/artifacts/12402717830).
Extract private-document-assistant-offline-debug.apk and open **Private Vault
Preview**. Android 12/API31+ is required. After importing a PDF/photo, open its
page evidence and choose **OCR · English** or **OCR · Hindi / Marathi**.
Native PDF text remains preferred; only missing text is recognized. Review OCR
before relying on important values. Search uses the encrypted recognized text.

| Variant | Permanent preview ID | Launcher |
| --- | --- | --- |
| Offline | com.privateassistant.app.preview | Private Vault Preview |
| Connected | com.privateassistant.app.connected.preview | Private Vault Connected Preview |

Version code 3 / 0.2.0-dev (connected suffix -connected). These IDs use a persistent
user-owned signing identity, verified against a committed public certificate pin.
They have separate vaults from the earlier .dev and original apps. There is no
automatic migration. Do not uninstall, clear data or reset any existing vault.
Future builds retain the preview IDs/key; an actual update-preserves-vault device
check remains pending. Keep source documents because recovery is unavailable.

## Verified build identity

- Source: 2420baface2aa061d0e22ff25fcbae404965a311 on main.
- [Stable delivery run 39724441904](https://github.com/nikslogy/private-doc-ai/actions/runs/39724441904),
  attempt 1; job 119645033677. Completed 22 October 2026 at 10:27 IST (04:57 UTC).
- APK artifact 12402717830: 309,071,567 bytes; expires 29 October at 10:27 IST.
- ZIP SHA-256: 4a0cb276ed4be6ae5189207e7a0943e20e2307e6a78056af05be0440918ab8dc.
- Both APK v2 signatures match public certificate SHA-256:
  0a4f0f2f8fa9f25b20d43c3d2c407f05fd757e4b6cd080548146e5e1ca182467.
- [Android reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39724441904/artifacts/12402882499)
  retain XML, real-credential test transcripts and resolved emulator/target metadata.
- [Full build record](checkpoints/2026-10-22-stable-ocr-build.json) contains exact hashes and scope.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| private-document-assistant-offline-debug.apk | 154440449 | 0d83672e44869b17c8d6abc820b38f28ecb155648c4b73e7c6cd28c827adaa30 |
| private-document-assistant-connected-debug.apk | 154440497 | f3bf3e773a64e290adda0d4321f48e8ece404a27d8ff64054a86d55b66493481 |

Evidence was verified through CI logs and GitHub metadata, not an independent
local ZIP-byte download. SHA256SUMS, APK_MANIFEST.json and exact-byte inspection
reports accompany the APKs. Stable delivery has no temporary-signing fallback.
The user saved both GitHub secrets; the runner verified the real input key with
keytool and each final APK signer. Private signing files are excluded from Git,
source packages, caches and uploaded artifacts. Keep the user's protected backup.

## Changes and actual validation

Tesseract4Android 4.9.0 now handles bounded memory bitmaps with exact-byte-pinned
English/Hindi/Marathi tessdata_fast files. Downloads occur during builds, not
in the app. Models are public; document pixels/text never go through the model
store. Each page gets a fresh engine, supported output/debug suppression checked
before pixels, a cooperative stop/deadline, exact line offsets and source boxes.
The indexer validates identity/script/geometry/limits before encrypted persistence.
Failed/empty retries retain previous useful OCR; originals remain immutable.
ML Kit stays instrumentation-only and absent from both production graphs.

| Check | Actual result |
| --- | --- |
| Python fixture/native/validation/Java/APK/OCR/signing checks | PASS: 77 cases |
| Source privacy/pinning policy | PASS |
| :core:test | PASS; 55 authored cases |
| Platform JVM tasks, both variants | PASS; 8 authored validator cases each |
| App JVM tasks | NO-SOURCE; no app JVM coverage claimed |
| Both modules/variants instrumentation Kotlin compilation | PASS |
| Production compilation, Room, lint and dependency inventory | PASS; ML Kit excluded |
| Both assemblies and exact staged manifest/package/native checks | PASS; offline has no INTERNET |
| Native static 16 KiB/alignment | PASS for 18 64-bit libraries/APK; 16 32-bit libraries remain unverified |
| Official apksigner/zipalign and fixed certificate pin | PASS for both APKs |
| SQLCipher3 + migration3 + Tink4 + durability2 per variant | PASS: 24 Android executions, none skipped |
| OCR boundary3 + actual Tesseract6 per variant | PASS: 18 Android executions, none skipped |
| Real-credential Keystore full vault lifecycle1 per variant | PASS: 2 Android executions, none skipped |
| APK/report uploads | PASS |

These 44 Android executions used explicit API36 x86_64 emulator-5554. Synthetic
English identifiers/premium, a Devanagari word, mixed native/raster PDF, blank
reset, pre-start cancellation, model tampering and limited unchanged app-private
file canaries passed. They are not representative language accuracy, full privacy,
physical fingerprint/UI or complete check-device.sh evidence. Native init/hangs
cannot be forcibly terminated safely; hard isolation remains pending.

The separate [source validation run 39723881468](https://github.com/nikslogy/private-doc-ai/actions/runs/39723881468)
also passed 77 Python/full host/44 Android checks and actual preview-mode assembly
with a disposable signing identity, verifying IDs/labels and rejection of its
unpinned signer. Those synthetic preview APKs were not published.
Local check-host passed 77 Python/source checks then stopped at absent pinned
Gradle/SDK prerequisites. No local Android execution is claimed.

## Earlier evidence

The user confirms the previous repair opens/unlocks and imports on iQOO Neo 10 /
Android 16. That report concerns the .dev repair, not this new Preview OCR build.
[Repair checkpoint](checkpoints/2026-10-22-first-unlock-repair.md) preserves its
26 Android executions and hashes. [OCR debug build record](checkpoints/2026-10-22-ocr-build.json)
preserves successful OCR validation before stable signing. The first OCR attempt
failed at JitPack POM lookup; artifact-only lookup with mandatory actual-AAR hash
verification fixed it. [OCR provenance](checkpoints/2026-10-22-ocr-input-provenance.json)
and [decision 0004](decisions/0004-local-tesseract-ocr.md) record actual public input
bytes/licences and source-to-binary provenance limits.

## Next concrete task

Validate and deliver the code-4 M3 document-facts update with the existing Preview
IDs and signing identity. Then implement M4 verified on-device model management,
actual inference and cancellation before M5 cited questions/summaries. General
AI remains unavailable while config/models.json is empty. Remaining M3 retrieval
evaluation and follow-up/ambiguity cases are tracked separately from this narrow
label reader; no full milestone or QA accuracy claim is made.

The full explicit-target device suite and same-key APK update preserving a
synthetic vault remain pending. Record actual outcomes; absence of a device
is never a pass. OCR tuning is deferred by user request. Remaining accuracy,
cancellation/privacy/hardware gates still apply. Measure English/Hindi/Marathi accuracy, active
cancellation, memory/thermal behavior, log/filesystem/connected traffic canaries
and actual 16 KiB loading. Finish complete native provenance/notices and M0/M1
hardware/privacy gates; encrypted thumbnails and remaining M2 validation are open.

AI answers/summaries remain unavailable: config/models.json is empty and no
on-device generation engine is shipped. Product steps continue with M4 verified
local model management/inference, M5 cited Q&A
and summaries, M6 document tools and M7 encrypted backup/recovery. No remote
processing, telemetry, cloud fallback or app-store release was enabled.
