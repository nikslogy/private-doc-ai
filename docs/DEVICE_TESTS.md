# Device verification

Device checks are pending until run on an explicitly selected, authorized Android target. An emulator can exercise lifecycle/API paths; native, biometric, model, thermal and performance claims need physical hardware. Use at least two physical phone tiers before release and record model/OS/API/ABI/RAM, APK and model SHA-256, runtime/backend, available storage, thermal state, test corpus version, and date. Never use private customer documents for these development probes.

1. Provision the pinned SDK/JDK, enable USB debugging, inspect `adb devices -l`, and select a target explicitly: `export ANDROID_SERIAL=<the-exact-authorized-serial>`. Scripts never choose the first available device.
2. Run `scripts/check-host.sh` and retain the unit/lint/assembly output and APK hashes. Run `scripts/verify-artifact.sh offline <apk>` and its connected equivalent on the exact APKs under test. Check native library 16 KiB compatibility with the Android official alignment/device procedures; source inclusion alone is insufficient.
3. Run `scripts/check-device.sh`. It runs platform and both app flavor instrumentation suites only on the explicit target. Inspect test reports for skipped tests and record them individually. The generic suite may report model availability as unavailable and skip an optional load probe; this is not a passed model check.
4. Review the real model catalogue record and licence. When a compatible registered artifact exists, run `ANDROID_SERIAL=<target> scripts/check-model.sh <catalog-id> <local-model-file> --accept-license`. It verifies byte count/hash before copying the model into test-private storage, requires the instrumented M0 runtime load test, and removes its test copy on exit. An empty catalogue exits unsuccessfully. This probe covers load, manual-tool conversation construction, idle cancellation call and release. Actual inference and cancellation during generation require a separate M4 harness and are currently pending.
5. Test setup/unlock with real system authentication; wrong/missing authentication, lock/background timeout, screen capture exclusion, DB closure, preview invalidation, job cancellation, process death/restart, interrupted object publication and deleted revisions. Verify originals are byte-identical after import/open. Exercise wrong key/AAD/vault/ciphertext and truncation rejection. Inspect app-private files, database, caches, logs and backups using synthetic canaries for plaintext leaks.
6. Import the synthetic native PDF, text, image and corrupt file through SAF. Test explicit source selection and page evidence, unavailable AI fallback, malformed/password/oversized files, pixel/page/memory/time bounds, cancellation and original retention when parsing/OCR fails. A successful API probe does not establish OCR quality; evaluate Latin/Devanagari and real authorized scans separately.
7. Test derivatives with explicit page/image order, geometry/orientation and true output byte counts. Test preview then user-initiated Sharesheet, narrow read-only URI grants, lock/restart/export expiry and recipient retention disclosure. Destructive actions must require the current user's exact-item confirmation. Parser/model/document text must never authorize an action.
8. With downloads and backup disabled, inspect connected import/OCR/search/QA traffic under a controlled network capture. Test offline merged permission removal and runtime behavior. Separately test backup/recovery to a fresh install, wrong key, missing/tampered/partial objects, consistent snapshots and preservation of the existing vault on restore failure when those features are implemented.

Current evidence/search checkpoint additions:

- `VaultEvidenceMigrationInstrumentedTest` exercises the authored schema 1→2
  migration and encrypted FTS/spans, source quotes, Devanagari/leading-zero
  queries, rollback, deletion, reopen and leakage canaries. These tests have not
  run. Also run the opt-in authenticated vault lifecycle probe described in
  CRYPTO_DESIGN.md; default skipped execution is not validation.
- Import the native PDF/text, search a unique phrase and open its source page.
  Check exact raw text/revision/method; lock while searching/loading/reindexing
  and confirm no stale UI state or index commit. Older schema-1 documents must
  remain exportable before explicitly extracting their text.
- Production OCR is unavailable. ML Kit's real recognizer is instrumentation
  only, because diagnostics collection is unresolved. Real SDK probes require
  an INTERNET-denied target and synthetic files; audit any SDK caches/logs and
  record skipped cases. They do not qualify production OCR or language quality.
- `OfflineIndexInstrumentedTest` covers native UTF-16 extraction, sampled image
  decode/framing/orientation and development OCR; the Task-lifetime probe checks
  that interruption cannot recycle a bitmap while recognition still uses it.
  Check all eight EXIF transforms and corrupt/truncated inputs on the device.
- Run the native gate and the official `zipalign -c -P 16 -v 4 <apk>` against
  actual APKs, then load libraries on a real 16 KiB target. See
  NATIVE_COMPATIBILITY.md for static-check limits. Both exact CI APKs passed
  static/signature/alignment checks on 21 October 2026; no device run is claimed.
- `DurableFilesInstrumentedTest` checks atomic replacement bytes and rejection
  of a regular file by directory sync through Android's public descriptor APIs.
  Both probes are authored and device-unrun.

Keep unsupported features marked pending. Do not mark all steps passed because the instrumentation command exits zero; biometric prompts, traffic, leakage, recovery, representative OCR/model evaluation and thermal measurements need their own evidence. Never disable lint/native checks or substitute synthetic successful runtime responses to satisfy a device gate.
