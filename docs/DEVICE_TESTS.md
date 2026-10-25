# Device verification

For current phone testing, use the stable **Private Vault Preview** APK linked
in [STATUS.md](STATUS.md), with permanent ID com.privateassistant.app.preview
(connected: com.privateassistant.app.connected.preview). Keep existing apps/data
and install the same-key update. The unlock repair below is historical evidence;
its Test app IDs and temporary signing identity differ from current Preview builds.

## Historical first-unlock repair

The original version 0.1.0 APKs install on the user's iQOO Neo 10 / Android 16,
but first fingerprint unlock reportedly fails before import. Version 0.1.1-dev /
code 2 passed the full host gate and the selected Android 16 emulator regressions
in the [repair run](https://github.com/nikslogy/private-doc-ai/actions/runs/39715674766).
[Download the repaired APKs and reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39715674766/artifacts/12399575330).
Both variants passed 12 regression tests and one real credential-bound lifecycle
test each, with none skipped. Physical fingerprint/UI behavior and the full
device suite remain unverified. See [STATUS.md](STATUS.md) for exact evidence.

The new debug IDs are `com.privateassistant.app.dev` (**Private Vault Test**) and
`com.privateassistant.app.connected.dev` (**Private Vault Connected Test**).
They install alongside the original apps because the original debug signing
key is unavailable. Their vaults are separate; they do not reopen or migrate
the original vault. Keep the original apps/data and source documents. Do not
uninstall, clear storage, replace keys or disable authentication to get a test
past first unlock.

Device checks require an explicitly selected, authorized Android target. An emulator can exercise lifecycle/API paths; native, biometric, model, thermal and performance claims need physical hardware. Use at least two physical phone tiers before release and record model/OS/API/ABI/RAM, APK and model SHA-256, runtime/backend, available storage, thermal state, test corpus version, and date. Never use private customer documents for these development probes.

## Android 16 unlock regression gate — selected tests passed

The repair CI provisions a disposable API36 emulator and requires every case in
this selected subset to execute without failure or skip for **each** platform
variant before publishing APKs. All 12 cases per variant passed in run
39715674766; this is the selected regression subset, not the full device suite:

| Instrumentation class | Required cases per variant | Scope |
| --- | ---: | --- |
| SqlCipherInstrumentedTest | 3 | Production open settings, reproduction of the rejected result-returning execSQL call, encrypted reopen/wrong-key rejection |
| VaultEvidenceMigrationInstrumentedTest | 3 | Schema 1→2 identity/evidence preservation, encrypted literal search/rollback/deletion, failed/interrupted indexing |
| StreamingCryptoInstrumentedTest | 4 | Real Tink keyset/original-byte round trips and wrong-key/AAD/tamper/truncation rejection |
| DurableFilesInstrumentedTest | 2 | Atomic replacement bytes and directory-sync rejection of regular files |

Run this subset on an explicitly authorized target with
`ANDROID_SERIAL=<explicit-target> scripts/check-unlock-regression.sh`. Its XML
verifier rejects missing, ambiguous, failed or skipped reports. It does not
authenticate the app or establish physical fingerprint behavior.

CI then uses `scripts/check-ci-authenticated-unlock.sh` only on the freshly
provisioned disposable GitHub API36 emulator. It confirms there is no existing
screen-lock credential before setting a public synthetic PIN, verifies that
credential through Android's real Gatekeeper path, and immediately invokes
`VaultLifecycleInstrumentedTest` inside the unchanged production 30-second
Keystore authorization window. It requires exactly one non-skipped passing
lifecycle test for each variant, covering import/dedup, original bytes,
evidence/search, lock/reopen, interrupted publication, missing-object rejection,
synthetic disk canaries and deletion. It never supplies a substitute vault key.
Do not run this credential-provisioning script on a personal phone or an
existing emulator. Both real-credential probes passed, one test per variant
with none skipped. These results cannot prove the user's fingerprint prompt,
vendor integration or physical device behavior.

Using the repaired APK bundle above, open **Private Vault Test** on the user's
phone and verify first fingerprint unlock reaches the document list. Repeat for
**Private Vault Connected Test**, recording exact version/APK hash and any safe
error code. Then test synthetic import, lock and unlock again, and process
restart. Preserve the original app throughout. Successful installation alone
does not satisfy these checks.

## Broader device procedure

### Selected-source document facts (code 4 / 0.3.0-dev)

Use only synthetic documents in the stable **Private Vault Preview** app. The
working OCR report is a manual user report; the following checks remain pending
until their exact APK/device/results are recorded.

1. Install the same-key update over the existing Preview app without uninstalling
   or clearing data. Check that an existing synthetic vault, originals, search
   and system unlock remain available. Compare exported original bytes/digests.
2. Open Assistant → Document facts. No source should be selected after unlock.
   Choose one policy, inspect leading-zero identifiers, distinct premium/tax/
   insured-value labels and the unchanged printed date. Open a cited page, then
   return to Assistant; the explicit selection must remain the same revision.
3. Choose a different synthetic policy with a different owner/total. Results
   must come only from the chosen source. Import time must not imply "current".
   Repeat rapid selections while loading; old results must never replace the
   newer selection. Interrupt with lock/background/screen-off and unlock again;
   no private selection, value or quote may remain from the old session.
4. Use two different total-premium lines in one document. Both candidates and
   exact quotes/page links must appear with a conflict warning. Generic owner,
   total and subtotal labels must stay generic. Unsupported/absent labels and
   unread pages must not produce an invented value; check empty/partial states.
5. Reindex the selected document or retry OCR, including an unsuccessful retry;
   prior facts must be invalidated and incomplete extraction disclosed. Delete
   only a deliberately chosen synthetic source through the explicit confirmation
   and verify that its selected facts clear. Check original byte identity again.

Core tests and the authenticated controller lifecycle regression cover narrower
properties; they do not establish these Compose UI races, real layouts, phone
biometrics or representative extraction accuracy. General AI questions and
summaries remain unavailable until the verified local model/inference phase.

### Experimental model setup (M4; phone checks pending)

Use synthetic documents only. Record the exact APK/model hashes, app ID,
phone OS/API/ABI/total and available RAM, free storage and thermal state. Consult
STATUS before native testing; a verified download or installed model alone is
not a successful generation/cancellation result.

1. Install the same-key Preview update without uninstalling or clearing data.
   Unlock and confirm existing synthetic originals still export byte-identically.
   Open Assistant → Set up on-device AI. Document QA must remain unavailable.
2. Review the exact immutable artifact and licence in MODEL_SETUP.md. Download
   the public file using the chosen browser, then return/unlock. Acknowledge the
   licence for this import and select the file using the system picker. Allow
   at least twice the model size plus 64 MiB free in app storage, separately from
   the downloaded source. Do not substitute a moving-main file with the same name.
3. First select an unrelated small synthetic canary file;
   installation must reject it. With authorized app-private inspection, verify
   staging ciphertext/cleanup and absence of the canary as plaintext. Test
   truncated/oversized/tampered input and cancellation without touching vault
   originals or earlier verified model copies.
4. Import the reviewed model, restart/unlock and require verified status. Test
   deliberate tampering only on its app-owned public test copy: verification
   must fail, generation must stay unavailable, and explicit model removal must
   preserve the downloaded source and vault documents. Reimport the approved file.
5. Once CI establishes native execution, use Test local model. It must use only
   the fixed synthetic prompt and display actual output as unverified. Measure
   cold initialization, complete generation, memory and cancellation. Test
   cancel, screen-off, lock, leaving the setup screen and process restart; old
   output must clear and cancelled work must not publish into a later session.
   Native initialization/hangs have no hard interrupt guarantee in this runtime.
6. Independently run the explicit target probe below. Record actual non-skipped
   results and safe metrics, then inspect synthetic disk/log canaries and
   connected traffic. An unsupported/resource-limited phone is a recorded
   failure or unavailable result, never a fabricated answer or cloud fallback.

```bash
ANDROID_SERIAL=<explicit-authorized-target> scripts/check-model-generation.sh qwen3-06b-q4-nothink <exact-local-model-file> --accept-license
```

Use the compact 347,251,840-byte Qwen 3 record for code7. The retired runtime-0.9
model must remain recognized after an update but must not be downloaded, imported
or loaded on the new runtime; only explicit removal may delete its public slot.
Check both the old-slot preservation regression and a real same-key phone update.

For the 4 GB target, record Android-reported total/available memory, cold load,
complete generation, cancellation and numeric process memory. Repeat after native
cleanup and after OCR to expose retained allocator/workspace memory; a single
successful load is not sustained-use proof. Capture only authorized synthetic
observations, never private document text or global phone logs. Total RAM alone
must not become a device-wide ban; a specific artifact/profile can be unavailable
while vault, search and literal document facts continue to work.

This opt-in probe uses test-private storage and removes its public model copy.
Its file scan is narrow; PID/package crash diagnostics are enabled only on the
disposable GitHub API36 emulator, never a physical target. It does not establish
document accuracy, language quality, full privacy or phone latency targets.

### Full suite

1. Provision the pinned SDK/JDK, enable USB debugging, inspect `adb devices -l`, and select a target explicitly: `export ANDROID_SERIAL=<the-exact-authorized-serial>`. Scripts never choose the first available device.
2. Run `scripts/check-host.sh` and retain the unit/lint/assembly output and APK hashes. Run `scripts/verify-artifact.sh offline <apk>` and its connected equivalent on the exact APKs under test. Check native library 16 KiB compatibility with the Android official alignment/device procedures; source inclusion alone is insufficient.
3. Run `scripts/check-device.sh`. It runs platform and both app flavor instrumentation suites only on the explicit target. Inspect test reports for skipped tests and record them individually. The generic suite may report model availability as unavailable and skip an optional load probe; this is not a passed model check.
4. Review the real model catalogue record and licence. When a compatible registered artifact exists, run `ANDROID_SERIAL=<target> scripts/check-model.sh <catalog-id> <local-model-file> --accept-license`. It verifies byte count/hash before copying the model into test-private storage, requires the instrumented M0 runtime load test, and removes its test copy on exit. An empty catalogue exits unsuccessfully. This probe covers load, manual-tool conversation construction, idle cancellation call and release. Use the separate explicit M4 generation probe above for real inference/cancellation; consult STATUS for actual results.
5. Test setup/unlock with real system authentication; wrong/missing authentication, lock/background timeout, screen capture exclusion, DB closure, preview invalidation, job cancellation, process death/restart, interrupted object publication and deleted revisions. Verify originals are byte-identical after import/open. Exercise wrong key/AAD/vault/ciphertext and truncation rejection. Inspect app-private files, database, caches, logs and backups using synthetic canaries for plaintext leaks.
6. Import the synthetic native PDF, text, image and corrupt file through SAF. Test explicit source selection and page evidence, unavailable AI fallback, malformed/password/oversized files, pixel/page/memory/time bounds, cancellation and original retention when parsing/OCR fails. A successful API probe does not establish OCR quality; evaluate Latin/Devanagari and real authorized scans separately.
7. Test derivatives with explicit page/image order, geometry/orientation and true output byte counts. Test preview then user-initiated Sharesheet, narrow read-only URI grants, lock/restart/export expiry and recipient retention disclosure. Destructive actions must require the current user's exact-item confirmation. Parser/model/document text must never authorize an action.
8. With downloads and backup disabled, inspect connected import/OCR/search/QA traffic under a controlled network capture. Test offline merged permission removal and runtime behavior. Separately test backup/recovery to a fresh install, wrong key, missing/tampered/partial objects, consistent snapshots and preservation of the existing vault on restore failure when those features are implemented.

Current evidence/search checkpoint additions:

- `VaultEvidenceMigrationInstrumentedTest` exercises the authored schema 1→2
  migration and encrypted FTS/spans, source quotes, Devanagari/leading-zero
  queries, rollback, deletion, reopen and leakage canaries. All three migration
  tests passed on the API36 emulator for both repaired variants. Also run the
  opt-in authenticated vault lifecycle probe described in
  CRYPTO_DESIGN.md; default skipped execution is not validation.
- Import the native PDF/text, search a unique phrase and open its source page.
  Check exact raw text/revision/method; lock while searching/loading/reindexing
  and confirm no stale UI state or index commit. Older schema-1 documents must
  remain exportable before explicitly extracting their text.
- The Tesseract candidate has six synthetic actual-engine cases and three
  boundary cases selected for both emulator variants. Verify exact non-skipped
  results; inspect recognized text, line boxes, model tampering, blank pages and
  unchanged app-file canaries. These do not replace representative language,
  active cancellation, logs, traffic, memory or physical-device tests.
  ML Kit's real recognizer remains instrumentation-only because diagnostics
  collection is unresolved. Its separate SDK probes require
  an INTERNET-denied target and synthetic files; audit any SDK caches/logs and
  record skipped cases. They do not qualify production OCR or language quality.
- `OfflineIndexInstrumentedTest` covers native UTF-16 extraction, sampled image
  decode/framing/orientation and development OCR; the Task-lifetime probe checks
  that interruption cannot recycle a bitmap while recognition still uses it.
  Check all eight EXIF transforms and corrupt/truncated inputs on the device.
- Run the native gate and the official `zipalign -c -P 16 -v 4 <apk>` against
  actual APKs, then load libraries on a real 16 KiB target. See
  NATIVE_COMPATIBILITY.md for static-check limits. Both exact CI APKs passed
  static/signature/alignment checks on 21 October 2026; that result concerns the
  original APKs with the reported unlock failure. The repaired APKs passed the
  same checks in run 39715674766; physical 16 KiB behavior remains unverified.
- `DurableFilesInstrumentedTest` checks atomic replacement bytes and rejection
  of a regular file by directory sync through Android's public descriptor APIs.
  Both probes passed on the API36 emulator for both repaired variants; physical
  storage and power-loss behavior still require separate evidence.

The instrumentation APK disables only AndroidX ProfileInstallerInitializer
automatic startup, following the upstream manifest opt-out. The OCR file-canary
test asserts that the installed merged startup metadata omits that initializer
before its baseline/pixels. This isolates a delayed unrelated profileInstalled
marker write; it does not exclude any files from scanning or change production
startup. The full production startup/filesystem/traffic audit remains pending.

Keep unsupported features marked pending. Do not mark all steps passed because the instrumentation command exits zero; biometric prompts, traffic, leakage, recovery, representative OCR/model evaluation and thermal measurements need their own evidence. Never disable lint/native checks or substitute synthetic successful runtime responses to satisfy a device gate.

## Code8 actual-chat phone checks

Install the matching stable Preview APK over code7 without uninstalling or
clearing storage; verify old vault documents and the verified compact model
remain available. Open Assistant, type a synthetic greeting and Send. Follow
with a synthetic name and a question about that name; record actual output and
latency without treating one reply as an accuracy benchmark. Confirm only recent
context is used and chat clears on lock/restart. Cancel during generation and
initialization, clear while work is pending, change source and lock/unlock;
old completions must not reappear. Native cleanup can return later.

Choose an imported synthetic labelled policy. Ask its policy number, total
premium, pre-tax premium, tax and insured value separately; review exact source
pages. Check leading zeros, Indian grouping, missing/conflicting fields and
partial OCR evidence. Summarize document facts must label truncation/conflicts;
unsupported narrative questions must explain the current limit. Reindex/OCR and
delete the selected synthetic source; prior chat/citations must be invalidated.
General chat must request a source for obvious private-document questions and
must never perform or claim app-authorized file actions from generated text.
No textual answer can authorize export/deletion; use direct controls.

The selected stable CI gate runs nine controller cases and five gateway cases
per variant in addition to vault/OCR/credential cases (96 Android executions).
It is not a full Compose tap/keyboard test or physical authentication proof.
The separate native probe now includes actual chat/history/gateway execution;
source/model absence, skipped tests, dependency retrieval failure or missing
reports must never be counted as a passed native or phone suite.
