# Development status

Checkpoint 7: 22 October 2026 — offline OCR built and Android-verified.
**English and Devanagari synthetic OCR tests pass. Both APKs are built, but their
temporary signer cannot update the user's installed repair. Keep existing apps/data.**
Stable preview signing support is being validated; actual stable delivery needs
one-time repository secrets. AI questions/summaries remain unavailable.

## What changed

- Added a separate Tesseract4Android 4.9.0 adapter for memory-only bitmap OCR.
  ML Kit remains instrumentation-only and excluded from both production graphs.
- Bundled English, Hindi and Marathi tessdata_fast assets are downloaded only
  during builds, with immutable revision, exact sizes and actual SHA-256 pins.
  No weights are committed. The app verifies installed public model bytes before
  native initialization; no document or vault key enters the model store.
- Explicit English or Hindi/Marathi recognition actions handle missing PDF text
  and images. Existing native PDF text stays preferred. Recognized lines carry
  exact UTF-16 offsets and source coordinates into the encrypted evidence/search
  index. Originals remain immutable.
- Added result validation, bounded spans, per-page failure, cancellation
  propagation and retention of prior useful OCR after failed/empty retries.
- A fresh native engine per page checks supported debug/output suppression before
  private pixels. Cooperative stop/deadline does not forcibly terminate hung
  native initialization/segmentation; process isolation remains pending.

## Evidence actually obtained

[Public-input probe](https://github.com/nikslogy/private-doc-ai/actions/runs/39718922514),
job 119627600961, succeeded from 3b75dc1dce24d838e858f236162e93cfa4cbb14c.
Actual AAR/model bytes, manifest, dependency metadata and native inventory are
recorded in [the provenance report](checkpoints/2026-10-22-ocr-input-provenance.json).
The AAR is now consumed without POM/transitive dependencies, through a narrow
repository rule and a mandatory exact-byte pre-build check. Its source JAR was
unavailable; reproducible native-build provenance is not claimed. The 64-bit
OCR ELF libraries have 16 KiB alignment; 32-bit ones have 4 KiB alignment.

[Successful OCR host/Android run 39720899685](https://github.com/nikslogy/private-doc-ai/actions/runs/39720899685)
built source 8a5f0d39a8a6feec8cdf5d50f8b5ed9325cfb59d, job 119633835266.
Completed 22 October 2026 at 09:49 IST (04:19 UTC). Full host checks passed:
71 Python checks, source policy, core tests, both platform JVM tasks (8 authored
validator cases each), all instrumentation compilation, lint, dependency inventory
and both assemblies. App JVM tasks remain NO-SOURCE. Both APKs passed exact
manifest/native/signature/alignment checks; offline has no INTERNET permission.

The explicit API36 x86_64 emulator ran 21 non-skipped cases plus one real-credential
lifecycle case per variant: **44 Android test executions passed**. These include
six actual OCR cases and three boundary cases per variant, plus SQLCipher,
migrations, Tink and durable files. Real English identifiers/premium, a synthetic
Devanagari word, mixed native/raster PDF, blank-state reset, pre-start cancellation,
model tampering, and limited unchanged app-private-file canaries passed. This is
not a representative OCR score, full physical suite, active-cancel latency or
complete log/filesystem/traffic privacy proof. OCR has not been tried on the user's phone.

[OCR APK build evidence](https://github.com/nikslogy/private-doc-ai/actions/runs/39720899685/artifacts/12401805689)
and [Android reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39720899685/artifacts/12401571155)
are retained for seven days. The ZIP is 309,070,788 bytes, SHA-256
e270532d74dd6a62900a5939d8ca016e9569c68d57ff1d83a7f9c4d29fcdafa7.
Version 0.2.0-dev / code 3 keeps the .dev IDs but has a different signer from the
installed repair. Do not try to resolve the conflict by uninstalling or clearing data.
Exact APK hashes/certificate and gate evidence are in
[the build record](checkpoints/2026-10-22-ocr-build.json). Evidence was verified
through logs/metadata, not an independent local ZIP download. 64-bit static
16 KiB checks passed for 18 libraries; 16 additional 32-bit libraries remain
runtime/16 KiB unverified.

Local scripts/check-host.sh passed its Python/source checks, then stopped at
missing pinned Gradle/SDK prerequisites; Android verification used GitHub.
The first source attempt 39720562663 failed at unreliable JitPack POM lookup.
Artifact-only metadata plus the mandatory actual-AAR hash gate fixed it.

Stable signing support now uses a pinned public certificate and separate
.preview app IDs/launcher names, with no temporary-key fallback. Six new
synthetic signing tests pass locally, and the actual PKCS12 public identity
was verified locally without printing secrets. A disposable-key CI build check
will verify both preview IDs/labels and rejection of an unpinned signer.
No actual stable-key APK or update-preserves-vault result is claimed yet.
See [the one-time setup](DEVELOPMENT_SIGNING.md). Private key/password files
exist only in the ignored private workspace; only the public fingerprint is committed.

## Last working phone build

[Repair APKs](https://github.com/nikslogy/private-doc-ai/actions/runs/39715674766/artifacts/12399575330)
from source 850a87567dba8cc686539419110ca0eae7d5baad (version 0.1.1-dev, code 2).
The user confirms unlock/login and document import on iQOO Neo 10 / Android 16.
This is a manual report, not the complete physical-device suite. All host gates
and 26 selected Android test executions passed for that build. Full evidence,
checksums and failure history are in [the repair checkpoint](checkpoints/2026-10-22-first-unlock-repair.md).

Keep installed apps and documents. CI debug signing keys are ephemeral and
excluded from caches; the next generated APK cannot be assumed to update the
installed copy. Stable signing and safe install delivery remain an explicit
follow-up; do not uninstall/clear data/reset keys to fix a signature mismatch.
Recovery is not implemented, so retain source documents and use synthetic data.

## Next concrete task

Finish the stable preview build-configuration CI check. Then the user must add
the two prepared values as GitHub repository Actions secrets; the current GitHub
connection cannot write secrets. Trigger the main-only stable delivery workflow,
verify both signers against the public pin, run all host/Android gates, and test
that a subsequent same-key update preserves a synthetic vault. Deliver the
preview APKs with separate app IDs; preserve the user's existing apps/data.
Then measure physical OCR quality, active cancellation, memory, logs/files/traffic
and actual 16 KiB loading.

M0/M1 remain open for hardware, provenance/notices and security checks. M2 now
has an actual candidate local OCR implementation; encrypted thumbnails and full
runtime validation remain. AI answers/summaries are unavailable: config/models.json
is empty and no generation engine is shipped. Next product steps remain M3
retrieval/fields/selection, M4 verified local model management/inference, M5 cited
questions/summaries, M6 file tools and M7 encrypted backup/recovery. No remote
processing, telemetry, app-store publication or cloud fallback was enabled.
