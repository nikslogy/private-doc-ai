# Development status

## M4 model setup implementation in progress — 22 October 2026

New source adds a model manager, verified SAF installation into no-backup storage,
scoped immutable model leases and real CPU inference with no tools/cache, a joined
cancellation watchdog and fixed synthetic diagnostic input. The public legacy
Qwen artifact is registered only as experimental with empty evaluated-language/
task lists; normal document questions/summaries remain disabled. Immutable upstream
metadata and the pinned runtime README support this candidate; the later artifact
has different bytes and requires a newer runtime. See decision 0005's correction.

Untrusted selected bytes are staged with Tink Streaming AEAD using a fresh key
held only in memory. Only after the approved public size/hash match are they
authenticated/decrypted into plaintext public weights and checked again before
atomic publication. Wrong private documents therefore cannot reach plaintext disk.
Installation needs room for encrypted staging plus the public model copy. Model
leases serialize verification/native work with removal. No model weights are
bundled with the app or downloaded by routine checks. User import requires
current licence acknowledgement; vault originals/keys/text never enter the test.

First M4 stable run 39759673652 failed app Kotlin compilation at a missing closing
brace in the provider-stream use block. No APK/reports were uploaded. All 77
Python/source checks, :core:test and both platform production/instrumentation
Kotlin compilations passed; new platform test execution/full host/Android suites
were not completed. The brace is corrected and a fresh stable run is required.
The separate real-model probe 39759683730 compiled the offline test APK and
independently verified all 1,597,931,520 public model bytes against the reviewed
SHA-256. Its one test failed before native loading because a strict raw-path
equality check rejected an Android app-data alias. No generation/cancel pass is
claimed. The test now anchors canonical containment to the context-owned probe
directory, rejects leaf/probe symlinks and requires a controlled filename. Hash
and canary checks are unchanged; a fresh model probe is required.

Local check-host passed 77 Python/source checks, then stopped at missing pinned
Gradle/SDK. New platform JVM metadata cases (8; 16 authored total per variant),
10 real-filesystem installer tests and one in-flight private-canary regression
await CI. Standard Android selection is now 32 cases plus one real-credential
lifecycle case per variant (66 total), not yet executed for this source. A
separate opt-in public-model download/real generation/cancellation probe is ready.
No new model or phone result is claimed. Code 5 / 0.4.0-dev awaits validation;
the verified downloadable build below remains code 4. The conservative 6 GiB/
64-bit/free-memory UI check is configuration, not a measured compatibility claim.

Checkpoint 9: 22 October 2026 — selected-source document facts development APKs ready.
**Full host checks and 44 non-skipped Android test executions passed.**

## Install and use

[Download both stable-signed APKs, checksums and reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39729671003/artifacts/12404384786).
Unzip and install private-document-assistant-offline-debug.apk as an update to
**Private Vault Preview**. Version code 4 / 0.3.0-dev requires Android 12/API31+.
The connected version adds the -connected suffix. Preview IDs and signing key
are unchanged; do not uninstall, clear data or reset a vault. Earlier Test/original
apps retain separate vaults; no automatic migration or portable recovery exists.
An actual same-key update preserving a synthetic vault still needs phone testing.

Open **Assistant → Document facts**, choose a document, inspect supported English
label:value fields and open their source pages. Identifiers, printed dates and
exact monetary formatting are retained. Premium, pre-tax premium, tax, insured
value, sum insured and deductible stay distinct; generic owner/total/subtotal
labels stay generic. Different raw values remain visibly conflicting. Unsupported
wording/layouts may not match. Partial or unread text warns that values/conflicts
may be missing. These are literal source matches, not verified policy validity
or AI answers. See [DOCUMENT_FACTS.md](DOCUMENT_FACTS.md).

The user reports the app working after the OCR delivery and asks to defer OCR
tuning. This is a manual report, without an independently verified phone APK hash,
and does not replace the physical-device suite. OCR production code is unchanged.

| Variant | Permanent ID | Launcher |
| --- | --- | --- |
| Offline | com.privateassistant.app.preview | Private Vault Preview |
| Connected | com.privateassistant.app.connected.preview | Private Vault Connected Preview |

## Verified build identity

