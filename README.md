# Private Document Assistant

Native Android encrypted document vault with offline OCR, local search and
selected-source labelled document facts.
**Stable-signed development APKs are ready to test.**
[Download APKs, checksums and reports](https://github.com/nikslogy/private-doc-ai/actions/runs/39760351605/artifacts/12419471716).
Unzip and install the offline APK, then open **Private Vault Preview**.
Version 0.4.0-dev / code 5 requires Android 12/API31+. Install it as an update to
the existing Preview app without uninstalling or clearing data. Preview IDs and
signing identity are unchanged; actual device update-preservation testing remains
pending. Earlier Test/original apps retain separate vaults; keep their data.

Import PDF/JPEG/PNG/UTF-8 text, open page evidence and choose English or
Hindi/Marathi OCR for scans/photos. Native PDF text stays preferred. Originals
remain immutable; page text, coordinates and literal search persist in SQLCipher.
Tink-encrypted objects, system authentication, explicit original export/delete
and automatic locking are implemented. Review OCR against originals for important
values. In Assistant → Document facts, explicitly choose a source to inspect
supported English labels, exact quotes/page links and conflicting values. Raw
identifiers/dates and monetary precision are preserved; unsupported layouts may
not match. AI questions/summaries, conversions, recovery and backups remain unavailable.

Experimental model setup adds licence acknowledgement and exact-byte verified
local import. Its fixed synthetic native diagnostic has not yet passed; normal
document questions remain disabled. See [model setup](docs/MODEL_SETUP.md).

[The stable build](https://github.com/nikslogy/private-doc-ai/actions/runs/39760351605)
passed the full host gate and 66 non-skipped Android 16 emulator executions.
These include real OCR and authenticated Keystore/vault lifecycles, pinned
revision reads, cited/conflicting fields and original preservation. Phone UI,
representative accuracy, full privacy/traffic and release gates remain open.
See [STATUS](docs/STATUS.md) for exact source/artifact identities and limits.
No cloud OCR, inference, document backend or telemetry was added.

## Build

Linux setup requires Bash 4+, Python 3.10+, curl and a JDK 17 or newer.
The inspected host has JDK 21; output bytecode targets Java 17. Pinned versions
are in [config/toolchain.json](config/toolchain.json) and
[COMPATIBILITY](docs/COMPATIBILITY.md).

```bash
# Review and accept the Android SDK terms before using the acceptance option.
scripts/bootstrap-android.sh --accept-sdk-licenses
source .tooling/env.sh
scripts/check-host.sh
```

Bootstrap checks disk/RAM, verifies pinned Gradle, wrapper and command-line-tools
SHA-256 values, installs official SDK packages, and writes local environment
configuration. Existing verified archives can be placed in `.tooling/downloads`
for restricted environments. The official wrapper JAR is installed by bootstrap;
it is not included in this source checkpoint. The repository `gradlew` launcher
also supports the pinned bootstrap-installed distribution. Do not substitute
an unverified binary or bypass the proxy/TLS policy. SDK platform-tools is an
official rolling package; see the compatibility record for that pin limitation.

Bootstrap and `gradlew` share `scripts/java-tool-env.sh`: Java receives the
inherited credential-free proxy route, and Gradle/Android user caches default
to ignored writable `.tooling/` paths. Inherited JVM trust/options are preserved,
not written into generated environment files. Unsupported authenticated proxy
configuration fails with an explanation instead of exposing credentials.

Expected output after a successful build:

- `app/build/outputs/apk/offline/debug/app-offline-debug.apk`
- `app/build/outputs/apk/connected/debug/app-connected-debug.apk`

These are debug builds with development signing, not app-store packages.
The offline overlay removes INTERNET and network-state permissions; the
artifact checker inspects the actual merged APK. Connected currently has no
configured service. Dependency network behavior and native compatibility still
require device/traffic checks.

To build, verify and stage both downloadable APKs after setup:

```bash
python3 scripts/stage-apks.py
```

It runs the full host gate, then verifies the exact staged APKs with the manifest/
native checker, official `apksigner` and `zipalign`. Successful output includes
both APKs, checksums and package/signing metadata in `artifacts/apk/`. The CI
workflow now additionally requires Android 16 vault regressions and real
credential-bound emulator lifecycle probes for both variants before APK upload.
Artifacts are retained for seven days. The
[original host build](https://github.com/nikslogy/private-doc-ai/actions/runs/39641658548)
completed on 21 October 2026, before the phone's first-unlock failure was
reported; that host result does not verify the repair. See
[APK delivery](docs/APK_DELIVERY.md).

## Validation

Run the checks that need only Python without downloading Android tools:

```bash
python3 scripts/test-fixtures.py
python3 scripts/test-validation.py
python3 scripts/test-native-libraries.py
python3 scripts/test-apk-delivery.py
python3 scripts/test-ocr-assets.py
python3 scripts/test-development-signing.py
python3 scripts/security-check.py
```

`check-host.sh` additionally runs pure Kotlin tests, both platform/app flavours'
instrumentation Kotlin compilation, unit-test tasks and lint, production dependency inventory, both app assemblies,
merged APK checks, and native ELF/ZIP alignment checks. It fails when prerequisites are
missing; passing the Python checks alone does not establish Android correctness.
Core has 82 authored JVM tests; M4 adds 8 metadata cases to the existing 8
platform validator cases per variant. App JVM tasks report NO-SOURCE. The delivered model-setup build passed 32 selected Android
cases and one real credential-bound lifecycle case per variant on an API36 emulator,
with no skips: 66 executions. The full device suite and physical fingerprint/UI tests
remain pending; see STATUS for the exact scope and earlier failures.

```bash
ANDROID_SERIAL=<explicit-device-serial> scripts/check-device.sh
```

Device procedures are in [DEVICE_TESTS](docs/DEVICE_TESTS.md). Real Tink,
SQLCipher/migration/search, Keystore-policy, encrypted-memory PDF/image,
development OCR and activity tests are authored.
The [model catalogue](config/models.json) registers a reviewed legacy Qwen
artifact as experimental, with no evaluated tasks/languages. Normal document QA
remains disabled. [Model setup](docs/MODEL_SETUP.md) offers verified local import
and a fixed synthetic runtime diagnostic; actual validation is recorded in STATUS.
No inference model or AI API credential is needed for routine host CI.
Pinned public OCR weights are downloaded/verified during the build. Stable APK
delivery additionally uses the configured user-owned signing identity.

## Supported development boundaries

- PDF, JPEG, PNG and UTF-8 text import; 25 MiB/file, 50 PDF pages,
  20 megapixels/source image. Password-protected PDFs are rejected explicitly.
- Native PDF/text page evidence, source spans, extractor versions and the FTS
  index live inside SQLCipher. Search returns a quote from a specific revision
  and page. It is literal lexical matching, not an answer or policy-validity judgment.
- PDF/text validation runs in bounded input memory. Images get framing/PNG CRC,
  bounded sampled pixel decode and EXIF orientation handling. These checks do
  not establish parser isolation or successful decoding of every possible image.
- ML Kit remains test-only because its diagnostics collection is unresolved.
  The new OCR implementation uses a separate local Tesseract engine, exact-byte
  library/model checks and memory bitmaps; synthetic Android checks passed; physical accuracy/privacy gates remain open. See STATUS and the OCR decisions.
- No plaintext vault originals or metadata are intentionally written to disk.
  Authorized exports are ordinary external files; recipients/providers can keep
  them. Runtime disk/log leakage has not been checked on a device.
- Screen-off/manual lock is immediate at the session boundary; background lock
  timeout is 15 seconds. Blocking provider/native calls cancel cooperatively.
- No recovery yet. Uninstalling or losing the Keystore key can lose vault access.
  Use synthetic test files and retain their sources while developing.

## Project map

| Path | Responsibility |
| --- | --- |
| `app/` | Compose UI, system authentication, picker and lifecycle |
| `core/` | Immutable references, evidence/money validation, typed actions and authorization |
| `platform/` | Encrypted vault, Android adapters, bounded parser and real runtime probe |
| `test-fixtures/` | Deterministic synthetic insurance documents and separate ground truth |
| `scripts/` | Bootstrap, fixtures, host/device/model/artifact checks |
| `docs/` | Requirements reference, design, compatibility and progress evidence |

Start subsequent work with [STATUS](docs/STATUS.md), then the
[development reference](docs/DEVELOPMENT_PLAN.md). The original temporary plan
attachment disappeared when the environment restarted; that file transparently
records a condensed transcription of the requirements already read.

To package a reviewable source checkpoint without SDKs, weights or build caches:

```bash
python3 scripts/package-source.py
```

The deterministic ZIP includes a source-file checksum manifest. A source archive
and its checksums do not imply Android compilation or device verification.
