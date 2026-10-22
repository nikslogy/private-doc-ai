# Development status

Checkpoint 7: 22 October 2026 — production offline OCR implementation in progress.
**The installed first-unlock repair works on the user's phone. New OCR code is
written; Android compilation/recognition verification is pending. No new APK
is claimed ready yet.**

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

Locally, scripts/check-host.sh passed all 71 Python checks and source policy,
then failed prerequisite checks because pinned Gradle/Android SDK tools are
absent. No local Kotlin, lint, assembly or Android execution is claimed.
The authorized GitHub runner will execute the full gate and actual synthetic
OCR/vault regressions. Eight new JVM validator cases, three boundary Android
cases and six actual OCR Android cases are authored, not yet passing evidence.

Source 62613d1bdf923142b0dd5114e08df4f4c9cd69a2 is published on feat/offline-ocr.
[Host/Android run 39720562663](https://github.com/nikslogy/private-doc-ai/actions/runs/39720562663)
failed before Android compilation: Gradle looked for an unreliable JitPack POM
despite the explicit AAR dependency. All 71 Python checks, source policy and
model-asset verification passed. Repository metadata is now artifact-only;
the mandatory exact-byte AAR verification remains. A fresh full run is required.

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

Run and repair the full GitHub host gate plus non-skipped API36 OCR/vault tests
for both variants. Record exact source/build/artifact identities and any failures.
Then complete stable update signing before phone delivery and measure OCR on
physical devices, including Devanagari quality, active cancellation, memory,
logs/files/connected traffic and actual 16 KiB loading. Synthetic word checks
are not representative OCR accuracy or full privacy evidence.

M0/M1 remain open for hardware, provenance/notices and security checks. M2 now
has an actual candidate local OCR implementation; encrypted thumbnails and full
runtime validation remain. AI answers/summaries are unavailable: config/models.json
is empty and no generation engine is shipped. Next product steps remain M3
retrieval/fields/selection, M4 verified local model management/inference, M5 cited
questions/summaries, M6 file tools and M7 encrypted backup/recovery. No remote
processing, telemetry, app-store publication or cloud fallback was enabled.