- Source: 7d9badf3f55fb0b320f5013e676b802f091a24dd.
- Built commit: 3089d1e7d0011abba93275b650be87e419f238c0 on main.
- [Stable run 39729671003](https://github.com/nikslogy/private-doc-ai/actions/runs/39729671003),
  attempt 1, job 119661577642; completed 22 October at 05:53:22 UTC / 11:23:22 IST.
- APK artifact 12404384786: 309,301,513 bytes; expires 29 October at 05:53:17 UTC.
- ZIP SHA-256: 0b46a1eedee4d1c30625985b965cc34d61eb277db9148fc325345f124c77b467.
- Both APK v2 signatures match the existing public certificate SHA-256:
  0a4f0f2f8fa9f25b20d43c3d2c407f05fd757e4b6cd080548146e5e1ca182467.
- [Android reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39729671003/artifacts/12405321545):
  118,320 bytes, ZIP SHA-256 a626f0ef49446ac08fd0ee4e1997a7125820217977de93eec6ec9117d5cfe186.
- [Machine-readable build record](checkpoints/2026-10-22-document-facts-build.json).

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| private-document-assistant-offline-debug.apk | 154555112 | 53d09cf0807c1e97c638cec190f50824de6ac535efe43bc5d0d7977cdd8ec3fb |
| private-document-assistant-connected-debug.apk | 154555164 | 9b92d8ca96764210b391175e7dfb387e3dc9e5a71de4da17ab58c3ac5659eeae |

Evidence was verified through CI logs and GitHub metadata, not an independent
local ZIP-byte download. SHA256SUMS, APK_MANIFEST.json, signer and exact-byte
inspection reports accompany the APKs. User-owned signing secrets remain in
repository Actions secrets and excluded local backup; no key was regenerated,
published or cached. Stable delivery has no temporary-signing fallback.

## Changes and actual validation

The controller rejects an unavailable selected revision instead of following a
moving source. The bounded core grammar returns validated exact revision/page/
UTF-16 line citations. The UI validates evidence again, pins the explicit
selection across page inspection, and rejects delayed results after a source or
session change. Lock/delete/reindex clear facts. Values persist only in unlocked
memory; originals and encrypted database schema are unchanged. No model,
generated answer, remote processing or telemetry was added.

| Check | Actual result |
| --- | --- |
| Python fixture/native/validation/Java/APK/OCR/signing checks | PASS: 77 cases |
| Source policy | PASS |
| :core:test | PASS; 82 authored cases, including 27 new facts cases |
| Platform JVM tasks, both variants | PASS; 8 authored validator cases each |
| App JVM tasks | NO-SOURCE; no app JVM coverage claimed |
| Both modules/variants instrumentation Kotlin compilation | PASS |
| Production compilation, Room, lint and dependency inventory | PASS; ML Kit excluded from production |
| Both assemblies and exact staged manifest/package checks | PASS; offline has no INTERNET |
| Native static 16 KiB/alignment | PASS: 18 64-bit libraries/APK; 16 32-bit libraries remain unverified |
| Official apksigner/zipalign and fixed certificate pin | PASS for both APKs |
| SQLCipher3 + migration3 + Tink4 + durability2 per variant | PASS: 24 Android executions |
| OCR boundary3 + actual Tesseract6 per variant | PASS: 18 Android executions |
| Real-credential Keystore lifecycle1 per variant | PASS: 2 Android executions |
| APK/report uploads | PASS |

All 44 Android executions used explicit API36 x86_64 emulator-5554, with zero
failures/skips. The augmented authenticated lifecycle checks pinned reads, wrong
revision/locked rejection, exact field citations, conflicting premiums, leading
zeros/raw dates, encrypted reopen consistency and unchanged actual original
ciphertext/bytes. Core tests additionally cover precision/grouping, unsafe or
unsupported numbers, injection-like prose, Unicode offsets and resource bounds.
A long grouped numeric token now skips safely while later valid fields remain.

These tests do not establish Compose UI races, representative financial QA,
physical fingerprint/vendor behavior, full check-device.sh, active native
cancellation, comprehensive privacy/traffic or release readiness. Existing
Tesseract probes cover only synthetic English/Devanagari recognition, mixed
native/raster PDF, blank reset, pre-start cancellation, model tampering and a
narrow app-files/cache/noBackup canary/hash check.

Local scripts/check-host.sh passed all 77 Python cases and source policy, then
failed at missing pinned Gradle/Android SDK prerequisites. Local Android
compilation/execution is not claimed; the GitHub runner supplied actual evidence.

## Earlier attempts and test isolation

[Initial M3 run 39727560530](https://github.com/nikslogy/private-doc-ai/actions/runs/39727560530)
passed full host and 44 Android executions. It was superseded by the numeric
boundary fix and extra regression test.

[Run 39727845022](https://github.com/nikslogy/private-doc-ai/actions/runs/39727845022)
passed host/unit/lint/assembly and package/signature checks, then failed the OCR
file snapshot: AndroidX delayed startup created files/profileInstalled between
snapshots. Pinned model hashes were unchanged. All 21 offline cases executed,
one failed, none skipped; later suites did not run and no APK was uploaded.
[Failure reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39727845022/artifacts/12404182778)
retain that result.

The fix removes only ProfileInstallerInitializer metadata in the synthetic test
APK. The English probe checks actual installed startup metadata before its file
baseline/pixels. Every file/canary/hash assertion remains intact; production
startup and OCR code are unchanged. AndroidX's official
[manifest](https://github.com/androidx/androidx/blob/androidx-main/profileinstaller/profileinstaller/src/main/AndroidManifest.xml),
[initializer](https://github.com/androidx/androidx/blob/androidx-main/profileinstaller/profileinstaller/src/main/java/androidx/profileinstaller/ProfileInstallerInitializer.java)
and [verifier](https://github.com/androidx/androidx/blob/androidx-main/profileinstaller/profileinstaller/src/main/java/androidx/profileinstaller/ProfileVerifier.java)
document the opt-out, delayed write and profileInstalled marker. Reviewed Git
blobs respectively: a417d4ac8ff0d2204e942d6535488ed23e58c7ee,
c2e3c3bb5b892be483d4e2b920fce09c5d1caff0,
4e105910d0983f4335763d2a744f507a96142d30.
The fresh final run passed the complete suite after this deterministic isolation.

The [stable OCR build record](checkpoints/2026-10-22-stable-ocr-build.json),
[earlier OCR build](checkpoints/2026-10-22-ocr-build.json),
[OCR provenance](checkpoints/2026-10-22-ocr-input-provenance.json) and
[first-unlock repair](checkpoints/2026-10-22-first-unlock-repair.md) retain prior
identities, failures, fixes and actual narrower evidence.

## Next concrete task and remaining gates

Implement M4's verified SAF model installer: bounded staging, reviewed expected
size/hash, explicit licence acknowledgement, atomic publication, tamper/cancel
cleanup and verification before load. Add the real LiteRT-LM generation/native
cancellation/release adapter with bounded excerpts and no automatic tools. Keep
normal document QA unavailable until an approved artifact and actual device
inference/privacy/cancellation evidence exist. [Decision 0005](decisions/0005-local-model-candidate.md)
records authoritative candidate research: Gemma's checksum/licence gate remains
unresolved; the public Qwen artifact requires a separately reviewed runtime upgrade.
No weights were downloaded and config/models.json remains empty.

Then M5 adds evaluated cited questions/summaries, M6 document tools, and M7
portable encrypted backup/recovery. This narrow label reader does not complete
full M3 retrieval/ambiguity evaluation or any model QA milestone.

Run the new [phone facts/update procedure](DEVICE_TESTS.md) and full explicit-target
device suite. Verify same-key update preservation, source changes/lock races,
synthetic originals and real phone layouts. Absence is never a passing suite.
OCR tuning is deferred by user request. Remaining M0/M1/M2 gates include real
16 KiB loading, physical authentication/lifecycle, active cancellation/hard native
isolation, complete native provenance/notices, comprehensive filesystem/log/
connected traffic/memory/thermal checks, encrypted thumbnails and remaining
import validation. Representative accuracy needs held-out labelled evaluation
and the plan's two physical phone tiers. Recovery remains unavailable. No
app-store release, cloud fallback or remote private processing was enabled.
